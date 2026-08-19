package com.aigate.router.quota

import com.aigate.router.data.model.QuotaSnapshot

/**
 * Темп расхода квоты и его последствия.
 *
 * Фиксированные доли остатка здесь непригодны: 29 % у одного тарифа — сутки
 * работы, у другого — месяц простоя. Поэтому всё считается из собственной
 * истории снимков квоты, а при отсутствии истории расчёт молчит, не подставляя
 * выдуманных значений.
 */
object QuotaBurn {

    private const val HOUR_MS = 3_600_000.0
    private const val DAY_MS = 86_400_000L
    private const val RATE_WINDOW_HOURS = 24.0
    private const val PEAK_WINDOW_DAYS = 30

    /** Единиц квоты в час: средний темп за сутки и пиковый суточный за месяц. */
    data class Rate(val perHour: Double, val peakPerHour: Double)

    /**
     * @param exhaustAtMs момент исчерпания, если квота кончится раньше сброса; иначе null
     * @param surplus сколько сгорит неиспользованным при нынешнем темпе
     * @param hoursToReset часов до сброса
     * @param hoursNeededAtPeak часов работы на пиковом темпе, чтобы израсходовать [surplus]
     */
    data class Outlook(
        val exhaustAtMs: Long?,
        val surplus: Double,
        val hoursToReset: Double,
        val hoursNeededAtPeak: Double,
    )

    /**
     * Средний и пиковый темп расхода. Считается по приросту израсходованного:
     * участки, где счётчик упал (произошёл сброс квоты), в расчёт не идут.
     */
    fun rate(history: List<QuotaSnapshot>, now: Long): Rate? {
        val all = history.filter { it.used != null }.sortedBy { it.updatedAt }
        // Сравнивать снимки в разных единицах нельзя: пул мог считаться локально
        // в долларах, а потом провайдер начал отдавать проценты — переход 0 USD →
        // 33 % не расход, а смена шкалы, и без этого фильтра он давал бы
        // мгновенный «конец квоты через полчаса».
        val unit = all.lastOrNull()?.unit
        val ordered = all.filter { it.unit.equals(unit, ignoreCase = true) }
        if (ordered.size < 2) return null

        val windowStart = now - (RATE_WINDOW_HOURS * HOUR_MS).toLong()
        val window = ordered.filter { it.updatedAt >= windowStart }
        val avg = paceOf(window.ifEmpty { ordered.takeLast(2) }) ?: return null
        if (avg <= 0.0) return null

        // Пик ищем скользящим суточным окном, а не группировкой по календарным
        // сутками: границы суток разрезали бы участки расхода и занижали пик.
        val peakStart = now - PEAK_WINDOW_DAYS * DAY_MS
        val recent = ordered.filter { it.updatedAt >= peakStart }
        val windowMs = (RATE_WINDOW_HOURS * HOUR_MS).toLong()
        val peak = recent.indices.mapNotNull { end ->
            val from = recent[end].updatedAt - windowMs
            paceOf(recent.filter { it.updatedAt in from..recent[end].updatedAt })
        }.maxOrNull() ?: avg

        return Rate(perHour = avg, peakPerHour = maxOf(peak, avg))
    }

    /**
     * Темп по последовательности снимков: суммируем только положительные приросты
     * израсходованного и делим на прошедшее время. Отрицательный прирост означает
     * сброс квоты и обнуляет вклад этого участка, а не даёт отрицательный темп.
     */
    private fun paceOf(points: List<QuotaSnapshot>): Double? {
        if (points.size < 2) return null
        val hours = (points.last().updatedAt - points.first().updatedAt) / HOUR_MS
        if (hours <= 0.0) return null
        var consumed = 0.0
        for (i in 1 until points.size) {
            val delta = (points[i].used ?: continue) - (points[i - 1].used ?: continue)
            if (delta > 0.0) consumed += delta
        }
        if (consumed <= 0.0) return null
        return consumed / hours
    }

    /**
     * Что произойдёт раньше: исчерпание квоты или её сброс. Это две стороны одного
     * сравнения, поэтому оба уведомления («кончится раньше» и «сгорит
     * неиспользованной») строятся на одном расчёте.
     */
    fun outlook(remaining: Double, resetsAt: Long, rate: Rate, now: Long): Outlook? {
        val hoursToReset = (resetsAt - now) / HOUR_MS
        if (hoursToReset <= 0.0 || rate.perHour <= 0.0) return null

        val projected = rate.perHour * hoursToReset
        if (projected >= remaining) {
            val hoursLeft = remaining / rate.perHour
            return Outlook(
                exhaustAtMs = now + (hoursLeft * HOUR_MS).toLong(),
                surplus = 0.0,
                hoursToReset = hoursToReset,
                hoursNeededAtPeak = 0.0,
            )
        }
        val surplus = remaining - projected
        val peak = if (rate.peakPerHour > 0.0) rate.peakPerHour else rate.perHour
        return Outlook(
            exhaustAtMs = null,
            surplus = surplus,
            hoursToReset = hoursToReset,
            hoursNeededAtPeak = surplus / peak,
        )
    }
}
