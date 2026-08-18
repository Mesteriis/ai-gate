package com.aigate.router.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aigate.router.GatewayApplication
import com.aigate.router.service.GatewayForegroundService
import com.aigate.router.ui.design.FormSheet
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.HelpSection
import com.aigate.router.ui.design.SectionHeader
import com.aigate.router.ui.design.SettingsRow
import com.aigate.router.ui.viewmodel.GatewayViewModel
import com.aigate.router.utils.AppLanguage
import com.aigate.router.utils.TranslationManager
import com.aigate.router.utils.tr

/**
 * Справка раздела «Настройки». Сюда переехали все пояснения, инструкции и
 * подсказки, которые раньше висели текстом прямо на экране настроек.
 */
internal val settingsHelp: List<HelpSection> = listOf(
    HelpSection(
        "API-ключи",
        "Запросы с самого устройства (127.0.0.1) ключа не требуют. Ключ нужен внешним " +
            "клиентам: каждый ключ отдельно задаёт, к каким моделям он открывает доступ.",
    ),
    HelpSection(
        "Режим локальной сети",
        "По умолчанию шлюз слушает только 127.0.0.1. Когда режим включён, шлюз принимает " +
            "запросы из локальной сети, но каждый запрос не с 127.0.0.1 обязан прислать пароль " +
            "в заголовке «Authorization: Bearer <пароль>». Адрес прослушивания меняется только " +
            "после перезапуска шлюза.",
    ),
    HelpSection(
        "Прокси",
        "Прокси используется для исходящих запросов к облачным провайдерам — локальные " +
            "адреса идут напрямую. Активен ровно один профиль; подписку можно импортировать " +
            "ссылкой, ссылку из буфера обмена шлюз распознаёт сам.",
    ),
    HelpSection(
        "Правила маршрутизации",
        "Правило перенаправляет или блокирует запрос по пути, имени модели, префиксу " +
            "API-ключа и провайдеру. Условия объединяются логикой И; пустое поле не " +
            "учитывается. Приоритет: меньше значение — раньше проверяется правило.",
    ),
    HelpSection(
        "Резервные копии",
        "Копия сохраняется в файл .qtbk (GZIP + SHA-256 + AES-256) и содержит провайдеров, " +
            "модели, ключи и статистику. Автоматическое копирование выполняется раз в сутки в " +
            "заданное время. Восстановление заменяет текущую базу целиком.",
    ),
    HelpSection(
        "Отладочные логи перехвата",
        "Пока перехват включён, запросы и ответы шлюза хранятся только в памяти процесса " +
            "(последние записи) и пропадают после перезапуска. Тела запросов могут содержать " +
            "ключи и переписку — не передавайте их третьим лицам.",
    ),
    HelpSection(
        "Автозапуск и работа в фоне",
        "Разрешения автозапуска и отключение оптимизации батареи не дают системе завершить " +
            "шлюз в фоне. «Скрыть из недавних» убирает приложение из списка недавних задач.",
    ),
    HelpSection(
        "Язык",
        "По умолчанию язык интерфейса берётся из системных настроек. Ручной выбор " +
            "переключает интерфейс независимо от системы.",
    ),
    HelpSection(
        "Сброс всех данных",
        "Сброс безвозвратно удаляет провайдеров, модели, ключи, историю чатов и статистику. " +
            "Чтобы подтвердить, нужно ввести контрольную фразу целиком.",
    ),
)

/** Вложенные полноэкранные разделы настроек. */
private enum class SettingsSection { RoutingRules }

/**
 * «Настройки» — только секции и строки со шевроном. Ничего тяжёлого инлайном:
 * каждый домен живёт в своём шите или полноэкранном разделе. Раньше это был один
 * вертикальный скролл из десяти несвязанных карточек.
 *
 * Своего Scaffold нет — шапку (и справку [settingsHelp]) даёт навигация.
 */
@Composable
fun SettingsScreen(
    viewModel: GatewayViewModel,
    modifier: Modifier = Modifier,
    onOpenKeys: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenBackups: () -> Unit,
    onOpenCapture: () -> Unit,
) {
    val context = LocalContext.current
    // Пересборка при смене языка интерфейса.
    val currentLang by TranslationManager.currentLanguageFlow.collectAsState()
    val autoDetectLang by TranslationManager.autoDetectFlow.collectAsState()

    var section by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    var showLanSheet by remember { mutableStateOf(false) }
    var showBackgroundSheet by remember { mutableStateOf(false) }
    var showLanguageSheet by remember { mutableStateOf(false) }
    var showResetSheet by remember { mutableStateOf(false) }

    var lanEnabled by remember { mutableStateOf(GatewayForegroundService.getLanModeEnabled()) }
    var hideFromRecents by remember {
        mutableStateOf(GatewayForegroundService.getGatewayConfig("hide_from_recents", "false").toBoolean())
    }
    val proxyEnabled by viewModel.proxyEnabled.collectAsState()
    val proxyProfiles by viewModel.proxyProfiles.collectAsState()
    val activeProxyId by viewModel.activeProxyId.collectAsState()
    val debugMode by viewModel.debugMode.collectAsState()
    val showProxySection by viewModel.showProxyConfigDialog.collectAsState()

    // Сводка для valueText: пересчитывается при возврате из вложенного раздела.
    val backupSummary = remember(section) { backupScheduleSummary() }
    // Счётчик правил берём из Flow Room — он обновляется сам после правок.
    val db = remember { GatewayApplication.getInstance().database }
    val rules by remember { db.routingRuleDao().getAllRules() }
        .collectAsState(initial = emptyList())
    val rulesSummary = remember(rules) {
        rules.count { it.enabled }.let { if (it == 0) "нет активных" else "$it активных" }
    }
    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = Gateway.spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Gateway.spacing.xs),
        ) {
            SettingsSectionHeader("Доступ")
            SettingsGroup {
                SettingsRow(
                    title = "API-ключи",
                    icon = Icons.Outlined.VpnKey,
                    onClick = onOpenKeys,
                )
                HorizontalDivider()
                SettingsRow(
                    title = "Режим локальной сети",
                    icon = Icons.Outlined.Lan,
                    valueText = if (lanEnabled) "включён" else "выключен",
                    onClick = { showLanSheet = true },
                )
            }

            SettingsSectionHeader("Сеть")
            SettingsGroup {
                // Раньше сюда можно было попасть только тройным тапом по
                // полупрозрачной плашке в «О программе» — теперь это обычная строка.
                SettingsRow(
                    title = "Прокси",
                    icon = Icons.Outlined.Dns,
                    valueText = proxySummary(proxyEnabled, proxyProfiles, activeProxyId),
                    onClick = { viewModel.showProxyConfig() },
                )
                HorizontalDivider()
                SettingsRow(
                    title = "Правила маршрутизации",
                    icon = Icons.AutoMirrored.Outlined.Rule,
                    valueText = rulesSummary,
                    onClick = { section = SettingsSection.RoutingRules },
                )
            }

            SettingsSectionHeader("Данные")
            SettingsGroup {
                SettingsRow(
                    title = "Резервные копии",
                    icon = Icons.Outlined.Save,
                    valueText = backupSummary,
                    onClick = onOpenBackups,
                )
                HorizontalDivider()
                SettingsRow(
                    title = "Отладочные логи перехвата",
                    icon = Icons.Outlined.BugReport,
                    valueText = if (debugMode) "запись" else "выключен",
                    onClick = onOpenCapture,
                )
            }

            SettingsSectionHeader("Приложение")
            SettingsGroup {
                SettingsRow(
                    title = "Автозапуск и работа в фоне",
                    icon = Icons.Outlined.BatterySaver,
                    onClick = { showBackgroundSheet = true },
                )
                HorizontalDivider()
                SettingsToggleRow(
                    title = "Скрыть из недавних",
                    icon = Icons.Outlined.VisibilityOff,
                    checked = hideFromRecents,
                    onCheckedChange = { enabled ->
                        hideFromRecents = enabled
                        GatewayForegroundService.saveGatewayConfig("hide_from_recents", enabled.toString())
                        runCatching {
                            val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                                as android.app.ActivityManager
                            am.appTasks.firstOrNull()?.setExcludeFromRecents(enabled)
                        }
                    },
                )
                HorizontalDivider()
                SettingsRow(
                    title = "Язык",
                    icon = Icons.Outlined.Translate,
                    valueText = if (autoDetectLang) "системный" else currentLang.displayName,
                    onClick = { showLanguageSheet = true },
                )
            }

            Spacer(Modifier.size(Gateway.spacing.sm))
            SettingsGroup {
                SettingsRow(
                    title = "О программе",
                    subtitle = appVersion.takeIf { it.isNotBlank() }?.let { "Версия $it" },
                    icon = Icons.Outlined.Info,
                    onClick = onOpenAbout,
                )
            }

            SettingsSectionHeader("Опасная зона")
            SettingsGroup {
                SettingsDangerRow(
                    title = "Сбросить все данные",
                    icon = Icons.Outlined.DeleteForever,
                    onClick = { showResetSheet = true },
                )
            }
        }

        // Вложенные разделы — полноэкранные, с рабочим системным «назад».
        when (section) {
            SettingsSection.RoutingRules -> RoutingRulesDialog(
                viewModel = viewModel,
                onDismiss = { section = null },
            )
            null -> Unit
        }
        if (showProxySection) {
            ProxyManagementDialog(viewModel = viewModel, onDismiss = { viewModel.hideProxyConfig() })
        }
    }

    if (showLanSheet) {
        LanModeSheet(
            initialEnabled = lanEnabled,
            initialToken = GatewayForegroundService.getLanToken(),
            onDismiss = { showLanSheet = false },
            onApply = { enabled, token ->
                GatewayForegroundService.setLanToken(token.trim())
                GatewayForegroundService.setLanModeEnabled(enabled && token.isNotBlank())
                lanEnabled = GatewayForegroundService.getLanModeEnabled()
                showLanSheet = false
            },
        )
    }

    if (showBackgroundSheet) {
        BackgroundWorkSheet(
            onBindPermissions = { viewModel.bindBackgroundPermissions() },
            onOpenAppSettings = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.fromParts("package", context.packageName, null)),
                    )
                }
            },
            onOpenBatterySettings = {
                runCatching {
                    context.startActivity(
                        android.content.Intent("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"),
                    )
                }
            },
            onDismiss = { showBackgroundSheet = false },
        )
    }

    if (showLanguageSheet) {
        LanguageSheet(
            current = currentLang,
            autoDetect = autoDetectLang,
            onAutoDetect = { TranslationManager.setAutoDetect(it, context) },
            onSelect = { TranslationManager.setLanguage(it, context) },
            onDismiss = { showLanguageSheet = false },
        )
    }

    if (showResetSheet) {
        ResetAllDataSheet(
            onConfirm = { viewModel.resetAllData() },
            onDismiss = { showResetSheet = false },
        )
    }
}

// ============================================================
// Строительные блоки списка настроек
// ============================================================

@Composable
private fun SettingsSectionHeader(title: String) {
    SectionHeader(title, modifier = Modifier.padding(horizontal = Gateway.spacing.lg))
}

/**
 * Группа строк одной секции. Строки полноширинные и кликабельные целиком,
 * поэтому внутренние отступы задаёт сама строка, а не контейнер.
 */
@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = Gateway.colors.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Gateway.spacing.lg),
    ) {
        Column(content = content)
    }
}

/** Строка-переключатель: состояние меняется на месте, отдельный экран не нужен. */
@Composable
private fun SettingsToggleRow(
    title: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = Gateway.spacing.lg, vertical = Gateway.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(Gateway.spacing.md))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Строка деструктивного действия — единственный красный элемент в списке. */
@Composable
private fun SettingsDangerRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Gateway.spacing.lg, vertical = Gateway.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(Gateway.spacing.md))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
    }
}

// ============================================================
// Шиты верхнего уровня
// ============================================================

/** Режим локальной сети: свитч + пароль. Инструкция живёт в [settingsHelp]. */
@Composable
private fun LanModeSheet(
    initialEnabled: Boolean,
    initialToken: String,
    onDismiss: () -> Unit,
    onApply: (Boolean, String) -> Unit,
) {
    var enabled by remember { mutableStateOf(initialEnabled) }
    var token by remember { mutableStateOf(initialToken) }
    var tokenVisible by remember { mutableStateOf(false) }

    FormSheet(
        title = "Режим локальной сети",
        onDismiss = onDismiss,
        confirmText = "Применить",
        confirmEnabled = !enabled || token.isNotBlank(),
        onConfirm = { onApply(enabled, token) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Принимать запросы из локальной сети", style = MaterialTheme.typography.bodyLarge)
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        if (enabled) {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Пароль (Bearer-токен)") },
                singleLine = true,
                visualTransformation = if (tokenVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            imageVector = if (tokenVisible) Icons.Outlined.VisibilityOff
                            else Icons.Outlined.Visibility,
                            contentDescription = if (tokenVisible) "Скрыть пароль" else "Показать пароль",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Автозапуск и работа в фоне — три системных перехода, которые раньше висели инлайном. */
@Composable
private fun BackgroundWorkSheet(
    onBindPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    FormSheet(
        title = "Автозапуск и работа в фоне",
        onDismiss = onDismiss,
        confirmText = "Готово",
        dismissText = "Закрыть",
        onConfirm = onDismiss,
    ) {
        Button(
            onClick = { onBindPermissions(); onDismiss() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Настроить разрешения автозапуска") }
        OutlinedButton(
            onClick = { onOpenAppSettings(); onDismiss() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Системные настройки приложения") }
        OutlinedButton(
            onClick = { onOpenBatterySettings(); onDismiss() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Оптимизация батареи") }
    }
}

/** Выбор языка: следовать системе или ручной выбор из списка. */
@Composable
private fun LanguageSheet(
    current: AppLanguage,
    autoDetect: Boolean,
    onAutoDetect: (Boolean) -> Unit,
    onSelect: (AppLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    FormSheet(
        title = "Язык",
        onDismiss = onDismiss,
        confirmText = "Готово",
        dismissText = "Закрыть",
        onConfirm = onDismiss,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Следовать системе", style = MaterialTheme.typography.bodyLarge)
            Switch(checked = autoDetect, onCheckedChange = onAutoDetect)
        }
        if (!autoDetect) {
            // Список короткий и фиксированный, поэтому forEach: LazyColumn внутри
            // скроллящегося шита измерялся бы бесконечной высотой.
            AppLanguage.entries.forEach { lang ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(lang) }
                        .padding(vertical = Gateway.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        lang.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (lang == current) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Сброс всех данных. Защита контрольной фразой сохранена: подтверждение
 * возможно только после посимвольного ввода локализованной фразы — это строже
 * обычного ConfirmDialog, поэтому диалог здесь не дублируется.
 */
@Composable
private fun ResetAllDataSheet(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val language by TranslationManager.currentLanguageFlow.collectAsState()
    val phrase = resetConfirmPhrase(language)
    var input by remember { mutableStateOf("") }

    FormSheet(
        title = "Сбросить все данные",
        onDismiss = onDismiss,
        confirmText = "Удалить навсегда",
        confirmEnabled = input.trim() == phrase,
        onConfirm = { onConfirm(); onDismiss() },
    ) {
        Text(
            text = "Провайдеры, модели, ключи, история чатов и статистика будут удалены " +
                "безвозвратно.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(phrase) },
            singleLine = true,
            isError = input.isNotBlank() && input.trim() != phrase,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ============================================================
// Сводки состояния для строк
// ============================================================

private fun proxySummary(
    enabled: Boolean,
    profiles: List<GatewayViewModel.ProxyProfile>,
    activeId: String?,
): String = when {
    !enabled -> "выключен"
    else -> profiles.firstOrNull { it.id == activeId }?.let { "${it.type} · ${it.host}" }
        ?: "включён"
}

/**
 * Контрольная фраза сброса. Сначала пробуем каталог переводов; пока ключа
 * `reset_confirm_phrase` там нет, `tr` возвращает сам ключ — тогда берём фразу
 * по текущему языку, а не захардкоженную русскую строку, как было раньше.
 */
private fun resetConfirmPhrase(language: AppLanguage): String {
    val key = "reset_confirm_phrase"
    val fromCatalog = tr(key)
    if (fromCatalog != key) return fromCatalog
    return when (language) {
        AppLanguage.ZH_CN -> "确认重置"
        AppLanguage.ZH_TW -> "確認重置"
        AppLanguage.JA -> "リセットを確認"
        AppLanguage.KO -> "초기화 확인"
        AppLanguage.ES -> "CONFIRMAR REINICIO"
        AppLanguage.FR -> "CONFIRMER LA REINITIALISATION"
        AppLanguage.DE -> "ZURUCKSETZEN BESTATIGEN"
        AppLanguage.RU -> "Подтвердить сброс"
        AppLanguage.PT -> "CONFIRMAR REDEFINICAO"
        AppLanguage.VI -> "XAC NHAN DAT LAI"
        AppLanguage.TH -> "ยืนยันการรีเซ็ต"
        AppLanguage.AR -> "تأكيد إعادة التعيين"
        AppLanguage.HI -> "रीसेट की पुष्टि करें"
        AppLanguage.ID -> "KONFIRMASI RESET"
        AppLanguage.EN -> "CONFIRM RESET"
    }
}

/** Сводка расписания автокопирования для строки «Резервные копии». */
private fun backupScheduleSummary(): String {
    val enabled = GatewayForegroundService
        .getGatewayConfig("auto_backup_enabled", "false").toBoolean()
    if (!enabled) return "вручную"
    val hour = GatewayForegroundService.getGatewayConfig("auto_backup_hour", "3").toIntOrNull() ?: 3
    val minute = GatewayForegroundService.getGatewayConfig("auto_backup_minute", "0").toIntOrNull() ?: 0
    return "ежедневно %02d:%02d".format(hour, minute)
}
