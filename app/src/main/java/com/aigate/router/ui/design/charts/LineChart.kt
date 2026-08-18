package com.aigate.router.ui.design.charts

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aigate.router.ui.design.Gateway

/** Одна точка ряда: x — время/индекс, y — значение. */
data class ChartPoint(val x: Float, val y: Float)

/**
 * Ряд данных линейного графика.
 * [projected] — рисуется пунктиром (прогноз к концу периода).
 */
data class LineSeries(
    val label: String,
    val points: List<ChartPoint>,
    val colorIndex: Int = 0,
    val projected: Boolean = false,
    val filled: Boolean = false,
)

/**
 * Линейный/area-график с осями, сеткой и подписями. В отличие от прежнего
 * «слепого» графика подписи осей действительно рендерятся.
 *
 * [xLabelAt] — форматирование подписи по значению x (например, времени),
 * [yLabelAt] — по значению y. Обе оси подписываются 3 значениями (min/mid/max).
 * [referenceY] — горизонтальная линия-ориентир (бюджет/лимит).
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
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = Gateway.colors.chartAxisLabel)
    val gridColor = Gateway.colors.chartGrid
    val palette = Gateway.colors.chartSeries
    val projectionColor = Gateway.colors.chartProjection
    val refColor = MaterialTheme.colorScheme.onSurfaceVariant

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
        val maxY = maxOf(referenceY ?: rawMaxY, rawMaxY).let { if (it == minY) minY + 1f else it }

        Canvas(Modifier.fillMaxWidth().height(height)) {
            val yLabels = listOf(maxY, (maxY + minY) / 2f, minY)
            val yLabelWidth = yLabels.maxOf {
                measurer.measure(yLabelAt(it), labelStyle).size.width
            }.toFloat()
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

            // Подписи оси X: начало, середина, конец
            listOf(minX, (minX + maxX) / 2f, maxX).forEachIndexed { i, v ->
                val label = xLabelAt(v)
                val t = measurer.measure(label, labelStyle)
                val rawX = px(v)
                val x = when (i) {
                    0 -> rawX
                    1 -> rawX - t.size.width / 2f
                    else -> rawX - t.size.width
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
                    drawPath(area, color.copy(alpha = 0.14f))
                }

                val path = Path().apply {
                    pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
                }
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = if (s.projected) PathEffect.dashPathEffect(
                            floatArrayOf(7.dp.toPx(), 5.dp.toPx())
                        ) else null,
                    ),
                )
                if (!s.projected && pts.size <= 24) {
                    pts.forEach { drawCircle(color, radius = 2.5.dp.toPx(), center = it) }
                }
            }
        }
    }
}

internal fun Float.nonZero(): Float = if (this == 0f) 1f else this

/** Компактный спарклайн без осей — для метрик-плиток. */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    height: Dp = 32.dp,
    colorIndex: Int = 0,
    color: Color? = null,
) {
    val palette = Gateway.colors.chartSeries
    val stroke = color ?: palette[colorIndex % palette.size]
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
        drawPath(area, stroke.copy(alpha = 0.16f))
        val line = Path().apply {
            pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        }
        drawPath(line, stroke, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}
