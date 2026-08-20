package com.aigate.router.widget

import android.content.Context
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.toArgb
import com.aigate.router.R
import com.aigate.router.quota.ResourcePressure
import com.aigate.router.ui.design.providerBrand

/**
 * Палитра виджета, прочитанная из ресурсов.
 *
 * Compose-темы (ui/theme/Theme.kt) для RemoteViews недоступны, поэтому цвета
 * берутся из values/widget_colors.xml и values-night/widget_colors.xml — так
 * виджет переключается вместе с системной темой без нашего участия.
 */
class WidgetTheme private constructor(private val context: Context) {

    @ColorInt
    private fun c(@ColorRes id: Int): Int = ContextCompat.getColor(context, id)

    val surface: Int get() = c(R.color.w_surface)
    val surfaceHigh: Int get() = c(R.color.w_surface_high)
    val on: Int get() = c(R.color.w_on)
    val onVariant: Int get() = c(R.color.w_on_var)
    val onPrimary: Int get() = c(R.color.w_on_prim)
    val primary: Int get() = c(R.color.w_prim)
    val outline: Int get() = c(R.color.w_outline)
    val grid: Int get() = c(R.color.w_grid)
    val axis: Int get() = c(R.color.w_axis)
    val projection: Int get() = c(R.color.w_proj)

    /** Цвета серий: индекс закреплён за сущностью, пара 0 и 1 — входные и выходные. */
    fun series(index: Int): Int = when (index % 6) {
        0 -> c(R.color.w_s0)
        1 -> c(R.color.w_s1)
        2 -> c(R.color.w_s2)
        3 -> c(R.color.w_s3)
        4 -> c(R.color.w_s4)
        else -> c(R.color.w_s5)
    }

    /** Тёмная ли поверхность — по ней решается, поднимать ли яркость знака бренда. */
    val isDark: Boolean get() = luminance(surface) < 0.5

    fun pressureColor(pressure: ResourcePressure): Int = when (pressure) {
        ResourcePressure.FREE -> c(R.color.w_succ)
        ResourcePressure.NORMAL -> c(R.color.w_p_normal)
        ResourcePressure.CONSERVE -> c(R.color.w_warn)
        ResourcePressure.CRITICAL -> c(R.color.w_err)
        ResourcePressure.UNKNOWN -> c(R.color.w_unk)
    }

    fun chipBackground(tone: WidgetTone): Int = when (tone) {
        WidgetTone.SUCCESS -> R.drawable.widget_chip_succ
        WidgetTone.WARNING -> R.drawable.widget_chip_warn
        WidgetTone.ERROR -> R.drawable.widget_chip_err
        WidgetTone.INFO -> R.drawable.widget_chip_info
        WidgetTone.NEUTRAL -> R.drawable.widget_chip_neu
    }

    fun chipForeground(tone: WidgetTone): Int = when (tone) {
        WidgetTone.SUCCESS -> c(R.color.w_on_succ_c)
        WidgetTone.WARNING -> c(R.color.w_on_warn_c)
        WidgetTone.ERROR -> c(R.color.w_on_err_c)
        WidgetTone.INFO -> c(R.color.w_on_info_c)
        WidgetTone.NEUTRAL -> c(R.color.w_on_neu_c)
    }

    /**
     * Фирменный цвет провайдера, поднятый до различимого на текущей поверхности.
     * Повторяет правило readableOn из ui/design/ProviderBrand.kt: тон бренда
     * сохраняется, меняется только светлота.
     */
    fun brand(name: String, type: String = ""): Int {
        val base = providerBrand(name, type).color.toArgb()
        val lum = luminance(base)
        return when {
            isDark && lum < 0.16 -> mix(base, 0xFFFFFFFF.toInt(), 0.62f)
            !isDark && lum > 0.82 -> mix(base, 0xFF000000.toInt(), 0.35f)
            else -> base
        }
    }

    fun monogram(name: String, type: String = ""): String = providerBrand(name, type).monogram

    /** Цвет монограммы на брендовой подложке: на светлом бренде буквы тёмные. */
    fun ink(@ColorInt background: Int): Int =
        if (luminance(background) > 0.42) 0xFF06121F.toInt() else 0xFFFFFFFF.toInt()

    companion object {
        fun of(context: Context): WidgetTheme = WidgetTheme(context)

        private fun channel(value: Int): Double {
            val v = value / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }

        /** Относительная яркость по WCAG — тот же расчёт, что у Compose Color.luminance(). */
        fun luminance(@ColorInt color: Int): Double {
            val r = channel((color shr 16) and 0xFF)
            val g = channel((color shr 8) and 0xFF)
            val b = channel(color and 0xFF)
            return 0.2126 * r + 0.7152 * g + 0.0722 * b
        }

        @ColorInt
        fun mix(@ColorInt from: Int, @ColorInt to: Int, fraction: Float): Int {
            fun ch(shift: Int): Int {
                val a = (from shr shift) and 0xFF
                val b = (to shr shift) and 0xFF
                return (a + (b - a) * fraction).toInt().coerceIn(0, 255)
            }
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        @ColorInt
        fun withAlpha(@ColorInt color: Int, alpha: Float): Int =
            ((alpha * 255).toInt().coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)
    }
}

/** Тона вердиктов — те же пять, что у StatusChip в приложении. */
enum class WidgetTone { SUCCESS, WARNING, ERROR, INFO, NEUTRAL }

fun ResourcePressure.widgetTone(): WidgetTone = when (this) {
    ResourcePressure.FREE -> WidgetTone.SUCCESS
    ResourcePressure.NORMAL -> WidgetTone.INFO
    ResourcePressure.CONSERVE -> WidgetTone.WARNING
    ResourcePressure.CRITICAL -> WidgetTone.ERROR
    ResourcePressure.UNKNOWN -> WidgetTone.NEUTRAL
}

/** Иконка-заглушка не нужна: все знаки виджет рисует сам в WidgetDraw. */
@DrawableRes
internal val NO_DRAWABLE: Int = 0
