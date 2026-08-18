package com.aigate.router.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AllInclusive
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aigate.router.GatewayApplication
import com.aigate.router.quota.QuotaRepository
import com.aigate.router.quota.QuotaBurn
import com.aigate.router.quota.ResourcePoolKind
import com.aigate.router.service.GatewayForegroundService
import com.aigate.router.ui.design.AppCard
import com.aigate.router.ui.design.CardTone
import com.aigate.router.ui.design.Fmt
import com.aigate.router.ui.design.FormSheet
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.HelpSection
import com.aigate.router.ui.design.HelpSheet
import com.aigate.router.ui.design.MetricTile
import com.aigate.router.ui.design.ProviderAvatar
import com.aigate.router.ui.design.QuotaRing
import com.aigate.router.ui.design.SectionHeader
import com.aigate.router.ui.design.StatusChip
import com.aigate.router.ui.design.StatusTone
import com.aigate.router.ui.design.charts.ChartPoint
import com.aigate.router.ui.design.charts.LineChart
import com.aigate.router.ui.design.charts.LineSeries
import com.aigate.router.ui.design.pressureTone
import com.aigate.router.ui.util.localIpAddress
import com.aigate.router.ui.util.rememberTicker
import com.aigate.router.ui.viewmodel.GatewayViewModel
import com.aigate.router.usage.UsageHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Справка экрана «Обзор» — всё, что раньше висело инструкцией прямо на главной. */
internal val overviewHelp = listOf(
    HelpSection(
        "Как подключить приложение",
        "Запустите шлюз и укажите в стороннем приложении Base URL из этого экрана " +
            "(адрес localhost — для приложений на этом же телефоне, адрес сети — для других устройств). " +
            "API-ключ можно указать любой, если в настройках доступа не включено требование ключа.",
    ),
    HelpSection(
        "Устройство не видит шлюз",
        "Телефон и целевое устройство должны быть в одной локальной сети, а брандмауэр не должен " +
            "блокировать порт шлюза.",
    ),
    HelpSection(
        "Квота, баланс и бесплатные модели",
        "Плитки ресурсов показывают три разные сущности. Квота — лимит подписки: расходуется " +
            "и сбрасывается по периоду, поэтому у неё есть кольцо процентов и срок сброса. " +
            "Баланс — оплаченные заранее деньги: уменьшается и сам не восстанавливается, " +
            "поэтому показывается сумма на счету, а не проценты. Бесплатные локальные модели " +
            "лимита не имеют вовсе. Прочерк означает, что провайдер данных не отдаёт.",
    ),
    HelpSection(
        "Автопереключение и тест скорости",
        "Тест скорости измеряет время до первого токена и скорость генерации у каждой модели и строит " +
            "рейтинг. При включённом автопереключении шлюз сам берёт быструю доступную модель; " +
            "выбрать модель вручную можно в разделе «Маршруты».",
    ),
)

/**
 * «Обзор» — рабочий дашборд шлюза: состояние и управление, метрики квот и
 * расхода, скорость моделей, живой поток запросов. Инструкции и подсказки
 * вынесены в справку по кнопке «?» в шапке.
 */
@Composable
fun OverviewScreen(
    viewModel: GatewayViewModel,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
) {
    val context = LocalContext.current
    val db = remember { GatewayApplication.getInstance().database }
    val serviceRunning by viewModel.serviceRunning.collectAsState()
    val gatewayPort by viewModel.gatewayPort.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val pools by remember { QuotaRepository.observe(db) }.collectAsState(initial = emptyList())
    val ticker by rememberTicker(2_000L)

    // Расход и прогноз пересчитываем на тике — оба запроса тяжёлые, поэтому в IO.
    val usage by produceState<Pair<UsageHistory.Forecast, List<UsageHistory.DayUsage>>?>(initialValue = null, ticker / 15) {
        value = withContext(Dispatchers.IO) {
            UsageHistory.forecast(db) to UsageHistory.daily(db, days = 14)
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gateway.spacing.lg)
            .padding(bottom = Gateway.spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Gateway.spacing.md),
    ) {
        var showPortSheet by remember { mutableStateOf(false) }
        GatewayStatusCard(
            running = serviceRunning,
            port = gatewayPort,
            onToggle = { viewModel.toggleGateway() },
            onCopy = { label, value -> copyToClipboard(context, label, value) },
            onEditPort = { showPortSheet = true },
        )
        if (showPortSheet) {
            PortSheet(
                current = gatewayPort,
                onDismiss = { showPortSheet = false },
                onApply = { viewModel.setGatewayPort(it) },
            )
        }

        SectionHeader("Ресурсы провайдеров")
        QuotaStrip(
            pools = pools,
            providerTypes = providers.associate { it.id to it.type },
        )

        val forecast = usage?.first
        val days = usage?.second.orEmpty()
        MonthSpendCard(forecast = forecast, days = days)

        TrafficCard(ticker = ticker)

        LiveSessionsCard(viewModel = viewModel, running = serviceRunning, ticker = ticker)
    }

    // Сообщения ViewModel показываем снекбаром (раньше был Toast поверх всего).
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    LaunchedEffect(snackbarMessage) {
        val message = snackbarMessage ?: return@LaunchedEffect
        snackbarHostState?.showSnackbar(message)
        viewModel.clearSnackbar()
    }
}

@Composable
private fun GatewayStatusCard(
    running: Boolean,
    port: Int,
    onToggle: () -> Unit,
    onCopy: (String, String) -> Unit,
    onEditPort: () -> Unit,
) {
    val lanIp = remember { localIpAddress() }
    AppCard(tone = CardTone.Raised) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = if (running) "Шлюз работает" else "Шлюз остановлен",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Порт $port",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onEditPort) { Text("Изменить") }
                }
            }
            StatusChip(
                text = if (running) "Активен" else "Остановлен",
                tone = if (running) StatusTone.Success else StatusTone.Neutral,
                withDot = true,
            )
        }

        Spacer(Modifier.height(Gateway.spacing.md))
        AddressRow("localhost", "http://localhost:$port", onCopy)
        // Нет подходящего интерфейса — честная строка вместо чужого адреса.
        if (lanIp == null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Адрес в сети",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "нет сети",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            AddressRow("Адрес в сети", "http://$lanIp:$port", onCopy)
        }

        Spacer(Modifier.height(Gateway.spacing.lg))
        OutlinedButton(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (running) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            ),
            border = BorderStroke(
                width = 1.dp,
                color = if (running) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            ),
        ) {
            Icon(
                imageVector = if (running) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(Gateway.spacing.sm))
            Text(
                text = if (running) "Остановить шлюз" else "Запустить шлюз",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun AddressRow(label: String, value: String, onCopy: (String, String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCopy(label, value) }
            .padding(vertical = Gateway.spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(Gateway.spacing.sm))
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = "Скопировать",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Кольца квот по пулам — вместо тонкой полоски, спрятанной в подразделе. */
@Composable
private fun QuotaStrip(
    pools: List<QuotaRepository.PoolQuota>,
    providerTypes: Map<Long, String>,
) {
    if (pools.isEmpty()) {
        AppCard(tone = CardTone.Raised) {
            Text(
                text = "Пулы квот появятся после подключения провайдеров",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    // Все пулы, а не первые три: скрывать часть ресурсов на дашборде нельзя,
    // поэтому ряд горизонтально прокручивается.
    var notifyFor by remember { mutableStateOf<QuotaRepository.PoolQuota?>(null) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Gateway.spacing.md),
    ) {
        pools.forEach { pq ->
            ResourceTile(
                pq = pq,
                providerType = providerTypes[pq.pool.providerId].orEmpty(),
                onClick = { notifyFor = pq },
            )
        }
    }

    notifyFor?.let { pq ->
        ResourceNotifySheet(
            pool = pq.pool,
            kind = ResourcePoolKind.fromName(pq.pool.kind),
            onDismiss = { notifyFor = null },
        )
    }
}

/**
 * Плитка ресурса. Квота, баланс и бесплатный ресурс — три разные сущности,
 * поэтому и показываются по-разному: у квоты есть доля и сброс, у баланса —
 * только сумма на счету, у бесплатного ресурса нет ни того, ни другого.
 */
@Composable
private fun ResourceTile(
    pq: QuotaRepository.PoolQuota,
    providerType: String,
    onClick: () -> Unit,
) {
    val kind = ResourcePoolKind.fromName(pq.pool.kind)
    val snapshot = pq.snapshot
    val unit = snapshot?.unit ?: pq.pool.unit
    // Прогноз считаем от истории расхода: он даёт «хватит до» или «сгорит».
    val db = remember { GatewayApplication.getInstance().database }
    val outlook by produceState<QuotaBurn.Outlook?>(initialValue = null, pq.pool.id, snapshot?.updatedAt) {
        val remaining = snapshot?.remaining
        val resetsAt = snapshot?.resetsAt
        value = if (remaining == null || resetsAt == null) null else withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            QuotaBurn.rate(db.quotaSnapshotDao().getHistoryForPool(pq.pool.id), now)
                ?.let { QuotaBurn.outlook(remaining, resetsAt, it, now) }
        }
    }

    AppCard(
        tone = CardTone.Raised,
        onClick = onClick,
        // Одинаковая высота: плитки не «прыгают» от разного набора данных.
        modifier = Modifier.width(168.dp).height(190.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Шапка: тип несёт логотип, крупно — имя провайдера.
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderAvatar(name = pq.pool.name, type = providerType, size = 16.dp)
                Spacer(Modifier.width(Gateway.spacing.xs))
                Text(
                    text = pq.pool.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            when {
                kind == ResourcePoolKind.FREE -> Icon(
                    imageVector = Icons.Outlined.AllInclusive,
                    contentDescription = null,
                    tint = Gateway.colors.success,
                    modifier = Modifier.size(72.dp).padding(Gateway.spacing.md),
                )

                kind.hasFraction -> {
                    val used = snapshot?.used
                    val limit = snapshot?.limit
                    QuotaRing(
                        fractionUsed = if (used != null && limit != null && limit > 0) {
                            (used / limit).toFloat()
                        } else null,
                        pressure = pq.pressure,
                        size = 86.dp,
                    )
                }

                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // У баланса процентов нет: изначальное пополнение неизвестно.
                    Text(
                        text = snapshot?.remaining?.let { Fmt.quota(it, unit) } ?: "—",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (snapshot?.remaining != null) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    snapshot?.used?.let {
                        Text(
                            text = "потрачено ${Fmt.quota(it, unit)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Нижняя строка: что будет дальше — из расчёта, а не из порога.
            Text(
                text = tileFooter(kind, snapshot?.resetsAt, outlook),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Подпись под значением: «хватит до …», если квота кончится раньше сброса;
 * «сгорит …», если часть останется неиспользованной; иначе срок сброса.
 * У бесплатного ресурса и без данных — пустая строка, а не выдумка.
 */
private fun tileFooter(
    kind: ResourcePoolKind,
    resetsAt: Long?,
    outlook: QuotaBurn.Outlook?,
): String {
    if (kind == ResourcePoolKind.FREE) return "без лимита"
    outlook?.let { o ->
        o.exhaustAtMs?.let { return "хватит до ${Fmt.time(it)}" }
        if (o.surplus > 0.0) return "сгорит ${Math.round(o.surplus)}"
    }
    if (kind.hasReset && resetsAt != null) {
        val left = resetsAt - System.currentTimeMillis()
        if (left > 0) return "сброс через ${Fmt.duration(left)}"
    }
    return ""
}

/**
 * Расход месяца: накопительная линия по дням, пунктирный прогноз до конца
 * месяца. Такого графика не было — расход показывался двумя строками текста.
 */
@Composable
private fun MonthSpendCard(forecast: UsageHistory.Forecast?, days: List<UsageHistory.DayUsage>) {
    MetricTile(
        // Стоимость месяца — это подписки плюс расход по токенам.
        label = "Расход за месяц",
        value = Fmt.usd(forecast?.totalMonthToDateUsd ?: 0.0),
        unit = forecast?.let { "прогноз ${Fmt.usd(it.projectedTotalUsd)}" },
        below = {
            // Слагаемые показываем раздельно: подписка — фиксированная плата,
            // а расход по токенам считается по факту вызовов.
            forecast?.let { f ->
                if (f.subscriptionsUsd > 0.0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Тарифы",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = Fmt.usd(f.subscriptionsUsd),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Токены",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = Fmt.usd(f.monthToDateUsd),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            if (days.size >= 2) {
                var acc = 0.0
                val cumulative = days.map { day ->
                    acc += day.usd
                    ChartPoint(day.dayStartMs.toFloat(), acc.toFloat())
                }
                val projection = forecast?.let { f ->
                    val last = cumulative.last()
                    val daysLeft = (f.daysInMonth - f.daysElapsed).coerceAtLeast(0)
                    if (daysLeft == 0) null else listOf(
                        last,
                        ChartPoint(
                            last.x + daysLeft * 86_400_000f,
                            f.projectedMonthEndUsd.toFloat(),
                        ),
                    )
                }
                LineChart(
                    series = buildList {
                        add(LineSeries("Факт", cumulative, colorIndex = 0, filled = true))
                        projection?.let { add(LineSeries("Прогноз", it, projected = true)) }
                    },
                    height = 150.dp,
                    xLabelAt = { Fmt.day(it.toLong()) },
                    yLabelAt = { Fmt.usd(it.toDouble()) },
                )
            } else {
                Text(
                    text = "Данных о расходе пока нет",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (forecast?.isEstimate == true) {
                Text(
                    text = "день ${forecast.daysElapsed} из ${forecast.daysInMonth}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/** Сводка скорости: лучшая модель и здоровье парка моделей. */
@Composable
private fun TrafficCard(ticker: Long) {
    val up = remember(ticker) { GatewayForegroundService.totalUploadBytes.get() }
    val down = remember(ticker) { GatewayForegroundService.totalDownloadBytes.get() }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Gateway.spacing.md),
    ) {
        MetricTile(
            label = "Отправлено",
            value = Fmt.bytes(up),
            modifier = Modifier.weight(1f),
        )
        MetricTile(
            label = "Получено",
            value = Fmt.bytes(down),
            modifier = Modifier.weight(1f),
        )
    }
}

/** Живой поток запросов — обычный список вместо бегущей строки. */
@Composable
private fun LiveSessionsCard(
    viewModel: GatewayViewModel,
    running: Boolean,
    ticker: Long,
) {
    val sessions = remember(ticker) { viewModel.liveSessions }
    if (!running || sessions.isEmpty()) return

    SectionHeader("Запросы") {
        TextButton(onClick = { viewModel.clearLiveSessions() }) { Text("Очистить") }
    }
    AppCard(tone = CardTone.Raised) {
        Column(verticalArrangement = Arrangement.spacedBy(Gateway.spacing.md)) {
            sessions.take(8).forEach { session ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = Fmt.time(session.timestamp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(Gateway.spacing.sm))
                        Text(
                            text = session.modelName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (session.responsePreview.isBlank()) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                            )
                        }
                    }
                    val preview = listOfNotNull(
                        session.requestPreview.takeIf { it.isNotBlank() },
                        session.responsePreview.takeIf { it.isNotBlank() },
                    ).joinToString(" → ")
                    if (preview.isNotBlank()) {
                        Text(
                            text = preview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Смена порта шлюза. Раньше порт правился прямо в карточке и применялся на
 * КАЖДЫЙ введённый символ: набор «8889» последовательно ставил порты 8, 88, 888.
 * Здесь значение применяется один раз по кнопке и только если оно валидно.
 */
@Composable
private fun PortSheet(current: Int, onDismiss: () -> Unit, onApply: (Int) -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in 1..65535

    FormSheet(
        title = "Порт шлюза",
        onDismiss = onDismiss,
        confirmText = "Применить",
        confirmEnabled = valid && parsed != current,
        onConfirm = {
            parsed?.let(onApply)
            onDismiss()
        },
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.filter(Char::isDigit).take(5) },
            label = { Text("Порт") },
            singleLine = true,
            isError = text.isNotEmpty() && !valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text("По умолчанию 8889") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}
