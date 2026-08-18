package com.aigate.router.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aigate.router.GatewayApplication
import com.aigate.router.data.model.AiModel
import com.aigate.router.data.model.ModelPricing
import com.aigate.router.data.model.Provider
import com.aigate.router.data.model.SpeedHistory
import com.aigate.router.data.model.routeKey
import com.aigate.router.pricing.CostCalculator
import com.aigate.router.ui.design.Fmt
import com.aigate.router.ui.design.FormSheet
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.SectionHeader
import com.aigate.router.ui.design.charts.ChartPoint
import com.aigate.router.ui.design.charts.LineChart
import com.aigate.router.ui.design.charts.LineSeries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Деталь модели: её замеры скорости, цена, контекст и расход. Раньше эти
 * сведения были разбросаны — скорость на главной, цена в ресурсах, расход в
 * статистике, — и по конкретной модели картина не собиралась.
 */
@Composable
fun ModelDetailSheet(
    model: AiModel,
    provider: Provider?,
    onDismiss: () -> Unit,
) {
    val db = remember { GatewayApplication.getInstance().database }
    val history by db.speedHistoryDao()
        .getHistoryByModel(model.routeKey, 60)
        .collectAsState(initial = emptyList())

    val pricing by produceState<ModelPricing?>(initialValue = null, model.id) {
        value = withContext(Dispatchers.IO) {
            CostCalculator.priceFor(db, provider?.type ?: "custom", model.modelId)
        }
    }
    val usage by produceState(initialValue = 0L to 0L, model.id) {
        value = withContext(Dispatchers.IO) {
            val rows = db.tokenUsageDao().getAllUsageOnce().filter { it.modelId == model.modelId }
            rows.sumOf { it.totalTokens.toLong() } to rows.size.toLong()
        }
    }

    FormSheet(title = model.customAlias.ifBlank { model.displayName }, onDismiss = onDismiss) {
        DetailRow("Идентификатор", model.modelId, mono = true)
        provider?.let { DetailRow("Провайдер", it.name) }
        DetailRow("Контекст", "${Fmt.compact(model.contextWindow.toLong())} токенов")
        DetailRow(
            label = "Цена за 1M",
            value = pricing?.let { "вход ${Fmt.usd(it.inputPer1M)} · выход ${Fmt.usd(it.outputPer1M)}" }
                ?: "цена не задана",
        )
        DetailRow(
            label = "Расход",
            value = "${Fmt.compact(usage.first)} токенов за ${usage.second} " +
                Fmt.plural(usage.second, "вызов", "вызова", "вызовов"),
        )

        SectionHeader("Скорость")
        SpeedChart(history)
    }
}

@Composable
private fun SpeedChart(history: List<SpeedHistory>) {
    val successful = history.filter { it.success && it.ttftMs > 0 }
    if (successful.size < 2) {
        Text(
            text = if (successful.isEmpty()) "Замеров пока нет" else "Нужен ещё один замер для графика",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LineChart(
        series = listOf(
            LineSeries(
                label = "Время до первого токена",
                points = successful.map { ChartPoint(it.measuredAt.toFloat(), it.ttftMs.toFloat()) },
                colorIndex = 0,
                filled = true,
            )
        ),
        height = 140.dp,
        xLabelAt = { Fmt.time(it.toLong()) },
        yLabelAt = { Fmt.latency(it.toLong()) },
    )
    val last = successful.last()
    Text(
        text = "последний замер: ${Fmt.latency(last.ttftMs)} · ${"%.1f".format(last.tps)} ток/с",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            else MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}
