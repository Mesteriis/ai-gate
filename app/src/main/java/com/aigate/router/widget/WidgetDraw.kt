package com.aigate.router.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.annotation.ColorInt
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Растровая графика виджетов: кольца, донат, столбцы, линии, пилюли и знаки.
 *
 * Почему растр. RemoteViews не умеет ни своих View, ни задавать размеры детей до
 * API 31, поэтому геометрия рисуется на Canvas и отдаётся как Bitmap. Текст в
 * растр НЕ попадает — подписи остаются настоящими TextView и потому резкие.
 *
 * Почему с ограничением плотности. Bitmap уезжает в лаунчер через Binder, а его
 * буфер — около мегабайта на транзакцию. Поэтому масштаб растра ограничен
 * бюджетом пикселей: непрозрачные графики (на плоской поверхности) рисуются
 * RGB_565 и почти всегда получают полную плотность экрана, а прозрачные знаки —
 * ARGB_8888 с более скромным потолком.
 */
object WidgetDraw {

    /** Потолок для прозрачных растров: 40 000 px ≈ 160 КБ в ARGB_8888. */
    private const val MAX_PX_ALPHA = 40_000

    /** Потолок для непрозрачных: 170 000 px ≈ 340 КБ в RGB_565. */
    private const val MAX_PX_OPAQUE = 170_000

    private const val GAP_DEGREES = 3f
    private const val START_ANGLE = -90f

    private class Target(val bitmap: Bitmap, val canvas: Canvas, val scale: Float) {
        /** dp в пиксели растра. */
        fun px(dp: Float): Float = dp * scale
    }

    private fun target(
        context: Context,
        widthDp: Float,
        heightDp: Float,
        @ColorInt background: Int?,
    ): Target {
        val density = context.resources.displayMetrics.density
        val area = (widthDp * heightDp).coerceAtLeast(1f)
        val budget = if (background == null) MAX_PX_ALPHA else MAX_PX_OPAQUE
        val scale = min(density, sqrt(budget / area)).coerceAtLeast(1f)
        val config = if (background == null) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        val bitmap = Bitmap.createBitmap(
            (widthDp * scale).roundToInt().coerceAtLeast(1),
            (heightDp * scale).roundToInt().coerceAtLeast(1),
            config,
        )
        // Плотность растра выставляется под выбранный масштаб, иначе ImageView
        // с wrap_content показал бы картинку не того размера, что задумано в dp.
        bitmap.density = (160f * scale).roundToInt()
        val canvas = Canvas(bitmap)
        if (background != null) canvas.drawColor(background)
        return Target(bitmap, canvas, scale)
    }

    private fun fill(@ColorInt color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    private fun stroke(@ColorInt color: Int, widthPx: Float, round: Boolean = true) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = widthPx
            strokeCap = if (round) Paint.Cap.ROUND else Paint.Cap.BUTT
            strokeJoin = Paint.Join.ROUND
        }

    /**
     * Пилюля давления: трек во всю ширину, заливка — израсходованная доля.
     * Прозрачная, потому что лежит и на плоской поверхности, и на градиенте витрины.
     */
    fun pill(
        context: Context,
        widthDp: Float,
        heightDp: Float,
        usedFraction: Double?,
        @ColorInt fillColor: Int,
        @ColorInt trackColor: Int,
    ): Bitmap {
        val t = target(context, widthDp, heightDp, null)
        val h = t.px(heightDp)
        val r = h / 2f
        t.canvas.drawRoundRect(RectF(0f, 0f, t.px(widthDp), h), r, r, fill(trackColor))
        val fraction = (usedFraction ?: 0.0).coerceIn(0.0, 1.0)
        if (fraction > 0.0) {
            val w = (t.px(widthDp) * fraction).toFloat().coerceAtLeast(h)
            t.canvas.drawRoundRect(RectF(0f, 0f, w, h), r, r, fill(fillColor))
        }
        return t.bitmap
    }

    /** Кольцо квоты: дуга от 12 часов по часовой, круглые торцы, как в приложении. */
    fun ring(
        context: Context,
        sizeDp: Float,
        strokeDp: Float,
        usedFraction: Double?,
        @ColorInt color: Int,
        @ColorInt trackColor: Int,
        @ColorInt background: Int? = null,
    ): Bitmap {
        val t = target(context, sizeDp, sizeDp, background)
        val stroke = t.px(strokeDp)
        val inset = stroke / 2f
        val box = RectF(inset, inset, t.px(sizeDp) - inset, t.px(sizeDp) - inset)
        t.canvas.drawArc(box, 0f, 360f, false, stroke(trackColor, stroke, round = false))
        val sweep = ((usedFraction ?: 0.0).coerceIn(0.0, 1.0) * 360.0).toFloat()
        // Нулевую дугу не рисуем: круглый торец превратил бы её в точку, а точка
        // на кольце читается как «немного израсходовано».
        if (sweep > 0.5f) {
            t.canvas.drawArc(box, START_ANGLE, sweep, false, stroke(color, stroke))
        }
        return t.bitmap
    }

    /**
     * Два кольца одного ресурса: у подписки Claude окна идут парой — сессия и
     * неделя. Внутреннее кольцо тоньше внешнего в тех же пропорциях, что и на
     * плитке приложения (ui/design/QuotaIndicators.kt).
     */
    fun dualRing(
        context: Context,
        sizeDp: Float,
        strokeDp: Float,
        outerFraction: Double?,
        innerFraction: Double?,
        @ColorInt outerColor: Int,
        @ColorInt innerColor: Int,
        @ColorInt trackColor: Int,
        @ColorInt background: Int? = null,
    ): Bitmap {
        val t = target(context, sizeDp, sizeDp, background)
        val outer = t.px(strokeDp)
        val inner = outer * 0.62f
        val gap = outer * 0.85f
        val centre = t.px(sizeDp) / 2f

        fun arc(radius: Float, width: Float, fraction: Double?, @ColorInt color: Int) {
            val box = RectF(centre - radius, centre - radius, centre + radius, centre + radius)
            t.canvas.drawArc(box, 0f, 360f, false, stroke(trackColor, width, round = false))
            val sweep = ((fraction ?: 0.0).coerceIn(0.0, 1.0) * 360.0).toFloat()
            if (sweep > 0.5f) t.canvas.drawArc(box, START_ANGLE, sweep, false, stroke(color, width))
        }

        arc(centre - outer / 2f, outer, outerFraction, outerColor)
        arc(centre - outer - gap - inner / 2f, inner, innerFraction, innerColor)
        return t.bitmap
    }

    /** Донат долей: сектора с зазором 3°, начало в 12 часов. */
    fun donut(
        context: Context,
        sizeDp: Float,
        strokeDp: Float,
        segments: List<Pair<Double, Int>>,
        @ColorInt trackColor: Int,
        @ColorInt background: Int,
    ): Bitmap {
        val t = target(context, sizeDp, sizeDp, background)
        val stroke = t.px(strokeDp)
        val inset = stroke / 2f
        val box = RectF(inset, inset, t.px(sizeDp) - inset, t.px(sizeDp) - inset)
        val total = segments.sumOf { it.first }
        if (total <= 0.0) {
            t.canvas.drawArc(box, 0f, 360f, false, stroke(trackColor, stroke, round = false))
            return t.bitmap
        }
        var angle = START_ANGLE
        for ((value, color) in segments) {
            val sweep = (value / total * 360.0).toFloat()
            val drawn = sweep - GAP_DEGREES
            if (drawn > 0.5f) {
                t.canvas.drawArc(box, angle + GAP_DEGREES / 2f, drawn, false, stroke(color, stroke, round = false))
            }
            angle += sweep
        }
        return t.bitmap
    }

    /**
     * Столбцы «входные и выходные» по дням: пара серий в стопке, верхние углы
     * скруглены на 4dp только у самого верхнего сегмента.
     */
    fun stackedBars(
        context: Context,
        widthDp: Float,
        heightDp: Float,
        columns: List<Pair<Double, Double>>,
        axisMax: Double,
        @ColorInt colorLower: Int,
        @ColorInt colorUpper: Int,
        @ColorInt gridColor: Int,
        @ColorInt background: Int,
    ): Bitmap {
        val t = target(context, widthDp, heightDp, background)
        val w = t.px(widthDp)
        val h = t.px(heightDp)
        val top = t.px(4f)
        val bottom = h - t.px(1f)
        val gridPaint = stroke(gridColor, t.px(1f), round = false)
        for (fraction in listOf(0.0, 0.5, 1.0)) {
            val y = bottom - ((bottom - top) * fraction).toFloat()
            t.canvas.drawLine(0f, y, w, y, gridPaint)
        }
        if (columns.isEmpty() || axisMax <= 0.0) return t.bitmap

        val slot = w / columns.size
        val barWidth = min(slot * 0.62f, t.px(28f))
        val gap = t.px(2f)
        val radius = t.px(4f)
        val lower = fill(colorLower)
        val upper = fill(colorUpper)
        columns.forEachIndexed { index, (low, high) ->
            val centre = slot * index + slot / 2f
            val left = centre - barWidth / 2f
            val right = left + barWidth
            fun y(value: Double): Float =
                bottom - ((value / axisMax).coerceIn(0.0, 1.0) * (bottom - top)).toFloat()

            val yLow = y(low)
            val yTop = y(low + high)
            if (bottom - yLow > 0.5f) {
                t.canvas.drawRect(RectF(left, yLow, right, bottom), lower)
            }
            val upperHeight = (yLow - gap) - yTop
            if (upperHeight > 0.5f) {
                t.canvas.drawPath(roundedTop(left, yTop, barWidth, upperHeight, radius), upper)
            }
        }
        return t.bitmap
    }

    private fun roundedTop(left: Float, top: Float, width: Float, height: Float, radius: Float): Path {
        val r = min(min(radius, height), width / 2f)
        val right = left + width
        val bottom = top + height
        return Path().apply {
            moveTo(left, bottom)
            lineTo(left, top + r)
            quadTo(left, top, left + r, top)
            lineTo(right - r, top)
            quadTo(right, top, right, top + r)
            lineTo(right, bottom)
            close()
        }
    }

    /**
     * Накопительная линия с прогнозом: факт заливкой и сплошной линией,
     * прогноз — пунктиром цвета chartProjection, как на графиках приложения.
     */
    fun lineWithForecast(
        context: Context,
        widthDp: Float,
        heightDp: Float,
        factValues: List<Double>,
        slots: Int,
        axisMax: Double,
        projectedEnd: Double?,
        @ColorInt lineColor: Int,
        @ColorInt gridColor: Int,
        @ColorInt projectionColor: Int,
        @ColorInt background: Int,
    ): Bitmap {
        val t = target(context, widthDp, heightDp, background)
        val w = t.px(widthDp)
        val h = t.px(heightDp)
        val top = t.px(4f)
        val bottom = h - t.px(1f)
        val gridPaint = stroke(gridColor, t.px(1f), round = false)
        for (fraction in listOf(0.0, 0.5, 1.0)) {
            val y = bottom - ((bottom - top) * fraction).toFloat()
            t.canvas.drawLine(0f, y, w, y, gridPaint)
        }
        if (factValues.size < 2 || axisMax <= 0.0) return t.bitmap

        val columns = slots.coerceAtLeast(factValues.size)
        fun x(index: Int): Float = (index + 1f) / columns * w
        fun y(value: Double): Float =
            bottom - ((value / axisMax).coerceIn(0.0, 1.0) * (bottom - top)).toFloat()

        val path = Path()
        factValues.forEachIndexed { index, value ->
            if (index == 0) path.moveTo(x(index), y(value)) else path.lineTo(x(index), y(value))
        }
        val area = Path(path).apply {
            lineTo(x(factValues.lastIndex), bottom)
            lineTo(x(0), bottom)
            close()
        }
        t.canvas.drawPath(area, fill(WidgetTheme.withAlpha(lineColor, 0.14f)))

        if (projectedEnd != null) {
            val dash = stroke(projectionColor, t.px(2f)).apply {
                pathEffect = android.graphics.DashPathEffect(
                    floatArrayOf(t.px(7f), t.px(5f)), 0f
                )
            }
            t.canvas.drawLine(
                x(factValues.lastIndex), y(factValues.last()),
                x(columns - 1), y(projectedEnd),
                dash,
            )
        }
        t.canvas.drawPath(path, stroke(lineColor, t.px(2f)))

        // «Где мы сейчас»: точка последнего факта в кольце цвета поверхности.
        val cx = x(factValues.lastIndex)
        val cy = y(factValues.last())
        t.canvas.drawCircle(cx, cy, t.px(6.5f), fill(background))
        t.canvas.drawCircle(cx, cy, t.px(4.5f), fill(lineColor))
        return t.bitmap
    }

    /** Спарклайн без осей: линия, заливка и точка на конце. */
    fun sparkline(
        context: Context,
        widthDp: Float,
        heightDp: Float,
        values: List<Double>,
        @ColorInt color: Int,
        @ColorInt background: Int,
        invert: Boolean = false,
    ): Bitmap {
        val t = target(context, widthDp, heightDp, background)
        if (values.size < 2) return t.bitmap
        val w = t.px(widthDp)
        val h = t.px(heightDp)
        val pad = t.px(2f)
        val max = values.max()
        val min = values.min()
        val span = (max - min).takeIf { it > 0.0 } ?: 1.0
        val path = Path()
        values.forEachIndexed { index, value ->
            val fraction = ((value - min) / span).let { if (invert) 1.0 - it else it }
            val x = pad + (w - 2 * pad) * index / (values.size - 1f)
            val y = (h - pad - (h - 2 * pad) * fraction).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val area = Path(path).apply {
            lineTo(w - pad, h)
            lineTo(pad, h)
            close()
        }
        t.canvas.drawPath(area, fill(WidgetTheme.withAlpha(color, 0.16f)))
        t.canvas.drawPath(path, stroke(color, t.px(2f)))
        val lastFraction = ((values.last() - min) / span).let { if (invert) 1.0 - it else it }
        val lx = w - pad
        val ly = (h - pad - (h - 2 * pad) * lastFraction).toFloat()
        t.canvas.drawCircle(lx, ly, t.px(4.5f), fill(background))
        t.canvas.drawCircle(lx, ly, t.px(3f), fill(color))
        return t.bitmap
    }

    /** Знак провайдера: монограмма на брендовой подложке, радиус size/3.6. */
    fun avatar(
        context: Context,
        sizeDp: Float,
        @ColorInt background: Int,
        @ColorInt ink: Int,
        monogram: String,
    ): Bitmap {
        val t = target(context, sizeDp, sizeDp, null)
        val size = t.px(sizeDp)
        val radius = size / 3.6f
        t.canvas.drawRoundRect(RectF(0f, 0f, size, size), radius, radius, fill(background))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = size * 0.42f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val metrics = paint.fontMetrics
        val baseline = size / 2f - (metrics.ascent + metrics.descent) / 2f
        t.canvas.drawText(monogram, size / 2f, baseline, paint)
        return t.bitmap
    }

    /** Точка серии или состояния. */
    fun dot(context: Context, sizeDp: Float, @ColorInt color: Int): Bitmap {
        val t = target(context, sizeDp, sizeDp, null)
        val r = t.px(sizeDp) / 2f
        t.canvas.drawCircle(r, r, r, fill(color))
        return t.bitmap
    }

    /** Знак врат: арка на двух опорах — тот же силуэт, что у героя приложения. */
    fun gateGlyph(context: Context, sizeDp: Float, @ColorInt color: Int): Bitmap {
        val t = target(context, sizeDp, sizeDp, null)
        val s = t.px(sizeDp)
        val paint = stroke(color, s / 12f)
        val inset = s * 0.16f
        val bottom = s - inset
        val shoulder = s * 0.46f
        val path = Path().apply {
            moveTo(inset, bottom)
            lineTo(inset, shoulder)
            quadTo(inset, inset, s / 2f, inset)
            quadTo(s - inset, inset, s - inset, shoulder)
            lineTo(s - inset, bottom)
        }
        t.canvas.drawPath(path, paint)
        t.canvas.drawLine(s / 2f, bottom, s / 2f, s * 0.55f, paint)
        return t.bitmap
    }

    /** Значок пустого состояния: три столбика на оси. */
    fun emptyGlyph(context: Context, sizeDp: Float, @ColorInt color: Int): Bitmap {
        val t = target(context, sizeDp, sizeDp, null)
        val s = t.px(sizeDp)
        val paint = stroke(color, s / 16f)
        val base = s * 0.82f
        t.canvas.drawLine(s * 0.16f, base, s * 0.84f, base, paint)
        t.canvas.drawLine(s * 0.30f, base, s * 0.30f, s * 0.54f, paint)
        t.canvas.drawLine(s * 0.50f, base, s * 0.50f, s * 0.34f, paint)
        t.canvas.drawLine(s * 0.70f, base, s * 0.70f, s * 0.62f, paint)
        return t.bitmap
    }
}
