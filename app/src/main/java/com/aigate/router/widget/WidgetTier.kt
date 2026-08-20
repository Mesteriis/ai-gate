package com.aigate.router.widget

import android.appwidget.AppWidgetManager
import android.os.Bundle

/**
 * Ярус размера виджета в клетках лаунчера.
 *
 * Названия — по сетке телефона 412 dp: 2×1 = 184 × 86, 4×1 = 380 × 86,
 * 2×2 = 184 × 184, 4×2 = 380 × 184, 4×4 = 380 × 380. Ярус решает, сколько
 * строк и какого размера график поместится, а не только высоту текста.
 */
enum class WidgetTier(val cells: String) {
    ROW_NARROW("2×1"),
    ROW_WIDE("4×1"),
    SQUARE("2×2"),
    WIDE("4×2"),
    LARGE("4×4");

    val isWide: Boolean get() = this == ROW_WIDE || this == WIDE || this == LARGE
    val isRow: Boolean get() = this == ROW_NARROW || this == ROW_WIDE
}

object WidgetTiers {

    /** Порог по ширине, за которым виджет считается четырёхклеточным. */
    private const val WIDE_DP = 260

    /** Порог по высоте между строкой и квадратом. */
    private const val ROW_DP = 120

    /** Порог по высоте, за которым помещается большой ярус. */
    private const val LARGE_DP = 260

    /** Чистое отображение размера в ярус — то, что проверяется тестом. */
    fun of(minWidthDp: Int, minHeightDp: Int): WidgetTier = when {
        minHeightDp < ROW_DP -> if (minWidthDp < WIDE_DP) WidgetTier.ROW_NARROW else WidgetTier.ROW_WIDE
        minWidthDp < WIDE_DP -> WidgetTier.SQUARE
        minHeightDp < LARGE_DP -> WidgetTier.WIDE
        else -> WidgetTier.LARGE
    }

    /**
     * Ярус из опций экземпляра виджета. Берём минимальные размеры: лаунчер
     * сообщает их для текущей ориентации, и по ним виджет обязан выглядеть целым.
     */
    fun fromOptions(options: Bundle?, fallback: WidgetTier = WidgetTier.WIDE): WidgetTier {
        if (options == null) return fallback
        val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        if (w <= 0 || h <= 0) return fallback
        return of(w, h)
    }
}
