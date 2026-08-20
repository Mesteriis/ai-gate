package com.aigate.router.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aigate.router.GatewayApplication
import com.aigate.router.data.model.Provider
import com.aigate.router.data.model.SpeedHistory
import com.aigate.router.data.model.TokenUsage
import com.aigate.router.quota.ProviderSpend
import com.aigate.router.quota.QuotaRepository
import com.aigate.router.quota.ResourcePoolKind
import com.aigate.router.service.GatewayForegroundService
import com.aigate.router.ui.design.AppCard
import com.aigate.router.ui.design.ChartCard
import com.aigate.router.ui.design.ConfirmDialog
import com.aigate.router.ui.design.EmptyState
import com.aigate.router.ui.design.Fmt
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.HeroCard
import com.aigate.router.ui.design.appear
import com.aigate.router.ui.design.parallax
import com.aigate.router.ui.design.PeriodFilter
import com.aigate.router.ui.design.SectionHeader
import com.aigate.router.ui.design.StatTriple
import com.aigate.router.ui.design.StatusChip
import com.aigate.router.ui.design.StatusTone
import com.aigate.router.ui.design.charts.BarDatum
import com.aigate.router.ui.design.charts.ChartLegend
import com.aigate.router.ui.design.charts.ChartMath
import com.aigate.router.ui.design.charts.ChartPoint
import com.aigate.router.ui.design.charts.DonutChart
import com.aigate.router.ui.design.charts.HorizontalBarChart
import com.aigate.router.ui.design.charts.LineChart
import com.aigate.router.ui.design.charts.LineSeries
import com.aigate.router.ui.design.charts.StackSegment
import com.aigate.router.ui.design.charts.StackedBar100
import com.aigate.router.ui.design.charts.StackedBarChart
import com.aigate.router.ui.design.charts.StackedColumn
import com.aigate.router.ui.design.charts.UNSET_COLOR
import com.aigate.router.ui.util.rememberTicker
import com.aigate.router.ui.viewmodel.GatewayViewModel
import com.aigate.router.usage.UsageHistory
import com.aigate.router.usage.UsageStats
import kotlin.math.roundToInt
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
 * Сегмент «Графики»: дашборд расхода за выбранный период. Один фильтр периода
 * управляет всеми разрезами (герой, дни, доли, модели, ключи), а каждая
 * карточка с графиком отвечает «живой строкой-выводом» на выбор отметки.
 */
@Composable
internal fun StatsSegment(viewModel: GatewayViewModel, modifier: Modifier = Modifier) {
    val db = remember { GatewayApplication.getInstance().database }
    val allTokenUsage by viewModel.allTokenUsage.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val periodDays by viewModel.statsPeriodDays.collectAsState()
    val snapshot by viewModel.statsSnapshot.collectAsState()

    // Дневные агрегаты не реактивны — пересчитываем их раз в ~30 секунд.
    val ticker by rememberTicker(2_000L)
    val slowTick = ticker / 15

    // Тяжёлая выборка по дням идёт в IO; глубина зависит от периода фильтра.
    val daily by produceState(initialValue = emptyList<UsageHistory.DayUsage>(), periodDays, slowTick) {
        value = withContext(Dispatchers.IO) { UsageHistory.daily(db, days = periodDays) }
    }

    var pendingClear by remember { mutableStateOf<ClearTarget?>(null) }

    BoxWithConstraints(modifier = modifier) {
        // Раскрытый Fold: фильтр и герой во всю ширину, карточки — в две колонки,
        // чтобы широкий экран не растягивал графики до нечитаемых пропорций.
        val wide = maxWidth >= 640.dp
        // Скролл держим сами: витрина расхода уходит с параллаксом, а он
        // считается от текущего смещения списка.
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = Gateway.spacing.lg)
                .padding(bottom = Gateway.spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Gateway.spacing.md),
        ) {
            PeriodFilter(
                selectedDays = periodDays,
                onSelect = viewModel::setStatsPeriod,
                rangeLabel = snapshot?.let {
                    "${Fmt.day(it.fromMs)} – ${Fmt.day(System.currentTimeMillis())}"
                },
            )

            snapshot?.let { snap ->
                val avgPerCall = if (snap.calls > 0) snap.totalTokens / snap.calls else 0L
                HeroCard(
                    label = "Расход за ${snap.periodDays} дней",
                    value = Fmt.compact(snap.totalTokens),
                    unit = "токенов",
                    deltaPercent = snap.deltaPercent,
                    deltaCaption = "к прошлым ${snap.periodDays} дням",
                    sparkline = daily
                        .map { (it.promptTokens + it.completionTokens).toFloat() }
                        .takeIf { it.size >= 2 },
                    subLines = listOf(
                        "${snap.calls} " +
                            Fmt.plural(snap.calls.toLong(), "вызов", "вызова", "вызовов") +
                            " · в среднем ${Fmt.compact(avgPerCall)}",
                        "передано ${Fmt.bytes(snap.uploadBytes)} · " +
                            "получено ${Fmt.bytes(snap.downloadBytes)}",
                        // Собственный учёт видит только запросы через шлюз;
                        // расход мимо него — в карточке данных поставщиков.
                        "по локальному подсчёту — только запросы через шлюз",
                    ),
                    modifier = Modifier.parallax(scroll, fadeDistance = 300.dp),
                )
            }

            // Карточки входят волной: индекс задаёт задержку, поэтому дашборд
            // собирается на глазах, а не мигает целиком.
            if (wide) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Gateway.spacing.md),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Gateway.spacing.md),
                    ) {
                        DailyUsageCard(daily, snapshot, periodDays, Modifier.appear(1))
                        ProviderSpendCard(periodDays, Modifier.appear(3))
                        ProviderSharesCard(snapshot, providers, periodDays, Modifier.appear(5))
                        RecentCallsCard(allTokenUsage, providers, Modifier.appear(7))
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Gateway.spacing.md),
                    ) {
                        SpeedTrendCard(viewModel, periodDays, providers, Modifier.appear(2))
                        TopModelsCard(snapshot, providers, Modifier.appear(4))
                        ApiKeysCard(snapshot, Modifier.appear(6))
                        ClearSection(onClear = { pendingClear = it }, Modifier.appear(7))
                    }
                }
            } else {
                DailyUsageCard(daily, snapshot, periodDays, Modifier.appear(1))
                ProviderSpendCard(periodDays, Modifier.appear(2))
                ProviderSharesCard(snapshot, providers, periodDays, Modifier.appear(3))
                TopModelsCard(snapshot, providers, Modifier.appear(4))
                SpeedTrendCard(viewModel, periodDays, providers, Modifier.appear(5))
                ApiKeysCard(snapshot, Modifier.appear(6))
                RecentCallsCard(allTokenUsage, providers, Modifier.appear(7))
                ClearSection(onClear = { pendingClear = it }, Modifier.appear(8))
            }
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
 * Цвет закреплён за провайдером: берётся его индекс в общем списке провайдеров,
 * а не позиция в конкретном графике, поэтому цвет не «прыгает» при фильтрах.
 * Удалённый провайдер из списка выпадает — тогда цвет отдаём на усмотрение
 * графика ([UNSET_COLOR]), но подменять его чужим индексом нельзя.
 */
private fun List<Provider>.colorIndexOf(providerId: Long): Int =
    indexOfFirst { it.id == providerId }.takeIf { it >= 0 } ?: UNSET_COLOR

/** Заголовок-eyebrow карточки: тихая подпись «о чём карточка». */
@Composable
private fun CardEyebrow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * «Расход по дням»: стек входные/выходные по дням плюс пропорция за весь период.
 * Выбор дня переписывает строку-вывод карточки; по умолчанию она называет пик.
 */
@Composable
private fun DailyUsageCard(
    daily: List<UsageHistory.DayUsage>,
    snapshot: UsageStats.Snapshot?,
    periodDays: Int,
    modifier: Modifier = Modifier,
) {
    val totals = daily.map { (it.promptTokens + it.completionTokens).toFloat() }
    if (totals.none { it > 0f }) {
        AppCard(modifier) {
            CardEyebrow("Расход по дням")
            EmptyState(Icons.Outlined.BarChart, "За период расхода не было")
        }
        return
    }

    // Смена периода делает прежний индекс дня бессмысленным — сбрасываем выбор.
    var selDay by rememberSaveable(periodDays) { mutableStateOf<Int?>(null) }
    // Данные обновляются по тику: выбор, переживший пересчёт, может указать
    // за пределы списка — такой просто игнорируем.
    val selected = selDay?.let { daily.getOrNull(it) }

    val avgPerDay = totals.sum() / daily.size
    val peakIndex = totals.indices.maxByOrNull { totals[it] } ?: 0

    val readMain: String
    val readSub: String
    if (selected != null) {
        val dayTotal = selected.promptTokens + selected.completionTokens
        val dayLabel = if (selDay == daily.lastIndex) "сегодня" else Fmt.day(selected.dayStartMs)
        readMain = "$dayLabel · ${Fmt.compact(dayTotal)}"
        val deltaToAvg =
            if (avgPerDay > 0f) ((dayTotal - avgPerDay) / avgPerDay * 100f).roundToInt() else 0
        readSub = "входные ${Fmt.compact(selected.promptTokens)} · " +
            "выходные ${Fmt.compact(selected.completionTokens)} · " +
            "%+d%% к среднему".format(deltaToAvg)
    } else {
        readMain = "пик ${Fmt.day(daily[peakIndex].dayStartMs)} · " +
            Fmt.compact(totals[peakIndex].toLong())
        readSub = "в среднем ${Fmt.compact(avgPerDay.toLong())} в день"
    }

    ChartCard(
        eyebrow = "Расход по дням",
        readMain = readMain,
        readSub = readSub,
        modifier = modifier,
    ) {
        StackedBarChart(
            columns = daily.map { day ->
                StackedColumn(
                    label = Fmt.day(day.dayStartMs),
                    segments = listOf(
                        StackSegment(day.promptTokens.toFloat(), 0),
                        StackSegment(day.completionTokens.toFloat(), 1),
                    ),
                )
            },
            height = 200.dp,
            valueLabel = { Fmt.compact(it.toLong()) },
            xLabelEvery = ChartMath.labelEvery(daily.size),
            selectedIndex = selDay,
            onSelect = { selDay = it },
            lastLabel = "сегодня",
        )
        // Структура расхода за период слита сюда: отдельная карточка дублировала
        // те же две доли, что уже видны в стеках по дням.
        if (snapshot != null && snapshot.totalTokens > 0) {
            Spacer(Modifier.height(Gateway.spacing.md))
            val periodTotal =
                (snapshot.promptTokens + snapshot.completionTokens).toFloat().coerceAtLeast(1f)
            StackedBar100(
                segments = listOf(
                    StackSegment(snapshot.promptTokens.toFloat(), 0),
                    StackSegment(snapshot.completionTokens.toFloat(), 1),
                ),
                inlineLabel = { value -> "${(value / periodTotal * 100f).roundToInt()}%" },
            )
            Spacer(Modifier.height(Gateway.spacing.sm))
            ChartLegend(
                data = listOf(
                    BarDatum("Входные", snapshot.promptTokens.toFloat(), 0),
                    BarDatum("Выходные", snapshot.completionTokens.toFloat(), 1),
                ),
                valueLabel = { Fmt.compact(it.toLong()) },
            )
        }
    }
}

/**
 * «Доли провайдеров»: донат и легенда с общим выбором. Центр доната по
 * умолчанию отдан итогу периода, при выборе — доле и имени провайдера.
 */
@Composable
private fun ProviderSharesCard(
    snapshot: UsageStats.Snapshot?,
    providers: List<Provider>,
    periodDays: Int,
    modifier: Modifier = Modifier,
) {
    val shares = snapshot?.byProvider.orEmpty()
    AppCard(modifier) {
        CardEyebrow("Доли провайдеров")
        if (shares.isEmpty()) {
            EmptyState(Icons.Outlined.Hub, "Расход по провайдерам не записан")
            return@AppCard
        }
        Spacer(Modifier.height(Gateway.spacing.sm))

        val data = shares.map { share ->
            BarDatum(
                label = share.name,
                value = share.tokens.toFloat(),
                colorIndex = providers.colorIndexOf(share.providerId),
            )
        }
        var selProv by rememberSaveable(periodDays) { mutableStateOf<Int?>(null) }
        val sel = selProv?.let { data.getOrNull(it) }
        val totalTokens = shares.sumOf { it.tokens }
        val totalF = totalTokens.toFloat().coerceAtLeast(1f)

        val donut = @Composable {
            DonutChart(
                data = data,
                diameter = 124.dp,
                centerPrimary =
                if (sel != null) "${(sel.value / totalF * 100f).roundToInt()}%"
                else Fmt.compact(totalTokens),
                centerSecondary = sel?.label ?: "токенов",
                // Диапазон периода не дублируем: он стоит в фильтре над карточками.
                centerTertiary = sel?.let { Fmt.compact(it.value.toLong()) },
                selectedIndex = selProv,
                onSelect = { selProv = it },
            )
        }
        val legend = @Composable { legendModifier: Modifier ->
            ChartLegend(
                data = data,
                modifier = legendModifier,
                valueLabel = { Fmt.compact(it.toLong()) },
                showShare = true,
                selectedIndex = selProv,
                onSelect = { selProv = it },
            )
        }

        BoxWithConstraints {
            // Рядом с донатом легенде остаётся слишком мало места в колонке
            // раскрытого Fold — имена провайдеров обрезались до многоточия,
            // поэтому в узкой карточке легенда уходит под донат.
            if (maxWidth >= 380.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    donut()
                    Spacer(Modifier.width(Gateway.spacing.md))
                    legend(Modifier.weight(1f))
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Gateway.spacing.md),
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { donut() }
                    legend(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** «Топ моделей»: рейтинг с рангами и долями, цвет бара — цвет провайдера. */
@Composable
private fun TopModelsCard(
    snapshot: UsageStats.Snapshot?,
    providers: List<Provider>,
    modifier: Modifier = Modifier,
) {
    val models = snapshot?.byModel.orEmpty()
    AppCard(modifier) {
        CardEyebrow("Топ моделей · цвет — провайдер")
        if (models.isEmpty()) {
            EmptyState(Icons.Outlined.BarChart, "Расход по моделям не записан")
            return@AppCard
        }
        Spacer(Modifier.height(Gateway.spacing.sm))
        HorizontalBarChart(
            data = models.map { model ->
                BarDatum(
                    label = model.modelId,
                    value = model.tokens.toFloat(),
                    colorIndex = providers.colorIndexOf(model.providerId),
                )
            },
            valueLabel = { Fmt.compact(it.toLong()) },
            maxBars = 6,
            barHeight = 8.dp,
            singleColor = false,
            showShare = true,
            showRank = true,
        )
    }
}

/**
 * «Тренд скорости»: линия выбранной метрики по выбранной модели. Строка-вывод
 * по умолчанию читает последний замер, выбор точки — конкретный замер.
 */
@Composable
private fun SpeedTrendCard(
    viewModel: GatewayViewModel,
    periodDays: Int,
    providers: List<Provider>,
    modifier: Modifier = Modifier,
) {
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

    // Смена модели, метрики или периода делает индекс замера чужим — сброс.
    var selPt by rememberSaveable(selectedHistoryModelKey, metricIndex, periodDays) {
        mutableStateOf<Int?>(null)
    }

    val metricValue: (SpeedHistory) -> Float = { history ->
        when (metricIndex) {
            0 -> history.ttftMs.toFloat()
            1 -> history.tps.toFloat()
            else -> history.totalMs.toFloat()
        }
    }
    // TPS — «больше лучше» и меряется в токенах в секунду, остальные метрики —
    // задержка, где меньше лучше.
    val isThroughput = metricIndex == 1
    val formatValue: (Float) -> String = { value ->
        if (isThroughput) "${value.roundToInt()} ток/с" else Fmt.latency(value.toLong())
    }
    // «Все модели» — это срез по последним замерам разных моделей, а не ряд во
    // времени: у всех точек почти одинаковый x, и линия вырождалась в вертикаль.
    val comparingModels = selectedHistoryModelKey == null

    if (points.isEmpty()) {
        AppCard(modifier) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { CardEyebrow("Тренд скорости") }
                if (failed > 0) {
                    StatusChip(text = "$failed неудачных", tone = StatusTone.Warning)
                }
            }
            Spacer(Modifier.height(Gateway.spacing.sm))
            SpeedTrendChips(
                latestSpeedHistory = latestSpeedHistory,
                selectedModelKey = selectedHistoryModelKey,
                metrics = metrics,
                metricIndex = metricIndex,
                onSelectModel = viewModel::loadModelHistory,
                onSelectMetric = { metricIndex = it },
            )
            EmptyState(Icons.Outlined.Speed, "Замеров скорости пока нет")
        }
        return
    }

    // Делегированный var не смарткастится — фиксируем индекс локально.
    val selIdx = selPt
    val sel = selIdx?.let { points.getOrNull(it) }
    val values = points.map { metricValue(it) }
    // Лучшая модель: по задержке — минимум, по пропускной способности — максимум.
    val best = if (isThroughput) points.maxBy { metricValue(it) } else points.minBy { metricValue(it) }

    val readMain: String
    val readSub: String
    if (comparingModels) {
        readMain = formatValue(metricValue(best))
        // Число моделей не дублируем: оно стоит в сводке под графиком.
        readSub = "быстрее всех: ${best.modelName}"
    } else if (sel != null && selIdx != null) {
        readMain = formatValue(metricValue(sel))
        readSub = "замер ${selIdx + 1} из ${points.size} · ${Fmt.dateTime(sel.measuredAt)}"
    } else {
        readMain = formatValue(metricValue(points.last()))
        readSub = "последний замер · ${Fmt.dateTime(points.last().measuredAt)}"
    }

    ChartCard(
        eyebrow = "Тренд скорости",
        readMain = readMain,
        readSub = readSub,
        modifier = modifier,
        headerAction = if (failed > 0) {
            { StatusChip(text = "$failed неудачных", tone = StatusTone.Warning) }
        } else {
            null
        },
    ) {
        SpeedTrendChips(
            latestSpeedHistory = latestSpeedHistory,
            selectedModelKey = selectedHistoryModelKey,
            metrics = metrics,
            metricIndex = metricIndex,
            onSelectModel = viewModel::loadModelHistory,
            onSelectMetric = { metricIndex = it },
        )

        Spacer(Modifier.height(Gateway.spacing.md))

        if (comparingModels) {
            // Сравнение моделей — рейтинг: у задержки первым идёт самый быстрый.
            HorizontalBarChart(
                data = points.map {
                    BarDatum(
                        label = it.modelName,
                        value = metricValue(it),
                        colorIndex = providers.colorIndexOf(it.providerId),
                    )
                },
                valueLabel = formatValue,
                maxBars = 8,
                barHeight = 8.dp,
                singleColor = false,
                sortDescending = isThroughput,
            )
        } else {
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
                height = 200.dp,
                xLabelAt = { Fmt.time(it.toLong()) },
                yLabelAt = { value ->
                    if (isThroughput) value.roundToInt().toString() else Fmt.latency(value.toLong())
                },
                niceMax = true,
                medianLabel = { "медиана ${formatValue(it)}" },
                selectedIndex = selPt,
                onSelectPoint = { selPt = it },
            )
        }

        Spacer(Modifier.height(Gateway.spacing.md))

        StatTriple(
            items = if (comparingModels) {
                listOf(
                    "Моделей" to points.size.toString(),
                    "Медиана" to formatValue(ChartMath.median(values)),
                    "Разброс" to "${formatValue(values.min())} – ${formatValue(values.max())}",
                )
            } else {
                listOf(
                    "Медиана" to formatValue(ChartMath.median(values)),
                    "p95" to formatValue(ChartMath.percentile(values, 0.95f)),
                    "Замеров" to points.size.toString(),
                )
            },
        )
    }
}

/** Чипы модели и метрики тренда скорости — общие для пустого и живого состояний. */
@Composable
private fun SpeedTrendChips(
    latestSpeedHistory: List<SpeedHistory>,
    selectedModelKey: String?,
    metrics: List<String>,
    metricIndex: Int,
    onSelectModel: (String) -> Unit,
    onSelectMetric: (Int) -> Unit,
) {
    // Выбор модели — плоский ряд чипов вместо модального диалога.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Gateway.spacing.sm),
    ) {
        FilterChip(
            selected = selectedModelKey == null,
            onClick = { onSelectModel("") },
            label = { Text("Все модели") },
        )
        latestSpeedHistory.forEach { entry ->
            FilterChip(
                selected = selectedModelKey == entry.modelKey,
                onClick = { onSelectModel(entry.modelKey) },
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
                onClick = { onSelectMetric(index) },
                label = { Text(label) },
            )
        }
    }
}

/** «Расход по API-ключам»: рейтинг ключей по токенам за период. */
@Composable
private fun ApiKeysCard(snapshot: UsageStats.Snapshot?, modifier: Modifier = Modifier) {
    val keys = snapshot?.byApiKey.orEmpty()
    AppCard(modifier) {
        CardEyebrow("Расход по API-ключам")
        if (keys.isEmpty()) {
            EmptyState(Icons.Outlined.VpnKey, "Запросов с API-ключом не было")
            return@AppCard
        }
        Spacer(Modifier.height(Gateway.spacing.sm))
        HorizontalBarChart(
            data = keys.map { BarDatum(it.label, it.tokens.toFloat()) },
            valueLabel = { Fmt.compact(it.toLong()) },
            barHeight = 8.dp,
            showShare = true,
        )
    }
}

/**
 * «Расход по данным поставщиков»: то, что израсходовано на самом деле.
 *
 * Остальные карточки этого экрана считают расход по собственному учёту, а он
 * записывает только запросы, прошедшие через шлюз. Если запросы идут к
 * поставщику напрямую, они видны лишь здесь — по приростам счётчиков самого
 * поставщика. Карточка молчит целиком, когда таких данных нет: у большинства
 * поставщиков публичного API расхода не существует, и это нормально.
 */
@Composable
private fun ProviderSpendCard(periodDays: Int, modifier: Modifier = Modifier) {
    val db = remember { GatewayApplication.getInstance().database }
    val ticker by rememberTicker(2_000L)

    data class Row(val name: String, val spend: ProviderSpend.PeriodSpend?, val kind: ResourcePoolKind)

    val rows by produceState(initialValue = emptyList<Row>(), periodDays, ticker / 30) {
        value = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val from = now - periodDays * 24L * 3600 * 1000
            db.resourcePoolDao().getAll()
                .filter { it.enabled && ResourcePoolKind.fromName(it.kind) != ResourcePoolKind.FREE }
                .map { pool ->
                    Row(
                        name = pool.name,
                        spend = QuotaRepository.providerReportedSpend(db, pool, from, now),
                        kind = ResourcePoolKind.fromName(pool.kind),
                    )
                }
                .filter { it.spend != null }
        }
    }

    if (rows.isEmpty()) return
    AppCard(modifier) {
        CardEyebrow("Расход по данным поставщиков")
        Spacer(Modifier.height(Gateway.spacing.sm))
        rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(Modifier.padding(vertical = Gateway.spacing.sm))
            val spend = row.spend ?: return@forEachIndexed
            Text(row.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            // Проценты загрузки окна в деньги не переводятся, поэтому у подписок
            // показываем израсходованную долю и не выдумываем сумму.
            Text(
                text = "израсходовано ${Fmt.quota(spend.amount, spend.unit)} за ${periodDays} дн",
                style = MaterialTheme.typography.bodyMedium,
            )
            val from = spend.coveredFromMs
            val expectedFrom = System.currentTimeMillis() - periodDays * 24L * 3600 * 1000
            Text(
                text = if (from > expectedFrom + 3_600_000L) {
                    "данные есть с ${Fmt.dateTime(from)}"
                } else {
                    "учтён расход мимо шлюза"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** «Последние вызовы»: точка цвета провайдера связывает строку с донатом долей. */
@Composable
private fun RecentCallsCard(
    usageRows: List<TokenUsage>,
    providers: List<Provider>,
    modifier: Modifier = Modifier,
) {
    if (usageRows.isEmpty()) return
    val palette = Gateway.colors.chartSeries
    val providerNames = remember(providers) { providers.associate { it.id to it.name } }
    val providerIndex = remember(providers) {
        providers.mapIndexed { index, provider -> provider.id to index }.toMap()
    }
    AppCard(modifier) {
        CardEyebrow("Последние вызовы")
        Spacer(Modifier.height(Gateway.spacing.sm))
        usageRows.take(6).forEachIndexed { index, usage ->
            if (index > 0) {
                HorizontalDivider(Modifier.padding(vertical = Gateway.spacing.sm))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(9.dp)
                        .background(
                            color = palette[(providerIndex[usage.providerId] ?: 0) % palette.size],
                            shape = CircleShape,
                        ),
                )
                Spacer(Modifier.width(Gateway.spacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = usage.modelId,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${providerNames[usage.providerId] ?: "—"} · " +
                            Fmt.time(usage.timestamp),
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

/** Кнопки очистки; подтверждение — в общем ConfirmDialog сегмента. */
@Composable
private fun ClearSection(onClear: (ClearTarget) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        SectionHeader("Очистка данных")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Gateway.spacing.md),
        ) {
            ClearButton(
                text = "Расход",
                modifier = Modifier.weight(1f),
                onClick = { onClear(ClearTarget.Usage) },
            )
            ClearButton(
                text = "Трафик",
                modifier = Modifier.weight(1f),
                onClick = { onClear(ClearTarget.Traffic) },
            )
        }
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
