package com.aigate.router.ui.design.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aigate.router.ui.design.Gateway

/** Значение с подписью для баров/донатов. */
data class BarDatum(val label: String, val value: Float, val colorIndex: Int = 0)

/**
 * Горизонтальный бар-чарт для рейтингов: топ моделей по расходу, скорость
 * моделей, сравнение провайдеров. Значение печатается справа — читается
 * без легенды.
 */
@Composable
fun HorizontalBarChart(
    data: List<BarDatum>,
    modifier: Modifier = Modifier,
    valueLabel: (Float) -> String = { it.toInt().toString() },
    maxBars: Int = 10,
    barHeight: Dp = 10.dp,
    singleColor: Boolean = true,
) {
    if (data.isEmpty()) return
    val palette = Gateway.colors.chartSeries
    val track = Gateway.colors.surfaceContainerHigh
    val shown = data.sortedByDescending { it.value }.take(maxBars)
    val max = shown.maxOf { it.value }.nonZero()

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Gateway.spacing.md)) {
        shown.forEachIndexed { index, d ->
            val color = if (singleColor) palette[0] else palette[d.colorIndex % palette.size]
            Column(verticalArrangement = Arrangement.spacedBy(Gateway.spacing.xs)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = d.label,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(Gateway.spacing.sm))
                    Text(
                        text = valueLabel(d.value),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Canvas(Modifier.fillMaxWidth().height(barHeight)) {
                    val r = CornerRadius(size.height / 2f)
                    drawRoundRect(track, cornerRadius = r)
                    val w = size.width * (d.value / max).coerceIn(0f, 1f)
                    if (w > 0f) drawRoundRect(color, size = Size(w, size.height), cornerRadius = r)
                }
            }
        }
    }
}

/** Сегмент вертикального стека (prompt/completion токены и т.п.). */
data class StackSegment(val value: Float, val colorIndex: Int)

/** Колонка вертикального стека с подписью по оси X. */
data class StackedColumn(val label: String, val segments: List<StackSegment>)

/**
 * Вертикальный стек-бар-чарт с осью значений и подписями X — история расхода
 * по дням со разбивкой prompt/completion. Пропорции честные: короткие столбцы
 * не «поднимаются» искусственным минимумом.
 */
@Composable
fun StackedBarChart(
    columns: List<StackedColumn>,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    valueLabel: (Float) -> String = { it.toInt().toString() },
    xLabelEvery: Int = 1,
) {
    if (columns.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = Gateway.colors.chartAxisLabel)
    val palette = Gateway.colors.chartSeries
    val gridColor = Gateway.colors.chartGrid
    val totals = columns.map { c -> c.segments.sumOf { it.value.toDouble() }.toFloat() }
    val max = totals.max().nonZero()

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val yLabels = listOf(max, max / 2f, 0f)
        val yLabelWidth = yLabels.maxOf { measurer.measure(valueLabel(it), labelStyle).size.width }.toFloat()
        val xLabelHeight = measurer.measure("0", labelStyle).size.height.toFloat()
        val left = yLabelWidth + 6.dp.toPx()
        val bottom = size.height - xLabelHeight - 4.dp.toPx()
        val top = xLabelHeight / 2f
        val plotW = (size.width - left).coerceAtLeast(1f)
        val plotH = (bottom - top).coerceAtLeast(1f)

        yLabels.forEach { v ->
            val y = bottom - (v / max) * plotH
            drawLine(gridColor, Offset(left, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            val t = measurer.measure(valueLabel(v), labelStyle)
            drawText(measurer, valueLabel(v), Offset(0f, y - t.size.height / 2f), labelStyle)
        }

        val slot = plotW / columns.size
        val barW = (slot * 0.62f).coerceAtMost(28.dp.toPx())
        columns.forEachIndexed { i, col ->
            val cx = left + slot * i + slot / 2f
            var yCursor = bottom
            col.segments.forEach { seg ->
                val h = (seg.value / max) * plotH
                if (h > 0f) {
                    drawRoundRect(
                        color = palette[seg.colorIndex % palette.size],
                        topLeft = Offset(cx - barW / 2f, yCursor - h),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                    yCursor -= h
                }
            }
            if (i % xLabelEvery == 0) {
                val t = measurer.measure(col.label, labelStyle)
                drawText(
                    measurer, col.label,
                    Offset(cx - t.size.width / 2f, bottom + 4.dp.toPx()),
                    labelStyle
                )
            }
        }
    }
}

/**
 * Один бар на 100% — соотношение двух-трёх частей (prompt/completion).
 * Заменяет два несравнимых прогресс-бара на карточке.
 */
@Composable
fun StackedBar100(
    segments: List<StackSegment>,
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
) {
    val palette = Gateway.colors.chartSeries
    val track = Gateway.colors.surfaceContainerHigh
    val total = segments.sumOf { it.value.toDouble() }.toFloat().nonZero()
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val r = CornerRadius(size.height / 2f)
        drawRoundRect(track, cornerRadius = r)
        var x = 0f
        segments.forEach { seg ->
            val w = size.width * (seg.value / total)
            if (w > 0f) {
                drawRoundRect(
                    color = palette[seg.colorIndex % palette.size],
                    topLeft = Offset(x, 0f),
                    size = Size(w, size.height),
                    cornerRadius = r,
                )
                x += w
            }
        }
    }
}

/** Донат — доли провайдеров/моделей. Центр отдан итогу. */
@Composable
fun DonutChart(
    data: List<BarDatum>,
    modifier: Modifier = Modifier,
    diameter: Dp = 132.dp,
    stroke: Dp = 18.dp,
    centerPrimary: String? = null,
    centerSecondary: String? = null,
) {
    if (data.isEmpty()) return
    val palette = Gateway.colors.chartSeries
    val total = data.sumOf { it.value.toDouble() }.toFloat().nonZero()
    androidx.compose.foundation.layout.Box(
        modifier.size(diameter),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(diameter)) {
            val strokePx = stroke.toPx()
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            val topLeft = Offset(strokePx / 2f, strokePx / 2f)
            var start = -90f
            data.forEachIndexed { i, d ->
                val sweep = 360f * (d.value / total)
                drawArc(
                    color = palette[(if (d.colorIndex != 0) d.colorIndex else i) % palette.size],
                    startAngle = start + 1f,
                    sweepAngle = (sweep - 2f).coerceAtLeast(0.5f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(strokePx, cap = StrokeCap.Butt),
                )
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            centerPrimary?.let {
                Text(it, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            centerSecondary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Легенда для доната/многорядных графиков. */
@Composable
fun ChartLegend(
    data: List<BarDatum>,
    modifier: Modifier = Modifier,
    valueLabel: (Float) -> String = { it.toInt().toString() },
) {
    val palette = Gateway.colors.chartSeries
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Gateway.spacing.xs)) {
        data.forEachIndexed { i, d ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendDot(palette[(if (d.colorIndex != 0) d.colorIndex else i) % palette.size])
                Spacer(Modifier.width(Gateway.spacing.sm))
                Text(
                    d.label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Gateway.spacing.sm))
                Text(
                    valueLabel(d.value),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .size(9.dp)
            .background(color, CircleShape)
    )
}
