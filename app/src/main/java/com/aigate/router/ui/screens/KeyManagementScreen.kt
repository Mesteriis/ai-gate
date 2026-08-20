package com.aigate.router.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import com.aigate.router.service.ApiKeyEntry
import com.aigate.router.service.GatewayForegroundService
import com.aigate.router.service.KeyManager
import com.aigate.router.ui.design.AppScaffold
import com.aigate.router.ui.design.ConfirmDialog
import com.aigate.router.ui.design.EmptyState
import com.aigate.router.ui.design.EntityCard
import com.aigate.router.ui.design.Fmt
import com.aigate.router.ui.design.FormSheet
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.StatusChip
import com.aigate.router.ui.design.StatusTone
import kotlinx.coroutines.launch

/**
 * Ключи API: глобальная авторизация, список ключей и CRUD в шитах.
 * Секрет целиком в списке не показываем — только маску; полный ключ
 * доступен через копирование в буфер из шита редактирования.
 */
@Composable
fun KeyManagementScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var keys by remember { mutableStateOf(KeyManager.getAllKeys()) }
    var showAddSheet by remember { mutableStateOf(false) }
    var editingKey by remember { mutableStateOf<ApiKeyEntry?>(null) }
    var pendingDelete by remember { mutableStateOf<ApiKeyEntry?>(null) }
    var requireApiKey by remember { mutableStateOf(GatewayForegroundService.getRequireApiKey()) }

    fun refreshKeys() { keys = KeyManager.getAllKeys() }

    AppScaffold(
        // Название совпадает с пунктом в настройках, иначе раздел выглядит чужим.
        title = "API-ключи",
        onBack = onDismiss,
        snackbarHostState = snackbarHostState,
    ) { contentModifier ->
        LazyColumn(
            modifier = contentModifier.padding(horizontal = Gateway.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Gateway.spacing.sm),
            contentPadding = PaddingValues(bottom = Gateway.spacing.xl),
        ) {
            item {
                EntityCard(
                    title = "Авторизация по API-ключу",
                    subtitle = if (requireApiKey) "Требуется для запросов из сети" else "Не требуется",
                    leadingIcon = Icons.Outlined.Lock,
                    trailing = {
                        Switch(
                            checked = requireApiKey,
                            onCheckedChange = { enabled ->
                                requireApiKey = enabled
                                GatewayForegroundService.saveRequireApiKey(enabled)
                            },
                        )
                    },
                )
            }
            if (keys.isEmpty()) {
                // Пустое состояние несёт единственное действие — отдельная
                // кнопка сверху дублировала бы его.
                item {
                    EmptyState(
                        icon = Icons.Outlined.Key,
                        text = "Ключей пока нет",
                        actionText = "Добавить ключ",
                        onAction = { showAddSheet = true },
                    )
                }
            } else {
                item {
                    Button(onClick = { showAddSheet = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text(
                            text = "Добавить ключ",
                            modifier = Modifier.padding(start = Gateway.spacing.xs),
                        )
                    }
                }
                items(keys, key = { it.key }) { entry ->
                    KeyCard(
                        entry = entry,
                        onClick = { editingKey = entry },
                        onToggle = { enabled ->
                            KeyManager.updateKey(entry.key, enabled = enabled)
                            refreshKeys()
                        },
                    )
                }
            }
        }
    }

    if (showAddSheet) {
        AddKeySheet(
            onDismiss = { showAddSheet = false },
            onSave = { key, label, autoAccess ->
                val added = KeyManager.addKey(key, label, emptyList(), autoAccess)
                refreshKeys()
                showAddSheet = false
                // Дубликат KeyManager молча отклоняет — без сообщения кажется,
                // что ключ пропал.
                if (!added) scope.launch { snackbarHostState.showSnackbar("Такой ключ уже добавлен") }
            },
        )
    }

    editingKey?.let { entry ->
        EditKeySheet(
            entry = entry,
            onDismiss = { editingKey = null },
            onSave = { label, enabled, models, autoAccess ->
                KeyManager.updateKey(
                    entry.key,
                    label = label,
                    enabled = enabled,
                    allowedModels = models,
                    autoAccess = autoAccess,
                )
                refreshKeys()
                editingKey = null
            },
            onCopy = {
                copyKeyToClipboard(context, entry.key)
                scope.launch { snackbarHostState.showSnackbar("Ключ скопирован") }
            },
            onDelete = {
                editingKey = null
                pendingDelete = entry
            },
        )
    }

    // Удаление отзывает доступ у всех клиентов с этим ключом — только через
    // подтверждение.
    pendingDelete?.let { entry ->
        ConfirmDialog(
            title = "Удалить ключ?",
            message = "Ключ «${entry.displayName()}» перестанет действовать сразу же. " +
                "Действие необратимо.",
            confirmText = "Удалить",
            onConfirm = {
                KeyManager.deleteKey(entry.key)
                refreshKeys()
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** Имя ключа для заголовков и подтверждений: метка, а без неё — маска. */
private fun ApiKeyEntry.displayName(): String = label.ifBlank { maskKey(key) }

/** Маска секрета: начало и хвост достаточно, чтобы отличить ключи друг от друга. */
private fun maskKey(key: String): String =
    if (key.length <= 12) key else key.take(7) + "…" + key.takeLast(4)

/** Карточка ключа в списке: маска и дата в подзаголовке, ограничения — чипами. */
@Composable
private fun KeyCard(
    entry: ApiKeyEntry,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val hasRestrictions = entry.allowedModels.isNotEmpty() || !entry.autoAccess
    EntityCard(
        title = entry.label.ifBlank { "Без метки" },
        subtitle = maskKey(entry.key) + " · создан " + Fmt.dateTime(entry.createdAt),
        leadingIcon = Icons.Outlined.Key,
        statusText = if (entry.enabled) null else "Выключен",
        statusTone = if (entry.enabled) null else StatusTone.Neutral,
        dimmed = !entry.enabled,
        onClick = onClick,
        trailing = { Switch(checked = entry.enabled, onCheckedChange = onToggle) },
        content = if (hasRestrictions) {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(Gateway.spacing.sm)) {
                    if (entry.allowedModels.isNotEmpty()) {
                        StatusChip(
                            text = "Ограничение: ${entry.allowedModels.size} " + Fmt.plural(
                                entry.allowedModels.size.toLong(),
                                "модель", "модели", "моделей",
                            ),
                            tone = StatusTone.Info,
                        )
                    }
                    if (!entry.autoAccess) {
                        StatusChip(text = "auto запрещён", tone = StatusTone.Warning)
                    }
                }
            }
        } else null,
    )
}

/** Строка «подпись + переключатель» внутри формы. */
@Composable
private fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Добавление ключа: секрет по умолчанию скрыт, как в остальных формах ключей. */
@Composable
private fun AddKeySheet(
    onDismiss: () -> Unit,
    onSave: (key: String, label: String, autoAccess: Boolean) -> Unit,
) {
    var key by remember { mutableStateOf("sk-") }
    var label by remember { mutableStateOf("") }
    var autoAccess by remember { mutableStateOf(true) }
    var showKey by remember { mutableStateOf(false) }

    FormSheet(
        title = "Новый ключ",
        onDismiss = onDismiss,
        confirmText = "Добавить",
        confirmEnabled = key.isNotBlank(),
        onConfirm = { onSave(key, label, autoAccess) },
    ) {
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("Ключ") },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None
            else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        imageVector = if (showKey) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (showKey) "Скрыть ключ" else "Показать ключ",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Метка (необязательно)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SwitchRow(title = "Разрешить доступ к auto", checked = autoAccess) { autoAccess = it }
    }
}

/** Редактирование ключа; копирование и удаление живут здесь, а не в списке. */
@Composable
private fun EditKeySheet(
    entry: ApiKeyEntry,
    onDismiss: () -> Unit,
    onSave: (label: String, enabled: Boolean, models: List<String>, autoAccess: Boolean) -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    var label by remember(entry) { mutableStateOf(entry.label) }
    var enabled by remember(entry) { mutableStateOf(entry.enabled) }
    var autoAccess by remember(entry) { mutableStateOf(entry.autoAccess) }
    var modelsText by remember(entry) { mutableStateOf(entry.allowedModels.joinToString(", ")) }
    val models = modelsText.split(",").map { it.trim() }.filter { it.isNotBlank() }

    FormSheet(
        title = "Ключ: ${entry.displayName()}",
        onDismiss = onDismiss,
        confirmText = "Сохранить",
        onConfirm = { onSave(label, enabled, models, autoAccess) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = maskKey(entry.key),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Скопировать ключ")
            }
        }
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Метка") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SwitchRow(title = "Включён", checked = enabled) { enabled = it }
        SwitchRow(title = "Разрешить auto", checked = autoAccess) { autoAccess = it }
        OutlinedTextField(
            value = modelsText,
            onValueChange = { modelsText = it },
            label = { Text("Разрешённые ID моделей") },
            placeholder = { Text("id1, id2") },
            // Живой итог ввода вместо подсказки: сразу видно, что даёт поле.
            supportingText = {
                Text(
                    text = if (models.isEmpty()) "Доступны все модели"
                    else "Ограничение: ${models.size} " + Fmt.plural(
                        models.size.toLong(), "модель", "модели", "моделей",
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = onDelete) {
            Text("Удалить ключ", color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Полный секрет уходит только в буфер обмена — на экране остаётся маска. */
private fun copyKeyToClipboard(context: Context, key: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("api-key", key))
}
