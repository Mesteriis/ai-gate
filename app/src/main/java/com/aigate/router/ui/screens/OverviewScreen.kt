package com.aigate.router.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AllInclusive
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aigate.router.GatewayApplication
import com.aigate.router.quota.QuotaRepository
import com.aigate.router.quota.QuotaBurn
import com.aigate.router.quota.QuotaWindow
import com.aigate.router.quota.QuotaWindows
import com.aigate.router.quota.ResourcePoolKind
import com.aigate.router.service.GatewayForegroundService
import com.aigate.router.ui.design.AppCard
import com.aigate.router.ui.design.CardTone
import com.aigate.router.ui.design.ChartCard
import com.aigate.router.ui.design.Fmt
import com.aigate.router.ui.design.animatedValue
import com.aigate.router.ui.design.appear
import com.aigate.router.ui.design.FormSheet
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.HelpSection
import com.aigate.router.ui.design.HelpSheet
import com.aigate.router.ui.design.MetricTile
import com.aigate.router.ui.design.parallax
import com.aigate.router.ui.design.ProviderAvatar
import com.aigate.router.ui.design.QrCodeImage
import com.aigate.router.ui.design.QuotaRing
import com.aigate.router.ui.design.QuotaRings
import com.aigate.router.ui.design.SectionHeader
import com.aigate.router.ui.design.StatusChip
import com.aigate.router.ui.design.StatusTone
import com.aigate.router.ui.design.charts.ChartMarker
import com.aigate.router.ui.design.charts.ChartPoint
import com.aigate.router.ui.design.charts.LineChart
import com.aigate.router.ui.design.charts.LineSeries
import com.aigate.router.ui.design.pressureTone
import com.aigate.router.ui.util.localIpAddress
import com.aigate.router.ui.util.rememberTicker
import com.aigate.router.ui.viewmodel.GatewayViewModel
import com.aigate.router.usage.UsageHistory
import kotlin.math.roundToLong
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

    // Скролл держим сами: витринная карточка уходит с параллаксом, а он
    // считается от текущего смещения списка.
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .verticalScroll(scroll)
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
            modifier = Modifier.parallax(scroll, fadeDistance = 320.dp),
        )
        if (showPortSheet) {
            PortSheet(
                current = gatewayPort,
                onDismiss = { showPortSheet = false },
                onApply = { viewModel.setGatewayPort(it) },
            )
        }

        // Поводы для реакции считаем теми же триггерами, что и уведомления.
        val attention by produceState(initialValue = emptyList<com.aigate.router.notify.QuotaTriggers.Alert>(), pools.size, ticker / 15) {
            value = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                pools.flatMap { pq ->
                    val kind = ResourcePoolKind.fromName(pq.pool.kind)
                    com.aigate.router.notify.QuotaTriggers.evaluate(
                        com.aigate.router.notify.QuotaTriggers.Input(
                            poolName = pq.pool.name,
                            kind = kind,
                            remaining = pq.snapshot?.remaining,
                            limit = pq.snapshot?.limit,
                            unit = pq.snapshot?.unit ?: pq.pool.unit,
                            resetsAt = pq.snapshot?.resetsAt,
                            rate = QuotaBurn.rate(db.quotaSnapshotDao().getHistoryForPool(pq.pool.id), now),
                            settings = com.aigate.router.notify.NotifyPrefs.load(pq.pool.id, kind),
                            now = now,
                            // На экране показываем состояние как есть, без учёта
                            // того, о чём уже уведомляли.
                            resetSeenAt = System.currentTimeMillis(),
                        )
                    )
                }
            }
        }
        // Дальше карточки входят волной: индекс задаёт задержку появления,
        // поэтому экран собирается по порядку чтения, а не мигает целиком.
        // Витрина в индексах не участвует — у неё параллакс.
        AttentionBlock(
            alerts = attention,
            gatewayStopped = !serviceRunning,
            blockedAttempts = GatewayForegroundService.blockedAttempts.get(),
            modifier = Modifier.appear(index = 1),
        )

        // NextRequestCard живёт в отдельном файле и модификатора не принимает,
        // поэтому волну входа даём обёрткой, а не правкой чужой карточки.
        Column(Modifier.appear(index = 2)) {
            NextRequestCard(viewModel = viewModel, ticker = ticker)
        }

        ConnectivityCheckCard(
            db = db,
            port = gatewayPort,
            modifier = Modifier.appear(index = 3),
        )

        // Плитки бесплатных ресурсов на обзор не выносим: у них нечего
        // показывать, кроме знака бесконечности, а обзор нужен для того, что
        // кончается. Пять одинаковых «без лимита» занимали половину сетки и
        // прятали ресурсы, за которыми действительно надо следить. Сами
        // ресурсы никуда не делись — они в разделе «Ресурсы».
        val limited = pools.filterNot { ResourcePoolKind.fromName(it.pool.kind) == ResourcePoolKind.FREE }

        val forecast = usage?.first
        val days = usage?.second.orEmpty()

        // История снимков нужна графику темпа: читаем её один раз на все пулы.
        val histories by produceState(initialValue = emptyMap<Long, List<com.aigate.router.data.model.QuotaSnapshot>>(), pools.size, ticker / 15) {
            value = withContext(Dispatchers.IO) {
                pools.associate { it.pool.id to db.quotaSnapshotDao().getHistoryForPool(it.pool.id) }
            }
        }

        // QuotaBurnCard рисует только ресурсы со сбросом и при их отсутствии не
        // рисует ничего. Проверяем это здесь: пустая обёртка с appear забрала бы
        // себе отступ в раскладке и оставила дырку между карточками.
        val hasBurnPools = pools.any { ResourcePoolKind.fromName(it.pool.kind).hasReset }

        // Общая мерка ширины для fold-раскладки: от неё зависят и число плиток
        // ресурсов в ряду, и двухколоночный блок графиков на развёрнутом Fold.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val tileColumns = (maxWidth / 170.dp).toInt().coerceIn(2, 4)
            val wide = maxWidth >= 640.dp
            Column(verticalArrangement = Arrangement.spacedBy(Gateway.spacing.md)) {
                if (pools.isEmpty() || limited.isNotEmpty()) {
                    SectionHeader(
                        title = "Ресурсы провайдеров",
                        modifier = Modifier.appear(index = 4),
                    )
                    QuotaStrip(
                        pools = limited,
                        providerTypes = providers.associate { it.id to it.type },
                        tilesPerRow = tileColumns,
                        appearFrom = 5,
                    )
                }

                MonthSpendCard(
                    forecast = forecast,
                    days = days,
                    modifier = Modifier.appear(index = 8),
                )

                if (wide) {
                    // На развёрнутом Fold графики стоят рядом: сравнение расхода
                    // по дням и темпа квоты не требует прокрутки. Колонки входят
                    // по очереди — каждая со своим индексом.
                    Row(horizontalArrangement = Arrangement.spacedBy(Gateway.spacing.md)) {
                        Column(
                            modifier = Modifier.weight(1f).appear(index = 9),
                            verticalArrangement = Arrangement.spacedBy(Gateway.spacing.md),
                        ) {
                            UsageByDayCard(days = days)
                        }
                        Column(
                            modifier = Modifier.weight(1f).appear(index = 10),
                            verticalArrangement = Arrangement.spacedBy(Gateway.spacing.md),
                        ) {
                            QuotaBurnCard(pools = pools, histories = histories)
                        }
                    }
                } else {
                    // Карточки графиков модификатора не принимают (соседний
                    // файл), поэтому волну входа даём обёртками.
                    Column(Modifier.appear(index = 9)) {
                        UsageByDayCard(days = days)
                    }
                    if (hasBurnPools) {
                        Column(
                            modifier = Modifier.appear(index = 10),
                            verticalArrangement = Arrangement.spacedBy(Gateway.spacing.md),
                        ) {
                            QuotaBurnCard(pools = pools, histories = histories)
                        }
                    }
                }
            }
        }

        // Экономия локальных моделей: показываем только когда есть по чему считать.
        val savings by produceState<com.aigate.router.usage.LocalSavings.Result?>(initialValue = null, ticker / 15) {
            value = withContext(Dispatchers.IO) { com.aigate.router.usage.LocalSavings.monthToDate(db) }
        }
        savings?.takeIf { it.referenceModel != null && it.savedUsd > 0.0 }?.let { s ->
            MetricTile(
                label = "Экономия на локальных моделях",
                value = Fmt.usd(s.savedUsd),
                unit = "за месяц",
                modifier = Modifier.appear(index = 11),
                below = {
                    Text(
                        text = "${Fmt.compact(s.localTokens)} токенов локально; оценка по цене " +
                            "самой дешёвой облачной модели (${s.referenceModel})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }

        TrafficCard(ticker = ticker, modifier = Modifier.appear(index = 12))

        LiveSessionsCard(
            viewModel = viewModel,
            running = serviceRunning,
            ticker = ticker,
            modifier = Modifier.appear(index = 13),
        )
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
    modifier: Modifier = Modifier,
) {
    val lanIp = remember { localIpAddress() }
    // Главный блок экрана — витрина: состояние шлюза читается первым, поэтому
    // тон Hero с градиентом врат, а не рядовая приподнятая карточка.
    AppCard(modifier = modifier, tone = CardTone.Hero) {
        Text(
            text = "Шлюз",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (running) "Работает" else "Остановлен",
                    style = MaterialTheme.typography.displayLarge,
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

            // QR избавляет от ручного ввода адреса на другом устройстве —
            // тем нужнее, что адрес меняется при смене сети.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                QrCodeImage(content = "http://$lanIp:$port", size = 132.dp)
            }
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

/**
 * Кольца квот по пулам — вместо тонкой полоски, спрятанной в подразделе.
 * [appearFrom] — индекс первой плитки в волне входа экрана.
 */
@Composable
private fun QuotaStrip(
    pools: List<QuotaRepository.PoolQuota>,
    providerTypes: Map<Long, String>,
    tilesPerRow: Int,
    appearFrom: Int = 0,
) {
    if (pools.isEmpty()) {
        AppCard(tone = CardTone.Raised, modifier = Modifier.appear(index = appearFrom)) {
            Text(
                text = "Пулы квот появятся после подключения провайдеров",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    // Сетка с переносом; число плиток в ряду выбирает экран по своей ширине:
    // на внешнем экране Fold — две, на развёрнутом внутреннем — четыре.
    var notifyFor by remember { mutableStateOf<QuotaRepository.PoolQuota?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(Gateway.spacing.md)) {
        pools.chunked(tilesPerRow).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Gateway.spacing.md),
            ) {
                row.forEachIndexed { columnIndex, pq ->
                    ResourceTile(
                        pq = pq,
                        providerType = providerTypes[pq.pool.providerId].orEmpty(),
                        onClick = { notifyFor = pq },
                        // Задержку наращиваем по позиции плитки, но не глубже
                        // двух шагов: у владельца с десятком пулов последняя
                        // плитка иначе ждала бы своей очереди полсекунды.
                        appearIndex = appearFrom +
                            (rowIndex * tilesPerRow + columnIndex).coerceAtMost(2),
                        modifier = Modifier.weight(1f),
                    )
                }
                // Пустые места в неполном ряду, чтобы плитки не растягивались.
                repeat(tilesPerRow - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
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
    appearIndex: Int = 0,
    modifier: Modifier = Modifier,
) {
    val kind = ResourcePoolKind.fromName(pq.pool.kind)
    val snapshot = pq.snapshot
    val unit = snapshot?.unit ?: pq.pool.unit
    // Окна лимита провайдера: у подписки Claude их два — сессия и неделя.
    val windows = remember(pq.pool.id, snapshot?.updatedAt) {
        if (kind.hasFraction) QuotaWindows.of(pq.pool.id) else emptyList()
    }
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
        // Ширину задаёт ряд, поэтому плитка её не навязывает. Высота выросла
        // под подпись типа ресурса — кольцу и подвалу места хватает.
        modifier = modifier.height(204.dp).appear(index = appearIndex),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Шапка: сверху мелко тип ресурса. Квота, баланс и бесплатный
            // ресурс живут по разным правилам, и плитка обязана сказать, что
            // именно перед тобой, — иначе кольцо и сумма читаются как одно и
            // то же. Ниже логотип провайдера и его имя.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = kind.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            }

            when {
                kind == ResourcePoolKind.FREE -> Icon(
                    imageVector = Icons.Outlined.AllInclusive,
                    contentDescription = null,
                    tint = Gateway.colors.success,
                    modifier = Modifier.size(72.dp).padding(Gateway.spacing.md),
                )

                // Два окна лимита сразу (сессия и неделя у подписки Claude):
                // одно кольцо не покажет, во что упрёшься первым.
                windows.size >= 2 -> {
                    val outer = windows[0]
                    val inner = windows[1]
                    QuotaRings(
                        outerFraction = (outer.percent / 100.0).toFloat(),
                        innerFraction = (inner.percent / 100.0).toFloat(),
                        outerPressure = windowPressure(outer.percent),
                        innerPressure = windowPressure(inner.percent),
                        // В центре — то окно, которое ограничит работу первым.
                        centerText = "${Math.round(maxOf(outer.percent, inner.percent))}%",
                        size = 82.dp,
                    )
                }

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
            // У двух окон сначала расшифровка колец, потом ближайший сброс.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (windows.size >= 2) {
                    Text(
                        text = windows.take(2).joinToString(" · ") {
                            "${it.label} ${Math.round(it.percent)}%"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = if (windows.size >= 2) windowsFooter(windows)
                    else tileFooter(kind, snapshot?.resetsAt, outlook),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Подпись для двух окон: когда сбрасывается ближайшее из них. */
private fun windowsFooter(windows: List<QuotaWindow>): String {
    val soonest = windows.mapNotNull { w -> w.resetsAt?.let { w to it } }
        .minByOrNull { it.second } ?: return ""
    val left = soonest.second - System.currentTimeMillis()
    if (left <= 0) return ""
    return "сброс ${soonest.first.label} через ${Fmt.duration(left)}"
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
 * месяца. Итог и прогноз читаются из шапки, маркер на последней точке отделяет
 * расход по токенам от итога с тарифами.
 *
 * Тон Plain, а не Hero: витрина на экране одна — состояние шлюза. Две карточки
 * с градиентом спорили бы за первый взгляд, и обе перестали бы быть главными.
 */
@Composable
private fun MonthSpendCard(
    forecast: UsageHistory.Forecast?,
    days: List<UsageHistory.DayUsage>,
    modifier: Modifier = Modifier,
) {
    ChartCard(
        // Стоимость месяца — это подписки плюс расход по токенам.
        eyebrow = "Расход за месяц",
        readMain = Fmt.usd(forecast?.totalMonthToDateUsd ?: 0.0),
        readSub = forecast?.let {
            "прогноз ${Fmt.usd(it.projectedTotalUsd)} · день ${it.daysElapsed} из ${it.daysInMonth}"
        },
        modifier = modifier,
        // Витрина экрана одна — состояние шлюза; расход месяца выглядит как
        // остальные карточки графиков, иначе на экране две «главные».
        tone = CardTone.Raised,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Gateway.spacing.sm)) {
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
                val last = cumulative.last()
                val projection = forecast?.let { f ->
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
                    // Целые доллары на тиках: «$12» читается быстрее «$12,38».
                    yLabelAt = { "$" + it.roundToLong() },
                    niceMax = true,
                    // Маркер сегодняшней точки подписан расходом по токенам:
                    // итог в шапке включает тарифы и с линией не совпадает.
                    markers = listOf(
                        ChartMarker(
                            x = last.x,
                            y = last.y,
                            colorIndex = 0,
                            label = "токены ${Fmt.usd(forecast?.monthToDateUsd ?: last.y.toDouble())}",
                        )
                    ),
                )
            } else {
                Text(
                    text = "Данных о расходе пока нет",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Трафик шлюза: суммарный объём крупно, разбор по направлениям — строками.
 * Раньше это были две отдельные плитки: рядом стояли два числа, а общий объём
 * приходилось складывать в уме.
 */
@Composable
private fun TrafficCard(ticker: Long, modifier: Modifier = Modifier) {
    val up = remember(ticker) { GatewayForegroundService.totalUploadBytes.get() }
    val down = remember(ticker) { GatewayForegroundService.totalDownloadBytes.get() }
    // Счётчики обновляются на тике: значения доезжают анимацией, иначе цифры
    // дёргаются рывком каждые две секунды.
    val upValue = animatedValue(up.toFloat(), key = "traffic-up").toLong()
    val downValue = animatedValue(down.toFloat(), key = "traffic-down").toLong()

    ChartCard(
        eyebrow = "Трафик",
        readMain = Fmt.bytes(upValue + downValue),
        // Счётчики переживают перезапуск: это итог за всё время, а не за сеанс.
        readSub = "за всё время",
        modifier = modifier,
        tone = CardTone.Raised,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Gateway.spacing.sm)) {
            TrafficRow(
                icon = Icons.Outlined.ArrowUpward,
                label = "Отправлено",
                value = Fmt.bytes(upValue),
            )
            TrafficRow(
                icon = Icons.Outlined.ArrowDownward,
                label = "Получено",
                value = Fmt.bytes(downValue),
            )
        }
    }
}

/** Направление трафика: стрелка вместо цветной подписи — читается без чтения. */
@Composable
private fun TrafficRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Gateway.spacing.sm))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Живой поток запросов. Шапка подводит итог потока, строки — сами запросы:
 * состояние каждого показано чипом тона, а не словом в общем ряду текста.
 */
@Composable
private fun LiveSessionsCard(
    viewModel: GatewayViewModel,
    running: Boolean,
    ticker: Long,
    modifier: Modifier = Modifier,
) {
    val sessions = remember(ticker) { viewModel.liveSessions }
    if (!running || sessions.isEmpty()) return

    val shown = sessions.take(8)
    ChartCard(
        eyebrow = "Запросы",
        readMain = "${sessions.size} " +
            Fmt.plural(sessions.size.toLong(), "запрос", "запроса", "запросов"),
        // Свежие сессии добавляются в начало списка, поэтому «последний» — первый.
        readSub = shown.firstOrNull()?.let { "последний в ${Fmt.time(it.timestamp)}" },
        modifier = modifier,
        tone = CardTone.Raised,
        headerAction = {
            TextButton(onClick = { viewModel.clearLiveSessions() }) { Text("Очистить") }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Gateway.spacing.md)) {
            shown.forEachIndexed { index, session ->
                // Ключ по сессии: новый запрос приходит с появлением, а уже
                // показанные строки не переигрывают анимацию при обновлении
                // статуса — иначе список мигал бы на каждом тике.
                key(session.id) {
                    Column(Modifier.appear(index = index)) {
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
                            Spacer(Modifier.width(Gateway.spacing.sm))
                            StatusChip(
                                text = session.status,
                                tone = sessionTone(session.status),
                            )
                            // Ответ ещё не пришёл — оставляем индикатор работы.
                            if (session.responsePreview.isBlank()) {
                                Spacer(Modifier.width(Gateway.spacing.sm))
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
}

/**
 * Тон состояния сессии. Готовый ответ — успех, всё промежуточное — работа в
 * процессе, поэтому информационный тон. Неизвестное слово красить успехом
 * нельзя: статус приходит строкой из сервиса.
 */
private fun sessionTone(status: String): StatusTone = when (status) {
    "Ответ" -> StatusTone.Success
    "Отправка", "Размышление" -> StatusTone.Info
    else -> StatusTone.Neutral
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
