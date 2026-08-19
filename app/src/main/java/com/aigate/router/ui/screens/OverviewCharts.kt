package com.aigate.router.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aigate.router.data.model.QuotaSnapshot
import com.aigate.router.quota.QuotaBurn
import com.aigate.router.quota.QuotaRepository
import com.aigate.router.quota.ResourcePoolKind
import com.aigate.router.ui.design.AppCard
import com.aigate.router.ui.design.CardTone
import com.aigate.router.ui.design.Fmt
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.SectionHeader
import com.aigate.router.ui.design.charts.BarDatum
import com.aigate.router.ui.design.charts.ChartLegend
import com.aigate.router.ui.design.charts.ChartPoint
import com.aigate.router.ui.design.charts.LineChart
import com.aigate.router.ui.design.charts.LineSeries
import com.aigate.router.ui.design.charts.StackSegment
import com.aigate.router.ui.design.charts.StackedBarChart
import com.aigate.router.ui.design.charts.StackedColumn
import com.aigate.router.usage.UsageHistory
import kotlin.math.roundToLong

/**
 * Графики главного экрана. Их три, и они отвечают на разные вопросы:
 * сколько месяц стоит, сколько работы прошло через шлюз и успею ли я
 * израсходовать квоту до сброса.
 */

/** Объём работы по дням: входные и выходные токены стопкой. */
@Composable
fun UsageByDayCard(days: List<UsageHistory.DayUsage>) {
    AppCard(tone = CardTone.Raised) {
        Column(verticalArrangement = Arrangement.spacedBy(Gateway.spacing.sm)) {
            Text(
                text = "Использование по дням",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val withData = days.filter { it.promptTokens + it.completionTokens > 0 }
            if (withData.isEmpty()) {
                Text(
                    text = "Запросов пока не было",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
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
                height = 150.dp,
                valueLabel = { Fmt.compact(it.toLong()) },
                xLabelEvery = 3,
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
 * сгорит неиспользованной.
 */
@Composable
fun QuotaBurnCard(
    pools: List<QuotaRepository.PoolQuota>,
    histories: Map<Long, List<QuotaSnapshot>>,
) {
    // Только ресурсы со сбросом: у баланса и бесплатных «темпа до сброса» нет.
    val quotaPools = pools.filter { ResourcePoolKind.fromName(it.pool.kind).hasReset }
    if (quotaPools.isEmpty()) return

    SectionHeader("Темп расхода квоты")
    quotaPools.forEach { pq ->
        val history = histories[pq.pool.id].orEmpty()
        AppCard(tone = CardTone.Raised) {
            Column(verticalArrangement = Arrangement.spacedBy(Gateway.spacing.sm)) {
                Text(
                    text = pq.pool.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                val now = System.currentTimeMillis()
                val remaining = pq.snapshot?.remaining
                val resetsAt = pq.snapshot?.resetsAt
                val points = history
                    .filter { it.remaining != null }
                    .map { ChartPoint(it.updatedAt.toFloat(), it.remaining!!.toFloat()) }

                if (points.size < 2 || remaining == null || resetsAt == null) {
                    Text(
                        text = "Истории расхода пока нет",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }

                // Ровный темп: из нынешнего остатка в ноль ровно к сбросу.
                val evenPace = listOf(
                    ChartPoint(now.toFloat(), remaining.toFloat()),
                    ChartPoint(resetsAt.toFloat(), 0f),
                )
                val unit = pq.snapshot?.unit ?: pq.pool.unit
                LineChart(
                    series = listOf(
                        LineSeries("Остаток", points, colorIndex = 0, filled = true),
                        LineSeries("Ровный темп", evenPace, projected = true),
                    ),
                    height = 150.dp,
                    xLabelAt = { Fmt.time(it.toLong()) },
                    yLabelAt = { axisLabel(it, remaining, unit) },
                )
                Text(
                    text = burnSummary(history, remaining, resetsAt, now),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Подпись под графиком: чем закончится нынешний темп. Нет истории — так и
 * пишем, вместо того чтобы придумывать прогноз.
 */
private fun burnSummary(
    history: List<QuotaSnapshot>,
    remaining: Double,
    resetsAt: Long,
    now: Long,
): String {
    val rate = QuotaBurn.rate(history, now) ?: return "темп расхода пока неизвестен"
    val outlook = QuotaBurn.outlook(remaining, resetsAt, rate, now) ?: return "сброс уже наступил"
    outlook.exhaustAtMs?.let {
        val hoursEarlier = (resetsAt - it) / 3_600_000.0
        return "кончится на ${humanHours(hoursEarlier)} раньше сброса"
    }
    if (outlook.surplus > 0.0) {
        return "сгорит ${outlook.surplus.roundToLong()}; нужно " +
            "${humanHours(outlook.hoursNeededAtPeak)} работы, осталось " +
            humanHours(outlook.hoursToReset)
    }
    return "расход идёт ровно по периоду"
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

private fun humanHours(h: Double): String =
    if (h >= 24) "${(h / 24).roundToLong()} дн" else "${h.roundToLong()} ч"
