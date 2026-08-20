package com.aigate.router.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aigate.router.GatewayApplication
import com.aigate.router.data.model.AiModel
import com.aigate.router.data.model.RoutingRule
import com.aigate.router.data.model.routeKey
import com.aigate.router.service.GatewayForegroundService
import com.aigate.router.ui.design.AppCard
import com.aigate.router.ui.design.AppScaffold
import com.aigate.router.ui.design.CardTone
import com.aigate.router.ui.design.ConfirmDialog
import com.aigate.router.ui.design.EmptyState
import com.aigate.router.ui.design.EntityCard
import com.aigate.router.ui.design.Fmt
import com.aigate.router.ui.design.FormSheet
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.SectionHeader
import com.aigate.router.ui.design.StatusChip
import com.aigate.router.ui.design.StatusTone
import com.aigate.router.ui.util.rememberTicker
import com.aigate.router.ui.viewmodel.GatewayViewModel
import com.aigate.router.utils.CrashHandler
import com.aigate.router.utils.TranslationManager
import com.aigate.router.utils.localizeGeneratedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================
// О программе
// ============================================================

/**
 * «О программе»: версия, текущая конфигурация шлюза и трафик. Шапку с кнопкой
 * «назад» даёт навигация, поэтому своего каркаса у экрана нет. Скрытый вход в
 * управление прокси (тройной тап по плашке) убран — прокси теперь обычная
 * строка в настройках.
 */
@Composable
fun AboutScreen(
    viewModel: GatewayViewModel = viewModel(factory = GatewayViewModel.Factory()),
    modifier: Modifier = Modifier,
) {
    TranslationManager.currentLanguageFlow.collectAsState().value
    val context = LocalContext.current
    val gatewayPort by viewModel.gatewayPort.collectAsState(initial = 8889)
    val proxyEnabled by viewModel.proxyEnabled.collectAsState(initial = false)
    val proxyProfiles by viewModel.proxyProfiles.collectAsState()
    val activeProxyId by viewModel.activeProxyId.collectAsState()
    var crashLogCopied by remember { mutableStateOf(false) }

    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "—" }
    }
    // Счётчики трафика живут в сервисе и не являются Flow — обновляем по тикеру.
    val tick by rememberTicker(2_000L)
    val uploaded = remember(tick) { GatewayForegroundService.trafficUploadBytes.get() }
    val downloaded = remember(tick) { GatewayForegroundService.trafficDownloadBytes.get() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gateway.spacing.lg)
            .padding(bottom = Gateway.spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Gateway.spacing.md),
    ) {
        SectionHeader("Приложение")
        AppCard(tone = CardTone.Raised) {
            InfoRow("Версия", appVersion)
            InfoRow("Протокол", "OpenAI-совместимый API")
            // Атрибуция апстрима — требование Apache-2.0, см. NOTICE в корне репозитория.
            InfoRow("Основано на", "QiTong AI Gateway (Apache-2.0)")
        }

        SectionHeader("Конфигурация")
        AppCard(tone = CardTone.Raised) {
            InfoRow("Порт шлюза", gatewayPort.toString())
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Gateway.spacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Прокси",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StatusChip(
                    text = if (proxyEnabled) "включён" else "выключен",
                    tone = if (proxyEnabled) StatusTone.Success else StatusTone.Neutral,
                    withDot = true,
                )
            }
            val activeProxy = proxyProfiles
                .firstOrNull { it.id == activeProxyId }
                ?.takeIf { proxyEnabled }
            activeProxy?.let {
                InfoRow("Узел прокси", "${it.type} · ${it.host}:${it.port}")
                if (it.username.isNotBlank()) InfoRow("Пользователь", it.username)
            }
            InfoRow("Отправлено", Fmt.bytes(uploaded))
            InfoRow("Получено", Fmt.bytes(downloaded))
        }

        SectionHeader("Поддержать")
        AppCard(tone = CardTone.Raised) {
            Text(
                "AiGate — открытый проект и развивается в свободное время.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(Gateway.spacing.md))
            // Пожертвования — только внешняя страница, платежей в приложении нет.
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://buymeacoffee.com/mesteriis"),
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Угостить кофе — buymeacoffee.com/mesteriis") }
        }

        if (CrashHandler.hasCrashLog()) {
            SectionHeader("Диагностика")
            AppCard(tone = CardTone.Raised) {
                Text(
                    "Предыдущий запуск завершился аварийно, лог сохранён.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(Gateway.spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Gateway.spacing.sm)) {
                    OutlinedButton(
                        onClick = {
                            val clipboard = context
                                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("Crash log", CrashHandler.getCrashLog()),
                            )
                            crashLogCopied = true
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (crashLogCopied) "Скопировано" else "Копировать лог") }
                    TextButton(
                        onClick = { CrashHandler.clearCrashLog(); crashLogCopied = false },
                        modifier = Modifier.weight(1f),
                    ) { Text("Очистить лог", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Gateway.spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(Gateway.spacing.sm))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

// ============================================================
// Прокси
// ============================================================

/**
 * Управление прокси. Раньше это был AlertDialog, поверх которого открывались
 * ещё три диалога; теперь это полноэкранный раздел с рабочим «назад», а формы
 * живут в шитах. Имя функции сохранено — её вызывает строка «Прокси».
 */
@Composable
internal fun ProxyManagementDialog(viewModel: GatewayViewModel, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val profiles by viewModel.proxyProfiles.collectAsState()
    val proxyEnabled by viewModel.proxyEnabled.collectAsState()
    val activeProxyId by viewModel.activeProxyId.collectAsState()

    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<GatewayViewModel.ProxyProfile?>(null) }
    var showSubscription by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<GatewayViewModel.ProxyProfile?>(null) }
    var clipboardLink by remember { mutableStateOf<String?>(null) }

    // Ссылку из буфера обмена предлагаем импортировать — но только с подтверждением.
    LaunchedEffect(Unit) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (cm.hasPrimaryClip()) {
                val text = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                clipboardLink = viewModel.detectClipboardLink(text)
            }
        }
    }

    AppScaffold(
        title = "Прокси",
        onBack = onDismiss,
        snackbarHostState = snackbarHostState,
        modifier = Modifier.fillMaxSize(),
        actions = {
            IconButton(onClick = { showSubscription = true }) {
                Icon(Icons.Outlined.CloudDownload, contentDescription = "Импорт подписки")
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = "Добавить прокси")
            }
        },
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Gateway.spacing.lg)
                .padding(bottom = Gateway.spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Gateway.spacing.sm),
        ) {
            if (profiles.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.Dns,
                    text = "Прокси не настроены",
                    actionText = "Добавить прокси",
                    onAction = { showAdd = true },
                )
            } else {
                profiles.forEach { profile ->
                    val isActive = profile.id == activeProxyId && proxyEnabled
                    EntityCard(
                        title = localizeGeneratedName(profile.name),
                        subtitle = "${profile.type} · ${profile.host}:${profile.port}",
                        leadingIcon = when (profile.type.uppercase()) {
                            "HTTP", "HTTPS" -> Icons.Outlined.Language
                            "SOCKS5", "SOCKS" -> Icons.Outlined.Shield
                            else -> Icons.Outlined.Dns
                        },
                        statusText = if (isActive) "активен" else null,
                        statusTone = if (isActive) StatusTone.Success else null,
                        dimmed = !profile.enabled,
                        trailing = {
                            Switch(
                                checked = profile.enabled,
                                onCheckedChange = { viewModel.toggleProxyEnabled(profile) },
                            )
                        },
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Gateway.spacing.sm)) {
                            TextButton(onClick = {
                                viewModel.testProxySpeed(profile)
                                scope.launch {
                                    snackbarHostState.showSnackbar("Замер скорости запущен")
                                }
                            }) { Text("Замер") }
                            TextButton(onClick = { editing = profile }) { Text("Изменить") }
                            TextButton(onClick = { pendingDelete = profile }) {
                                Text("Удалить", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddEditProxySheet(
            title = "Новый прокси",
            onDismiss = { showAdd = false },
            onConfirm = { profile -> viewModel.addProxy(profile); showAdd = false },
        )
    }
    editing?.let { profile ->
        AddEditProxySheet(
            title = "Прокси: ${localizeGeneratedName(profile.name)}",
            initialProfile = profile,
            onDismiss = { editing = null },
            onConfirm = { updated -> viewModel.updateProxy(updated); editing = null },
        )
    }
    if (showSubscription) {
        ProxySubscriptionSheet(
            onDismiss = { showSubscription = false },
            onImport = { url -> viewModel.importSubscription(url); showSubscription = false },
        )
    }
    pendingDelete?.let { profile ->
        ConfirmDialog(
            title = "Удалить прокси?",
            message = "«${localizeGeneratedName(profile.name)}» будет удалён из списка.",
            confirmText = "Удалить",
            onConfirm = { viewModel.deleteProxy(profile) },
            onDismiss = { pendingDelete = null },
        )
    }
    clipboardLink?.let { link ->
        ConfirmDialog(
            title = "Импортировать ссылку из буфера обмена?",
            message = link.take(120),
            confirmText = "Импортировать",
            destructive = false,
            onConfirm = {
                if (link.startsWith("http")) viewModel.importSubscription(link)
                else viewModel.addProxyFromLink(link)
            },
            onDismiss = { clipboardLink = null },
        )
    }
}

/** Форма прокси — шит вместо диалога: восемь полей в диалог не помещались. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEditProxySheet(
    title: String,
    initialProfile: GatewayViewModel.ProxyProfile? = null,
    onDismiss: () -> Unit,
    onConfirm: (GatewayViewModel.ProxyProfile) -> Unit,
) {
    val typeOptions = listOf(
        "HTTP", "HTTPS", "SOCKS5", "SOCKS", "VMESS", "SS", "VLESS", "Trojan", "Hysteria2",
    )
    var name by remember { mutableStateOf(initialProfile?.name.orEmpty()) }
    var type by remember { mutableStateOf(initialProfile?.type ?: "HTTP") }
    var host by remember { mutableStateOf(initialProfile?.host.orEmpty()) }
    var port by remember { mutableStateOf((initialProfile?.port ?: 1080).toString()) }
    var username by remember { mutableStateOf(initialProfile?.username.orEmpty()) }
    var password by remember { mutableStateOf(initialProfile?.password.orEmpty()) }
    var passwordVisible by remember { mutableStateOf(false) }
    var typeExpanded by remember { mutableStateOf(false) }

    FormSheet(
        title = title,
        onDismiss = onDismiss,
        confirmText = "Сохранить",
        confirmEnabled = host.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535,
        onConfirm = {
            onConfirm(
                GatewayViewModel.ProxyProfile(
                    id = initialProfile?.id ?: java.util.UUID.randomUUID().toString().take(8),
                    name = name.ifBlank { "Безымянный прокси" },
                    type = type,
                    host = host.trim(),
                    port = port.toIntOrNull() ?: 1080,
                    username = username,
                    password = password,
                    enabled = initialProfile?.enabled ?: false,
                ),
            )
        },
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Название") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ExposedDropdownMenuBox(
            expanded = typeExpanded,
            onExpandedChange = { typeExpanded = !typeExpanded },
        ) {
            OutlinedTextField(
                value = type,
                onValueChange = {},
                readOnly = true,
                label = { Text("Тип") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = typeExpanded,
                onDismissRequest = { typeExpanded = false },
            ) {
                typeOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            type = option
                            // Порт по умолчанию подставляем только если его не правили руками.
                            if (port == "1080" || port == "7890") {
                                port = if (option.startsWith("SOCKS")) "1080" else "7890"
                            }
                            typeExpanded = false
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Адрес сервера") },
            placeholder = { Text("10.0.0.2") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { value -> port = value.filter { it.isDigit() } },
            label = { Text("Порт") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        HorizontalDivider()
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Имя пользователя") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Пароль") },
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff
                        else Icons.Outlined.Visibility,
                        contentDescription = if (passwordVisible) "Скрыть пароль" else "Показать пароль",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (type.startsWith("SOCKS") && (username.isNotBlank() || password.isNotBlank())) {
            StatusChip(text = "Аутентификация SOCKS5 (RFC 1929)", tone = StatusTone.Info)
        }
    }
}

/** Импорт подписки по ссылке. */
@Composable
private fun ProxySubscriptionSheet(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    FormSheet(
        title = "Импорт подписки",
        onDismiss = onDismiss,
        confirmText = "Импортировать",
        confirmEnabled = url.startsWith("http"),
        onConfirm = { onImport(url.trim()) },
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Ссылка подписки") },
            placeholder = { Text("https://example.com/sub") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ============================================================
// Правила маршрутизации
// ============================================================

/**
 * Правила маршрутизации: полноэкранный раздел вместо AlertDialog, поверх
 * которого открывался второй диалог с десятью полями. Имя функции сохранено —
 * её вызывает строка «Правила маршрутизации».
 */
@Composable
internal fun RoutingRulesDialog(viewModel: GatewayViewModel, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)

    val snackbarHostState = remember { SnackbarHostState() }
    val models by viewModel.models.collectAsState()

    // Правила берём из Flow Room: запись сама присылает новое значение, поэтому
    // ручной reload после каждой мутации (и гонка чтения после записи) не нужны.
    val db = remember { GatewayApplication.getInstance().database }
    val loaded by remember { db.routingRuleDao().getAllRules() }.collectAsState(initial = null)
    val rules = loaded.orEmpty()
    val isLoading = loaded == null

    var showForm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<RoutingRule?>(null) }
    var pendingDelete by remember { mutableStateOf<RoutingRule?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }

    AppScaffold(
        title = "Правила маршрутизации",
        onBack = onDismiss,
        snackbarHostState = snackbarHostState,
        modifier = Modifier.fillMaxSize(),
        actions = {
            if (rules.isNotEmpty()) {
                TextButton(onClick = { confirmClearAll = true }) {
                    Text("Очистить", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showForm = true }) {
                Icon(Icons.Default.Add, contentDescription = "Добавить правило")
            }
        },
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Gateway.spacing.lg)
                .padding(bottom = Gateway.spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Gateway.spacing.sm),
        ) {
            when {
                isLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                rules.isEmpty() -> EmptyState(
                    icon = Icons.AutoMirrored.Outlined.Rule,
                    text = "Правил нет",
                    actionText = "Добавить правило",
                    onAction = { editing = null; showForm = true },
                )
                else -> rules.forEach { rule ->
                    EntityCard(
                        title = rule.name,
                        subtitle = ruleConditions(rule),
                        statusText = if (rule.action == "block") "блокировка" else "маршрут",
                        statusTone = if (rule.action == "block") StatusTone.Error else StatusTone.Info,
                        dimmed = !rule.enabled,
                        trailing = {
                            Switch(
                                checked = rule.enabled,
                                onCheckedChange = { enabled ->
                                    viewModel.setRoutingRuleEnabled(rule.id, enabled)
                                },
                            )
                        },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = ruleAction(rule),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { editing = rule; showForm = true }) {
                                Text("Изменить")
                            }
                            TextButton(onClick = { pendingDelete = rule }) {
                                Text("Удалить", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showForm) {
        val current = editing
        RoutingRuleSheet(
            existingRule = current,
            models = models,
            onDismiss = { showForm = false },
            onSave = { rule ->
                if (current != null) viewModel.updateRoutingRule(rule)
                else viewModel.saveRoutingRule(rule)
                showForm = false
            },
        )
    }
    pendingDelete?.let { rule ->
        ConfirmDialog(
            title = "Удалить правило?",
            message = "«${rule.name}» перестанет применяться. Действие необратимо.",
            confirmText = "Удалить",
            onConfirm = { viewModel.deleteRoutingRule(rule) },
            onDismiss = { pendingDelete = null },
        )
    }
    if (confirmClearAll) {
        ConfirmDialog(
            title = "Очистить все правила?",
            message = "Будут удалены все правила маршрутизации, включая пресеты раздела " +
                "«Маршруты». Действие необратимо.",
            confirmText = "Очистить",
            onConfirm = { viewModel.clearAllRoutingRules() },
            onDismiss = { confirmClearAll = false },
        )
    }
}

/** Условия правила одной строкой. */
private fun ruleConditions(rule: RoutingRule): String = buildList {
    if (rule.pathPattern.isNotBlank()) add("путь ${rule.pathPattern}")
    if (rule.modelPattern.isNotBlank()) add("модель ${rule.modelPattern}")
    if (rule.apiKeyPattern.isNotBlank()) add("ключ ${rule.apiKeyPattern.take(8)}…")
    rule.providerId?.let { add("провайдер $it") }
}.joinToString(" · ").ifEmpty { "без условий" }

/** Действие правила одной строкой. */
private fun ruleAction(rule: RoutingRule): String = when (rule.action) {
    "block" -> "Блокировать запрос"
    "route" -> "Направить на ${rule.targetModelKey}"
    else -> rule.action
}

/** Форма правила: десять полей, поэтому только шит. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutingRuleSheet(
    existingRule: RoutingRule?,
    models: List<AiModel>,
    onDismiss: () -> Unit,
    onSave: (RoutingRule) -> Unit,
) {
    var name by remember { mutableStateOf(existingRule?.name.orEmpty()) }
    var priority by remember { mutableStateOf((existingRule?.priority ?: 0).toString()) }
    var pathPattern by remember { mutableStateOf(existingRule?.pathPattern.orEmpty()) }
    var modelPattern by remember { mutableStateOf(existingRule?.modelPattern.orEmpty()) }
    var apiKeyPattern by remember { mutableStateOf(existingRule?.apiKeyPattern.orEmpty()) }
    var providerIdText by remember { mutableStateOf(existingRule?.providerId?.toString().orEmpty()) }
    var targetModelKey by remember { mutableStateOf(existingRule?.targetModelKey.orEmpty()) }
    var action by remember { mutableStateOf(existingRule?.action ?: "route") }
    var blockMessage by remember { mutableStateOf(existingRule?.blockMessage.orEmpty()) }
    var targetExpanded by remember { mutableStateOf(false) }

    FormSheet(
        title = if (existingRule != null) "Правило: ${existingRule.name}" else "Новое правило",
        onDismiss = onDismiss,
        confirmText = "Сохранить",
        confirmEnabled = name.isNotBlank() && (action == "block" || targetModelKey.isNotBlank()),
        onConfirm = {
            onSave(
                RoutingRule(
                    id = existingRule?.id ?: 0,
                    name = name.trim(),
                    enabled = existingRule?.enabled ?: true,
                    priority = priority.toIntOrNull() ?: 0,
                    pathPattern = pathPattern.trim(),
                    modelPattern = modelPattern.trim(),
                    apiKeyPattern = apiKeyPattern.trim(),
                    providerId = providerIdText.toLongOrNull(),
                    targetModelKey = targetModelKey.trim(),
                    action = action,
                    blockMessage = blockMessage.trim(),
                    createdAt = existingRule?.createdAt ?: System.currentTimeMillis(),
                ),
            )
        },
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Название") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = priority,
            onValueChange = { value -> priority = value.filter { it.isDigit() || it == '-' } },
            label = { Text("Приоритет") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        HorizontalDivider()
        OutlinedTextField(
            value = pathPattern,
            onValueChange = { pathPattern = it },
            label = { Text("Путь") },
            placeholder = { Text("/v1/chat/completions") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = modelPattern,
            onValueChange = { modelPattern = it },
            label = { Text("Модель") },
            placeholder = { Text("gpt-*") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKeyPattern,
            onValueChange = { apiKeyPattern = it },
            label = { Text("Префикс API-ключа") },
            placeholder = { Text("sk-proj-") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = providerIdText,
            onValueChange = { value -> providerIdText = value.filter { it.isDigit() } },
            label = { Text("ID провайдера") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(Gateway.spacing.sm)) {
            FilterChip(
                selected = action == "route",
                onClick = { action = "route" },
                label = { Text("Маршрут") },
            )
            FilterChip(
                selected = action == "block",
                onClick = { action = "block" },
                label = { Text("Блокировать") },
            )
        }
        if (action == "route") {
            // Выпадающий список вместо длинного перечня моделей прямо в форме:
            // моделей бывает несколько сотен.
            ExposedDropdownMenuBox(
                expanded = targetExpanded,
                onExpandedChange = { targetExpanded = it },
            ) {
                OutlinedTextField(
                    value = targetModelKey,
                    onValueChange = { targetModelKey = it; targetExpanded = true },
                    label = { Text("Целевая модель") },
                    placeholder = { Text("providerId:modelId") },
                    singleLine = true,
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = targetExpanded)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                )
                ExposedDropdownMenu(
                    expanded = targetExpanded,
                    onDismissRequest = { targetExpanded = false },
                ) {
                    models
                        .filter {
                            targetModelKey.isBlank() ||
                                it.routeKey.contains(targetModelKey, ignoreCase = true) ||
                                it.displayName.contains(targetModelKey, ignoreCase = true)
                        }
                        .take(40)
                        .forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "${model.displayName} · ${model.routeKey}",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                },
                                onClick = {
                                    targetModelKey = model.routeKey
                                    targetExpanded = false
                                },
                            )
                        }
                }
            }
        }
        if (action == "block") {
            OutlinedTextField(
                value = blockMessage,
                onValueChange = { blockMessage = it },
                label = { Text("Сообщение при блокировке") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
