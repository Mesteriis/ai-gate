package com.aigate.router.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.aigate.router.data.credential.CredentialStore
import com.aigate.router.data.model.Provider
import com.aigate.router.ui.design.EntityCard
import com.aigate.router.ui.design.FormSheet
import com.aigate.router.ui.design.ProviderAvatar
import com.aigate.router.ui.design.StatusTone
import com.aigate.router.ui.viewmodel.GatewayViewModel

/** Варианты пути chat-эндпоинта — один список на всю форму. */
private val apiPathOptions = listOf(
    "/v1/chat/completions", "/v1/messages", "/v1/completions", "/v1/embeddings",
    "/v1/rerank", "/v1/moderations", "/v1/audio/speech", "/v1/images/generations",
    "/v1/videos", "/chat/completions", "/completions", "/generate",
)

/** Поля формы провайдера — общее тело для добавления и редактирования. */
private class ProviderFields(
    val name: String,
    val type: String,
    val baseUrl: String,
    val port: String,
    val chatPath: String,
    val apiKey: String,
)

/**
 * Добавление провайдера. Форма живёт в шите, а не в AlertDialog: семь полей
 * и два выпадающих списка в диалоге не помещались и обрезались.
 */
@Composable
fun AddProviderSheet(viewModel: GatewayViewModel, onDismiss: () -> Unit) {
    val form by viewModel.providerForm.collectAsState()
    val types = GatewayViewModel.PROVIDER_TYPES
    val selectedIndex = remember(form.type) {
        types.indexOfFirst { it.defaultType == form.type }.takeIf { it >= 0 } ?: types.lastIndex
    }

    FormSheet(
        title = "Новый провайдер",
        onDismiss = onDismiss,
        confirmText = "Сохранить",
        confirmEnabled = form.name.isNotBlank() && form.baseUrl.startsWith("http"),
        onConfirm = { viewModel.saveProvider() },
    ) {
        ProviderFormBody(
            fields = ProviderFields(
                name = form.name,
                type = form.type,
                baseUrl = form.baseUrl,
                port = form.port,
                chatPath = form.chatPath,
                apiKey = form.apiKey,
            ),
            typeIndex = selectedIndex,
            onTypeSelected = { viewModel.selectProviderType(it) },
            onFieldChange = { field, value ->
                viewModel.updateFormField(field, value)
                if (field == "baseUrl") {
                    val extracted = viewModel.extractPortFromUrl(value)
                    if (extracted.isNotBlank()) viewModel.updateFormField("port", extracted)
                }
            },
        )
    }
}

/** Редактирование существующего провайдера — та же форма, локальное состояние. */
@Composable
fun EditProviderSheet(
    provider: Provider,
    onDismiss: () -> Unit,
    onSave: (Provider, String) -> Unit,
) {
    val types = GatewayViewModel.PROVIDER_TYPES
    var name by remember { mutableStateOf(provider.name) }
    var type by remember { mutableStateOf(provider.type) }
    var baseUrl by remember { mutableStateOf(provider.baseUrl) }
    var port by remember { mutableStateOf(provider.port) }
    var chatPath by remember { mutableStateOf(provider.chatPath ?: "") }
    val typeIndex = remember(type) {
        types.indexOfFirst { it.defaultType == type }.takeIf { it >= 0 } ?: types.lastIndex
    }
    // Провайдер с OAuth-сессией (Codex, Claude Code) настроен самим провайдером:
    // тип, адрес и путь менять нельзя — иначе форма подставит «Custom» и сломает
    // его. Токен сессии в поле «ключ» тоже не подставляем: показывать его незачем.
    val sessionManaged = remember(provider.type) {
        types.none { it.defaultType == provider.type }
    }
    var apiKey by remember {
        mutableStateOf(if (sessionManaged) "" else CredentialStore.apiKeyForProvider(provider) ?: "")
    }

    FormSheet(
        title = if (sessionManaged) "Имя провайдера" else "Провайдер: ${provider.name}",
        onDismiss = onDismiss,
        confirmText = "Сохранить",
        confirmEnabled = name.isNotBlank() && baseUrl.startsWith("http"),
        onConfirm = {
            onSave(
                provider.copy(
                    name = name,
                    // Тип и адрес сохраняем как есть, если ими владеет провайдер.
                    type = if (sessionManaged) provider.type else type,
                    baseUrl = if (sessionManaged) provider.baseUrl else baseUrl.trimEnd('/'),
                    port = if (sessionManaged) provider.port else port,
                    chatPath = if (sessionManaged) provider.chatPath else chatPath.ifBlank { null },
                ),
                apiKey,
            )
        },
    ) {
        ProviderFormBody(
            fields = ProviderFields(name, type, baseUrl, port, chatPath, apiKey),
            typeIndex = typeIndex,
            sessionManaged = sessionManaged,
            onTypeSelected = { index ->
                val preset = types[index]
                type = preset.defaultType
                baseUrl = preset.defaultBaseUrl
                port = preset.defaultPort
            },
            onFieldChange = { field, value ->
                when (field) {
                    "name" -> name = value
                    "baseUrl" -> {
                        baseUrl = value
                        extractPort(value)?.let { port = it }
                    }
                    "port" -> port = value
                    "chatPath" -> chatPath = value
                    "apiKey" -> apiKey = value
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderFormBody(
    fields: ProviderFields,
    typeIndex: Int,
    onTypeSelected: (Int) -> Unit,
    onFieldChange: (String, String) -> Unit,
    /** Тип, адрес и путь заданы провайдером — показываем их только как справку. */
    sessionManaged: Boolean = false,
) {
    val types = GatewayViewModel.PROVIDER_TYPES
    var typeExpanded by remember { mutableStateOf(false) }
    var pathExpanded by remember { mutableStateOf(false) }
    var showApiKey by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = fields.name,
        onValueChange = { onFieldChange("name", it) },
        label = { Text("Название") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    if (sessionManaged) {
        // Всё остальное настраивает сам провайдер: показываем состояние, а не поля.
        EntityCard(
            title = fields.type,
            subtitle = fields.baseUrl,
            leading = { ProviderAvatar(name = fields.name, type = fields.type) },
            statusText = "настроено провайдером",
            statusTone = StatusTone.Info,
        )
        return
    }

    ExposedDropdownMenuBox(
        expanded = typeExpanded,
        onExpandedChange = { typeExpanded = !typeExpanded },
    ) {
        OutlinedTextField(
            value = types.getOrElse(typeIndex) { types.last() }.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Тип провайдера") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = typeExpanded,
            onDismissRequest = { typeExpanded = false },
        ) {
            types.forEachIndexed { index, preset ->
                DropdownMenuItem(
                    text = { Text(preset.displayName) },
                    onClick = {
                        onTypeSelected(index)
                        typeExpanded = false
                    },
                )
            }
        }
    }

    OutlinedTextField(
        value = fields.baseUrl,
        onValueChange = { onFieldChange("baseUrl", it) },
        label = { Text("Адрес API") },
        placeholder = { Text("http://10.0.0.2:11434") },
        // Итоговый URL — это обратная связь на ввод, а не подсказка-инструкция.
        supportingText = if (fields.baseUrl.startsWith("http")) {
            {
                val path = fields.chatPath.ifBlank { "/v1/chat/completions" }
                Text(
                    text = fields.baseUrl.trimEnd('/') + path,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = fields.port,
        onValueChange = { onFieldChange("port", it) },
        label = { Text("Порт") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    ExposedDropdownMenuBox(
        expanded = pathExpanded,
        onExpandedChange = { pathExpanded = it },
    ) {
        OutlinedTextField(
            value = fields.chatPath,
            onValueChange = { onFieldChange("chatPath", it); pathExpanded = true },
            label = { Text("Путь chat-эндпоинта") },
            placeholder = { Text("/v1/chat/completions") },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = pathExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = pathExpanded,
            onDismissRequest = { pathExpanded = false },
        ) {
            apiPathOptions
                .filter { fields.chatPath.isBlank() || it.contains(fields.chatPath, ignoreCase = true) }
                .forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, style = MaterialTheme.typography.bodyMedium) },
                        onClick = { onFieldChange("chatPath", option); pathExpanded = false },
                    )
                }
        }
    }

    OutlinedTextField(
        value = fields.apiKey,
        onValueChange = { onFieldChange("apiKey", it) },
        label = { Text("API-ключ") },
        singleLine = true,
        visualTransformation = if (showApiKey) VisualTransformation.None
        else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { showApiKey = !showApiKey }) {
                Icon(
                    imageVector = if (showApiKey) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = if (showApiKey) "Скрыть ключ" else "Показать ключ",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Порт из URL вида http://host:port — для автозаполнения поля порта. */
private fun extractPort(url: String): String? =
    Regex("://[^:/]+:(\\d+)").find(url)?.groupValues?.get(1)
