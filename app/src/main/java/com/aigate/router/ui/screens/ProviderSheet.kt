package com.aigate.router.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aigate.router.GatewayApplication
import com.aigate.router.auth.CliSessionManager
import com.aigate.router.auth.CodexAccount
import com.aigate.router.data.model.Provider
import com.aigate.router.data.model.ResourcePool
import com.aigate.router.quota.QuotaBurn
import com.aigate.router.quota.QuotaRepository
import com.aigate.router.quota.QuotaWindows
import com.aigate.router.quota.ResourcePoolKind
import com.aigate.router.quota.ResourcePressure
import com.aigate.router.ui.design.ConfirmDialog
import com.aigate.router.ui.design.Fmt
import com.aigate.router.ui.design.FormSheet
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.ProviderAvatar
import com.aigate.router.ui.design.QuotaBar
import com.aigate.router.ui.design.SectionHeader
import com.aigate.router.ui.design.StatusChip
import com.aigate.router.ui.design.StatusTone
import com.aigate.router.ui.design.pressureTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Всё об одном провайдере в одном месте: его ресурс, тариф, бюджет,
 * уведомления и состояние сессии. Раньше это лежало на отдельном табе
 * «Лимиты», где ресурсы всех провайдеров были свалены в один список.
 */
@Composable
fun ProviderSheet(
    provider: Provider,
    modelCount: Int,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSyncModels: () -> Unit,
) {
    val db = remember { GatewayApplication.getInstance().database }
    val scope = rememberCoroutineScope()
    val pools by remember { QuotaRepository.observe(db) }.collectAsState(initial = emptyList())
    val pq = pools.firstOrNull { it.pool.providerId == provider.id }
    val kind = pq?.let { ResourcePoolKind.fromName(it.pool.kind) }

    // Вход учётной записью или ключ API — от этого зависят и блок сессии,
    // и то, что вообще можно изменить.
    val isOAuth by produceState(initialValue = false, provider.id) {
        value = withContext(Dispatchers.IO) {
            db.credentialDao().getByProvider(provider.id)?.type ==
                com.aigate.router.data.model.Credential.TYPE_OAUTH
        }
    }

    var showNotify by remember { mutableStateOf(false) }
    var showBudget by remember { mutableStateOf(false) }
    var showPlanPrice by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (showNotify && pq != null && kind != null) {
        ResourceNotifySheet(pool = pq.pool, kind = kind, onDismiss = { showNotify = false })
        return
    }
    if (showBudget && pq != null) {
        BudgetSheet(pool = pq.pool, onDismiss = { showBudget = false })
        return
    }
    if (showPlanPrice) {
        PlanPriceSheet(provider = provider, onDismiss = { showPlanPrice = false })
        return
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Удалить провайдера?",
            message = "«${provider.name}» и его модели будут удалены. Историю расхода это не затронет.",
            confirmText = "Удалить",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }

    FormSheet(title = provider.name, onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderAvatar(name = provider.name, type = provider.type, size = 40.dp)
            Spacer(Modifier.width(Gateway.spacing.md))
            Column {
                Text(
                    text = provider.resolvedBaseUrl,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "$modelCount ${Fmt.plural(modelCount.toLong(), "модель", "модели", "моделей")}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (pq != null && kind != null) {
            SectionHeader("Ресурс")
            ResourceSummary(pq = pq, kind = kind, providerId = provider.id)
        }

        SectionHeader("Настройки ресурса")
        SheetActionRow("Тариф и его цена", planPriceSummary(provider.id)) { showPlanPrice = true }
        if (pq != null) {
            SheetActionRow(
                title = "Бюджет",
                value = pq.pool.configuredLimit?.let { Fmt.usd(it) } ?: "не задан",
            ) { showBudget = true }
        }
        if (pq != null && kind != ResourcePoolKind.FREE) {
            SheetActionRow("Уведомления", "пороги и темп") { showNotify = true }
        }

        // Провайдер с OAuth-сессией: срок действия и повторный вход. Проверяем
        // сам credential, а не тип провайдера: сессий уже больше одной (Codex,
        // Claude Code), и перечислять типы значило бы забыть следующий.
        if (isOAuth) {
            SectionHeader("Сессия")
            SessionRow(provider = provider)
            TextButton(onClick = {
                scope.launch { withContext(Dispatchers.IO) { CliSessionManager.refreshNow(db, provider.id) } }
            }) { Text("Обновить сессию") }
        }

        SectionHeader("Действия")
        SheetActionRow("Синхронизировать модели", "запросить список у провайдера", onClick = onSyncModels)
        SheetActionRow(
            title = if (isOAuth) "Переименовать" else "Изменить",
            value = if (isOAuth) "имя провайдера" else "имя и ключ",
            onClick = onEdit,
        )
        SheetActionRow("Удалить провайдера", "", destructive = true) { confirmDelete = true }
    }
}

@Composable
private fun ResourceSummary(pq: QuotaRepository.PoolQuota, kind: ResourcePoolKind, providerId: Long) {
    val db = remember { GatewayApplication.getInstance().database }
    val snapshot = pq.snapshot
    val unit = snapshot?.unit ?: pq.pool.unit

    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusChip(
            text = kind.label,
            tone = if (kind == ResourcePoolKind.FREE) StatusTone.Success else pressureTone(pq.pressure),
        )
        Spacer(Modifier.width(Gateway.spacing.sm))
        if (kind != ResourcePoolKind.FREE) {
            Text(pq.pressure.label, style = MaterialTheme.typography.bodyMedium)
        }
    }

    // Окна лимита: у подписки Claude их два — сессия на 5 часов и неделя.
    // Показываем каждое своей полосой: сбрасываются они в разное время.
    val windows = remember(pq.pool.id, snapshot?.updatedAt) {
        if (kind.hasFraction) QuotaWindows.of(pq.pool.id) else emptyList()
    }

    when {
        kind == ResourcePoolKind.FREE -> Text(
            text = "Без лимита: локальные модели",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        windows.size >= 2 -> windows.forEach { w ->
            QuotaBar(
                fractionUsed = (w.percent / 100.0).toFloat(),
                pressure = windowPressure(w.percent),
            )
            Text(
                text = listOfNotNull(
                    "${w.label}: израсходовано ${Math.round(w.percent)}%",
                    w.resetsAt?.let { at ->
                        val left = at - System.currentTimeMillis()
                        if (left > 0) "сброс через ${Fmt.duration(left)}" else "сброс сейчас"
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }

        kind.hasFraction && snapshot?.remaining != null && snapshot.limit != null -> {
            QuotaBar(
                fractionUsed = (1.0 - snapshot.remaining / snapshot.limit).toFloat(),
                pressure = pq.pressure,
            )
            Text(
                text = "${kind.remainingLabel} ${Fmt.quota(snapshot.remaining, unit)} " +
                    "из ${Fmt.quota(snapshot.limit, unit)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }

        snapshot?.remaining != null -> Text(
            text = "${kind.remainingLabel}: ${Fmt.quota(snapshot.remaining, unit)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )

        else -> Text(
            text = "Провайдер не отдаёт остаток",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // Прогноз: когда кончится или сколько сгорит — из истории снимков.
    val outlook by produceState<QuotaBurn.Outlook?>(initialValue = null, pq.pool.id) {
        val remaining = snapshot?.remaining
        val resetsAt = snapshot?.resetsAt
        value = if (remaining == null || resetsAt == null) null else withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            QuotaBurn.rate(db.quotaSnapshotDao().getHistoryForPool(pq.pool.id), now)
                ?.let { QuotaBurn.outlook(remaining, resetsAt, it, now) }
        }
    }
    outlook?.let { o ->
        val text = when {
            o.exhaustAtMs != null -> "хватит до ${Fmt.time(o.exhaustAtMs!!)}"
            o.surplus > 0.0 -> "сгорит ${Math.round(o.surplus)}, если темп не изменится"
            else -> null
        }
        text?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    // Сброс: у нескольких окон он уже указан в строке каждого окна.
    if (kind.hasReset && windows.size < 2) {
        snapshot?.resetsAt?.let { resetsAt ->
            val left = resetsAt - System.currentTimeMillis()
            if (left > 0) {
                Text(
                    text = "сброс через ${Fmt.duration(left)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Давление по одному окну лимита: только уровень израсходованного. Темп сюда не
 * входит — своей истории снимков у отдельного окна нет.
 */
internal fun windowPressure(percent: Double): ResourcePressure = when {
    percent >= 90.0 -> ResourcePressure.CRITICAL
    percent >= 70.0 -> ResourcePressure.CONSERVE
    percent >= 30.0 -> ResourcePressure.NORMAL
    else -> ResourcePressure.FREE
}

@Composable
private fun SessionRow(provider: Provider) {
    val db = remember { GatewayApplication.getInstance().database }
    val expiry by produceState<Long?>(initialValue = null, provider.id) {
        value = withContext(Dispatchers.IO) {
            db.credentialDao().getByProvider(provider.id)?.oauthExpiresAt
        }
    }
    val plan = CodexAccount.planLabel(CodexAccount.storedPlan(provider.id))
    Text(
        text = listOfNotNull(
            plan?.let { "тариф $it" },
            expiry?.let { exp ->
                val left = exp - System.currentTimeMillis()
                if (left > 0) "действует ещё ${Fmt.duration(left)}" else "истекла, нужен повторный вход"
            } ?: "срок действия неизвестен",
        ).joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SheetActionRow(
    title: String,
    value: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (destructive) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
            if (value.isNotBlank()) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onClick) { Text(if (destructive) "Удалить" else "Открыть") }
    }
}

/** Цена тарифа за месяц: прейскурант предлагается, значение владельца важнее. */
@Composable
private fun PlanPriceSheet(provider: Provider, onDismiss: () -> Unit) {
    val plan = CodexAccount.planLabel(CodexAccount.storedPlan(provider.id))
    var text by remember {
        mutableStateOf(
            CodexAccount.monthlyPriceUsd(provider.id)
                ?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
                ?: ""
        )
    }
    FormSheet(
        title = plan?.let { "Тариф $it" } ?: "Цена тарифа",
        onDismiss = onDismiss,
        confirmText = "Сохранить",
        onConfirm = {
            CodexAccount.setMonthlyPriceUsd(provider.id, text.toDoubleOrNull())
            onDismiss()
        },
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { v -> text = v.filter { it.isDigit() || it == '.' } },
            label = { Text("Цена в месяц, USD") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Пустое поле — не учитывать тариф в расходах.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Свой лимит расхода на провайдера и день сброса периода. */
@Composable
private fun BudgetSheet(pool: ResourcePool, onDismiss: () -> Unit) {
    val db = remember { GatewayApplication.getInstance().database }
    val scope = rememberCoroutineScope()
    var limit by remember {
        mutableStateOf(pool.configuredLimit?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "")
    }
    var resetDay by remember { mutableStateOf((pool.resetDayOfMonth ?: 1).toString()) }

    FormSheet(
        title = "Бюджет: ${pool.name}",
        onDismiss = onDismiss,
        confirmText = "Сохранить",
        onConfirm = {
            scope.launch {
                withContext(Dispatchers.IO) {
                    db.resourcePoolDao().update(
                        pool.copy(
                            configuredLimit = limit.toDoubleOrNull(),
                            resetDayOfMonth = resetDay.toIntOrNull()?.coerceIn(1, 28) ?: 1,
                        )
                    )
                    QuotaRepository.refreshAll(db)
                }
            }
            onDismiss()
        },
    ) {
        OutlinedTextField(
            value = limit,
            onValueChange = { v -> limit = v.filter { it.isDigit() || it == '.' } },
            label = { Text("Лимит в месяц, USD") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = resetDay,
            onValueChange = { v -> resetDay = v.filter { it.isDigit() }.take(2) },
            label = { Text("День сброса периода (1–28)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Пустой лимит — считать только фактический расход, без остатка.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun planPriceSummary(providerId: Long): String {
    val plan = CodexAccount.planLabel(CodexAccount.storedPlan(providerId))
    val price = CodexAccount.monthlyPriceUsd(providerId)
    return listOfNotNull(plan, price?.let { "${Fmt.usd(it)} / мес" }).joinToString(" · ")
        .ifBlank { "не задан" }
}
