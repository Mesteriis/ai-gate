package com.aigate.router.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import com.aigate.router.GatewayApplication
import com.aigate.router.auth.CliSessionManager
import com.aigate.router.ui.design.AppCard
import com.aigate.router.ui.design.CardTone
import com.aigate.router.ui.design.FormSheet
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.ProviderAvatar
import com.aigate.router.ui.design.SectionHeader
import com.aigate.router.ui.viewmodel.GatewayViewModel
import kotlinx.coroutines.launch

/**
 * Как подключается провайдер.
 *
 * [OAuth] — вход учётной записью через браузер (Codex/ChatGPT): ни ключей, ни
 * адресов вводить не нужно. [ApiKey] — известный облачный сервис: нужен только
 * ключ, адрес и путь берутся из каталога. [LocalAddress] — локальный сервер:
 * нужен только адрес. [Manual] — всё остальное: адрес, ключ и, если требуется,
 * путь.
 */
private enum class ConnectKind { OAuth, ApiKey, LocalAddress, Manual }

private data class CatalogEntry(
    val title: String,
    val kind: ConnectKind,
    /** Индекс в [GatewayViewModel.PROVIDER_TYPES]; -1 для OAuth-входа. */
    val presetIndex: Int,
    val hint: String,
    /** Какой именно вход выполнять для OAuth-записей каталога. */
    val oauth: OAuthKind? = null,
)

/** Сервисы, куда входим учётной записью через браузер. */
private enum class OAuthKind { Codex, ClaudeCode }

/**
 * Каталог подключения. Эндпоинты и пути в форму не выносятся: для известных
 * сервисов они заданы каталогом, для Codex приходят вместе с сессией. Раньше
 * пользователь должен был сам вписывать адрес API и путь chat-эндпоинта.
 */
@Composable
fun ConnectProviderSheet(
    viewModel: GatewayViewModel,
    onDismiss: () -> Unit,
    onManual: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { GatewayApplication.getInstance().database }
    val presets = GatewayViewModel.PROVIDER_TYPES

    val entries = remember(presets) { buildCatalog(presets) }
    var pending by remember { mutableStateOf<CatalogEntry?>(null) }
    var connecting by remember { mutableStateOf(false) }

    val selected = pending
    if (selected == null) {
        FormSheet(title = "Подключить провайдера", onDismiss = onDismiss) {
            val oauth = entries.filter { it.kind == ConnectKind.OAuth }
            if (oauth.isNotEmpty()) {
                SectionHeader("Вход учётной записью")
                oauth.forEach { entry ->
                    CatalogRow(entry = entry, busy = connecting) {
                        connecting = true
                        scope.launch {
                            val result = when (entry.oauth) {
                                OAuthKind.ClaudeCode -> CliSessionManager.connectClaudeCli(context, db)
                                else -> CliSessionManager.connectCodex(context, db)
                            }
                            connecting = false
                            result.fold(
                                onSuccess = { onDismiss() },
                                onFailure = { viewModel.showMessage(it.message ?: "Вход не удался") },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Gateway.spacing.sm))
            }

            SectionHeader("Облачные сервисы")
            entries.filter { it.kind == ConnectKind.ApiKey }.forEach { entry ->
                CatalogRow(entry = entry) { pending = entry }
            }

            Spacer(Modifier.height(Gateway.spacing.sm))
            SectionHeader("Локальные модели")
            entries.filter { it.kind == ConnectKind.LocalAddress }.forEach { entry ->
                CatalogRow(entry = entry) { pending = entry }
            }

            Spacer(Modifier.height(Gateway.spacing.sm))
            SectionHeader("Другое")
            CatalogRow(
                entry = CatalogEntry("Свой сервис", ConnectKind.Manual, -1, "адрес и ключ вручную")
            ) {
                onDismiss()
                onManual()
            }
        }
        return
    }

    // Второй шаг: минимум полей для выбранного сервиса.
    val preset = presets[selected.presetIndex]
    var name by remember(selected) { mutableStateOf(preset.displayName) }
    var secret by remember(selected) { mutableStateOf("") }
    var address by remember(selected) {
        mutableStateOf(
            if (selected.kind == ConnectKind.LocalAddress) {
                preset.defaultBaseUrl + (preset.defaultPort.takeIf { it.isNotBlank() }?.let { ":$it" } ?: "")
            } else ""
        )
    }
    var showSecret by remember(selected) { mutableStateOf(false) }

    FormSheet(
        title = selected.title,
        onDismiss = { pending = null },
        confirmText = "Подключить",
        confirmEnabled = when (selected.kind) {
            ConnectKind.ApiKey -> secret.isNotBlank()
            ConnectKind.LocalAddress -> address.startsWith("http")
            else -> false
        },
        onConfirm = {
            viewModel.connectFromCatalog(
                presetIndex = selected.presetIndex,
                name = name.ifBlank { preset.displayName },
                apiKey = secret,
                baseUrlOverride = address.takeIf { selected.kind == ConnectKind.LocalAddress },
            )
            onDismiss()
        },
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Название") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        when (selected.kind) {
            ConnectKind.ApiKey -> OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text("API-ключ") },
                singleLine = true,
                visualTransformation = if (showSecret) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showSecret = !showSecret }) {
                        Icon(
                            imageVector = if (showSecret) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (showSecret) "Скрыть ключ" else "Показать ключ",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            ConnectKind.LocalAddress -> OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Адрес сервера") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            else -> Unit
        }
    }
}

@Composable
private fun CatalogRow(entry: CatalogEntry, busy: Boolean = false, onClick: () -> Unit) {
    AppCard(tone = CardTone.Plain, onClick = if (busy) null else onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gateway.spacing.md),
        ) {
            ProviderAvatar(name = entry.title, size = 34.dp)
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    entry.hint,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Каталог из пресетов провайдеров: локальные сервера просят адрес, облачные —
 * ключ. Вход через браузер добавляется отдельной записью: у него нет пресета,
 * потому что адрес и сессия приходят от самого провайдера.
 */
private fun buildCatalog(
    presets: List<GatewayViewModel.ProviderTypePreset>,
): List<CatalogEntry> {
    val out = mutableListOf(
        CatalogEntry(
            title = "Codex (ChatGPT)",
            kind = ConnectKind.OAuth,
            presetIndex = -1,
            hint = "вход через браузер, ключ не нужен",
            oauth = OAuthKind.Codex,
        ),
        CatalogEntry(
            title = "Claude Code",
            kind = ConnectKind.OAuth,
            presetIndex = -1,
            hint = "вход подпиской Claude через браузер",
            oauth = OAuthKind.ClaudeCode,
        ),
    )
    presets.forEachIndexed { index, preset ->
        val isLocal = preset.defaultBaseUrl.contains("localhost") ||
            preset.defaultType.equals("Ollama", ignoreCase = true)
        val isManual = preset.defaultBaseUrl.isBlank()
        if (isManual) return@forEachIndexed
        out += CatalogEntry(
            title = preset.displayName,
            kind = if (isLocal) ConnectKind.LocalAddress else ConnectKind.ApiKey,
            presetIndex = index,
            hint = when {
                isLocal -> "нужен только адрес сервера"
                // У Cursor нет публичного чат-API: это строка расхода, не провайдер моделей.
                preset.defaultType.contains("cursor", ignoreCase = true) ->
                    "ключ админ-API: только расход, без моделей"
                else -> "нужен только API-ключ"
            },
        )
    }
    return out
}
