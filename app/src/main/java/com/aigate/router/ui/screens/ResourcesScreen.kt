package com.aigate.router.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import com.aigate.router.data.model.ResourcePool
import com.aigate.router.notify.QuotaNotifier
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
