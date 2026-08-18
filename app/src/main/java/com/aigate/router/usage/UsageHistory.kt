package com.aigate.router.usage

import com.aigate.router.data.db.AppDatabase
import com.aigate.router.data.model.ModelPricing
import com.aigate.router.pricing.CostCalculator
import java.util.Calendar

/**
 * Агрегация истории использования по дням и простой прогноз расхода.
 * Прогноз ЯВНО помечается как оценка (estimate) и никогда не выдаётся за факт.
 */
object UsageHistory {

    data class DayUsage(
        val dayStartMs: Long,
        val promptTokens: Long,
        val completionTokens: Long,
        val calls: Int,
        val usd: Double,
        val hasUnpriced: Boolean
    )

    data class Forecast(
        val monthToDateUsd: Double,
        val projectedMonthEndUsd: Double,
        val daysElapsed: Int,
        val daysInMonth: Int,
        /** Всегда true: это экстраполяция, не факт. */
        val isEstimate: Boolean = true
    )

    /** Ежедневное использование за последние [days] дней (по локальным суткам). */
    suspend fun daily(db: AppDatabase, days: Int = 30, providerId: Long? = null): List<DayUsage> {
        val now = System.currentTimeMillis()
        val since = midnight(now).apply { add(Calendar.DAY_OF_MONTH, -(days - 1)) }.timeInMillis
        val rows = db.tokenUsageDao().getAllUsageOnce().filter {
            it.timestamp >= since && (providerId == null || it.providerId == providerId)
        }
        val providerTypes = db.providerDao().getAllProvidersOnce().associate { it.id to it.type }
        val priceCache = HashMap<String, ModelPricing?>()

        // группировка по началу суток
        data class Acc(var p: Long = 0, var c: Long = 0, var calls: Int = 0, var usd: Double = 0.0, var unpriced: Boolean = false)
        val byDay = LinkedHashMap<Long, Acc>()
        // предзаполнить дни (чтобы график был непрерывным)
        for (d in 0 until days) {
            val dayStart = midnight(now).apply { add(Calendar.DAY_OF_MONTH, -(days - 1 - d)) }.timeInMillis
            byDay[dayStart] = Acc()
        }
        for (row in rows) {
            val dayStart = midnight(row.timestamp).timeInMillis
            val acc = byDay.getOrPut(dayStart) { Acc() }
            acc.p += row.promptTokens
            acc.c += row.completionTokens
            acc.calls += 1
            val type = providerTypes[row.providerId] ?: "custom"
            val price = priceCache.getOrPut("$type::${row.modelId}") { CostCalculator.priceFor(db, type, row.modelId) }
            if (price == null) acc.unpriced = true
            else acc.usd += row.promptTokens / 1_000_000.0 * price.inputPer1M +
                row.completionTokens / 1_000_000.0 * price.outputPer1M
        }
        return byDay.entries.sortedBy { it.key }.map { (day, a) ->
            DayUsage(day, a.p, a.c, a.calls, a.usd, a.unpriced)
        }
    }

    /** Прогноз расхода до конца месяца (линейная экстраполяция среднесуточного). */
    suspend fun forecast(db: AppDatabase, providerId: Long? = null): Forecast {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val monthStart = midnight(now).apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val daysElapsed = cal.get(Calendar.DAY_OF_MONTH)

        val agg = CostCalculator.totalCostSince(db, monthStart, providerId)
        val mtd = agg.usd
        val avgPerDay = if (daysElapsed > 0) mtd / daysElapsed else 0.0
        val projected = avgPerDay * daysInMonth
        return Forecast(
            monthToDateUsd = mtd,
            projectedMonthEndUsd = projected,
            daysElapsed = daysElapsed,
            daysInMonth = daysInMonth
        )
    }

    private fun midnight(ms: Long): Calendar = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
}
