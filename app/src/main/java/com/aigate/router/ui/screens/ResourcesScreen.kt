package com.aigate.router.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aigate.router.auth.CliProviderCatalog
import com.aigate.router.auth.CliProviderTemplate
import com.aigate.router.auth.CliSessionImporter
import com.aigate.router.auth.CliSessionManager
import com.aigate.router.data.model.ModelPricing
import com.aigate.router.data.model.ResourcePool
import com.aigate.router.notify.QuotaNotifier
import com.aigate.router.pricing.PricingTable
import com.aigate.router.quota.QuotaRepository
import com.aigate.router.quota.QuotaRefreshWorker
import com.aigate.router.quota.QuotaSource
import com.aigate.router.quota.ResourcePressure
import com.aigate.router.routing.RouteStrategy
import com.aigate.router.service.GatewayForegroundService
import com.aigate.router.ui.theme.Error
import com.aigate.router.ui.theme.Offline
import com.aigate.router.ui.theme.Online
import com.aigate.router.ui.theme.Warning
import com.aigate.router.usage.UsageHistory
import kotlinx.coroutines.launch

/**
 * Диспетчер ИИ-ресурсов —— квоты, бюджет, прогноз расхода, стратегия маршрутизации.
 * Честные инварианты: null-значения показываются как «нет данных», прогноз всегда
 * помечен как оценка, источник данных подписывается явно.
 */
@Composable
fun ResourcesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = com.aigate.router.GatewayApplication.getInstance().database
    val scope = rememberCoroutineScope()

    // Наблюдаемый список пулов с квотами.
    val quotas by QuotaRepository.observe(db).collectAsState(initial = emptyList())

    // Прогноз расхода за месяц (suspend, всегда estimate).
    var forecast by remember { mutableStateOf<UsageHistory.Forecast?>(null) }

    // Стратегия маршрутизации.
    var strategy by remember {
        mutableStateOf(
            RouteStrategy.fromName(
                GatewayForegroundService.getGatewayConfig(RouteStrategy.CONFIG_KEY, "AUTO")
            )
        )
    }

    // Уведомления о квотах.
    var notifyEnabled by remember { mutableStateOf(QuotaNotifier.isEnabled()) }
    var threshold by remember { mutableStateOf(QuotaNotifier.threshold()) }

    // Редактируемый пул (диалог бюджета).
    var editingPool by remember { mutableStateOf<ResourcePool?>(null) }

    // При открытии экрана: одноразовое обновление квот + расчёт прогноза.
    LaunchedEffect(Unit) {
        QuotaRefreshWorker.refreshNow(ctx)
    }
    LaunchedEffect(Unit) {
        forecast = UsageHistory.forecast(db)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ==================== Заголовок с кнопкой «назад» ====================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                }
                Text(
                    "Ресурсы и квоты",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                // ==================== Прогноз расхода ====================
                item {
                    ForecastCard(forecast = forecast)
                }

                // ==================== Пулы ресурсов ====================
                if (quotas.isEmpty()) {
                    item { EmptyPoolsCard() }
                } else {
                    item {
                        Text(
                            text = "Пулы ресурсов",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    items(quotas, key = { it.pool.id }) { pq ->
                        PoolCard(
                            pool = pq.pool,
                            snapshot = pq.snapshot,
                            pressure = pq.pressure,
                            onEditBudget = { editingPool = pq.pool }
                        )
                    }
                }

                // ==================== Настройки ====================
                item {
                    Text(
                        text = "Настройки",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                item {
                    StrategyCard(
                        selected = strategy,
                        onSelect = { s ->
                            strategy = s
                            GatewayForegroundService.saveGatewayConfig(RouteStrategy.CONFIG_KEY, s.name)
                        }
                    )
                }
                item {
                    NotificationsCard(
                        enabled = notifyEnabled,
                        threshold = threshold,
                        onEnabledChange = { on ->
                            notifyEnabled = on
                            QuotaNotifier.setEnabled(on)
                        },
                        onThresholdChange = { v ->
                            threshold = v
                            QuotaNotifier.setThreshold(v)
                        }
                    )
                }

                // ==================== Цены моделей ====================
                item {
                    ModelPricingCard(db = db, scope = scope)
                }

                // ==================== История расхода ====================
                item {
                    UsageHistoryCard(db = db)
                }

                // ==================== CLI-сессии (эксперимент) ====================
                item {
                    CliSessionsCard(db = db, scope = scope)
                }

                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }

    // ==================== Диалог бюджета ====================
    editingPool?.let { pool ->
        BudgetDialog(
            pool = pool,
            onDismiss = { editingPool = null },
            onSave = { limit, day ->
                val target = editingPool
                editingPool = null
                if (target != null) {
                    scope.launch {
                        db.resourcePoolDao().update(
                            target.copy(configuredLimit = limit, resetDayOfMonth = day)
                        )
                        // Пересчитать снимок квоты, чтобы остаток обновился.
                        QuotaRefreshWorker.refreshNow(ctx)
                    }
                }
            }
        )
    }
}

// ============================================================
// Карточка прогноза расхода (всегда estimate)
// ============================================================
@Composable
private fun ForecastCard(forecast: UsageHistory.Forecast?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "📈 Оценка расхода за месяц",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (forecast == null) {
                Text(
                    text = "Расчёт…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Потрачено:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = usd(forecast.monthToDateUsd),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Прогноз к концу месяца:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "~" + usd(forecast.projectedMonthEndUsd),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "День ${forecast.daysElapsed} из ${forecast.daysInMonth} · оценка по среднесуточному расходу",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

// ============================================================
// Карточка одного пула ресурсов
// ============================================================
@Composable
private fun PoolCard(
    pool: ResourcePool,
    snapshot: com.aigate.router.data.model.QuotaSnapshot?,
    pressure: ResourcePressure,
    onEditBudget: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Заголовок: имя пула + чип источника
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = pool.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                snapshot?.let {
                    Chip(
                        text = sourceLabel(it.source),
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Чип давления
            Row(verticalAlignment = Alignment.CenterVertically) {
                val pc = pressureColor(pressure)
                Chip(text = pressure.label, color = pc)
            }

            Spacer(modifier = Modifier.height(10.dp))

            val remaining = snapshot?.remaining
            val limit = snapshot?.limit
            val unit = snapshot?.unit ?: pool.unit
            if (remaining != null && limit != null) {
                // Есть остаток и лимит → прогресс-бар
                val progress = if (limit > 0) (remaining / limit).toFloat().coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Осталось ${quotaPair(remaining, limit, unit)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            } else {
                // Остаток неизвестен → честно сообщаем
                Text(
                    text = "Данные о квоте недоступны",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val used = snapshot?.used
                if (used != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Израсходовано: ${quotaValue(used, unit)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Время до сброса
            val resetsAt = snapshot?.resetsAt
            if (resetsAt != null) {
                val remainingMs = resetsAt - System.currentTimeMillis()
                if (remainingMs > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Сброс через ${humanDuration(remainingMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(6.dp))

            // Управление бюджетом
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (pool.configuredLimit != null)
                        "Бюджет: ${usd(pool.configuredLimit)}"
                    else "Бюджет не задан",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onEditBudget) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (pool.configuredLimit != null) "Изменить лимит" else "Задать лимит")
                }
            }
        }
    }
}

// ============================================================
// Пустое состояние
// ============================================================
@Composable
private fun EmptyPoolsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("💳", fontSize = 40.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Пул ресурсов появится после первого запроса через шлюз.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Расход считается локально, данные не покидают устройство.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

// ============================================================
// Карточка стратегии маршрутизации
// ============================================================
@Composable
private fun StrategyCard(
    selected: RouteStrategy,
    onSelect: (RouteStrategy) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "🧭 Стратегия маршрутизации",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Как выбирать модель для auto-запросов",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RouteStrategy.entries.forEach { s ->
                    FilterChip(
                        selected = selected == s,
                        onClick = { onSelect(s) },
                        label = { Text(strategyLabel(s)) }
                    )
                }
            }
        }
    }
}

// ============================================================
// Карточка уведомлений
// ============================================================
@Composable
private fun NotificationsCard(
    enabled: Boolean,
    threshold: Double,
    onEnabledChange: (Boolean) -> Unit,
    onThresholdChange: (Double) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "🔔 Уведомления о квотах",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Предупреждать, когда ресурс на исходе",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            if (enabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Порог остатка: ${"%.0f".format(threshold * 100)}%",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                Slider(
                    value = threshold.toFloat(),
                    onValueChange = { onThresholdChange(it.toDouble()) },
                    valueRange = 0.05f..0.5f
                )
                Text(
                    text = "Уведомить, когда остаток пула опустится ниже порога.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ============================================================
// Диалог редактирования бюджета пула
// ============================================================
@Composable
private fun BudgetDialog(
    pool: ResourcePool,
    onDismiss: () -> Unit,
    onSave: (limit: Double?, day: Int?) -> Unit
) {
    var limitText by remember {
        mutableStateOf(pool.configuredLimit?.let { "%.2f".format(it) } ?: "")
    }
    var dayText by remember {
        mutableStateOf((pool.resetDayOfMonth ?: 1).toString())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Месячный бюджет", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    text = pool.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = limitText,
                    onValueChange = { limitText = it },
                    label = { Text("Лимит, USD") },
                    placeholder = { Text("напр. 20.00") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = dayText,
                    onValueChange = { v -> v.toIntOrNull()?.let { if (it in 1..28) dayText = it.toString() } },
                    label = { Text("День сброса (1–28)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Оставьте лимит пустым, чтобы убрать бюджет — тогда показывается только израсходованное.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val limit = limitText.trim().replace(',', '.').toDoubleOrNull()
                val day = dayText.trim().toIntOrNull()?.coerceIn(1, 28)
                onSave(limit, day)
            }) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

// ============================================================
// Маленький цветной чип
// ============================================================
@Composable
private fun Chip(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

// ============================================================
// Вспомогательные функции
// ============================================================

/** Цвет чипа давления. @Composable, т.к. NORMAL берёт primary из темы. */
@Composable
private fun pressureColor(pressure: ResourcePressure): Color = when (pressure) {
    ResourcePressure.FREE -> Online
    ResourcePressure.NORMAL -> MaterialTheme.colorScheme.primary
    ResourcePressure.CONSERVE -> Warning
    ResourcePressure.CRITICAL -> Error
    ResourcePressure.UNKNOWN -> Offline
}

private fun sourceLabel(source: String): String = when (QuotaSource.fromName(source)) {
    QuotaSource.PROVIDER_API -> "данные провайдера"
    QuotaSource.LOCAL_USAGE -> "локальный расчёт"
    QuotaSource.USER_CONFIGURED -> "ваш бюджет"
    QuotaSource.ESTIMATED -> "оценка"
}

private fun strategyLabel(strategy: RouteStrategy): String = when (strategy) {
    RouteStrategy.AUTO -> "Авто"
    RouteStrategy.FAST -> "Быстро"
    RouteStrategy.CHEAP -> "Дёшево"
    RouteStrategy.QUALITY -> "Качество"
    RouteStrategy.OFFLINE -> "Локально"
    RouteStrategy.QUOTA -> "По квоте"
}

/** Форматирует USD как «$X.XX». */
private fun usd(value: Double): String = "$" + "%.2f".format(value)

/** Форматирует значение квоты с учётом единицы (USD → «$X.XX»). */
private fun quotaValue(value: Double, unit: String): String =
    if (unit.equals("USD", ignoreCase = true)) usd(value)
    else "%.2f".format(value) + " " + unit

/** Форматирует пару «остаток из лимита» с единицей один раз. */
private fun quotaPair(remaining: Double, limit: Double, unit: String): String {
    val isUsd = unit.equals("USD", ignoreCase = true)
    return if (isUsd) {
        usd(remaining) + " из " + usd(limit)
    } else {
        "%.2f".format(remaining) + " из " + "%.2f".format(limit) + " " + unit
    }
}

/** Humanized RU-длительность: «2 дн», «5 ч», «30 мин». */
private fun humanDuration(ms: Long): String {
    if (ms <= 0) return "0 мин"
    val totalMinutes = ms / 60_000
    val days = totalMinutes / (60 * 24)
    val hours = (totalMinutes % (60 * 24)) / 60
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> "$days дн"
        hours > 0 -> "$hours ч"
        else -> "$minutes мин"
    }
}

// ============================================================
// Карточка цен моделей (редактор пользовательских цен)
// ============================================================
@Composable
private fun ModelPricingCard(
    db: com.aigate.router.data.db.AppDatabase,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val pricesFlow = remember { db.modelPricingDao().observeAll() }
    val prices by pricesFlow.collectAsState(initial = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }
    val asOf = remember {
        java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale("ru"))
            .format(java.util.Date(PricingTable.BUNDLED_AS_OF))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "💲 Цены моделей",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Для оценки стоимости запросов. Встроенные цены помечены «встроено», ваши — «ваше».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))

            if (prices.isEmpty()) {
                Text(
                    text = "Цены ещё не загружены.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                prices.take(20).forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${row.providerType}/${row.modelId}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "вход \$${row.inputPer1M}/1M · выход \$${row.outputPer1M}/1M",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Chip(
                            text = if (row.source == "user") "ваше" else "встроено",
                            color = if (row.source == "user")
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (row.source == "user") {
                            IconButton(
                                onClick = { scope.launch { db.modelPricingDao().deleteById(row.id) } },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Удалить цену",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
                if (prices.size > 20) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "…и ещё ${prices.size - 20}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Встроенные цены на $asOf",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Добавить цену")
                }
            }
        }
    }

    if (showAddDialog) {
        var providerText by remember { mutableStateOf("") }
        var modelText by remember { mutableStateOf("") }
        var inputText by remember { mutableStateOf("") }
        var outputText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Новая цена", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = providerText,
                        onValueChange = { providerText = it },
                        label = { Text("Тип провайдера") },
                        placeholder = { Text("напр. openai") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = modelText,
                        onValueChange = { modelText = it },
                        label = { Text("Модель") },
                        placeholder = { Text("напр. gpt-4o") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        label = { Text("Цена входа за 1M, USD") },
                        placeholder = { Text("напр. 2.50") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = outputText,
                        onValueChange = { outputText = it },
                        label = { Text("Цена выхода за 1M, USD") },
                        placeholder = { Text("напр. 10.00") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Ваша цена приоритетнее встроенной для той же пары «тип · модель».",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val provider = providerText.trim()
                    val model = modelText.trim()
                    val input = inputText.trim().replace(',', '.').toDoubleOrNull()
                    val output = outputText.trim().replace(',', '.').toDoubleOrNull()
                    if (provider.isNotEmpty() && model.isNotEmpty() && input != null && output != null) {
                        showAddDialog = false
                        scope.launch {
                            db.modelPricingDao().upsert(
                                ModelPricing(
                                    providerType = provider,
                                    modelId = model,
                                    inputPer1M = input,
                                    outputPer1M = output,
                                    source = "user",
                                    cachedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Отмена") }
            }
        )
    }
}

// ============================================================
// Карточка истории расхода (14 дней, только чтение)
// ============================================================
@Composable
private fun UsageHistoryCard(db: com.aigate.router.data.db.AppDatabase) {
    var history by remember { mutableStateOf<List<UsageHistory.DayUsage>>(emptyList()) }
    LaunchedEffect(Unit) {
        history = UsageHistory.daily(db, 14)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "📊 История расхода (14 дней)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))

            val active = history.filter { it.calls > 0 }
            if (active.isEmpty()) {
                Text(
                    text = "Пока нет запросов через шлюз.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val maxUsd = active.maxOf { it.usd }
                val useTokens = maxUsd <= 0.0
                val maxTokens = active.maxOf { it.promptTokens + it.completionTokens }.coerceAtLeast(1L)
                val fmt = remember { java.text.SimpleDateFormat("dd.MM", java.util.Locale("ru")) }
                active.forEach { day ->
                    val totalTokens = day.promptTokens + day.completionTokens
                    val fraction = (if (useTokens)
                        totalTokens.toFloat() / maxTokens.toFloat()
                    else
                        (day.usd / maxUsd).toFloat()
                    ).coerceIn(0.05f, 1f)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = fmt.format(java.util.Date(day.dayStartMs)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(40.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(10.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .height(10.dp),
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(4.dp)
                            ) {}
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${day.calls} зпр · $totalTokens ток · \$${"%.2f".format(day.usd)}" +
                                if (day.hasUnpriced) " ≈" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (active.any { it.hasUnpriced }) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "≈ — часть запросов без цены, стоимость занижена.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

// ============================================================
// Карточка CLI-сессий (эксперимент): подключение провайдеров через
// сохранённую сессию их CLI (Codex / Gemini / Claude) — как в omniroute.
// Сессия хранится в Keystore и обновляется автоматически.
// ============================================================
@Composable
private fun CliSessionsCard(
    db: com.aigate.router.data.db.AppDatabase,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var sessions by remember { mutableStateOf<List<CliSessionManager.SessionStatus>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    var showConnectDialog by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        sessions = CliSessionManager.listSessions(db)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "🔐 CLI-сессии (эксперим.)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Подключение провайдеров через сохранённую сессию их CLI " +
                    "(Codex, Gemini, Claude) — как в omniroute. Сессия хранится в Keystore " +
                    "и обновляется автоматически.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Использование приватных эндпоинтов провайдера сторонним клиентом " +
                    "может нарушать их Terms — на ваш риск.",
                style = MaterialTheme.typography.labelSmall,
                color = Warning
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (sessions.isEmpty()) {
                Text(
                    text = "Нет подключённых CLI-сессий.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                sessions.forEach { s ->
                    CliSessionRow(
                        status = s,
                        onRefresh = {
                            scope.launch {
                                CliSessionManager.refreshNow(db, s.provider.id)
                                reload++
                            }
                        },
                        onDisconnect = {
                            scope.launch {
                                CliSessionManager.disconnect(db, s.provider.id)
                                reload++
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(onClick = { showConnectDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Подключить CLI-сессию")
            }
        }
    }

    if (showConnectDialog) {
        ConnectCliSessionDialog(
            db = db,
            scope = scope,
            onDismiss = { showConnectDialog = false },
            onConnected = {
                showConnectDialog = false
                reload++
            }
        )
    }
}

// ============================================================
// Одна подключённая CLI-сессия
// ============================================================
@Composable
private fun CliSessionRow(
    status: CliSessionManager.SessionStatus,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = status.provider.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = status.provider.type,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (status.connected) {
                    Chip(text = "● Подключён", color = Online)
                } else {
                    Chip(text = "○ Нет токена", color = Offline)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Аккаунт: ${status.accountId ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(2.dp))
            val expiresAt = status.expiresAt
            if (expiresAt == null) {
                Text(
                    text = "Срок: без указания",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val remainingMs = expiresAt - System.currentTimeMillis()
                if (remainingMs <= 0) {
                    Text(
                        text = "Истекла",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = Error
                    )
                } else {
                    Text(
                        text = "Истекает через ${humanDuration(remainingMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Авто-обновление: ${if (status.hasRefresh) "да" else "нет"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onRefresh) { Text("Обновить") }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onDisconnect) {
                    Text("Отключить", color = Error)
                }
            }
        }
    }
}

// ============================================================
// Диалог подключения CLI-сессии
// ============================================================
@Composable
private fun ConnectCliSessionDialog(
    db: com.aigate.router.data.db.AppDatabase,
    scope: kotlinx.coroutines.CoroutineScope,
    onDismiss: () -> Unit,
    onConnected: () -> Unit
) {
    var template by remember { mutableStateOf(CliProviderCatalog.all().first()) }
    var name by remember { mutableStateOf(template.displayName) }
    var baseUrl by remember { mutableStateOf(template.defaultBaseUrl) }
    var sessionJson by remember { mutableStateOf("") }
    var tokenUrl by remember { mutableStateOf(template.tokenUrl ?: "") }
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Подключить CLI-сессию", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // Выбор шаблона провайдера.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CliProviderCatalog.all().forEach { t ->
                        FilterChip(
                            selected = template.id == t.id,
                            onClick = {
                                val prev = template
                                template = t
                                if (name.isBlank() || name == prev.displayName) name = t.displayName
                                if (baseUrl.isBlank() || baseUrl == prev.defaultBaseUrl) baseUrl = t.defaultBaseUrl
                                if (tokenUrl.isBlank() || tokenUrl == (prev.tokenUrl ?: "")) tokenUrl = t.tokenUrl ?: ""
                            },
                            label = { Text(t.displayName) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (template.experimental) "эксперим. · ${template.note}" else template.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = sessionJson,
                    onValueChange = { sessionJson = it },
                    label = { Text("Сессия (JSON из файла CLI)") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Вставьте содержимое auth.json / oauth_creds.json вашего CLI.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Автообновление (необязательно)",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = tokenUrl,
                    onValueChange = { tokenUrl = it },
                    label = { Text("OAuth token URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = clientId,
                    onValueChange = { clientId = it },
                    label = { Text("client_id") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = clientSecret,
                    onValueChange = { clientSecret = it },
                    label = { Text("client_secret") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                val err = error
                if (err != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = Error
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val s = if (baseUrl.isBlank()) null else CliSessionImporter.parse(sessionJson)
                when {
                    baseUrl.isBlank() -> error = "Укажите Base URL"
                    s == null -> error = "Не удалось разобрать сессию (нужен access_token)"
                    else -> {
                        error = null
                        scope.launch {
                            CliSessionManager.connect(
                                db = db,
                                providerType = template.id,
                                name = name.ifBlank { template.displayName },
                                baseUrl = baseUrl.trim(),
                                session = s,
                                refreshTokenUrl = tokenUrl.ifBlank { null },
                                clientId = clientId.ifBlank { null },
                                clientSecret = clientSecret.ifBlank { null }
                            )
                        }
                        onConnected()
                    }
                }
            }) { Text("Подключить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}
