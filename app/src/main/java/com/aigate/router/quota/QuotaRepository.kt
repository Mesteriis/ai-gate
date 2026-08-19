package com.aigate.router.quota

import com.aigate.router.data.db.AppDatabase
import com.aigate.router.data.model.QuotaSnapshot
import com.aigate.router.data.model.ResourcePool
import com.aigate.router.pricing.CostCalculator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.Calendar

/**
 * Оркестратор квот. Источники честные и раздельные:
 *  - LOCAL_USAGE — сколько израсходовано (реальные данные из token_usage × pricing).
 *  - USER_CONFIGURED — когда пользователь задал лимит бюджета (тогда есть remaining).
 *  - PROVIDER_API — если зарегистрирован адаптер провайдера (в v0.1.0 их нет → баланс
 *    провайдера остаётся неизвестным, а не выдуманным).
 */
object QuotaRepository {

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
     */
    suspend fun refreshAll(db: AppDatabase) {
        val now = System.currentTimeMillis()
        ensureProviderPools(db)
        val providers = db.providerDao().getAllProvidersOnce().associateBy { it.id }
        val pools = db.resourcePoolDao().getAll().filter { it.enabled }

        for (pool in pools) {
            // 1) Попробовать реальный адаптер провайдера (PROVIDER_API).
            val provider = providers[pool.providerId]
            if (provider != null) {
                val remote = QuotaProviderRegistry.resolve(provider)
                if (remote != null) {
                    val remoteSnap = runCatching { remote.fetch(db, provider, pool) }.getOrNull()
                    if (remoteSnap != null) {
                        db.quotaSnapshotDao().insert(remoteSnap.copy(poolId = pool.id, updatedAt = now))
                        continue
                    }
                }
            }
            // 2) Локальный расчёт: израсходовано за текущий период × pricing.
            val periodStart = periodStart(now, pool.resetDayOfMonth)
            val agg = CostCalculator.totalCostSince(
                db, periodStart,
                providerId = if (pool.providerId != 0L) pool.providerId else null
            )
            val used = agg.usd
            val limit = pool.configuredLimit
            val remaining = limit?.let { (it - used).coerceAtLeast(0.0) }
            val source = if (limit != null) QuotaSource.USER_CONFIGURED else QuotaSource.LOCAL_USAGE
            db.quotaSnapshotDao().insert(
                QuotaSnapshot(
                    poolId = pool.id,
                    used = used,
                    remaining = remaining,
                    limit = limit,
                    unit = QuotaUnit.USD.name,
                    resetsAt = nextReset(now, pool.resetDayOfMonth),
                    updatedAt = now,
                    source = source.name
                )
            )
        }
        // Ограничить историю (90 дней).
        db.quotaSnapshotDao().deleteOlderThan(now - 90L * 24 * 3600 * 1000)
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
            // Сессии CLI по подписке: расход квоты со сбросом по периоду.
            t.contains("codex") || t.contains("claude-cli") || t.contains("gemini-cli") ->
                ResourcePoolKind.QUOTA
            // Остальные облачные API — оплаченный баланс.
            else -> ResourcePoolKind.BALANCE
        }
    }

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

    /** Начало текущего периода: последний прошедший день сброса в 00:00. */
    private fun periodStart(now: Long, resetDay: Int?): Long {
        val day = (resetDay ?: 1).coerceIn(1, 28)
        val cal = midnight(now)
        return if (cal.get(Calendar.DAY_OF_MONTH) >= day) {
            cal.set(Calendar.DAY_OF_MONTH, day); cal.timeInMillis
        } else {
            cal.add(Calendar.MONTH, -1); cal.set(Calendar.DAY_OF_MONTH, day); cal.timeInMillis
        }
    }

    /** Следующий сброс: ближайший будущий день сброса в 00:00. */
    private fun nextReset(now: Long, resetDay: Int?): Long {
        val day = (resetDay ?: 1).coerceIn(1, 28)
        val cal = midnight(now)
        return if (cal.get(Calendar.DAY_OF_MONTH) < day) {
            cal.set(Calendar.DAY_OF_MONTH, day); cal.timeInMillis
        } else {
            cal.add(Calendar.MONTH, 1); cal.set(Calendar.DAY_OF_MONTH, day); cal.timeInMillis
        }
    }

    private fun midnight(now: Long): Calendar = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
}
