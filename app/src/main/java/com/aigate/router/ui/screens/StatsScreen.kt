package com.aigate.router.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aigate.router.GatewayApplication
import com.aigate.router.data.model.SpeedHistory
import com.aigate.router.service.GatewayForegroundService
import com.aigate.router.ui.design.AppCard
import com.aigate.router.ui.design.CardTone
import com.aigate.router.ui.design.ConfirmDialog
import com.aigate.router.ui.design.EmptyState
import com.aigate.router.ui.design.Fmt
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.MetricTile
import com.aigate.router.ui.design.SectionHeader
import com.aigate.router.ui.design.StatusChip
import com.aigate.router.ui.design.StatusTone
import com.aigate.router.ui.design.charts.BarDatum
import com.aigate.router.ui.design.charts.ChartLegend
import com.aigate.router.ui.design.charts.ChartPoint
import com.aigate.router.ui.design.charts.DonutChart
import com.aigate.router.ui.design.charts.HorizontalBarChart
import com.aigate.router.ui.design.charts.LineChart
import com.aigate.router.ui.design.charts.LineSeries
import com.aigate.router.ui.design.charts.StackSegment
import com.aigate.router.ui.design.charts.StackedBar100
import com.aigate.router.ui.design.charts.StackedBarChart
import com.aigate.router.ui.design.charts.StackedColumn
import com.aigate.router.ui.util.rememberTicker
import com.aigate.router.ui.viewmodel.GatewayViewModel
import com.aigate.router.usage.UsageHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/**
 * Тонкая обёртка над сегментом «Графики»: раздел «Статистика» из настроек
 * показывает тот же контент, что и таб «Активность».
 */
@Composable
fun StatsScreen(viewModel: GatewayViewModel, modifier: Modifier = Modifier) {
    StatsSegment(viewModel = viewModel, modifier = modifier.fillMaxSize())
}

/** Что именно очищаем — один диалог на оба необратимых действия. */
private enum class ClearTarget { Usage, Traffic }

/**
 * Сегмент «Графики»: расход токенов и трафик в виде дашборда. Раньше здесь были
 * списки карточек и два несравнимых прогресс-бара; теперь — доли, тренды и
 * рейтинги на общих чартах дизайн-системы.
 */
@Composable
internal fun StatsSegment(viewModel: GatewayViewModel, modifier: Modifier = Modifier) {
    val db = remember { GatewayApplication.getInstance().database }
    val allTokenUsage by viewModel.allTokenUsage.collectAsState()
    val totalPromptTokens by viewModel.totalPromptTokens.collectAsState()
    val totalCompletionTokens by viewModel.totalCompletionTokens.collectAsState()
    val totalTokensAll by viewModel.totalTokensAll.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val apiKeyUsageRows by viewModel.apiKeyUsageRows.collectAsState()

    // Счётчики трафика — AtomicLong, не реактивны: читаем по тику.
    val ticker by rememberTicker(2_000L)
    val slowTick = ticker / 15
    val uploadBytes = remember(ticker) { GatewayForegroundService.totalUploadBytes.get() }
    val downloadBytes = remember(ticker) { GatewayForegroundService.totalDownloadBytes.get() }

    LaunchedEffect(slowTick) {
        viewModel.refreshTokenStats()
        viewModel.loadApiKeyUsage()
    }

    // Тяжёлые агрегаты считаем в IO и пересчитываем раз в ~30 секунд.
    val daily by produceState(initialValue = emptyList<UsageHistory.DayUsage>(), slowTick) {
        value = withContext(Dispatchers.IO) { UsageHistory.daily(db, days = 14) }
    }
    val providerShares by produceState(initialValue = emptyList<BarDatum>(), providers, slowTick) {
        value = withContext(Dispatchers.IO) {
            providers
                .mapIndexed { index, provider ->
                    BarDatum(
                        label = provider.name,
                        value = viewModel.getTotalTokensByProvider(provider.id).toFloat(),
                        colorIndex = index,
                    )
                }
                .filter { it.value > 0f }
                .sortedByDescending { it.value }
        }
    }

    val modelBars = remember(allTokenUsage) {
        allTokenUsage
            .groupBy { it.modelId }
            .map { (modelId, rows) -> BarDatum(modelId, rows.sumOf { it.totalTokens }.toFloat()) }
            .filter { it.value > 0f }
    }
    val apiKeyBars = remember(apiKeyUsageRows) {
        apiKeyUsageRows
            .map { BarDatum(it.apiKeyLabel.ifBlank { "Без ключа" }, it.total.toFloat()) }
            .filter { it.value > 0f }
    }

    var pendingClear by remember { mutableStateOf<ClearTarget?>(null) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gateway.spacing.lg)
            .padding(bottom = Gateway.spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Gateway.spacing.md),
    ) {
        // ==================== KPI ====================
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Gateway.spacing.md),
        ) {
            MetricTile(
                label = "Токены",
                value = Fmt.compact(totalTokensAll),
                unit = "всего",
                modifier = Modifier.weight(1f),
                below = {
                    StatLine("вызовов", allTokenUsage.size.toString())
                    StatLine(
                        label = "в среднем",
                        value = Fmt.compact(
                            if (allTokenUsage.isEmpty()) 0L
                            else totalTokensAll / allTokenUsage.size,
                        ),
                    )
                },
            )
            MetricTile(
                label = "Трафик",
                value = Fmt.bytes(uploadBytes + downloadBytes),
                modifier = Modifier.weight(1f),
                below = {
                    TrafficLine(Icons.Outlined.ArrowUpward, Fmt.bytes(uploadBytes))
                    TrafficLine(Icons.Outlined.ArrowDownward, Fmt.bytes(downloadBytes))
                },
            )
        }

        // ==================== Структура расхода ====================
        SectionHeader("Структура расхода")
        AppCard(tone = CardTone.Raised) {
            if (totalTokensAll > 0) {
                StackedBar100(
                    segments = listOf(
                        StackSegment(totalPromptTokens.toFloat(), 0),
                        StackSegment(totalCompletionTokens.toFloat(), 1),
                    ),
                )
                Spacer(Modifier.height(Gateway.spacing.md))
                ChartLegend(
                    data = listOf(
                        BarDatum("Входные токены", totalPromptTokens.toFloat(), 0),
                        BarDatum("Выходные токены", totalCompletionTokens.toFloat(), 1),
                    ),
                    valueLabel = { Fmt.compact(it.toLong()) },
                )
            } else {
                EmptyState(Icons.Outlined.BarChart, "Расхода токенов пока нет")
            }
        }

        // ==================== Расход по дням ====================
        SectionHeader("Расход по дням")
        AppCard {
            val dayColumns = daily.map { day ->
                StackedColumn(
                    label = Fmt.day(day.dayStartMs),
                    segments = listOf(
                        StackSegment(day.promptTokens.toFloat(), 0),
                        StackSegment(day.completionTokens.toFloat(), 1),
                    ),
                )
            }
            val hasDays = daily.any { it.promptTokens + it.completionTokens > 0 }
            if (hasDays) {
                StackedBarChart(
                    columns = dayColumns,
                    height = 190.dp,
                    valueLabel = { Fmt.compact(it.toLong()) },
                    xLabelEvery = 3,
                )
                Spacer(Modifier.height(Gateway.spacing.md))
                ChartLegend(
                    data = listOf(
                        BarDatum("Входные", daily.sumOf { it.promptTokens }.toFloat(), 0),
                        BarDatum("Выходные", daily.sumOf { it.completionTokens }.toFloat(), 1),
                    ),
                    valueLabel = { Fmt.compact(it.toLong()) },
                )
            } else {
                EmptyState(Icons.Outlined.BarChart, "За две недели расхода не было")
            }
        }

        // ==================== Доли провайдеров ====================
        SectionHeader("Доли провайдеров")
        AppCard {
            if (providerShares.isEmpty()) {
                EmptyState(Icons.Outlined.Hub, "Расход по провайдерам не записан")
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DonutChart(
                        data = providerShares,
                        diameter = 124.dp,
                        centerPrimary = Fmt.compact(providerShares.sumOf { it.value.toLong() }),
                        centerSecondary = "токенов",
                    )
                    Spacer(Modifier.width(Gateway.spacing.md))
                    ChartLegend(
                        data = providerShares,
                        modifier = Modifier.weight(1f),
                        valueLabel = { Fmt.compact(it.toLong()) },
                    )
                }
            }
        }

        // ==================== Топ моделей ====================
        SectionHeader("Топ моделей")
        AppCard {
            if (modelBars.isEmpty()) {
                EmptyState(Icons.Outlined.BarChart, "Расход по моделям не записан")
            } else {
                HorizontalBarChart(
                    data = modelBars,
                    valueLabel = { Fmt.compact(it.toLong()) },
                    maxBars = 10,
                )
            }
        }

        // ==================== Тренд скорости ====================
        SpeedTrendSection(viewModel = viewModel)

        // ==================== Расход по API-ключам ====================
        SectionHeader("Расход по API-ключам")
        AppCard {
            if (apiKeyBars.isEmpty()) {
                EmptyState(Icons.Outlined.VpnKey, "Запросов с API-ключом не было")
            } else {
                HorizontalBarChart(
                    data = apiKeyBars,
                    valueLabel = { Fmt.compact(it.toLong()) },
                    maxBars = 10,
                )
            }
        }

        // ==================== Последние вызовы ====================
        if (allTokenUsage.isNotEmpty()) {
            SectionHeader("Последние вызовы")
            AppCard {
                val providerNames = remember(providers) { providers.associate { it.id to it.name } }
                allTokenUsage.take(6).forEachIndexed { index, usage ->
                    if (index > 0) {
                        HorizontalDivider(Modifier.padding(vertical = Gateway.spacing.sm))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = usage.modelId,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${providerNames[usage.providerId] ?: "—"} · " +
                                    Fmt.dateTime(usage.timestamp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(Gateway.spacing.sm))
                        Text(
                            text = Fmt.compact(usage.totalTokens.toLong()),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        // ==================== Очистка ====================
        SectionHeader("Очистка данных")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Gateway.spacing.md),
        ) {
            ClearButton(
                text = "Расход",
                modifier = Modifier.weight(1f),
                onClick = { pendingClear = ClearTarget.Usage },
            )
            ClearButton(
                text = "Трафик",
                modifier = Modifier.weight(1f),
                onClick = { pendingClear = ClearTarget.Traffic },
            )
        }
    }

    pendingClear?.let { target ->
        ConfirmDialog(
            title = when (target) {
                ClearTarget.Usage -> "Очистить расход"
                ClearTarget.Traffic -> "Очистить трафик"
            },
            message = when (target) {
                ClearTarget.Usage ->
                    "Все записи о расходе токенов будут удалены. Действие необратимо."
                ClearTarget.Traffic ->
                    "Счётчики отправленных и полученных байт будут обнулены. Действие необратимо."
            },
            confirmText = "Очистить",
            onConfirm = {
                when (target) {
                    ClearTarget.Usage -> viewModel.clearAllUsage()
                    ClearTarget.Traffic -> {
                        GatewayForegroundService.clearTotalTraffic()
                        viewModel.refreshTokenStats()
                    }
                }
            },
            onDismiss = { pendingClear = null },
        )
    }
}

/**
 * Тренд скорости: линейный график с осями и подписями (прежний график рисовал
 * подписи «в никуда»), выбор модели чипами вместо модального списка.
 */
@Composable
private fun SpeedTrendSection(viewModel: GatewayViewModel) {
    val latestSpeedHistory by viewModel.latestSpeedHistory.collectAsState()
    val selectedModelHistory by viewModel.selectedModelHistory.collectAsState()
    val selectedHistoryModelKey by viewModel.selectedHistoryModelKey.collectAsState()

    // Живая история выбранной модели; пока Flow не отдал первое значение,
    // показываем то, что уже загрузил loadModelHistory().
    val liveHistory by remember(selectedHistoryModelKey) {
        selectedHistoryModelKey
            ?.let { viewModel.speedHistoryDao.getHistoryByModel(it, 60) }
            ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    var metricIndex by remember { mutableIntStateOf(0) }
    val metrics = listOf("TTFT", "TPS", "Всего")

    val rawHistory: List<SpeedHistory> = when {
        selectedHistoryModelKey == null -> latestSpeedHistory
        liveHistory.isNotEmpty() -> liveHistory
        else -> selectedModelHistory
    }
    val points = rawHistory.filter { it.success }.sortedBy { it.measuredAt }
    val failed = rawHistory.count { !it.success }

    SectionHeader(
        title = "Тренд скорости",
        action = if (failed > 0) {
            { StatusChip(text = "$failed неудачных", tone = StatusTone.Warning) }
        } else {
            null
        },
    )

    AppCard {
        // Выбор модели — плоский ряд чипов вместо модального диалога.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Gateway.spacing.sm),
        ) {
            FilterChip(
                selected = selectedHistoryModelKey == null,
                onClick = { viewModel.loadModelHistory("") },
                label = { Text("Все модели") },
            )
            latestSpeedHistory.forEach { entry ->
                FilterChip(
                    selected = selectedHistoryModelKey == entry.modelKey,
                    onClick = { viewModel.loadModelHistory(entry.modelKey) },
                    label = {
                        Text(entry.modelName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                )
            }
        }

        Spacer(Modifier.height(Gateway.spacing.sm))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Gateway.spacing.sm),
        ) {
            metrics.forEachIndexed { index, label ->
                FilterChip(
                    selected = metricIndex == index,
                    onClick = { metricIndex = index },
                    label = { Text(label) },
                )
            }
        }

        Spacer(Modifier.height(Gateway.spacing.md))

        if (points.isEmpty()) {
            EmptyState(Icons.Outlined.Speed, "Замеров скорости пока нет")
            return@AppCard
        }

        val metricValue: (SpeedHistory) -> Float = { history ->
            when (metricIndex) {
                0 -> history.ttftMs.toFloat()
                1 -> history.tps.toFloat()
                else -> history.totalMs.toFloat()
            }
        }
        LineChart(
            series = listOf(
                LineSeries(
                    label = metrics[metricIndex],
                    points = points.map {
                        ChartPoint(it.measuredAt.toFloat(), metricValue(it))
                    },
                    colorIndex = metricIndex,
                    filled = true,
                ),
            ),
            height = 190.dp,
            xLabelAt = { Fmt.time(it.toLong()) },
            yLabelAt = { value ->
                if (metricIndex == 1) "%.0f".format(value) else Fmt.latency(value.toLong())
            },
        )

        Spacer(Modifier.height(Gateway.spacing.md))

        val values = points.map { metricValue(it).toDouble() }
        val formatValue: (Double) -> String = { value ->
            if (metricIndex == 1) "%.1f".format(value) else Fmt.latency(value.toLong())
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SummaryValue("Последнее", formatValue(values.last()))
            SummaryValue("Среднее", formatValue(values.average()))
            SummaryValue("Замеров", points.size.toString())
        }
    }
}

/** Строка счётчика трафика: направление — иконкой, не текстовой стрелкой. */
@Composable
private fun TrafficLine(icon: ImageVector, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Gateway.spacing.xs))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Пара «подпись — значение» внутри карточки. */
@Composable
private fun StatLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Итог под графиком скорости. */
@Composable
private fun SummaryValue(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Обе кнопки очистки оформлены одинаково; предупреждение — в ConfirmDialog. */
@Composable
private fun ClearButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Icon(
            Icons.Outlined.DeleteSweep,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Gateway.spacing.sm))
        Text(text, maxLines = 1)
    }
}
