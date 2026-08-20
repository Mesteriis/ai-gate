package com.aigate.router.quota

import android.util.Log
import com.aigate.router.auth.AuthRegistry
import com.aigate.router.data.credential.CredentialStore
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.data.db.QuotaSnapshotDao
import com.aigate.router.data.model.QuotaSnapshot
import com.aigate.router.data.model.ResourcePool
import com.aigate.router.pricing.CostCalculator
import com.aigate.router.service.GatewayForegroundService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Оркестратор квот. Источники честные и раздельные:
 *  - LOCAL_USAGE — сколько израсходовано (реальные данные из token_usage × pricing).
 *    Видит только трафик через шлюз, поэтому расход мимо шлюза сюда не попадает.
 *  - USER_CONFIGURED — когда пользователь задал лимит бюджета (тогда есть remaining).
 *  - PROVIDER_API — ответ адаптера провайдера. Единственный источник, который
 *    учитывает потребление в обход шлюза, поэтому локальная оценка его не подменяет.
 */
object QuotaRepository {

    private const val TAG = "QuotaRepository"

    /** Пул + последний снимок + вычисленное давление. */
    data class PoolQuota(
        val pool: ResourcePool,
        val snapshot: QuotaSnapshot?,
        val pressure: ResourcePressure
    )

    /** Наблюдаемый поток для UI (давление считается по долям остатка, без burn-rate). */
    fun observe(db: AppDatabase): Flow<List<PoolQuota>> =
        combine(
            db.resourcePoolDao().observeAll(),
            db.quotaSnapshotDao().observeLatest()
        ) { pools, snapshots ->
            val byPool = snapshots.associateBy { it.poolId }
            pools.map { pool ->
                val snap = byPool[pool.id]
                PoolQuota(
                    pool = pool,
                    snapshot = snap,
                    pressure = PressureCalculator.compute(
                        remaining = snap?.remaining,
                        limit = snap?.limit,
                        resetsAt = snap?.resetsAt,
                        spendPerHour = null,
                        now = System.currentTimeMillis()
                    )
                )
            }
        }

    /** Одноразовый снимок для виджетов/уведомлений (давление учитывает темп расхода). */
    suspend fun latest(db: AppDatabase): List<PoolQuota> {
        val now = System.currentTimeMillis()
        val pools = db.resourcePoolDao().getAll()
        return pools.map { pool ->
            val snap = db.quotaSnapshotDao().getLatestForPool(pool.id)
            val rate = recentSpendPerHour(db, pool)
            PoolQuota(
                pool = pool,
                snapshot = snap,
                pressure = PressureCalculator.compute(
                    remaining = snap?.remaining,
                    limit = snap?.limit,
                    resetsAt = snap?.resetsAt,
                    spendPerHour = rate,
                    now = now
                )
            )
        }
    }

    /**
     * Пересчитать квоты всех пулов. Автосоздаёт по одному пулу расхода на каждого
     * включённого провайдера (если ещё нет), затем считает снимок для каждого пула.
     *
     * @param trigger кто инициировал обновление — от этого зависит, не рано ли
     *   идти к провайдеру повторно
     * @param remoteAllowed false, когда сети нет: к провайдеру не ходим вовсе
     * @param lastAttemptAt время предыдущей попытки по каждому пулу (живёт в
     *   процессе, поэтому передаётся снаружи)
     * @param onPoolResult исход по каждому пулу — для журнала и показа в интерфейсе
     */
    suspend fun refreshAll(
        db: AppDatabase,
        trigger: RefreshTrigger = RefreshTrigger.USER_ACTION,
        remoteAllowed: Boolean = true,
        lastAttemptAt: (Long) -> Long? = { null },
        onPoolResult: ((Long, PoolRefreshOutcome, String?) -> Unit)? = null,
    ) {
        // Кэш секретов загружается асинхронно на старте приложения. Фоновое
        // обновление обгоняло его, и адаптеры получали пустые ключи.
        CredentialStore.ensureLoaded(db)

        val now = System.currentTimeMillis()
        ensureProviderPools(db)
        val providers = db.providerDao().getAllProvidersOnce().associateBy { it.id }
        val pools = db.resourcePoolDao().getAll().filter { it.enabled }
        val dao = db.quotaSnapshotDao()

        for (pool in pools) {
            // 1) Попробовать реальный адаптер провайдера (PROVIDER_API).
            val provider = providers[pool.providerId]
            val remote = provider?.let { QuotaProviderRegistry.resolve(it) }
            val lastProviderApiAt =
                dao.getLatestForPoolBySource(pool.id, QuotaSource.PROVIDER_API.name)?.updatedAt

            if (provider != null && remote != null) {
                // Свежий ответ провайдера трогать незачем: обновлять нечего.
                if (RefreshPolicy.shouldFetchRemote(
                        trigger, lastProviderApiAt, lastAttemptAt(pool.id), now
                    ).not()
                ) {
                    onPoolResult?.invoke(pool.id, PoolRefreshOutcome.SKIPPED_THROTTLED, null)
                    continue
                }

                // Без сети к провайдеру не ходим, но локальный расчёт ей и не
                // нужен: если данных провайдера нет или они давно мертвы, пул
                // всё равно получит честную локальную оценку ниже.
                if (!remoteAllowed) {
                    onPoolResult?.invoke(pool.id, PoolRefreshOutcome.OFFLINE, null)
                    if (!RefreshPolicy.shouldWriteLocalFallback(true, lastProviderApiAt, now)) continue
                    writeLocalSnapshot(db, dao, pool, now)
                    continue
                }

                // Токены Codex и Claude живут около часа. Без обновления перед
                // запросом провайдер отвечал 401, ошибка терялась, и поверх
                // реальных данных ложилась локальная оценка.
                val authFresh = runCatching { AuthRegistry.ensureFreshForProvider(db, provider) }
                    .getOrDefault(false)

                var failure: String? = null
                val remoteSnap = try {
                    remote.fetch(db, provider, pool)
                } catch (e: Exception) {
                    failure = e.message ?: e::class.java.simpleName
                    null
                }

                if (remoteSnap != null) {
                    save(dao, remoteSnap.copy(poolId = pool.id, updatedAt = now), now)
                    onPoolResult?.invoke(pool.id, PoolRefreshOutcome.OK_PROVIDER, null)
                    continue
                }

                val reason = failure ?: if (!authFresh) "не удалось обновить токен доступа" else null
                val outcomeOnMiss =
                    if (!authFresh) PoolRefreshOutcome.AUTH_EXPIRED else PoolRefreshOutcome.FETCH_FAILED
                logFailure(pool.name, reason)
                onPoolResult?.invoke(pool.id, outcomeOnMiss, reason)

                // Пока в базе лежит свежий ответ провайдера, локальная оценка его
                // не подменяет: устаревшее показание честнее выдуманной свежести.
                if (!RefreshPolicy.shouldWriteLocalFallback(true, lastProviderApiAt, now)) continue
            }

            // 2) Локальный расчёт: израсходовано за текущий период × pricing.
            writeLocalSnapshot(db, dao, pool, now)
            if (remote == null) onPoolResult?.invoke(pool.id, PoolRefreshOutcome.OK_LOCAL, null)
        }
        // Ограничить историю (90 дней).
        dao.deleteOlderThan(now - 90L * 24 * 3600 * 1000)
    }

    /**
     * Снимок из собственного учёта: израсходовано за текущий период × pricing.
     * Видит только трафик через шлюз, поэтому применяется там, где данных
     * провайдера нет или они давно не обновлялись.
     */
    private suspend fun writeLocalSnapshot(
        db: AppDatabase,
        dao: QuotaSnapshotDao,
        pool: ResourcePool,
        now: Long,
    ) {
        val periodStart = periodStart(now, pool.resetDayOfMonth)
        val agg = CostCalculator.totalCostSince(
            db, periodStart,
            providerId = if (pool.providerId != 0L) pool.providerId else null
        )
        val used = agg.usd
        val limit = pool.configuredLimit
        val remaining = limit?.let { (it - used).coerceAtLeast(0.0) }
        val source = if (limit != null) QuotaSource.USER_CONFIGURED else QuotaSource.LOCAL_USAGE
        save(
            dao,
            QuotaSnapshot(
                poolId = pool.id,
                used = used,
                remaining = remaining,
                limit = limit,
                unit = QuotaUnit.USD.name,
                resetsAt = nextReset(now, pool.resetDayOfMonth),
                updatedAt = now,
                source = source.name
            ),
            now,
        )
    }

    /**
     * Записать снимок. Неизменившееся показание только обновляет метку времени:
     * при обновлении раз в пять минут вставка каждого повтора раздувала бы
     * историю примерно до 288 строк на пул в сутки.
     */
    private suspend fun save(dao: QuotaSnapshotDao, snapshot: QuotaSnapshot, now: Long) {
        val previous = dao.getLatestForPool(snapshot.poolId)
        if (RefreshPolicy.sameReading(previous, snapshot)) {
            dao.touchUpdatedAt(previous!!.id, now)
        } else {
            dao.insert(snapshot)
        }
    }

    /** Отказ провайдера виден в журнале: раньше он терялся молча. */
    private fun logFailure(poolName: String, reason: String?) {
        val text = "Квота «$poolName»: ${reason ?: "провайдер не вернул данные"}"
        Log.w(TAG, text)
        GatewayForegroundService.addDebugLog(text)
    }

    /** Прежние имена типов пула — их нужно переклассифицировать один раз. */
    private val legacyKindNames = setOf("SUBSCRIPTION", "API_BALANCE", "LOCAL_BUDGET")

    /** Создать по одному пулу расхода на включённого провайдера, если его ещё нет. */
    private suspend fun ensureProviderPools(db: AppDatabase) {
        val dao = db.resourcePoolDao()
        val existingPools = dao.getAll()
        val existingProviderIds = existingPools.map { it.providerId }.toSet()
        val providers = db.providerDao().getEnabledProviders()

        // Разовая миграция: до разделения типов ВСЕ пулы провайдеров создавались
        // как «баланс», из-за чего локальные бесплатные модели и подписочные
        // квоты выглядели одинаково. Переклассифицируем только пулы со старыми
        // именами типа, не затрагивая выбранное пользователем.
        val providersById = providers.associateBy { it.id }
        for (pool in existingPools) {
            val provider = providersById[pool.providerId] ?: continue
            var updated = pool
            // Имя ресурса следует за именем провайдера: пользователь задаёт имя
            // сам, и два аккаунта одного типа не должны выглядеть одинаково.
            if (provider.name.isNotBlank() && pool.name != provider.name) {
                updated = updated.copy(name = provider.name)
            }
            if (pool.kind.uppercase() in legacyKindNames) {
                val corrected = kindForProvider(provider.type)
                if (corrected.name != pool.kind) updated = updated.copy(kind = corrected.name)
            }
            if (updated != pool) dao.update(updated)
        }

        for (p in providers) {
            if (p.id !in existingProviderIds) {
                dao.insert(
                    ResourcePool(
                        providerId = p.id,
                        name = p.name,
                        kind = kindForProvider(p.type).name,
                        unit = QuotaUnit.USD.name,
                        configuredLimit = null,
                        resetDayOfMonth = 1
                    )
                )
            }
        }
    }

    /**
     * Тип ресурса по типу провайдера. Разные вещи называются разными словами:
     * локальные модели бесплатны, подписка расходует квоту со сбросом,
     * pay-as-you-go тратит оплаченный баланс.
     */
    fun kindForProvider(providerType: String): ResourcePoolKind {
        val t = providerType.lowercase()
        return when {
            // Локальные и встроенные в устройство модели — ресурс без лимита.
            t.contains("ollama") || t.contains("local") || t.contains("device") ||
                t.contains("llama.cpp") || t.contains("lmstudio") -> ResourcePoolKind.FREE
            // Подписки: расход квоты со сбросом по периоду. Cursor сюда же —
            // у него месячный платёжный цикл, а не невосполнимый баланс.
            t.contains("codex") || t.contains("claude-cli") || t.contains("gemini-cli") ||
                t.contains("cursor") -> ResourcePoolKind.QUOTA
            // Остальные облачные API — оплаченный баланс.
            else -> ResourcePoolKind.BALANCE
        }
    }

    /**
     * Расход пула за период по данным поставщика. null, если поставщик такие
     * данные не отдаёт или снимков ещё слишком мало.
     *
     * Это единственный расчёт, который видит потребление в обход шлюза:
     * собственный учёт записывает только запросы, прошедшие через него.
     */
    suspend fun providerReportedSpend(
        db: AppDatabase,
        pool: ResourcePool,
        fromMs: Long,
        toMs: Long = System.currentTimeMillis(),
    ): ProviderSpend.PeriodSpend? {
        // Берём с запасом: снимок чуть раньше начала периода служит базой отсчёта,
        // без него расход на стыке периодов потерялся бы.
        val history = db.quotaSnapshotDao()
            .getHistoryForPoolSince(pool.id, fromMs - BASELINE_MARGIN_MS)
        return ProviderSpend.periodSpend(history, fromMs, toMs)
    }

    /** Насколько раньше начала периода искать базовый снимок. */
    private const val BASELINE_MARGIN_MS = 6 * 3_600_000L

    /** Начало текущего расчётного периода пула. */
    fun periodStartOf(pool: ResourcePool, now: Long = System.currentTimeMillis()): Long =
        QuotaPeriods.periodStart(now, pool.resetDayOfMonth)

    /** Недавний темп расхода (USD/час) за последние 24ч. null, если данных нет. */
    private suspend fun recentSpendPerHour(db: AppDatabase, pool: ResourcePool): Double? {
        val now = System.currentTimeMillis()
        val since = now - 24L * 3600 * 1000
        val agg = CostCalculator.totalCostSince(
            db, since,
            providerId = if (pool.providerId != 0L) pool.providerId else null
        )
        if (agg.pricedCalls == 0) return null
        return agg.usd / 24.0
    }

    // ---- Границы периода/сброса -------------------------------------------
    // Живут в QuotaPeriods: расход по данным поставщика считается по тем же
    // границам, что и локальный, иначе два числа на экране разошлись бы.

    private fun periodStart(now: Long, resetDay: Int?): Long =
        QuotaPeriods.periodStart(now, resetDay)

    private fun nextReset(now: Long, resetDay: Int?): Long =
        QuotaPeriods.nextReset(now, resetDay)
}
