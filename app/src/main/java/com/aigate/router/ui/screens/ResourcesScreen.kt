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
import androidx.compose.material.icons.outlined.AccountBalanceWallet
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
import com.aigate.router.quota.ResourcePoolKind
import com.aigate.router.ui.design.Gateway
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
 * Диспетчер ресурсов: квоты подписок, балансы, свои бюджеты, прогноз расхода и стратегия маршрутизации.
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
    // Пул, у которого правим цену тарифа.
    var editingPlanPool by remember { mutableStateOf<ResourcePool?>(null) }

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
                    "Ресурсы и лимиты",
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
                            onEditBudget = { editingPool = pq.pool },
                            onEditPlanPrice = { editingPlanPool = pq.pool }
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
    editingPlanPool?.let { pool ->
        PlanPriceDialog(
            pool = pool,
            onDismiss = { editingPlanPool = null },
            onSave = { price ->
                com.aigate.router.auth.CodexAccount.setMonthlyPriceUsd(pool.providerId, price)
                editingPlanPool = null
                scope.launch { forecast = UsageHistory.forecast(db) }
            }
        )
    }

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
                text = "Оценка расхода за месяц",
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
                // Тарифы — фиксированная плата, она известна точно и не прогнозируется.
                if (forecast.subscriptionsUsd > 0.0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Тарифы:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = usd(forecast.subscriptionsUsd),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (forecast.subscriptionsUsd > 0.0) "Токены:" else "Потрачено:",
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
                        text = "~" + usd(forecast.projectedTotalUsd),
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
    onEditBudget: () -> Unit,
    onEditPlanPrice: () -> Unit
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

            // Тип ресурса и давление. Тип обязателен: квота, баланс и бесплатный
            // ресурс ведут себя по-разному, и путать их нельзя.
            val kind = ResourcePoolKind.fromName(pool.kind)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Chip(text = kind.label, color = MaterialTheme.colorScheme.primary)
                if (kind != ResourcePoolKind.FREE) {
                    Chip(text = pressure.label, color = pressureColor(pressure))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val remaining = snapshot?.remaining
            val limit = snapshot?.limit
            val unit = snapshot?.unit ?: pool.unit
            when {
                // Бесплатный ресурс: ни остатка, ни лимита — и это не «нет данных».
                kind == ResourcePoolKind.FREE -> Text(
                    text = "Без лимита: локальные модели",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Квота и свой бюджет: доля остатка осмысленна → полоса прогресса.
                kind.hasFraction && remaining != null && limit != null -> {
                    val progress = if (limit > 0) (remaining / limit).toFloat().coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = pressureColor(pressure),
                        trackColor = Gateway.colors.surfaceContainerHigh
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${kind.remainingLabel} ${quotaPair(remaining, limit, unit)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Баланс: показываем сумму на счету, без процентов —
                // изначальное пополнение провайдер не сообщает.
                kind == ResourcePoolKind.BALANCE && remaining != null -> Text(
                    text = "${kind.remainingLabel}: ${quotaValue(remaining, unit)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )

                else -> {
                    Text(
                        text = "Провайдер не отдаёт остаток",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    snapshot?.used?.let { used ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Израсходовано: ${quotaValue(used, unit)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Сброс бывает только у периодической квоты.
            if (kind.hasReset) {
                snapshot?.resetsAt?.let { resetsAt ->
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
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(6.dp))

            // Тариф подписки: план приходит из токена сессии, цена —
            // прейскурантная, пока пользователь не задал свою.
            val plan = com.aigate.router.auth.CodexAccount.planLabel(
                com.aigate.router.auth.CodexAccount.storedPlan(pool.providerId)
            )
            val subPrice = com.aigate.router.auth.CodexAccount.monthlyPriceUsd(pool.providerId)
            if (plan != null || subPrice != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Тариф" + (plan?.let { ": $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onEditPlanPrice) {
                        Text(
                            text = subPrice?.let { "${usd(it)} / мес" } ?: "указать цену",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (subPrice != null &&
                    !com.aigate.router.auth.CodexAccount.isPriceUserDefined(pool.providerId)
                ) {
                    Text(
                        text = "цена по прейскуранту, можно изменить",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

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
            Icon(
                Icons.Outlined.AccountBalanceWallet,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
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
                text = "Стратегия маршрутизации",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
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
                        text = "Уведомления об исчерпании",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
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
/**
 * Цена тарифа за месяц. Значение по умолчанию — прейскурант для плана из
 * токена; пустое поле означает «не учитывать тариф в расходах».
 */
@Composable
private fun PlanPriceDialog(
    pool: ResourcePool,
    onDismiss: () -> Unit,
    onSave: (Double?) -> Unit
) {
    val plan = com.aigate.router.auth.CodexAccount.planLabel(
        com.aigate.router.auth.CodexAccount.storedPlan(pool.providerId)
    )
    var text by remember {
        mutableStateOf(
            com.aigate.router.auth.CodexAccount.monthlyPriceUsd(pool.providerId)
                ?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
                ?: ""
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(plan?.let { "Тариф $it" } ?: "Цена тарифа") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter { it.isDigit() || it == '.' } },
                label = { Text("Цена в месяц, USD") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.toDoubleOrNull()) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

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
    else trimNumber(value) + unitSuffix(unit)

/** Форматирует пару «остаток из лимита» с единицей один раз. */
private fun quotaPair(remaining: Double, limit: Double, unit: String): String {
    val isUsd = unit.equals("USD", ignoreCase = true)
    return if (isUsd) {
        usd(remaining) + " из " + usd(limit)
    } else {
        trimNumber(remaining) + " из " + trimNumber(limit) + unitSuffix(unit)
    }
}

/**
 * Человеческая подпись единицы вместо машинного имени: «PERCENT» в интерфейсе
 * читается как ошибка. Процент прижимается к числу, слова — через пробел.
 */
private fun unitSuffix(unit: String): String = when (unit.uppercase()) {
    "PERCENT" -> "%"
    "USD" -> " $"
    "TOKENS" -> " токенов"
    "REQUESTS" -> " запросов"
    "CREDITS" -> " кредитов"
    "COMPUTE_MINUTES" -> " мин"
    "UNKNOWN", "" -> ""
    else -> " " + unit.lowercase()
}

/** Без дробной части, если она нулевая: «3», а не «3,00». */
private fun trimNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(value)

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
                text = "Цены моделей",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
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
                text = "История расхода за 14 дней",
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
    val ctx = LocalContext.current
    var sessions by remember { mutableStateOf<List<CliSessionManager.SessionStatus>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    var showConnectDialog by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reload) {
        sessions = CliSessionManager.listSessions(db)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "CLI-сессии",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
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
            Spacer(modifier = Modifier.height(10.dp))

            // Ключевая платформа — Codex в один тап.
            Button(
                onClick = {
                    error = null
                    connecting = true
                    scope.launch {
                        val res = CliSessionManager.connectCodex(ctx, db)
                        connecting = false
                        res.onSuccess { reload++ }.onFailure { error = it.message ?: "Не удалось подключить Codex" }
                    }
                },
                enabled = !connecting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (connecting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Ожидание входа в браузере…")
                } else {
                    Text("Подключить Codex")
                }
            }
            error?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Error)
            }
            Spacer(modifier = Modifier.height(2.dp))
            TextButton(onClick = { showConnectDialog = true }, enabled = !connecting) {
                Text("Другой провайдер…")
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
                    Chip(text = "Подключён", color = Online)
                } else {
                    Chip(text = "Нет токена", color = Offline)
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
    val ctx = LocalContext.current
    var template by remember { mutableStateOf(CliProviderCatalog.all().first()) }
    var name by remember { mutableStateOf(template.displayName) }
    var baseUrl by remember { mutableStateOf(template.defaultBaseUrl) }
    var authUrl by remember { mutableStateOf(template.authUrl ?: "") }
    var tokenUrl by remember { mutableStateOf(template.tokenUrl ?: "") }
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var sessionJson by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun startBrowserLogin() {
        when {
            baseUrl.isBlank() -> { error = "Укажите Base URL"; return }
            authUrl.isBlank() || tokenUrl.isBlank() || clientId.isBlank() ->
                { error = "Нужны authorization URL, token URL и client_id"; return }
        }
        error = null
        connecting = true
        scope.launch {
            val res = com.aigate.router.auth.OAuthBrowserFlow.authorize(
                ctx,
                com.aigate.router.auth.OAuthFlowConfig(
                    providerType = template.id,
                    authUrl = authUrl.trim(),
                    tokenUrl = tokenUrl.trim(),
                    clientId = clientId.trim(),
                    clientSecret = clientSecret.ifBlank { null },
                    scopes = template.scopes
                )
            )
            connecting = false
            res.onSuccess { s ->
                CliSessionManager.connect(
                    db = db, providerType = template.id,
                    name = name.ifBlank { template.displayName },
                    baseUrl = baseUrl.trim(), session = s,
                    refreshTokenUrl = tokenUrl.ifBlank { null },
                    clientId = clientId.ifBlank { null },
                    clientSecret = clientSecret.ifBlank { null }
                )
                onConnected()
            }.onFailure { error = it.message ?: "Ошибка входа" }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!connecting) onDismiss() },
        title = { Text("Подключить провайдера", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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
                                if (authUrl.isBlank() || authUrl == (prev.authUrl ?: "")) authUrl = t.authUrl ?: ""
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
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL") }, placeholder = { Text("https://…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(authUrl, { authUrl = it }, label = { Text("Authorization URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(tokenUrl, { tokenUrl = it }, label = { Text("Token URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(clientId, { clientId = it }, label = { Text("client_id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(clientSecret, { clientSecret = it }, label = { Text("client_secret (если нужен)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Откроется браузер для входа; после авторизации токены сохранятся автоматически (loopback-редирект). Разрешите redirect на http://localhost в OAuth-клиенте.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Fallback: ручная вставка сессии.
                Spacer(modifier = Modifier.height(10.dp))
                TextButton(onClick = { showManual = !showManual }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (showManual) "Скрыть ручной импорт" else "Вставить сессию вручную")
                }
                if (showManual) {
                    OutlinedTextField(sessionJson, { sessionJson = it }, label = { Text("Сессия (JSON из файла CLI)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    Text("auth.json / oauth_creds.json вашего CLI.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (connecting) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Ожидание входа в браузере…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Error)
                }
            }
        },
        confirmButton = {
            if (showManual) {
                Button(enabled = !connecting, onClick = {
                    val s = if (baseUrl.isBlank()) null else CliSessionImporter.parse(sessionJson)
                    when {
                        baseUrl.isBlank() -> error = "Укажите Base URL"
                        s == null -> error = "Не удалось разобрать сессию (нужен access_token)"
                        else -> {
                            error = null
                            scope.launch {
                                CliSessionManager.connect(
                                    db, template.id, name.ifBlank { template.displayName }, baseUrl.trim(), s,
                                    tokenUrl.ifBlank { null }, clientId.ifBlank { null }, clientSecret.ifBlank { null }
                                )
                            }
                            onConnected()
                        }
                    }
                }) { Text("Импортировать") }
            } else {
                Button(enabled = !connecting, onClick = { startBrowserLogin() }) {
                    Text("Войти через браузер")
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !connecting, onClick = onDismiss) { Text("Отмена") }
        }
    )
}
