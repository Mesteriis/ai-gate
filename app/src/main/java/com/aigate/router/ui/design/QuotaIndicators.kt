package com.aigate.router.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aigate.router.quota.ResourcePressure

/** Цвет по давлению ресурса — единая шкала для всех индикаторов квот. */
@Composable
fun pressureColor(pressure: ResourcePressure?): Color = when (pressure) {
    ResourcePressure.FREE -> Gateway.colors.pressureFree
    ResourcePressure.NORMAL -> Gateway.colors.pressureNormal
    ResourcePressure.CONSERVE -> Gateway.colors.pressureConserve
    ResourcePressure.CRITICAL -> Gateway.colors.pressureCritical
    ResourcePressure.UNKNOWN, null -> Gateway.colors.pressureUnknown
}

/** Тон статуса по давлению — для StatusChip рядом с индикатором квоты. */
@Composable
fun pressureTone(pressure: ResourcePressure?): StatusTone = when (pressure) {
    ResourcePressure.FREE, ResourcePressure.NORMAL -> StatusTone.Success
    ResourcePressure.CONSERVE -> StatusTone.Warning
    ResourcePressure.CRITICAL -> StatusTone.Error
    ResourcePressure.UNKNOWN, null -> StatusTone.Neutral
}

/**
 * Горизонтальный бар квоты. Метафора едина по всему приложению:
 * заполнение = ИЗРАСХОДОВАНО (fractionUsed), цвет — по давлению.
 * [threshold] — необязательный маркер порога уведомления (0..1 от лимита).
 */
@Composable
fun QuotaBar(
    fractionUsed: Float,
    pressure: ResourcePressure?,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
    threshold: Float? = null,
) {
    val fill = pressureColor(pressure)
    val track = Gateway.colors.surfaceContainerHigh
    val thresholdColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val r = CornerRadius(size.height / 2f)
        drawRoundRect(color = track, cornerRadius = r)
        val w = size.width * fractionUsed.coerceIn(0f, 1f)
        if (w > 0f) {
            drawRoundRect(color = fill, size = Size(w, size.height), cornerRadius = r)
        }
        threshold?.let { t ->
            val x = size.width * t.coerceIn(0f, 1f)
            drawLine(
                color = thresholdColor,
                start = Offset(x, -2.dp.toPx()),
                end = Offset(x, size.height + 2.dp.toPx()),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
    }
}

/**
 * Два окна лимита одним знаком: внешнее кольцо — короткое окно (у Claude сессия
 * на 5 часов), внутреннее — длинное (неделя). Одного кольца для такой подписки
 * недостаточно: упереться можно и в сессию, и в неделю, а сбрасываются они
 * в разное время.
 *
 * В центре — самое напряжённое из двух значений: именно оно ограничит работу
 * первым. Расшифровка обоих окон идёт подписью под знаком, а не внутри кольца:
 * в кольцо она не влезает и налезает на дугу.
 */
@Composable
fun QuotaRings(
    outerFraction: Float?,
    innerFraction: Float?,
    outerPressure: ResourcePressure?,
    innerPressure: ResourcePressure?,
    centerText: String,
    modifier: Modifier = Modifier,
    size: Dp = 86.dp,
    stroke: Dp = 8.dp,
) {
    val track = Gateway.colors.surfaceContainerHigh
    val outerFill = pressureColor(outerPressure)
    val innerFill = pressureColor(innerPressure)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val strokePx = stroke.toPx()
            // Внутреннее кольцо тоньше и отстоит на толщину внешнего плюс зазор.
            val innerStrokePx = strokePx * 0.62f
            val gapPx = strokePx * 0.85f

            fun ring(inset: Float, width: Float, fraction: Float?, fill: Color) {
                val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
                val topLeft = Offset(inset, inset)
                drawArc(
                    color = track,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width, cap = StrokeCap.Round),
                )
                fraction?.let { f ->
                    drawArc(
                        color = fill,
                        startAngle = -90f,
                        sweepAngle = 360f * f.coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width, cap = StrokeCap.Round),
                    )
                }
            }

            ring(strokePx / 2f, strokePx, outerFraction, outerFill)
            ring(strokePx + gapPx + innerStrokePx / 2f, innerStrokePx, innerFraction, innerFill)
        }
        Text(
            text = centerText,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Кольцо квоты с процентом в центре. [fractionUsed] — израсходовано 0..1;
 * null — данных нет (рисуем только трек и прочерк).
 */
@Composable
fun QuotaRing(
    fractionUsed: Float?,
    pressure: ResourcePressure?,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    stroke: Dp = 8.dp,
    centerText: String? = null,
) {
    val fill = pressureColor(pressure)
    val track = Gateway.colors.surfaceContainerHigh
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val strokePx = stroke.toPx()
            val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
            val topLeft = Offset(strokePx / 2f, strokePx / 2f)
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(strokePx, cap = StrokeCap.Round),
            )
            fractionUsed?.let { f ->
                drawArc(
                    color = fill,
                    startAngle = -90f,
                    sweepAngle = 360f * f.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(strokePx, cap = StrokeCap.Round),
                )
            }
        }
        Text(
            text = centerText ?: fractionUsed?.let { "${(it * 100).toInt()}%" } ?: "—",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (fractionUsed != null) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
