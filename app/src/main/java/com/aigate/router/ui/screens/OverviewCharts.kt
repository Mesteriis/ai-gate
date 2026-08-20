package com.aigate.router.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.aigate.router.data.model.QuotaSnapshot
import com.aigate.router.quota.QuotaBurn
import com.aigate.router.quota.QuotaRepository
import com.aigate.router.quota.ResourcePoolKind
import com.aigate.router.ui.design.CardTone
import com.aigate.router.ui.design.ChartCard
import com.aigate.router.ui.design.Fmt
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.StatusChip
import com.aigate.router.ui.design.StatusTone
import com.aigate.router.ui.design.charts.BarDatum
import com.aigate.router.ui.design.charts.ChartLegend
import com.aigate.router.ui.design.charts.ChartMarker
import com.aigate.router.ui.design.charts.ChartMath
import com.aigate.router.ui.design.charts.ChartPoint
import com.aigate.router.ui.design.charts.LineChart
import com.aigate.router.ui.design.charts.LineSeries
import com.aigate.router.ui.design.charts.StackSegment
import com.aigate.router.ui.design.charts.StackedBarChart
import com.aigate.router.ui.design.charts.StackedColumn
import com.aigate.router.usage.UsageHistory
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Графики главного экрана. Их три, и они отвечают на разные вопросы:
 * сколько месяц стоит, сколько работы прошло через шлюз и успею ли я
 * израсходовать квоту до сброса.
 */

/**
 * Объём работы по дням: входные и выходные токены стопкой. Шапка карточки —
 * живая строка-вывод: без выбора она подводит итог периода, касание колонки
 * подставляет разбор конкретного дня.
 */
@Composable
fun UsageByDayCard(days: List<UsageHistory.DayUsage>) {
    // Выбранный день переживает поворот и раскрытие Fold: разбор дня в шапке
    // не должен сбрасываться при смене конфигурации.
    var selDay by rememberSaveable { mutableStateOf<Int?>(null) }
    val totals = days.map { it.promptTokens + it.completionTokens }
    val total = totals.sum()
    val avg = if (days.isNotEmpty()) total / days.size else 0L
    // Список дней мог обновиться под сохранённый выбор — индекс проверяется.
    val sel = selDay?.takeIf { it in days.indices }

    val readMain: String
    val readSub: String?
    if (sel != null) {
        val d = days[sel]
        val dayTotal = totals[sel]
        readMain = "${Fmt.day(d.dayStartMs)} · ${Fmt.compact(dayTotal)}"
        readSub = buildString {
            append("входные ${Fmt.compact(d.promptTokens)}")
            append(" · выходные ${Fmt.compact(d.completionTokens)}")
            if (avg > 0) {
                val delta = ((dayTotal - avg) * 100.0 / avg).roundToInt()
                append(" · ${"%+d%%".format(delta)} к среднему")
            }
        }
    } else {
        val peak = totals.withIndex().maxByOrNull { it.value }
        readMain = Fmt.compact(total)
        readSub = if (peak != null && peak.value > 0) {
            "за ${days.size} дней · пик ${Fmt.day(days[peak.index].dayStartMs)} " +
                "(${Fmt.compact(peak.value)}) · в среднем ${Fmt.compact(avg)} в день"
        } else {
            null
        }
    }

    ChartCard(
        eyebrow = "Использование по дням",
        readMain = readMain,
        readSub = readSub,
        tone = CardTone.Raised,
    ) {
        if (total <= 0L) {
            Text(
                text = "Запросов пока не было",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@ChartCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(Gateway.spacing.sm)) {
            StackedBarChart(
                columns = days.map { d ->
                    StackedColumn(
                        label = Fmt.day(d.dayStartMs),
                        segments = listOf(
                            StackSegment(d.promptTokens.toFloat(), colorIndex = 0),
                            StackSegment(d.completionTokens.toFloat(), colorIndex = 1),
                        ),
                    )
                },
                height = 170.dp,
                valueLabel = { Fmt.compact(it.toLong()) },
                xLabelEvery = ChartMath.labelEvery(days.size),
                selectedIndex = sel,
                onSelect = { selDay = it },
                lastLabel = "сегодня",
            )
            ChartLegend(
                data = listOf(
                    BarDatum("Входные", days.sumOf { it.promptTokens }.toFloat(), colorIndex = 0),
                    BarDatum("Выходные", days.sumOf { it.completionTokens }.toFloat(), colorIndex = 1),
                ),
                valueLabel = { Fmt.compact(it.toLong()) },
            )
        }
    }
}

/**
 * Темп расхода квоты: остаток во времени против ровного темпа до сброса.
 * Линия ниже пунктира — сжигаю быстрее, чем позволяет период; выше — часть
 * сгорит неиспользованной. Вердикт вынесен чипом в шапку карточки, а момент
 * исчерпания отмечен маркером прямо на оси.
 */
@Composable
fun QuotaBurnCard(
    pools: List<QuotaRepository.PoolQuota>,
    histories: Map<Long, List<QuotaSnapshot>>,
) {
    // Только ресурсы со сбросом: у баланса и бесплатных «темпа до сброса» нет.
    val quotaPools = pools.filter { ResourcePoolKind.fromName(it.pool.kind).hasReset }
    if (quotaPools.isEmpty()) return

    quotaPools.forEach { pq ->
        val history = histories[pq.pool.id].orEmpty()
        val now = System.currentTimeMillis()
        val remaining = pq.snapshot?.remaining
        val resetsAt = pq.snapshot?.resetsAt
        val unit = pq.snapshot?.unit ?: pq.pool.unit
        val rate = QuotaBurn.rate(history, now)
        val outlook = if (remaining != null && resetsAt != null && rate != null) {
            QuotaBurn.outlook(remaining, resetsAt, rate, now)
        } else {
            null
        }
        val verdict = burnVerdict(rate, outlook, remaining, resetsAt)

        ChartCard(
            eyebrow = "Темп расхода квоты",
            readMain = pq.pool.name,
            readSub = if (remaining != null && resetsAt != null) {
                "осталось ${Fmt.quota(remaining, unit)} · сброс ${Fmt.dateTime(resetsAt)}"
            } else {
                null
            },
            tone = CardTone.Raised,
            headerAction = { StatusChip(text = verdict.first, tone = verdict.second) },
        ) {
            val points = history
                .filter { it.remaining != null }
                .map { ChartPoint(it.updatedAt.toFloat(), it.remaining!!.toFloat()) }
            if (points.size < 2 || remaining == null || resetsAt == null) {
                Text(
                    text = "Истории расхода пока нет",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@ChartCard
            }

            val exhaustAt = outlook?.exhaustAtMs
            // Ось X обязана дотянуться и до сброса, и до маркера исчерпания;
            // по расчёту QuotaBurn исчерпание не позже сброса, но ось на это
            // не полагается.
            val axisEnd = maxOf(resetsAt, exhaustAt ?: resetsAt)
            // Ровный темп: из нынешнего остатка в ноль ровно к сбросу.
            val evenPace = buildList {
                add(ChartPoint(now.toFloat(), remaining.toFloat()))
                add(ChartPoint(resetsAt.toFloat(), 0f))
                if (axisEnd > resetsAt) add(ChartPoint(axisEnd.toFloat(), 0f))
            }
            LineChart(
                series = listOf(
                    LineSeries("Остаток", points, colorIndex = 0, filled = true),
                    LineSeries("Ровный темп", evenPace, projected = true),
                ),
                height = 150.dp,
                xLabelAt = { Fmt.time(it.toLong()) },
                yLabelAt = { axisLabel(it, remaining, unit) },
                markers = exhaustAt?.let {
                    listOf(
                        ChartMarker(
                            x = it.toFloat(),
                            y = 0f,
                            colorIndex = 5,
                            label = Fmt.dateTime(it),
                            labelAbove = true,
                        )
                    )
                }.orEmpty(),
                xTicks = dayTicks(minOf(points.minOf { it.x }.toLong(), now), axisEnd),
            )
        }
    }
}

/**
 * Вердикт по темпу для чипа в шапке: чем закончится период при нынешнем
 * расходе. Заменяет прежнюю строку мелким шрифтом под графиком.
 */
private fun burnVerdict(
    rate: QuotaBurn.Rate?,
    outlook: QuotaBurn.Outlook?,
    remaining: Double?,
    resetsAt: Long?,
): Pair<String, StatusTone> = when {
    rate == null -> "темпа нет" to StatusTone.Neutral
    remaining == null || resetsAt == null -> "нет данных" to StatusTone.Neutral
    // Темп известен, а прогноза нет — сброс уже позади нынешнего момента.
    outlook == null -> "сброс наступил" to StatusTone.Neutral
    outlook.exhaustAtMs != null -> {
        val earlier = resetsAt - outlook.exhaustAtMs
        // Совпадение исчерпания со сбросом — это «ровно», а не «на — раньше».
        if (earlier > 0) "на ~${Fmt.duration(earlier)} раньше" to StatusTone.Warning
        else "ровно по периоду" to StatusTone.Success
    }
    outlook.surplus > 0.0 -> "сгорит ${outlook.surplus.roundToLong()}" to StatusTone.Info
    else -> "ровно по периоду" to StatusTone.Success
}

/**
 * Деления оси X по границам суток (полночь по часовому поясу устройства),
 * прореженные до пяти: часы «14:05» на многодневном домене ничего не говорили.
 * Домен короче суток остаётся на стандартных подписях времени — null.
 */
private fun dayTicks(fromMs: Long, toMs: Long): List<Pair<Float, String>>? {
    val cal = Calendar.getInstance().apply {
        timeInMillis = fromMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis < fromMs) add(Calendar.DAY_OF_YEAR, 1)
    }
    val midnights = mutableListOf<Long>()
    while (cal.timeInMillis <= toMs) {
        midnights.add(cal.timeInMillis)
        cal.add(Calendar.DAY_OF_YEAR, 1)
    }
    if (midnights.isEmpty()) return null
    val shown = ChartMath.axisLabelIndices(midnights.size, ceil(midnights.size / 5.0).toInt())
    return midnights
        .filterIndexed { i, _ -> i in shown }
        .map { it.toFloat() to Fmt.day(it) }
}

/**
 * Подпись оси остатка. На мелких диапазонах целые числа дают одинаковые
 * подписи вида «1, 1, 0», поэтому там добавляется десятая доля. Единица
 * подставляется своя: «%» у процентной квоты.
 */
private fun axisLabel(value: Float, scale: Double, unit: String): String {
    val number = if (scale < 10.0) String.format("%.1f", value) else value.roundToLong().toString()
    return if (unit.equals("PERCENT", ignoreCase = true)) "$number%" else number
}
