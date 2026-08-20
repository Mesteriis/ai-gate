package com.aigate.router.ui.design.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.rememberChartReveal
import kotlin.math.abs

/** Одна точка ряда: x — время/индекс, y — значение. */
data class ChartPoint(val x: Float, val y: Float)

/**
 * Начальный отрезок пути по прогрессу [progress].
 *
 * Путь обрезаем через [PathMeasure], а не проявляем целиком через альфу: линия
 * должна вычерчиваться слева направо, как будто её ведут по холсту. Обрезка по
 * длине, а не по числу точек, даёт равномерную скорость на неравномерных данных.
 */
private fun Path.revealed(progress: Float): Path {
    if (progress >= 1f) return this
    val measure = PathMeasure()
    measure.setPath(this, false)
    val length = measure.length
    // Вырожденный путь (одна точка) измерять нечего — отдаём как есть.
    if (length <= 0f) return this
    val head = Path()
    measure.getSegment(0f, length * progress.coerceAtLeast(0f), head, true)
    return head
}

/**
 * Прозрачность «хвостовых» деталей — акцентной точки, маркеров: они не должны
 * появляться раньше, чем линия дойдёт до них, иначе точка «сейчас» висит
 * в пустоте. [from] — доля прогресса, с которой начинается проявление.
 */
private fun tailAlpha(reveal: Float, from: Float): Float =
    ((reveal - from) / (1f - from).nonZero()).coerceIn(0f, 1f)

/**
 * Ряд данных линейного графика.
 * [projected] — рисуется пунктиром (прогноз к концу периода).
 * [showPoints] — кружки на каждой точке; по умолчанию выключено, потому что
 * россыпь кружков на плотных рядах читается как шум, а не как данные.
 * [emphasizeLast] — последняя точка непрогнозного ряда рисуется акцентом
 * («где мы сейчас»), даже когда остальные точки скрыты.
 */
data class LineSeries(
    val label: String,
    val points: List<ChartPoint>,
    val colorIndex: Int = 0,
    val projected: Boolean = false,
    val filled: Boolean = false,
    val showPoints: Boolean = false,
    val emphasizeLast: Boolean = true,
)

/**
 * Точка-событие на графике (например «исчерпание чт ~18:30»): кружок цветом
 * серии [colorIndex] с кольцом цвета поверхности, чтобы отделяться от линий.
 * [labelAbove] — подпись над точкой, иначе под ней; у краёв холста подпись
 * прижимается внутрь, чтобы текст не обрезался.
 */
data class ChartMarker(
    val x: Float,
    val y: Float,
    val colorIndex: Int,
    val label: String? = null,
    val labelAbove: Boolean = true,
)

/**
 * Линейный/area-график с осями, сеткой и подписями. В отличие от прежнего
 * «слепого» графика подписи осей действительно рендерятся.
 *
 * [xLabelAt] — форматирование подписи по значению x (например, времени),
 * [yLabelAt] — по значению y. Обе оси подписываются 3 значениями (min/mid/max).
 * [referenceY] — горизонтальная линия-ориентир (бюджет/лимит).
 * [niceMax] — «чистый» потолок оси Y через [ChartMath.niceCeil]; опционально,
 * чтобы не сдвигать масштаб существующих графиков по умолчанию.
 * [medianLabel] — пунктир медианы первой непрогнозной серии с подписью справа.
 * [markers] — точки-события поверх рядов (см. [ChartMarker]).
 * [selectedIndex]/[onSelectPoint] — выбор точки первой непрогнозной серии
 * касанием: берётся ближайшая по X, повторное касание той же точки снимает
 * выбор (колбэк получает null).
 * [xTicks] — свои позиции/тексты подписей X вместо «min/mid/max»
 * (например ось «пн вт ср чт пт» у квоты).
 * [animate] выключает моторику для экспорта и тестов, где нужен финальный кадр.
 */
@Composable
fun LineChart(
    series: List<LineSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    xLabelAt: (Float) -> String = { it.toInt().toString() },
    yLabelAt: (Float) -> String = { it.toInt().toString() },
    referenceY: Float? = null,
    referenceLabel: String? = null,
    niceMax: Boolean = false,
    medianLabel: ((Float) -> String)? = null,
    markers: List<ChartMarker> = emptyList(),
    selectedIndex: Int? = null,
    onSelectPoint: ((Int?) -> Unit)? = null,
    xTicks: List<Pair<Float, String>>? = null,
    animate: Boolean = true,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = Gateway.colors.chartAxisLabel)
    val gridColor = Gateway.colors.chartGrid
    val palette = Gateway.colors.chartSeries
    val projectionColor = Gateway.colors.chartProjection
    val refColor = MaterialTheme.colorScheme.onSurfaceVariant
    val axisColor = Gateway.colors.chartAxisLabel
    val selectedColor = Gateway.colors.chartSelected
    // Кольца вокруг акцентных точек красятся цветом поверхности: так точка
    // визуально отделяется от линии и заливки в обеих темах.
    val surfaceColor = MaterialTheme.colorScheme.surface

    val all = series.flatMap { it.points }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
    ) {
        if (all.isEmpty()) return@Box
        val minX = all.minOf { it.x }
        val maxX = all.maxOf { it.x }
        val rawMinY = all.minOf { it.y }
        val rawMaxY = all.maxOf { it.y }
        val minY = minOf(0f, rawMinY)
        val maxY = maxOf(referenceY ?: rawMaxY, rawMaxY)
            .let { if (it == minY) minY + 1f else it }
            .let { if (niceMax) ChartMath.niceCeil(it) else it }

        // Линия вычерчивается слева направо: график читается как развитие
        // метрики во времени, а не как готовая картинка. Ключ — состав рядов и
        // потолок оси: прогресс привязан к текущим данным, а не к позиции
        // композиции.
        val reveal = if (animate) rememberChartReveal(series.size to maxY) else 1f

        // Ширина подписей Y считается до Canvas: одна и та же геометрия плота
        // нужна и отрисовке, и обработчику касаний.
        val yLabels = listOf(maxY, (maxY + minY) / 2f, minY)
        val yLabelWidth = yLabels.maxOf {
            measurer.measure(yLabelAt(it), labelStyle).size.width
        }.toFloat()

        // Выбор точки работает по первой непрогнозной серии: именно она несёт
        // фактические данные, прогноз выбирать бессмысленно.
        val interactive = series.firstOrNull { !it.projected }
        val tapModifier =
            if (onSelectPoint != null && interactive != null && interactive.points.isNotEmpty()) {
                Modifier.pointerInput(series, selectedIndex, minX, maxX, yLabelWidth) {
                    detectTapGestures { tap ->
                        val left = yLabelWidth + 6.dp.toPx()
                        val plotW = (size.width - left).coerceAtLeast(1f)
                        // Хит-зона — вся высота плота: попасть пальцем в саму
                        // линию на телефоне слишком трудно.
                        if (tap.x >= left) {
                            val nearest = interactive.points.indices.minByOrNull { i ->
                                val px = left +
                                    (interactive.points[i].x - minX) / (maxX - minX).nonZero() * plotW
                                abs(px - tap.x)
                            }
                            // Повторное касание выбранной точки снимает выбор.
                            onSelectPoint(if (nearest == selectedIndex) null else nearest)
                        }
                    }
                }
            } else {
                Modifier
            }

        Canvas(Modifier.fillMaxWidth().height(height).then(tapModifier)) {
            val xLabelHeight = measurer.measure("0", labelStyle).size.height.toFloat()

            val left = yLabelWidth + 6.dp.toPx()
            val right = size.width
            val top = xLabelHeight / 2f
            val bottom = size.height - xLabelHeight - 4.dp.toPx()
            val plotW = (right - left).coerceAtLeast(1f)
            val plotH = (bottom - top).coerceAtLeast(1f)

            fun px(x: Float) = left + (x - minX) / (maxX - minX).nonZero() * plotW
            fun py(y: Float) = bottom - (y - minY) / (maxY - minY).nonZero() * plotH

            // Сетка + подписи оси Y
            yLabels.forEach { v ->
                val y = py(v)
                drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
                val t = measurer.measure(yLabelAt(v), labelStyle)
                drawText(measurer, yLabelAt(v), Offset(0f, y - t.size.height / 2f), labelStyle)
            }

            // Линия-ориентир (бюджет)
            referenceY?.let { rv ->
                val y = py(rv)
                drawLine(
                    color = refColor,
                    start = Offset(left, y),
                    end = Offset(right, y),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(6.dp.toPx(), 4.dp.toPx())
                    ),
                )
                referenceLabel?.let { rl ->
                    val t = measurer.measure(rl, labelStyle)
                    drawText(
                        measurer, rl,
                        Offset(right - t.size.width, y - t.size.height - 2.dp.toPx()),
                        labelStyle
                    )
                }
            }

            // Медиана первой непрогнозной серии: спокойный ориентир «обычного»
            // уровня, в отличие от жёсткого лимита referenceY.
            medianLabel?.let { fmt ->
                val base = interactive?.points?.map { it.y }.orEmpty()
                if (base.isNotEmpty()) {
                    val median = ChartMath.median(base)
                    val y = py(median)
                    // Медиана — вывод из данных, а не разметка холста, поэтому
                    // проявляется вместе с линией, а не стоит на пустом графике.
                    val medianColor = axisColor.copy(alpha = axisColor.alpha * reveal)
                    drawLine(
                        color = medianColor,
                        start = Offset(left, y),
                        end = Offset(right, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(4.dp.toPx(), 4.dp.toPx())
                        ),
                    )
                    val text = fmt(median)
                    val medianStyle = labelStyle.copy(color = medianColor)
                    val t = measurer.measure(text, medianStyle)
                    drawText(
                        measurer, text,
                        Offset(right - t.size.width, y - t.size.height - 2.dp.toPx()),
                        medianStyle
                    )
                }
            }

            // Подписи оси X: либо явные деления (ось «пн…пт» у квоты), либо
            // прежние «начало/середина/конец». Крайние прижаты внутрь, чтобы
            // текст не вылезал за холст.
            val ticks = xTicks
                ?: listOf(minX, (minX + maxX) / 2f, maxX).map { it to xLabelAt(it) }
            ticks.forEachIndexed { i, (v, label) ->
                val t = measurer.measure(label, labelStyle)
                val rawX = px(v)
                val x = when (i) {
                    0 -> rawX
                    ticks.lastIndex -> rawX - t.size.width
                    else -> rawX - t.size.width / 2f
                }
                drawText(measurer, label, Offset(x, bottom + 4.dp.toPx()), labelStyle)
            }

            // Ряды
            series.forEach { s ->
                if (s.points.isEmpty()) return@forEach
                val color = if (s.projected) projectionColor
                else palette[s.colorIndex % palette.size]
                val pts = s.points.sortedBy { it.x }.map { Offset(px(it.x), py(it.y)) }

                if (s.filled && pts.size > 1) {
                    val area = Path().apply {
                        moveTo(pts.first().x, bottom)
                        pts.forEach { lineTo(it.x, it.y) }
                        lineTo(pts.last().x, bottom)
                        close()
                    }
                    // Заливка набирает плотность вместе с линией: обрезать её по
                    // длине нельзя (это замкнутый контур), но появиться раньше
                    // линии она не должна — иначе читается как отдельный фон.
                    drawPath(area, color.copy(alpha = 0.14f * reveal))
                }

                val path = Path().apply {
                    pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
                }
                // Пунктир прогноза считается по уже обрезанному пути, поэтому
                // штрихи выкладываются от начала ряда, а не «доезжают» из конца.
                val drawnPath = path.revealed(reveal)
                drawPath(
                    path = drawnPath,
                    color = color,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = if (s.projected) PathEffect.dashPathEffect(
                            floatArrayOf(7.dp.toPx(), 5.dp.toPx())
                        ) else null,
                    ),
                )
                // Кружки на точках теперь только по явному запросу: раньше они
                // появлялись на любом ряду до 24 точек и читались как шум.
                if (s.showPoints) {
                    // Точка появляется, только когда «перо» прошло её X. Правый
                    // край габаритов обрезанного пути и есть позиция пера: ряд
                    // отсортирован по X, поэтому путь монотонен вправо.
                    val penX = drawnPath.getBounds().right
                    pts.forEach {
                        if (it.x <= penX) drawCircle(color, radius = 2.5.dp.toPx(), center = it)
                    }
                }
                // Акцент «где мы сейчас»: последняя фактическая точка ряда.
                // Ставится в самом конце вычерчивания — до этого линия до неё
                // ещё не дошла, и точка висела бы в пустоте.
                if (!s.projected && s.emphasizeLast) {
                    val dotAlpha = tailAlpha(reveal, 0.98f)
                    if (dotAlpha > 0f) {
                        val last = pts.last()
                        drawCircle(
                            color = surfaceColor.copy(alpha = surfaceColor.alpha * dotAlpha),
                            radius = 4.5.dp.toPx() + 2.dp.toPx(),
                            center = last,
                        )
                        drawCircle(
                            color = color.copy(alpha = color.alpha * dotAlpha),
                            radius = 4.5.dp.toPx(),
                            center = last,
                        )
                    }
                }
            }

            // Маркеры-события поверх рядов: подпись прижимается внутрь холста,
            // чтобы событие у края не теряло текст. Проявляются в самом конце
            // вычерчивания — это подписи к уже проведённым линиям.
            val markerAlpha = tailAlpha(reveal, 0.9f)
            val shownMarkers = if (markerAlpha > 0f) markers else emptyList()
            shownMarkers.forEach { m ->
                val center = Offset(px(m.x), py(m.y))
                val mColor = palette[m.colorIndex % palette.size].let {
                    it.copy(alpha = it.alpha * markerAlpha)
                }
                drawCircle(
                    color = surfaceColor.copy(alpha = surfaceColor.alpha * markerAlpha),
                    radius = 4.5.dp.toPx() + 2.dp.toPx(),
                    center = center,
                )
                drawCircle(mColor, radius = 4.5.dp.toPx(), center = center)
                m.label?.let { label ->
                    val markerStyle = labelStyle.copy(
                        color = mColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val t = measurer.measure(label, markerStyle)
                    val dotGap = 4.5.dp.toPx() + 2.dp.toPx() + 2.dp.toPx()
                    val tx = (center.x - t.size.width / 2f)
                        .coerceIn(0f, (size.width - t.size.width).coerceAtLeast(0f))
                    val ty = (if (m.labelAbove) center.y - dotGap - t.size.height else center.y + dotGap)
                        .coerceIn(0f, (size.height - t.size.height).coerceAtLeast(0f))
                    drawText(measurer, label, Offset(tx, ty), markerStyle)
                }
            }

            // Выбранная точка: волосок на всю высоту плота помогает соотнести
            // точку с осью X, увеличенный кружок показывает сам выбор.
            selectedIndex?.let { idx ->
                val p = interactive?.points?.getOrNull(idx) ?: return@let
                val cx = px(p.x)
                drawLine(axisColor, Offset(cx, top), Offset(cx, bottom), strokeWidth = 1.dp.toPx())
                val center = Offset(cx, py(p.y))
                drawCircle(surfaceColor, radius = 5.dp.toPx() + 2.dp.toPx(), center = center)
                drawCircle(selectedColor, radius = 5.dp.toPx(), center = center)
            }
        }
    }
}

internal fun Float.nonZero(): Float = if (this == 0f) 1f else this

/**
 * Компактный спарклайн без осей — для метрик-плиток.
 * [endDot] — точка на последнем значении: показывает «сейчас» без подписей.
 * [animate] выключает моторику для экспорта и тестов, где нужен финальный кадр.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    height: Dp = 32.dp,
    colorIndex: Int = 0,
    color: Color? = null,
    endDot: Boolean = true,
    animate: Boolean = true,
) {
    val palette = Gateway.colors.chartSeries
    val stroke = color ?: palette[colorIndex % palette.size]
    val surfaceColor = MaterialTheme.colorScheme.surface
    // Тот же жест, что у большого графика: линия вычерчивается слева направо.
    // Ключ — длина ряда и его максимум, как у LineChart: прогресс привязан
    // к текущим данным плитки.
    val reveal = if (animate) rememberChartReveal(values.size to values.maxOrNull()) else 1f
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
    ) {
        if (values.size < 2) return@Canvas
        val minV = minOf(0f, values.min())
        val maxV = values.max().let { if (it == minV) minV + 1f else it }
        val stepX = size.width / (values.size - 1)
        val pts = values.mapIndexed { i, v ->
            Offset(i * stepX, size.height - (v - minV) / (maxV - minV) * size.height)
        }
        val area = Path().apply {
            moveTo(pts.first().x, size.height)
            pts.forEach { lineTo(it.x, it.y) }
            lineTo(pts.last().x, size.height)
            close()
        }
        // Заливка набирает плотность вместе с линией: замкнутый контур обрезать
        // по длине нельзя, но опережать линию он не должен.
        drawPath(area, stroke.copy(alpha = 0.16f * reveal))
        val line = Path().apply {
            pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        }
        drawPath(
            path = line.revealed(reveal),
            color = stroke,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
        // Кольцо цвета поверхности отделяет точку от линии на крошечном холсте.
        // Точка ставится в самом конце: раньше линия до неё не дошла.
        val dotAlpha = tailAlpha(reveal, 0.98f)
        if (endDot && dotAlpha > 0f) {
            drawCircle(
                color = surfaceColor.copy(alpha = surfaceColor.alpha * dotAlpha),
                radius = 3.dp.toPx() + 1.5.dp.toPx(),
                center = pts.last(),
            )
            drawCircle(
                color = stroke.copy(alpha = stroke.alpha * dotAlpha),
                radius = 3.dp.toPx(),
                center = pts.last(),
            )
        }
    }
}
