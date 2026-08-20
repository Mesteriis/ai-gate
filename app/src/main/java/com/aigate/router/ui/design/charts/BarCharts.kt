package com.aigate.router.ui.design.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.rememberChartReveal
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Значение с подписью для баров/донатов. [colorIndex] = -1 означает «цвет не
 * задан» — тогда берётся позиция в списке. Ноль раньше значил и то и другое,
 * из-за чего первый цвет серии нельзя было закрепить за сущностью явно.
 */
data class BarDatum(val label: String, val value: Float, val colorIndex: Int = UNSET_COLOR)

/** Явный признак «цвет не задан» вместо перегруженного нуля. */
const val UNSET_COLOR: Int = -1

/** Цвет отметки: заданный индекс, иначе позиция в списке. */
private fun List<Color>.seriesColor(colorIndex: Int, fallbackIndex: Int): Color =
    this[(if (colorIndex >= 0) colorIndex else fallbackIndex).mod(size)]

/** Глубина приглушения: невыбранный элемент уходит к альфе 0.3, но не в ноль. */
private const val DIM_DEPTH = 0.7f

/**
 * Приглушённый цвет невыбранного элемента: альфа доезжает от полной к 0.3
 * вместе с [progress]. Раньше альфа переключалась рывком и режим фокуса
 * читался как мигание, а не как смена акцента.
 *
 * Прямая работа с альфой — сознательное исключение из правила «только токены»
 * внутри слоя charts: отдельного токена «приглушённая серия» нет.
 */
private fun Color.dimmed(progress: Float): Color =
    if (progress <= 0f) this else copy(alpha = alpha * (1f - DIM_DEPTH * progress))

/**
 * Прогресс режима фокуса 0→1 и «залипший» индекс выбранного элемента.
 *
 * Индекс нужен именно залипший: после снятия выбора [selectedIndex] сразу
 * становится null, и без памяти о прежнем выборе цвета возвращались бы рывком,
 * не дождавшись обратной анимации.
 */
@Composable
private fun rememberFocus(selectedIndex: Int?, animate: Boolean): Pair<Float, Int?> {
    var focused by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(selectedIndex) { if (selectedIndex != null) focused = selectedIndex }
    val progress by animateFloatAsState(
        targetValue = if (selectedIndex != null) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (animate) Gateway.motion.fast else 0,
            easing = Gateway.motion.emphasized,
        ),
        label = "chart-focus",
    )
    return progress to focused
}

/**
 * Горизонтальный бар-чарт для рейтингов: топ моделей по расходу, скорость
 * моделей, сравнение провайдеров. Значение печатается справа — читается
 * без легенды. Опциональные ранг слева и доля справа превращают список
 * в честный рейтинг без отдельной таблицы.
 *
 * [animate] выключает моторику для экспорта и тестов, где нужен финальный кадр.
 */
@Composable
fun HorizontalBarChart(
    data: List<BarDatum>,
    modifier: Modifier = Modifier,
    valueLabel: (Float) -> String = { it.toInt().toString() },
    maxBars: Int = 10,
    barHeight: Dp = 10.dp,
    singleColor: Boolean = true,
    showShare: Boolean = false,
    showRank: Boolean = false,
    sortDescending: Boolean = true,
    animate: Boolean = true,
) {
    if (data.isEmpty()) return
    val palette = Gateway.colors.chartSeries
    val track = Gateway.colors.surfaceContainerHigh
    // Задержка — рейтинг «наоборот»: там первым должен стоять самый быстрый,
    // поэтому порядок сортировки задаётся вызывающим.
    val shown = (if (sortDescending) data.sortedByDescending { it.value } else data.sortedBy { it.value })
        .take(maxBars)
    val max = shown.maxOf { it.value }.nonZero()
    // Доля считается от суммы показанных баров: скрытый «хвост» за maxBars
    // не должен делать видимые проценты бессмысленными.
    val shownSum = shown.sumOf { it.value.toDouble() }.toFloat().nonZero()
    // Общий прогресс входа: при появлении бары выезжают из нуля. Ключ — размер
    // набора и потолок max, то есть прогресс привязан к текущим данным, а не
    // к позиции композиции.
    val reveal = if (animate) rememberChartReveal(data.size to max) else 1f

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Gateway.spacing.md)) {
        shown.forEachIndexed { index, d ->
            // key по идентичности бара, а не по позиции: доля — состояние
            // конкретной модели. При пересортировке рейтинга её бар доезжает до
            // новой длины вместо того, чтобы наследовать длину чужой строки.
            // colorIndex в ключе разводит одноимённые модели разных провайдеров.
            key(d.label, d.colorIndex) {
                val color =
                    if (singleColor) palette[0] else palette.seriesColor(d.colorIndex, index)
                // Доля каждого бара анимируется отдельно: при смене данных
                // ширины доезжают, а не подменяются одним кадром.
                val share by animateFloatAsState(
                    targetValue = (d.value / max).coerceIn(0f, 1f),
                    animationSpec = tween(
                        durationMillis = if (animate) Gateway.motion.normal else 0,
                        easing = Gateway.motion.emphasized,
                    ),
                    label = "bar-share",
                )
                Column(verticalArrangement = Arrangement.spacedBy(Gateway.spacing.xs)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (showRank) {
                            // Фиксированная ширина: «1» и «10» не сбивают выравнивание подписей.
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(18.dp),
                            )
                        }
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
                        if (showShare) {
                            Spacer(Modifier.width(Gateway.spacing.xs))
                            Text(
                                text = "${(d.value / shownSum * 100f).roundToInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Canvas(Modifier.fillMaxWidth().height(barHeight)) {
                        val r = CornerRadius(size.height / 2f)
                        drawRoundRect(track, cornerRadius = r)
                        // Заполнение = доля бара, помноженная на общий прогресс
                        // входа: на появлении бар выезжает из нуля, дальше живёт
                        // своей анимацией доли.
                        val w = size.width * (share * reveal).coerceIn(0f, 1f)
                        if (w > 0f) {
                            drawRoundRect(color, size = Size(w, size.height), cornerRadius = r)
                        }
                    }
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
 * не «поднимаются» искусственным минимумом. Касание колонки выделяет её и
 * отдаёт индекс наружу — карточка может показать детали дня.
 *
 * [animate] выключает моторику для экспорта и тестов, где нужен финальный кадр.
 */
@Composable
fun StackedBarChart(
    columns: List<StackedColumn>,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    valueLabel: (Float) -> String = { it.toInt().toString() },
    xLabelEvery: Int = 1,
    selectedIndex: Int? = null,
    onSelect: ((Int?) -> Unit)? = null,
    lastLabel: String? = null,
    animate: Boolean = true,
) {
    if (columns.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = Gateway.colors.chartAxisLabel)
    // Подпись «сегодня» выделяется цветом и весом — взгляд находит текущий
    // день без легенды.
    val todayStyle = MaterialTheme.typography.labelMedium.copy(
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
    val palette = Gateway.colors.chartSeries
    val selectedColor = Gateway.colors.chartSelected
    val gridColor = Gateway.colors.chartGrid
    val totals = columns.map { c -> c.segments.sumOf { it.value.toDouble() }.toFloat() }
    // «Чистый» потолок оси вместо сырого максимума данных: 473,0K → 500K.
    val axisMax = ChartMath.niceCeil(totals.max())
    val yLabels = listOf(axisMax, axisMax / 2f, 0f)
    // Ширина колонки подписей Y считается в композиции: она нужна и отрисовке,
    // и обработчику касаний — оба должны мерить слоты от одной левой границы.
    val yLabelWidth = yLabels.maxOf { measurer.measure(valueLabel(it), labelStyle).size.width }.toFloat()
    val labelIndices = ChartMath.axisLabelIndices(columns.size, xLabelEvery)
    // Столбцы растут от базовой линии: рост читается как «данные набираются»,
    // тогда как мгновенная отрисовка — как статичная картинка. Сетка и подписи
    // осей намеренно не анимируются: их мерцание выглядело бы дефектом.
    val reveal = if (animate) rememberChartReveal(columns.size to axisMax) else 1f
    val (focus, focused) = rememberFocus(selectedIndex, animate)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .then(
                if (onSelect != null) {
                    Modifier.pointerInput(columns.size, selectedIndex, yLabelWidth) {
                        detectTapGestures { pos ->
                            val left = yLabelWidth + 6.dp.toPx()
                            if (pos.x < left) return@detectTapGestures
                            // Хит-зона слота — вся его ширина и вся высота:
                            // тонкие столбцы пальцем иначе не поймать.
                            val slot = (size.width - left).coerceAtLeast(1f) / columns.size
                            val idx = ((pos.x - left) / slot).toInt().coerceIn(0, columns.lastIndex)
                            // Повторное касание выбранной колонки снимает выбор.
                            onSelect(if (idx == selectedIndex) null else idx)
                        }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        val xLabelHeight = measurer.measure("0", labelStyle).size.height.toFloat()
        val left = yLabelWidth + 6.dp.toPx()
        val bottom = size.height - xLabelHeight - 4.dp.toPx()
        val top = xLabelHeight / 2f
        val plotW = (size.width - left).coerceAtLeast(1f)
        val plotH = (bottom - top).coerceAtLeast(1f)

        yLabels.forEach { v ->
            val y = bottom - (v / axisMax) * plotH
            drawLine(gridColor, Offset(left, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            val t = measurer.measure(valueLabel(v), labelStyle)
            drawText(measurer, valueLabel(v), Offset(0f, y - t.size.height / 2f), labelStyle)
        }

        val slot = plotW / columns.size
        val barW = (slot * 0.62f).coerceAtMost(28.dp.toPx())
        val gap = 2.dp.toPx()
        val minSeg = 0.5.dp.toPx()
        val topRadius = CornerRadius(4.dp.toPx())
        columns.forEachIndexed { i, col ->
            val cx = left + slot * i + slot / 2f
            val x0 = cx - barW / 2f
            // Режим фокуса на одной колонке: остальные приглушаются плавно
            // (см. rememberFocus) — резкая подмена альфы читалась как мигание.
            val dimmedColumn = focused != null && focused != i
            val lastVisible = col.segments.indexOfLast { it.value > 0f }
            var yCursor = bottom
            var drawnBefore = 0
            col.segments.forEachIndexed { s, seg ->
                // Прогресс входа умножается на высоту, а не на масштаб оси:
                // пропорции сегментов внутри колонки честны на каждом кадре.
                val h = (seg.value / axisMax) * plotH * reveal
                if (h > 0f) {
                    val base = palette[seg.colorIndex % palette.size]
                    val color = when {
                        i == focused && drawnBefore == 0 -> lerp(base, selectedColor, focus)
                        dimmedColumn -> base.dimmed(focus)
                        else -> base
                    }
                    // Нижний сегмент рисуется полной высотой, каждый следующий
                    // уступает 2.dp зазору снизу — суммарная высота колонки
                    // остаётся честной, курсор идёт по «сырым» высотам.
                    val drawH = if (drawnBefore == 0) h else (h - gap).coerceAtLeast(minSeg)
                    val topY = yCursor - h
                    if (s == lastVisible) {
                        // Скругление только у верхних углов верхнего сегмента:
                        // у базовой линии колонка стоит на прямых углах.
                        val path = Path().apply {
                            addRoundRect(
                                RoundRect(
                                    rect = Rect(x0, topY, x0 + barW, topY + drawH),
                                    topLeft = topRadius,
                                    topRight = topRadius,
                                )
                            )
                        }
                        drawPath(path, color)
                    } else {
                        drawRect(color, topLeft = Offset(x0, topY), size = Size(barW, drawH))
                    }
                    yCursor -= h
                    drawnBefore++
                }
            }
            if (i in labelIndices) {
                val isToday = i == columns.lastIndex && lastLabel != null
                val text = if (isToday && lastLabel != null) lastLabel else col.label
                val style = if (isToday) todayStyle else labelStyle
                val t = measurer.measure(text, style)
                // Крайние подписи прижимаются к краям холста, а не обрезаются.
                val x = (cx - t.size.width / 2f)
                    .coerceAtMost(size.width - t.size.width)
                    .coerceAtLeast(0f)
                drawText(measurer, text, Offset(x, bottom + 4.dp.toPx()), style)
            }
        }
    }
}

/**
 * Один бар на 100% — соотношение двух-трёх частей (prompt/completion).
 * Заменяет два несравнимых прогресс-бара на карточке. Через [inlineLabel]
 * крупные сегменты подписываются прямо внутри — легенда становится необязательной.
 *
 * [animate] выключает моторику для экспорта и тестов, где нужен финальный кадр.
 */
@Composable
fun StackedBar100(
    segments: List<StackSegment>,
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
    inlineLabel: ((Float) -> String)? = null,
    animate: Boolean = true,
) {
    val palette = Gateway.colors.chartSeries
    val track = Gateway.colors.surfaceContainerHigh
    val measurer = rememberTextMeasurer()
    // onPrimary — семантический «текст на акцентной заливке»: белый в светлой
    // теме и подобранный к ярким акцентам в тёмной; чистого белого в токенах нет.
    val inlineStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onPrimary,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
    )
    val total = segments.sumOf { it.value.toDouble() }.toFloat().nonZero()
    // Бар заполняет дорожку слева направо: пустая дорожка видна сразу, а
    // соотношение частей проявляется тем же жестом, что и у остальных графиков.
    val reveal = if (animate) rememberChartReveal(segments.size to total) else 1f
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val r = CornerRadius(size.height / 2f)
        drawRoundRect(track, cornerRadius = r)
        val gap = 2.dp.toPx()
        val minSeg = 0.5.dp.toPx()
        val margin = 6.dp.toPx()
        var x = 0f
        var drawn = 0
        segments.forEach { seg ->
            val w = size.width * (seg.value / total) * reveal
            if (w > 0f) {
                // Первый сегмент занимает свою долю целиком, следующие уступают
                // 2.dp зазору слева — правая граница каждого остаётся честной.
                val offset = if (drawn == 0) 0f else gap
                val drawW = (w - offset).coerceAtLeast(minSeg)
                drawRoundRect(
                    color = palette[seg.colorIndex % palette.size],
                    topLeft = Offset(x + offset, 0f),
                    size = Size(drawW, size.height),
                    cornerRadius = r,
                )
                // Подписи ждут конца заполнения: на растущем сегменте они бы
                // всплывали и переезжали к центру — это читается как дребезг.
                if (inlineLabel != null && reveal > 0.98f) {
                    val text = inlineLabel(seg.value)
                    val t = measurer.measure(text, inlineStyle)
                    // Подпись рисуется только с запасом 6.dp с обеих сторон —
                    // иначе цифры налезали бы на границы узкого сегмента.
                    if (t.size.width + margin * 2f <= drawW && t.size.height <= size.height) {
                        drawText(
                            measurer,
                            text,
                            Offset(
                                x + offset + (drawW - t.size.width) / 2f,
                                (size.height - t.size.height) / 2f,
                            ),
                            inlineStyle,
                        )
                    }
                }
                x += w
                drawn++
            }
        }
    }
}

/**
 * Донат — доли провайдеров/моделей. Центр отдан итогу. Касание сегмента
 * выделяет его и отдаёт индекс наружу — легенда рядом может подсветить строку.
 *
 * [animate] выключает моторику для экспорта и тестов, где нужен финальный кадр.
 */
@Composable
fun DonutChart(
    data: List<BarDatum>,
    modifier: Modifier = Modifier,
    diameter: Dp = 132.dp,
    stroke: Dp = 18.dp,
    centerPrimary: String? = null,
    centerSecondary: String? = null,
    centerTertiary: String? = null,
    gapDegrees: Float = 3f,
    selectedIndex: Int? = null,
    onSelect: ((Int?) -> Unit)? = null,
    animate: Boolean = true,
) {
    if (data.isEmpty()) return
    val palette = Gateway.colors.chartSeries
    val total = data.sumOf { it.value.toDouble() }.toFloat().nonZero()
    // Кольцо прочерчивается от «12 часов» по часовой: доли складываются на
    // глазах, вместо того чтобы возникнуть готовой диаграммой.
    val reveal = if (animate) rememberChartReveal(data.size to total) else 1f
    val (focus, focused) = rememberFocus(selectedIndex, animate)
    androidx.compose.foundation.layout.Box(
        modifier.size(diameter),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .size(diameter)
                .then(
                    if (onSelect != null) {
                        Modifier.pointerInput(data, selectedIndex, gapDegrees) {
                            detectTapGestures { pos ->
                                val strokePx = stroke.toPx()
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val dist = hypot(pos.x - cx, pos.y - cy)
                                val ringR = (size.width - strokePx) / 2f
                                // Попадание засчитывается только по кольцу с допуском
                                // 10.dp наружу и внутрь — касания центра и углов
                                // не трогают выбор.
                                val tol = strokePx / 2f + 10.dp.toPx()
                                if (dist < ringR - tol || dist > ringR + tol) return@detectTapGestures
                                // Угол от «12 часов» по часовой — в той же системе,
                                // в которой рисуются сегменты (старт -90°).
                                val angle = (
                                    Math.toDegrees(
                                        atan2((pos.y - cy).toDouble(), (pos.x - cx).toDouble())
                                    ).toFloat() + 90f + 360f
                                ) % 360f
                                var start = 0f
                                var hit: Int? = null
                                for ((i, d) in data.withIndex()) {
                                    val sweep = 360f * (d.value / total)
                                    if (angle < start + sweep) {
                                        hit = i
                                        break
                                    }
                                    start += sweep
                                }
                                // Повторное касание выбранного сегмента снимает выбор.
                                if (hit != null) onSelect(if (hit == selectedIndex) null else hit)
                            }
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            val strokePx = stroke.toPx()
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            val topLeft = Offset(strokePx / 2f, strokePx / 2f)
            // Общий прочерченный угол; каждый сегмент показывает только ту свою
            // часть, до которой прогресс уже дошёл.
            val drawnAngle = 360f * reveal
            var cursor = 0f
            data.forEachIndexed { i, d ->
                val sweep = 360f * (d.value / total)
                val shown = (drawnAngle - cursor).coerceIn(0f, sweep)
                if (shown > 0f) {
                    val base = palette.seriesColor(d.colorIndex, i)
                    // Приглушение невыбранных сегментов доезжает вместе с focus
                    // (см. rememberFocus), а не переключается одним кадром.
                    val color = if (focused != null && focused != i) base.dimmed(focus) else base
                    // Выбранный сегмент утолщается на 4.dp вокруг той же осевой
                    // линии кольца — геометрия дуги не пересчитывается.
                    val w = if (i == focused) strokePx + 4.dp.toPx() * focus else strokePx
                    // Зазор вычитается из видимой части, а не из полной: дуга
                    // растёт монотонно и не дёргается в момент, когда сегмент
                    // дочерчен до конца.
                    val sweepAngle = (shown - gapDegrees)
                        .let { if (shown >= sweep) it.coerceAtLeast(0.5f) else it }
                    if (sweepAngle > 0f) {
                        drawArc(
                            color = color,
                            startAngle = -90f + cursor + gapDegrees / 2f,
                            sweepAngle = sweepAngle,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(w, cap = StrokeCap.Butt),
                        )
                    }
                }
                cursor += sweep
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
            centerTertiary?.let {
                // Третья строка ещё тише второй: outline вместо onSurfaceVariant.
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * Легенда для доната/многорядных графиков. При заданном [onSelect] строки
 * кликабельны и работают в паре с выбором на графике.
 */
@Composable
fun ChartLegend(
    data: List<BarDatum>,
    modifier: Modifier = Modifier,
    valueLabel: (Float) -> String = { it.toInt().toString() },
    showShare: Boolean = false,
    selectedIndex: Int? = null,
    onSelect: ((Int?) -> Unit)? = null,
) {
    val palette = Gateway.colors.chartSeries
    val total = data.sumOf { it.value.toDouble() }.toFloat().nonZero()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Gateway.spacing.xs)) {
        data.forEachIndexed { i, d ->
            val selected = selectedIndex == i
            // У интерактивной легенды паддинг постоянный на всех строках:
            // фон выбора появляется и исчезает, не сдвигая соседние строки.
            val rowModifier = if (onSelect != null) {
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .then(
                        if (selected) {
                            Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)
                        } else {
                            Modifier
                        }
                    )
                    .clickable { onSelect(if (selected) null else i) }
                    .padding(horizontal = Gateway.spacing.sm, vertical = Gateway.spacing.xs)
            } else {
                Modifier
            }
            Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
                LegendDot(palette.seriesColor(d.colorIndex, i))
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
                if (showShare) {
                    Spacer(Modifier.width(Gateway.spacing.sm))
                    // Фиксированная ширина выравнивает проценты в колонку.
                    Text(
                        text = "${(d.value / total * 100f).roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(36.dp),
                        textAlign = TextAlign.End,
                    )
                }
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
