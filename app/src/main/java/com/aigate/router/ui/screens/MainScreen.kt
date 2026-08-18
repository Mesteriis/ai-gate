package com.aigate.router.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aigate.router.data.credential.CredentialStore
import com.aigate.router.data.model.AiModel
import com.aigate.router.data.model.ModelRouteKey
import com.aigate.router.data.model.Provider
import com.aigate.router.gateway.VirtualModel
import com.aigate.router.ui.design.EmptyState
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.theme.Error
import com.aigate.router.ui.theme.Offline
import com.aigate.router.ui.theme.Online
import com.aigate.router.ui.theme.Warning
import com.aigate.router.ui.viewmodel.GatewayViewModel
import com.aigate.router.ui.viewmodel.pipelineStatus
import com.aigate.router.ui.viewmodel.pipelineRunning
import com.aigate.router.ui.viewmodel.pipelineProgress
import com.aigate.router.ui.viewmodel.pipelineCountdown
import com.aigate.router.service.LiveSession
import com.aigate.router.service.GatewayForegroundService
import com.aigate.router.utils.TranslationManager
import com.aigate.router.utils.tr
import kotlinx.coroutines.delay
import com.aigate.router.utils.localizedText
import com.aigate.router.utils.localizeRuntimeText
import com.aigate.router.utils.localizeGeneratedName

// Контейнер навигации переехал в ui/navigation/AppNavHost.kt (Navigation Compose
// с настоящим back stack). Здесь остаются только экраны.


// ============================================================
// 模型管理页面（带搜索）
// ============================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(viewModel: GatewayViewModel) {
    val languageTick = TranslationManager.currentLanguageFlow.collectAsState().value
    val models by viewModel.models.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val syncingProviderId by viewModel.syncingProviderId.collectAsState()
    val syncResult by viewModel.syncResult.collectAsState()
    val editModelDialogModel by viewModel.showEditModelDialog.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var filterToolCall by remember { mutableStateOf(false) }
    var filterVision by remember { mutableStateOf(false) }
    var showManualAddModel by remember { mutableStateOf(false) }

    // 搜索 + 标签筛选
    val filteredModels = remember(models, searchQuery, languageTick, filterToolCall, filterVision) {
        var fromDb = if (searchQuery.isBlank()) models
        else models.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
            it.modelId.contains(searchQuery, ignoreCase = true) ||
            it.customAlias.contains(searchQuery, ignoreCase = true)
        }
        if (filterToolCall) fromDb = fromDb.filter { com.aigate.router.gateway.ModelCapabilityManager.getCapabilities(it.modelId).first }
        if (filterVision) fromDb = fromDb.filter { com.aigate.router.gateway.ModelCapabilityManager.getCapabilities(it.modelId).second }
        listOfNotNull(
            AiModel(id = -1, modelId = VirtualModel.ID, displayName = "Автопереключение", providerId = 0, isEnabled = true)
        ) + fromDb
    }

    // 按服务商分组（按服务商 orderIndex 排序）
    val groupedModels: List<Pair<String, List<AiModel>>> = remember(filteredModels, providers, languageTick) {
        val providersById = providers.associateBy { it.id }
        val providerOrder = providers.sortedBy { it.orderIndex }.map { it.id }
        filteredModels
            .groupBy { it.providerId }
            .entries
            .sortedBy { (providerId, _) ->
                providerOrder.indexOf(providerId).let { if (it < 0) Int.MAX_VALUE else it }
            }
            .map { (providerId, modelList) ->
                val providerName = providersById[providerId]?.name
                    ?: if (providerId == 0L) "Автопереключение"
                    else "Неизвестный провайдер"
                providerName to modelList
            }
    }

    if (models.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.SmartToy,
            text = "Моделей пока нет",
        )
    } else {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 搜索框 + 标签筛选
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(tr("search_model")) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text(tr("search_hint")) },
                    singleLine = true,
                    trailingIcon = {
                        if (filterToolCall || filterVision || searchQuery.isNotBlank()) {
                            IconButton(onClick = {
                                searchQuery = ""
                                filterToolCall = false
                                filterVision = false
                            }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = filterToolCall,
                    onClick = { filterToolCall = !filterToolCall },
                    label = { Text("Инструменты") },
                    leadingIcon = if (filterToolCall) { { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) } } else null
                )
                FilterChip(
                    selected = filterVision,
                    onClick = { filterVision = !filterVision },
                    label = { Text("Зрение") },
                    leadingIcon = if (filterVision) { { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) } } else null
                )
            }
        }
        // 同步结果提示
            syncResult?.let { result ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                result.startsWith("✅") -> Gateway.colors.successContainer
                                result.startsWith("❌") -> Gateway.colors.errorContainer
                                else -> Gateway.colors.warningContainer
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = localizeRuntimeText(result),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.clearSyncResult() }) {
                                Text(tr("close"))
                            }
                        }
                    }
                }
            }

            // 批量测速按钮 + 手动添加模型按钮
            item {
                Column {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val isBatchTesting by viewModel.batchTesting.collectAsState()
                        Button(
                            onClick = { viewModel.batchTestAllModels() },
                            enabled = !isBatchTesting,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isBatchTesting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Тест скорости...")
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Тест скорости всех моделей")
                            }
                        }
                        // ★★ 手动添加模型按钮 ★★
                        OutlinedButton(
                            onClick = { showManualAddModel = true },
                            enabled = providers.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Вручную")
                        }
                    }
                    // Переключатель автоскрытия ошибок теста
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Скрывать ошибки автоматически", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.weight(1f))
                        val autoClose by viewModel.batchTestingAutoClose.collectAsState()
                        Switch(
                            checked = autoClose,
                            onCheckedChange = { viewModel.setBatchTestingAutoClose(it) }
                        )
                    }
                }
            }
// 按服务商分组显示模型
            groupedModels.forEach { (providerLabel, modelList) ->
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = providerLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                items(modelList, key = { it.id }) { model ->
                    ModelCard(model = model, viewModel = viewModel)
                }
            }

            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }

    // 编辑模型别名对话框
    editModelDialogModel?.let { model ->
        EditModelAliasDialog(
            model = model,
            viewModel = viewModel,
            onDismiss = { viewModel.hideEditModelAlias() }
        )
    }

    // ★★ 手动添加模型对话框 ★★
    if (showManualAddModel) {
        var selectedProviderId by remember { mutableStateOf(providers.firstOrNull()?.id ?: 0L) }
        var newModelId by remember { mutableStateOf("") }
        var newModelName by remember { mutableStateOf("") }
        var addResult by remember { mutableStateOf<String?>(null) }
        var providerExpanded by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showManualAddModel = false },
            title = { Text("Добавить модель", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 选择服务商
                    ExposedDropdownMenuBox(expanded = providerExpanded, onExpandedChange = { providerExpanded = it }) {
                        val selectedProvider = providers.find { it.id == selectedProviderId }
                        OutlinedTextField(
                            value = selectedProvider?.name ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Выберите провайдера") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            singleLine = true
                        )
                        ExposedDropdownMenu(expanded = providerExpanded, onDismissRequest = { providerExpanded = false }) {
                            providers.forEach { provider ->
                                DropdownMenuItem(
                                    text = { Text(provider.name) },
                                    onClick = { selectedProviderId = provider.id; providerExpanded = false }
                                )
                            }
                        }
                    }

                    // 模型ID输入
                    OutlinedTextField(
                        value = newModelId,
                        onValueChange = { newModelId = it },
                        label = { Text("ID модели") },
                        placeholder = { Text("gpt-4o, deepseek-chat, ...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // 显示名称（可选）
                    OutlinedTextField(
                        value = newModelName,
                        onValueChange = { newModelName = it },
                        label = { Text("Отображаемое имя (необязательно)") },
                        placeholder = { Text(newModelId.ifBlank { "Оставьте пустым, чтобы использовать ID модели" }) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // 结果提示
                    addResult?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = if (it.startsWith("✅")) Gateway.colors.success else MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newModelId.isNotBlank()) {
                            viewModel.manualAddModel(selectedProviderId, newModelId.trim(), newModelName.trim()) { success, msg ->
                                addResult = msg
                                if (success) {
                                    newModelId = ""
                                    newModelName = ""
                                }
                            }
                        }
                    },
                    enabled = newModelId.isNotBlank()
                ) {
                    Text("Добавить")
                }
            },
            dismissButton = { TextButton(onClick = { showManualAddModel = false }) { Text("Закрыть") } }
        )
    }
}
@Composable
private fun ModelCard(model: AiModel, viewModel: GatewayViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (model.isEnabled)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 第一行：模型名称 + ID
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = localizeGeneratedName(model.displayName),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (!model.isEnabled) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = Error.copy(alpha = 0.15f),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = "Выключено",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Error,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = "ID: ${model.modelId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 第二行：操作按钮
            if (VirtualModel.isVirtual(model.modelId)) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val autoModelEnabled by viewModel.autoModelEnabled.collectAsState()
                    Switch(checked = autoModelEnabled, onCheckedChange = { viewModel.toggleAutoModel() })
                }
            } else {
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.testModelSpeed(model) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Тест", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Text("Тест", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterVertically))
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = { viewModel.showEditModelAlias(model) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Изменить псевдоним", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Псевдоним", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterVertically))
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = { viewModel.deleteModel(model) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Удалить", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    }
                    Text("Удалить", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.CenterVertically))
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.toggleModelProxy(model) }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = if (model.useProxy) Icons.Default.Sync else Icons.Default.Link,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Switch(checked = model.isEnabled, onCheckedChange = { viewModel.toggleModelEnabled(model) }, modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

// ============================================================
// 编辑模型别名对话框
// ============================================================
@Composable
private fun EditModelAliasDialog(
    model: AiModel,
    viewModel: GatewayViewModel,
    onDismiss: () -> Unit
) {
    var aliasText by remember { mutableStateOf(model.customAlias) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Изменить псевдоним модели") },
        text = {
            Column {
                Text(
                    text = "Модель: " + model.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = aliasText,
                    onValueChange = { aliasText = it },
                    label = { Text("Свой псевдоним") },
                    placeholder = { Text("Введите псевдоним (пусто — имя по умолчанию)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.saveModelAlias(model, aliasText)
                    onDismiss()
                }
            ) {
                Text(tr("save"))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                viewModel.hideEditModelAlias()
                onDismiss()
            }) {
                Text(tr("cancel"))
            }
        }
    )
}
