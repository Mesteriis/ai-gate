package com.aigate.router.ui.design.charts

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Чистая математика графиков, вынесенная из отрисовки: «красивый» потолок оси,
 * медиана/перцентиль для сводок скорости и правило прореживания подписей X.
 * Раньше ось Y подписывалась сырым максимумом данных («473,0K»), а подписи
 * последних дней сталкивались с меткой «сегодня».
 */
object ChartMath {

    private val NICE_STEPS = floatArrayOf(1f, 1.2f, 1.5f, 2f, 2.5f, 3f, 4f, 5f, 6f, 8f, 10f)

    /** Ближайший сверху «чистый» потолок оси: 473 → 500, 58 → 60, 8560 → 10000. */
    fun niceCeil(max: Float): Float {
        if (max <= 0f || !max.isFinite()) return 1f
        val exp = 10.0.pow(floor(log10(max.toDouble()))).toFloat()
        for (step in NICE_STEPS) {
            val candidate = step * exp
            if (candidate >= max) return candidate
        }
        return 10f * exp
    }

    /** Медиана; пустой список — 0, чтобы графики не падали на вырожденных данных. */
    fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    /** Перцентиль [q] в 0..1 с линейной интерполяцией (p95 для сводки скорости). */
    fun percentile(values: List<Float>, q: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val pos = (sorted.size - 1) * q.coerceIn(0f, 1f)
        val lo = floor(pos).toInt()
        val hi = ceil(pos).toInt()
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }

    /** Изменение к прошлому периоду в процентах; null — сравнивать не с чем. */
    fun deltaPercent(current: Double, previous: Double): Int? {
        if (previous <= 0.0) return null
        return ((current - previous) / previous * 100.0).roundToInt()
    }

    /** Шаг подписей X по числу колонок: 7 → 1, 14 → 2, 30 → 5. */
    fun labelEvery(count: Int): Int {
        if (count <= 0) return 1
        return ceil(count / 7.0).toInt().coerceAtLeast(1)
    }

    /**
     * Индексы колонок с подписью X: каждые [every], последняя — всегда, а
     * предпоследняя регулярная прячется, чтобы не столкнуться с «сегодня».
     */
    fun axisLabelIndices(count: Int, every: Int): Set<Int> {
        if (count <= 0) return emptySet()
        val step = every.coerceAtLeast(1)
        val indices = mutableSetOf(count - 1)
        for (i in 0 until count) {
            if (i % step == 0 && count - 1 - i >= step) indices.add(i)
        }
        return indices
    }
}
