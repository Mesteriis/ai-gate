package com.aigate.router.widget

import com.aigate.router.data.db.AppDatabase
import com.aigate.router.data.model.QuotaSnapshot
import com.aigate.router.data.model.routeKey
import com.aigate.router.gateway.GatewayScheduler
import com.aigate.router.pricing.CostCalculator
import com.aigate.router.quota.QuotaBurn
import com.aigate.router.quota.QuotaRepository
import com.aigate.router.quota.QuotaWindows
import com.aigate.router.quota.ResourcePoolKind
import com.aigate.router.quota.ResourcePressure
import com.aigate.router.service.GatewayForegroundService
import com.aigate.router.ui.design.Fmt
import com.aigate.router.usage.UsageHistory
import com.aigate.router.usage.UsageStats
import java.util.Calendar

/**
 * Снимки данных для виджетов.
 *
 * Виджет ничего не опрашивает по сети: он читает то, что уже лежит в локальной
 * базе (обновлением занимается QuotaRefresher и WorkManager). Каждая функция —
 * один короткий проход по БД, чтобы ресивер успел отработать в отведённое время.
 */
object WidgetData {

    /** Один пул ресурсов в том виде, в каком он показывается строкой виджета. */
    data class PoolCard(
        val name: String,
        val providerType: String,
        val kind: ResourcePoolKind,
        val pressure: ResourcePressure,
        val value: String,
        val usedFraction: Double?,
        val note: String?,
        val reset: String,
        val updatedAt: Long?,
        val source: String?,
    )

    data class ResourcesData(val pools: List<PoolCard>, val now: Long) {
        val attention: Int
            get() = pools.count {
                it.pressure == ResourcePressure.CRITICAL || it.pressure == ResourcePressure.CONSERVE
            }
    }

    data class DayPoint(val dayStartMs: Long, val prompt: Long, val completion: Long) {
        val total: Long get() = prompt + completion
    }

    data class TokensData(
        val days: List<DayPoint>,
        val prompt: Long,
        val completion: Long,
        val periodDays: Int,
    ) {
        val total: Long get() = prompt + completion
        val peak: DayPoint? get() = days.maxByOrNull { it.total }?.takeIf { it.total > 0 }
        val average: Long get() = if (days.isEmpty()) 0 else total / days.size
    }

    data class SpendData(
        val cumulativeUsd: List<Double>,
        val dailyUsd: List<Double>,
        val monthToDateUsd: Double,
        val projectedUsd: Double,
        val subscriptionsUsd: Double,
        val daysElapsed: Int,
        val daysInMonth: Int,
        val isEstimate: Boolean,
        val monthStartMs: Long,
    )

    data class ShareRow(val name: String, val type: String, val tokens: Long)

    data class SharesData(val rows: List<ShareRow>, val periodDays: Int) {
        val total: Long get() = rows.sumOf { it.tokens }
    }

    data class CallRow(
        val at: Long,
        val modelId: String,
        val providerName: String,
        val providerType: String,
        val tokens: Long,
        val usd: Double?,
    )

    data class CallsData(val rows: List<CallRow>, val todayCalls: Int, val lastAt: Long?)

    data class StatusData(
        val running: Boolean,
        val port: Int,
        val modelId: String?,
        val providerName: String?,
        val reason: String,
    )

    // --- Ресурсы ------------------------------------------------------------

    suspend fun resources(db: AppDatabase, now: Long = System.currentTimeMillis()): ResourcesData {
        val providers = db.providerDao().getAllProvidersOnce().associateBy { it.id }
        val cards = QuotaRepository.latest(db).map { pq ->
            val kind = ResourcePoolKind.fromName(pq.pool.kind)
            val snapshot = pq.snapshot
            val unit = snapshot?.unit ?: pq.pool.unit
            val fraction = WidgetText.usedFraction(snapshot?.remaining, snapshot?.limit)
            val history = if (kind.hasReset || kind.hasFraction) {
                runCatching { db.quotaSnapshotDao().getHistoryForPool(pq.pool.id) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            PoolCard(
                name = pq.pool.name,
                providerType = providers[pq.pool.providerId]?.type ?: "",
                kind = kind,
                pressure = pq.pressure,
                value = WidgetText.poolValue(kind, snapshot?.remaining, snapshot?.limit, snapshot?.used, unit),
                usedFraction = fraction,
                note = outlookNote(snapshot, history, unit, now),
                reset = WidgetText.resetText(kind, snapshot?.resetsAt, now),
                updatedAt = snapshot?.updatedAt,
                source = snapshot?.source,
            )
        }.sortedWith(compareBy({ pressureRank(it.pressure) }, { it.name }))
        return ResourcesData(cards, now)
    }

    /**
     * Вердикт по темпу расхода теми же словами, что на экране обзора: «хватит
     * до …», если квота кончится раньше сброса, и «сгорит …», если наоборот
     * останется неиспользованной.
     */
    private fun outlookNote(
        snapshot: QuotaSnapshot?,
        history: List<QuotaSnapshot>,
        unit: String,
        now: Long,
    ): String? {
        val remaining = snapshot?.remaining ?: return null
        val resetsAt = snapshot.resetsAt ?: return null
        val rate = QuotaBurn.rate(history, now) ?: return null
        val outlook = QuotaBurn.outlook(remaining, resetsAt, rate, now) ?: return null
        val exhaust = outlook.exhaustAtMs
        return when {
            exhaust != null && exhaust < resetsAt -> "хватит до ${Fmt.time(exhaust)}"
            outlook.surplus > 0.0 -> "сгорит ${Fmt.quota(outlook.surplus, unit)}"
            else -> null
        }
    }

    fun pressureRank(pressure: ResourcePressure): Int = when (pressure) {
        ResourcePressure.CRITICAL -> 0
        ResourcePressure.CONSERVE -> 1
        ResourcePressure.NORMAL -> 2
        ResourcePressure.FREE -> 3
        ResourcePressure.UNKNOWN -> 4
    }

    // --- Токены по дням -----------------------------------------------------

    suspend fun tokens(db: AppDatabase, days: Int): TokensData {
        val daily = UsageHistory.daily(db, days = days)
        val points = daily.map { DayPoint(it.dayStartMs, it.promptTokens, it.completionTokens) }
        return TokensData(
            days = points,
            prompt = points.sumOf { it.prompt },
            completion = points.sumOf { it.completion },
            periodDays = days,
        )
    }

    // --- Расход за месяц ----------------------------------------------------

    suspend fun spend(db: AppDatabase, now: Long = System.currentTimeMillis()): SpendData {
        val forecast = UsageHistory.forecast(db)
        val monthStart = monthStart(now)
        val daily = UsageHistory.daily(db, days = 32)
            .filter { it.dayStartMs >= monthStart }
            .sortedBy { it.dayStartMs }
        val dailyUsd = daily.map { it.usd }
        var running = 0.0
        val cumulative = dailyUsd.map { running += it; running }
        return SpendData(
            cumulativeUsd = cumulative,
            dailyUsd = dailyUsd,
            monthToDateUsd = forecast.monthToDateUsd,
            projectedUsd = forecast.projectedMonthEndUsd,
            subscriptionsUsd = forecast.subscriptionsUsd,
            daysElapsed = forecast.daysElapsed,
            daysInMonth = forecast.daysInMonth,
            isEstimate = forecast.isEstimate,
            monthStartMs = monthStart,
        )
    }

    private fun monthStart(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun dayStart(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // --- Доли провайдеров ---------------------------------------------------

    suspend fun shares(db: AppDatabase, days: Int, now: Long = System.currentTimeMillis()): SharesData {
        val providers = db.providerDao().getAllProvidersOnce()
        val types = providers.associate { it.id to it.type }
        val names = providers.associate { it.id to it.name }
        val snapshot = UsageStats.snapshot(
            rows = db.tokenUsageDao().getAllUsageOnce(),
            providerNames = names,
            nowMs = now,
            days = days,
        )
        val rows = snapshot.byProvider.map {
            ShareRow(name = it.name, type = types[it.providerId] ?: "", tokens = it.tokens)
        }
        return SharesData(rows, days)
    }

    // --- Последние вызовы ---------------------------------------------------

    suspend fun calls(db: AppDatabase, limit: Int, now: Long = System.currentTimeMillis()): CallsData {
        val providers = db.providerDao().getAllProvidersOnce().associateBy { it.id }
        val all = db.tokenUsageDao().getAllUsageOnce()
        val today = dayStart(now)
        val rows = all.take(limit).map { row ->
            val provider = providers[row.providerId]
            val type = provider?.type ?: ""
            val usd = if (type.isBlank()) {
                null
            } else {
                runCatching {
                    CostCalculator.estimateCall(
                        db, type, row.modelId, row.promptTokens, row.completionTokens
                    )?.usd
                }.getOrNull()
            }
            CallRow(
                at = row.timestamp,
                modelId = row.modelId,
                providerName = provider?.name ?: WidgetText.DASH,
                providerType = type,
                tokens = row.totalTokens.toLong(),
                usd = usd,
            )
        }
        return CallsData(
            rows = rows,
            todayCalls = all.count { it.timestamp >= today },
            lastAt = all.firstOrNull()?.timestamp,
        )
    }

    // --- Статус шлюза -------------------------------------------------------

    suspend fun status(db: AppDatabase): StatusData {
        val running = runCatching { GatewayForegroundService.isServiceRunning }.getOrDefault(false) ||
            runCatching { GatewayForegroundService.getGatewayWasRunning() }.getOrDefault(false)
        val port = runCatching { GatewayForegroundService.getGatewayPort() }.getOrDefault(8889)

        val models = runCatching { db.aiModelDao().getEnabledModelsList() }.getOrDefault(emptyList())
        val providers = db.providerDao().getAllProvidersOnce().associateBy { it.id }
        val forced = runCatching { GatewayForegroundService.getForcedModel() }.getOrDefault("")
        val best = runCatching { GatewayScheduler.getBestModel() }.getOrNull()
        val chosenKey = forced.takeIf { it.isNotBlank() } ?: best
        val model = models.firstOrNull { it.routeKey == chosenKey } ?: models.firstOrNull()
        return StatusData(
            running = running,
            port = port,
            modelId = model?.modelId,
            providerName = model?.let { providers[it.providerId]?.name },
            reason = if (model == null) {
                "нет включённых моделей"
            } else {
                WidgetText.nextReason(
                    forced = forced.isNotBlank(),
                    isBest = chosenKey != null && chosenKey == best,
                    hasMeasurements = best != null,
                )
            },
        )
    }

    // --- Окна квоты ---------------------------------------------------------

    data class WindowCard(val label: String, val usedFraction: Double, val reset: String)

    data class WindowsData(
        val poolName: String,
        val providerType: String,
        val windows: List<WindowCard>,
        val pressure: ResourcePressure,
    )

    /**
     * Окна лимита одного ресурса. У подписки Claude их два одновременно —
     * сессия и неделя; они лежат рядом со снимком (quota/QuotaWindows.kt).
     * Берём пул, у которого окон больше всего: именно ему нужен отдельный виджет.
     */
    suspend fun quotaWindows(db: AppDatabase, now: Long = System.currentTimeMillis()): WindowsData? {
        val providers = db.providerDao().getAllProvidersOnce().associateBy { it.id }
        val best = QuotaRepository.latest(db)
            .mapNotNull { pq ->
                val windows = runCatching { QuotaWindows.of(pq.pool.id) }.getOrDefault(emptyList())
                if (windows.size < 2) null else pq to windows
            }
            .maxByOrNull { it.second.size }
            ?: return null
        val (pq, windows) = best
        return WindowsData(
            poolName = pq.pool.name,
            providerType = providers[pq.pool.providerId]?.type ?: "",
            pressure = pq.pressure,
            windows = windows.map { w ->
                WindowCard(
                    label = w.label,
                    usedFraction = (w.percent / 100.0).coerceIn(0.0, 1.0),
                    reset = if (w.resetsAt == null || w.resetsAt <= now) {
                        "сброс неизвестен"
                    } else {
                        "сброс через ${Fmt.duration(w.resetsAt - now)}"
                    },
                )
            },
        )
    }

    // --- Темп расхода квоты -------------------------------------------------

    data class BurnData(
        val poolName: String,
        val remainingPercent: Double,
        val history: List<Double>,
        val evenPacePercent: Double,
        val exhaustAtMs: Long?,
        val resetsAt: Long,
        val surplus: Double,
        val unit: String,
        val pressure: ResourcePressure,
        val now: Long,
    )

    /**
     * Самый напряжённый пул с известным сбросом: его темп и есть то, за чем
     * следят. Без истории снимков темпа не существует — тогда виджет молчит.
     */
    suspend fun burn(db: AppDatabase, now: Long = System.currentTimeMillis()): BurnData? {
        val candidates = QuotaRepository.latest(db)
            .filter { pq ->
                val snapshot = pq.snapshot
                snapshot?.resetsAt != null && snapshot.remaining != null && snapshot.limit != null
            }
            .sortedBy { pressureRank(it.pressure) }
        for (pq in candidates) {
            val snapshot = pq.snapshot ?: continue
            val limit = snapshot.limit ?: continue
            if (limit <= 0.0) continue
            val resetsAt = snapshot.resetsAt ?: continue
            val history = runCatching { db.quotaSnapshotDao().getHistoryForPool(pq.pool.id) }
                .getOrDefault(emptyList())
                .filter { it.remaining != null }
                .sortedBy { it.updatedAt }
            if (history.size < 2) continue
            val rate = QuotaBurn.rate(history, now)
            val outlook = rate?.let { QuotaBurn.outlook(snapshot.remaining ?: 0.0, resetsAt, it, now) }
            return BurnData(
                poolName = pq.pool.name,
                remainingPercent = (snapshot.remaining ?: 0.0) / limit * 100.0,
                history = history.map { (it.remaining ?: 0.0) / limit * 100.0 },
                evenPacePercent = 100.0,
                exhaustAtMs = outlook?.exhaustAtMs,
                resetsAt = resetsAt,
                surplus = outlook?.surplus ?: 0.0,
                unit = snapshot.unit,
                pressure = pq.pressure,
                now = now,
            )
        }
        return null
    }

    // --- Трафик -------------------------------------------------------------

    data class TrafficData(
        val upload: List<Double>,
        val download: List<Double>,
        val uploadTotal: Long,
        val downloadTotal: Long,
        val periodDays: Int,
    )

    suspend fun traffic(db: AppDatabase, days: Int, now: Long = System.currentTimeMillis()): TrafficData {
        val from = dayStart(now) - (days - 1).toLong() * 24 * 60 * 60 * 1000L
        val rows = db.tokenUsageDao().getAllUsageOnce().filter { it.timestamp >= from }
        val up = DoubleArray(days)
        val down = DoubleArray(days)
        for (row in rows) {
            val index = ((dayStart(row.timestamp) - from) / (24 * 60 * 60 * 1000L)).toInt()
            if (index in 0 until days) {
                up[index] += row.uploadBytes.toDouble()
                down[index] += row.downloadBytes.toDouble()
            }
        }
        return TrafficData(
            upload = up.toList(),
            download = down.toList(),
            uploadTotal = rows.sumOf { it.uploadBytes },
            downloadTotal = rows.sumOf { it.downloadBytes },
            periodDays = days,
        )
    }

    // --- Топ моделей --------------------------------------------------------

    data class ModelRow(
        val modelId: String,
        val providerName: String,
        val providerType: String,
        val tokens: Long,
    )

    data class ModelsData(val rows: List<ModelRow>, val periodDays: Int, val total: Long)

    suspend fun topModels(db: AppDatabase, days: Int, now: Long = System.currentTimeMillis()): ModelsData {
        val providers = db.providerDao().getAllProvidersOnce().associateBy { it.id }
        val snapshot = UsageStats.snapshot(
            rows = db.tokenUsageDao().getAllUsageOnce(),
            providerNames = providers.mapValues { it.value.name },
            nowMs = now,
            days = days,
        )
        val rows = snapshot.byModel.map {
            ModelRow(
                modelId = it.modelId,
                providerName = providers[it.providerId]?.name ?: WidgetText.DASH,
                providerType = providers[it.providerId]?.type ?: "",
                tokens = it.tokens,
            )
        }
        return ModelsData(rows, days, snapshot.totalTokens)
    }

    // --- Расход по API-ключам -----------------------------------------------

    data class KeyRow(val label: String, val tokens: Long, val calls: Int)

    data class KeysData(val rows: List<KeyRow>) {
        val total: Long get() = rows.sumOf { it.tokens }
    }

    suspend fun apiKeys(db: AppDatabase): KeysData {
        val rows = db.tokenUsageDao().getUsageByApiKey()
            .filter { it.total > 0 }
            .map {
                KeyRow(
                    // Пустая метка — это запросы без ключа, и называть их надо словами.
                    label = it.apiKeyLabel.ifBlank { "Без ключа" },
                    tokens = it.total,
                    calls = it.calls,
                )
            }
            .sortedByDescending { it.tokens }
        return KeysData(rows)
    }

    // --- Скорость -----------------------------------------------------------

    data class SpeedData(
        val ttftSeries: List<Double>,
        val tpsSeries: List<Double>,
        val ttftMedian: Long,
        val tpsMedian: Double,
        val measurements: Int,
        val failures: Int,
    )

    suspend fun speed(db: AppDatabase, limit: Int = 30): SpeedData {
        val all = runCatching { db.speedHistoryDao().getAllOnce() }.getOrDefault(emptyList())
            .sortedBy { it.measuredAt }
        val ok = all.filter { it.success }.takeLast(limit)
        return SpeedData(
            ttftSeries = ok.map { it.ttftMs.toDouble() },
            tpsSeries = ok.map { it.tps },
            ttftMedian = median(ok.map { it.ttftMs.toDouble() }).toLong(),
            tpsMedian = median(ok.map { it.tps }),
            measurements = ok.size,
            failures = all.count { !it.success },
        )
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    /** Единый потолок оси: те же «красивые» шаги, что у графиков приложения. */
    fun niceCeil(value: Double): Double {
        if (value <= 0.0) return 1.0
        val steps = doubleArrayOf(1.0, 1.2, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0)
        val power = Math.pow(10.0, Math.floor(Math.log10(value)))
        for (step in steps) {
            if (value <= step * power + 1e-9) return step * power
        }
        return 10 * power
    }
}
