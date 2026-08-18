package com.aigate.router.ui.screens

import com.aigate.router.data.model.routeKey
import com.aigate.router.data.model.RoutingRule

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.Manifest
import android.net.Uri
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aigate.router.service.GatewayForegroundService
import com.aigate.router.ui.theme.Error
import com.aigate.router.ui.theme.Online
import com.aigate.router.ui.theme.Warning
import com.aigate.router.ui.viewmodel.GatewayViewModel
import com.aigate.router.utils.AppLanguage
import com.aigate.router.utils.TranslationManager
import com.aigate.router.utils.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.aigate.router.utils.CrashHandler
import com.aigate.router.utils.localizedText
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.aigate.router.utils.localizeRuntimeText
import com.aigate.router.utils.localizeGeneratedName

/**
 * 数据管理 & 添加服务 统一界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataManagementScreen(
    viewModel: GatewayViewModel = viewModel(factory = GatewayViewModel.Factory())
) {
    TranslationManager.currentLanguageFlow.collectAsState().value
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // ★ 文件存储权限请求器（Android 11+ 专用目录写入需要）
    val storagePermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        scope.launch {
            if (granted) {
                snackbarHostState.showSnackbar("✅ Разрешение на доступ к файлам предоставлено")
            } else {
                snackbarHostState.showSnackbar("⚠️ Доступ запрещён; резервная копия будет сохранена в Downloads через MediaStore")
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    // 复制到临时文件
                    val tempFile = java.io.File(context.cacheDir, "restore_temp.qtbk")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    val result = withContext(Dispatchers.IO) {
                        viewModel.restoreFromFile(tempFile.absolutePath)
                    }
                    result.onSuccess {
                        snackbarHostState.showSnackbar("✅ Данные успешно восстановлены!")
                    }.onFailure { e ->
                        snackbarHostState.showSnackbar("❌ Ошибка восстановления: " + e.message)
                    }
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("❌ Не удалось прочитать файл: " + e.message)
                }
            }
        }
    }

    var showResetConfirm by remember { mutableStateOf(false) }
    var showAddServiceDialog by remember { mutableStateOf(false) }
    var showRoutingRules by remember { mutableStateOf(false) }
    var showDebugLogs by remember { mutableStateOf(false) }
    var showKeyManagement by remember { mutableStateOf(false) }
    var showModelsOverlay by remember { mutableStateOf(false) }
    var showStatsOverlay by remember { mutableStateOf(false) }
    var showAboutOverlay by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            // 标题
            Text("⚙️ Настройки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Провайдеры, ключи, прокси, резервные копии и разделы приложения",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // Разделы: Модели / Статистика / О программе (перенесены сюда из отдельных вкладок)
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column {
                    Row(modifier = Modifier.fillMaxWidth().clickable { showModelsOverlay = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Модели", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("Список моделей и синхронизация", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                    Row(modifier = Modifier.fillMaxWidth().clickable { showStatsOverlay = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BarChart, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Статистика", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("Токены, трафик, скорость", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                    Row(modifier = Modifier.fillMaxWidth().clickable { showAboutOverlay = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("О программе", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("Версия и информация", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // 自启管理 + 隐藏多任务
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BatterySaver, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("🔄 Управление автозапуском", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Гарантирует автозапуск приложения в фоне и защиту от завершения системой.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { viewModel.bindBackgroundPermissions() }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                        Text("🔗 Настроить разрешения автозапуска в один тап")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            try {
                                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = android.net.Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Settings, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Системные настройки")
                        }
                        OutlinedButton(onClick = {
                            try {
                                val intent = Intent("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS")
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.BatterySaver, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Оптимизация батареи")
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    // ★ 隐藏多任务开关
                    var hideFromRecents by remember { mutableStateOf(
                        GatewayForegroundService.getGatewayConfig("hide_from_recents", "false").toBoolean()
                    ) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("👻 Скрыть из недавних", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("Если включено, приложение не отображается в списке недавних задач", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = hideFromRecents,
                            onCheckedChange = { enabled ->
                                hideFromRecents = enabled
                                GatewayForegroundService.saveGatewayConfig("hide_from_recents", enabled.toString())
                                // 运行时从最近任务隐藏
                                try {
                                    val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                                    if (enabled) {
                                        am.appTasks.firstOrNull()?.setExcludeFromRecents(true)
                                    } else {
                                        am.appTasks.firstOrNull()?.setExcludeFromRecents(false)
                                    }
                                } catch (_: Exception) {}
                            }
                        )
                    }
                }
            }
            // 自动备份（含定时开关）+ 一键恢复
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Save, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("💾 Резервное копирование и восстановление", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Ручная копия / автоматическое копирование по расписанию / восстановление из файла в один тап",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))

                    // ★ 定时备份开关 + 时间设置（从持久化读取，避免重建丢失）
                    val autoBackupEnabled = remember { mutableStateOf(
                        GatewayForegroundService.getGatewayConfig("auto_backup_enabled", "false").toBoolean()
                    ) }
                    val autoBackupHour = remember { mutableStateOf(
                        GatewayForegroundService.getGatewayConfig("auto_backup_hour", "3").toIntOrNull() ?: 3
                    ) }
                    val autoBackupMinute = remember { mutableStateOf(
                        GatewayForegroundService.getGatewayConfig("auto_backup_minute", "0").toIntOrNull() ?: 0
                    ) }
                    var showTimePicker by remember { mutableStateOf(false) }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("⏰ Автоматическое копирование по расписанию", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Switch(
                            checked = autoBackupEnabled.value,
                            onCheckedChange = { enabled ->
                                autoBackupEnabled.value = enabled
                                GatewayForegroundService.saveGatewayConfig("auto_backup_enabled", enabled.toString())
                                if (enabled) {
                                    GatewayForegroundService.saveGatewayConfig("auto_backup_hour", autoBackupHour.value.toString())
                                    GatewayForegroundService.saveGatewayConfig("auto_backup_minute", autoBackupMinute.value.toString())
                                    // 调度 WorkManager
                                    com.aigate.router.data.db.AutoBackupWorker.schedule(context, autoBackupHour.value, autoBackupMinute.value)
                                } else {
                                    // 取消 WorkManager
                                    com.aigate.router.data.db.AutoBackupWorker.cancel(context)
                                }
                            }
                        )
                    }
                    if (autoBackupEnabled.value) {
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(onClick = { showTimePicker = true }) {
                            Text("🕐 Время копирования: " + String.format("%02d", autoBackupHour.value) + ":" + String.format("%02d", autoBackupMinute.value), style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    // 时间选择弹窗
                    if (showTimePicker) {
                        AlertDialog(
                            onDismissRequest = { showTimePicker = false },
                            title = { Text("Настроить время автоматического копирования") },
                            text = {
                                Column {
                                    Text("Выберите час и минуту ежедневного автоматического копирования", style = MaterialTheme.typography.bodySmall)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            value = autoBackupHour.value.toString(),
                                            onValueChange = { v -> v.toIntOrNull()?.let { if (it in 0..23) autoBackupHour.value = it } },
                                            label = { Text("Час (0-23)") },
                                            singleLine = true, modifier = Modifier.width(120.dp),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                        )
                                        Text(":", style = MaterialTheme.typography.titleLarge)
                                        OutlinedTextField(
                                            value = autoBackupMinute.value.toString(),
                                            onValueChange = { v -> v.toIntOrNull()?.let { if (it in 0..59) autoBackupMinute.value = it } },
                                            label = { Text("Минута (0-59)") },
                                            singleLine = true, modifier = Modifier.width(120.dp),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                Button(onClick = {
                                    GatewayForegroundService.saveGatewayConfig("auto_backup_hour", autoBackupHour.value.toString())
                                    GatewayForegroundService.saveGatewayConfig("auto_backup_minute", autoBackupMinute.value.toString())
                                    showTimePicker = false
                                }) { Text("OK") }
                            },
                            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Отмена") } }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    // ★ 按钮行：立即备份 | 恢复备份（自动扫 Downloads）
                    var showBackupList by remember { mutableStateOf(false) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                try {
                                    val db = com.aigate.router.data.db.AppDatabase.getInstance(context)
                                    val manager = com.aigate.router.data.db.BackupManager(db)
                                    val dir = manager.getBackupDir()
                                    val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                    val file = java.io.File(dir, "backup_$timeStr.qtbk")
                                    val result = manager.exportToFile(file)
                                    result.onSuccess {
                                        snackbarHostState.showSnackbar("✅ Копирование завершено: " + file.name)
                                    }.onFailure { e -> snackbarHostState.showSnackbar("❌ Ошибка копирования: " + e.message) }
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("❌ Ошибка копирования: " + e.message)
                                }
                            }
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Создать копию сейчас")
                        }
                        OutlinedButton(onClick = {
                            showBackupList = true
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Restore, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Восстановить из копии")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // ★ 导出备份行（复制到剪贴板 / 分享）
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { viewModel.getBackupJson() }
                                result.onSuccess { json ->
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Резервная копия AI-шлюза", json))
                                    snackbarHostState.showSnackbar("✅ JSON резервной копии скопирован в буфер обмена")
                                }.onFailure { e -> snackbarHostState.showSnackbar("❌ Ошибка экспорта: " + e.message) }
                            }
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Копировать в буфер обмена")
                        }
                        OutlinedButton(onClick = {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { viewModel.getBackupJson() }
                                result.onSuccess { json ->
                                    context.startActivity(Intent.createChooser(Intent().apply {
                                        action = Intent.ACTION_SEND; putExtra(Intent.EXTRA_TEXT, json); type = "application/json"
                                    }, "Поделиться копией"))
                                }.onFailure { e -> snackbarHostState.showSnackbar("❌ Ошибка экспорта: " + e.message) }
                            }
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Поделиться")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // ★ 第二行：手动导入（调文件选择器）
                    OutlinedButton(onClick = {
                        filePickerLauncher.launch("*/*")
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.NoteAdd, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("📂 Ручной импорт (выбрать файл .qtbk)")
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    if (autoBackupEnabled.value) {
                        Text("⏱️ Следующее автоматическое копирование: " + String.format("%02d", autoBackupHour.value) + ":" + String.format("%02d", autoBackupMinute.value),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    } else {
Text("💡 Формат копии: .qtbk (GZIP+SHA256+AES-256)")
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    // ★ 测试备份按钮
                    OutlinedButton(onClick = {
                        com.aigate.router.data.db.AutoBackupWorker.scheduleTest(context)
                        scope.launch { snackbarHostState.showSnackbar("🧪 Тестовое копирование запланировано (через 10 секунд)") }
                    }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary)) {
                        Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("🧪 Тестовое копирование (через 10 секунд)")
                    }
                    Spacer(modifier = Modifier.height(4.dp))

                    // 扫描并列出备份文件弹窗
                    if (showBackupList) {
                        var backupFiles by remember { mutableStateOf<List<com.aigate.router.data.db.BackupManager.BackupMetadata>>(emptyList()) }
                        var isLoading by remember { mutableStateOf(true) }
                        LaunchedEffect(showBackupList) {
                            withContext(Dispatchers.IO) {
                                val db = com.aigate.router.data.db.AppDatabase.getInstance(context)
                                val manager = com.aigate.router.data.db.BackupManager(db)
                                backupFiles = manager.getBackupHistory()
                                isLoading = false
                            }
                        }
                        AlertDialog(
                            onDismissRequest = { showBackupList = false },
                            title = { Text("Выберите файл копии для восстановления", fontWeight = FontWeight.Bold) },
                            text = {
                                if (isLoading) {
                                    Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator()
                                    }
                                } else if (backupFiles.isEmpty()) {
                                    Text("Файлы копий не найдены\nСначала нажмите «Создать копию сейчас», чтобы создать копию", style = MaterialTheme.typography.bodyMedium)
                                } else {
                                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                                        items(backupFiles) { meta ->
                                            Card(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable {
                                                    scope.launch {
                                                        try {
                                                            val result = withContext(Dispatchers.IO) {
                                                                val db = com.aigate.router.data.db.AppDatabase.getInstance(context)
                                                                val manager = com.aigate.router.data.db.BackupManager(db)
                                                                manager.importFromFile(java.io.File(meta.filePath))
                                                            }
                                                            result.onSuccess {
                                                                snackbarHostState.showSnackbar("✅ Данные успешно восстановлены!")
                                                                showBackupList = false
                                                            }.onFailure { e ->
                                                                snackbarHostState.showSnackbar("❌ Ошибка восстановления: " + e.message)
                                                            }
                                                        } catch (e: Exception) {
                                                            snackbarHostState.showSnackbar("❌ Не удалось прочитать копию: " + e.message)
                                                        }
                                                    }
                                                },
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                            ) {
                                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                    Spacer(Modifier.width(8.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(meta.fileName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        Text(meta.sizeReadable + " - " + meta.createdAtReadable, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                    Icon(Icons.Default.RestorePage, contentDescription = "Восстановить", tint = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = { TextButton(onClick = { showBackupList = false }) { Text("Закрыть") } }
                        )
                    }
                }
            }

            // 路由规则管理
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Route, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("🔀 Правила маршрутизации", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Пользовательские правила маршрутизации: перенаправление/блокировка запросов по пути, имени модели, API-ключу и т. д.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showRoutingRules = true }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Rule, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Управление правилами")
                        }
                        OutlinedButton(onClick = {
                            scope.launch {
                                val rules = withContext(Dispatchers.IO) { viewModel.getAllRoutingRules() }
                                snackbarHostState.showSnackbar("Загружено " + rules.size + " правил")
                            }
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Обновить")
                        }
                    }
                }
            }

            // ★ 多语言设置卡片
            var showLangSelector by remember { mutableStateOf(false) }
            val currentLang by TranslationManager.currentLanguageFlow.collectAsState()
            val autoDetect by TranslationManager.autoDetectFlow.collectAsState()
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Translate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("🌐 " + tr("language_settings"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    // 自动跟随系统开关
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tr("auto_follow_system"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                if (autoDetect) "Текущий: " + currentLang.displayName
                                else "Вручную: " + currentLang.displayName,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoDetect,
                            onCheckedChange = { enabled ->
                                TranslationManager.setAutoDetect(enabled, context)
                                showLangSelector = !enabled
                            }
                        )
                    }

                    if (!autoDetect) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = { showLangSelector = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Language, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(currentLang.displayName)
                        }
                    }
                }
            }

            // 语言选择弹窗
            if (showLangSelector) {
                AlertDialog(
                    onDismissRequest = { showLangSelector = false },
                    title = { Text(tr("manual_select")) },
                    text = {
                        LazyColumn(modifier = Modifier.heightIn(max = 350.dp)) {
                            items(AppLanguage.entries) { lang ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable {
                                        TranslationManager.setLanguage(lang, context)
                                        showLangSelector = false
                                    },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (lang == currentLang)
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                        else MaterialTheme.colorScheme.surface
                                    )
                                ) {
                                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(lang.displayName,
                                            modifier = Modifier.weight(1f),
                                            fontWeight = if (lang == currentLang) FontWeight.Bold else FontWeight.Normal
                                        )
                                        if (lang == currentLang) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showLangSelector = false }) { Text("Закрыть") } }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth().clickable { showKeyManagement = true }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("🔑 Управление API-ключами", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("Управление ключами доступа. Локальные запросы не требуют ключа. Каждый ключ отдельно управляет доступом к моделям", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // ★ LAN-режим: доступ из локальной сети по паролю-токену
            var lanEnabled by remember { mutableStateOf(GatewayForegroundService.getLanModeEnabled()) }
            var lanToken by remember { mutableStateOf(GatewayForegroundService.getLanToken()) }
            var lanTokenVisible by remember { mutableStateOf(false) }
            var lanSavedHint by remember { mutableStateOf(false) }
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lan, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("🌐 LAN-режим", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("По умолчанию шлюз слушает только 127.0.0.1. Включите доступ из локальной сети по паролю.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = lanEnabled, onCheckedChange = { lanEnabled = it; lanSavedHint = false })
                    }
                    if (lanEnabled) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = lanToken,
                            onValueChange = { lanToken = it; lanSavedHint = false },
                            label = { Text("Пароль (Bearer-токен для LAN)") },
                            singleLine = true,
                            visualTransformation = if (lanTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { lanTokenVisible = !lanTokenVisible }) {
                                    Icon(if (lanTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Каждый запрос не с 127.0.0.1 обязан прислать этот пароль как «Authorization: Bearer <пароль>».", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            GatewayForegroundService.setLanModeEnabled(lanEnabled && lanToken.isNotBlank())
                            GatewayForegroundService.setLanToken(lanToken.trim())
                            lanSavedHint = true
                        },
                        enabled = !lanEnabled || lanToken.isNotBlank()
                    ) { Text("Сохранить и применить") }
                    if (lanSavedHint) {
                        Spacer(Modifier.height(4.dp))
                        Text("Сохранено. Перезапустите шлюз, чтобы применить адрес прослушивания.", style = MaterialTheme.typography.bodySmall, color = Online)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // ★ Debug 抓包模式
            val debugMode by viewModel.debugMode.collectAsState()
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("🔍 Перехват пакетов шлюза", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Если включено, все запросы/ответы шлюза записываются в память; можно просмотреть последние 20 записей, включая трафик ввода/вывода в реальном времени", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { viewModel.toggleDebugMode() }, colors = ButtonDefaults.buttonColors(
                            containerColor = if (debugMode) Error else Online)) {
                            Text(if (debugMode) "⏹ Остановить перехват" else "▶️ Начать перехват")
                        }
                        OutlinedButton(onClick = { showDebugLogs = true }) {
                            Icon(Icons.Default.List, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Просмотр логов")
                        }
                    }
                    if (debugMode) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("🟢 Перехват выполняется...", style = MaterialTheme.typography.labelSmall, color = Online)
                    }
                }
            }

            // ★★ 抓包日志页面（全屏覆盖）★★

            Spacer(modifier = Modifier.height(16.dp))

            // 重置数据
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Error.copy(alpha = 0.08f))) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null, tint = Error)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Сбросить все данные", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Error)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("⚠️ Это удалит всех провайдеров, модели, историю чатов и статистику токенов. Отменить нельзя!",
                        style = MaterialTheme.typography.bodySmall, color = Error.copy(alpha = 0.8f))
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(onClick = { showResetConfirm = true }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Error)) {
                        Icon(Icons.Default.Warning, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Сбросить все данные")
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // 添加服务
            Text("🔌 Добавить сервис", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Поддерживает OpenAI-совместимые API, автоматически определяет порт и получает список моделей",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))

            ServiceTemplateCard("Ollama (локально)", "http://localhost:11434") { viewModel.showAddProvider() }
            ServiceTemplateCard("OpenAI", "https://api.openai.com") { viewModel.showAddProvider() }
            ServiceTemplateCard("Свой OpenAI-совместимый", "Введите любой адрес, совместимый с форматом OpenAI API") { showAddServiceDialog = true }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
// ★★ 抓包日志全屏覆盖（脱离 verticalScroll）★★
        if (showDebugLogs) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("🔍 Логи перехвата пакетов шлюза", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { showDebugLogs = false }) { Text("✕ Закрыть") }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    var filterText by remember { mutableStateOf("") }
                    var statusFilter by remember { mutableStateOf<String?>(null) }

                    // ★ 筛选后的记录列表
                    val filteredRecords = remember(filterText, statusFilter) {
                        var list = com.aigate.router.capture.PacketCapture.records.toList()
                        if (statusFilter == "200") list = list.filter { it.response?.httpStatus == 200 }
                        else if (statusFilter == "ERR") list = list.filter { it.response?.httpStatus ?: 0 >= 400 || it.failover != null }
                        if (filterText.isNotBlank()) list = list.filter {
                            it.summary.contains(filterText, ignoreCase = true) ||
                            it.outbound?.body?.contains(filterText, ignoreCase = true) == true ||
                            it.inbound?.body?.contains(filterText, ignoreCase = true) == true
                        }
                        list
                    }

                    val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = filterText, onValueChange = { filterText = it },
                            placeholder = { Text("🔍 Поиск") }, singleLine = true, modifier = Modifier.weight(2f),
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                        TextButton(onClick = { statusFilter = null }) { Text(if (statusFilter == null) "Все" else "Все") }
                        TextButton(onClick = { statusFilter = "200" }) { Text("200") }
                        TextButton(onClick = { statusFilter = "ERR" }) { Text("ERR") }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredRecords) { record ->
                            val isError = record.response?.httpStatus ?: 0 >= 400
                            var showDetail by remember(record.id) { mutableStateOf(false) }
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { showDetail = true },
                                colors = CardDefaults.cardColors(containerColor = if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.05f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(text = if (isError) "❌" else if (record.failover != null) "🔄" else "✅", style = MaterialTheme.typography.bodyMedium)
                                        Text(text = record.summary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = timeFmt.format(record.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                                        record.response?.let { resp -> if (resp.promptTokens > 0) Text(text = "↑${resp.promptTokens} ↓${resp.completionTokens}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) }
                                        Spacer(modifier = Modifier.weight(1f))
                                        Text(text = record.outbound?.modelId ?: "?", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                                    }
                                }
                            }
                            if (showDetail) {
                                AlertDialog(
                                    onDismissRequest = { showDetail = false },
                                    title = { Text("📦 Детали перехвата #" + record.id) },
                                    text = {
                                        LazyColumn(modifier = Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            record.inbound?.let { inbound -> item {
                                                Text("📥 Входящий", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small) {
                                                    Text(buildString {
                                                        appendLine("Метод: " + "${inbound.method} ${inbound.path}")
                                                        appendLine("Заголовки: " + inbound.headers)
                                                        appendLine("--- Тело запроса (" + "${inbound.bodySize}B) ---")
                                                        appendLine(inbound.body)
                                                    }, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(8.dp))
                                                }
                                            }}
                                            record.outbound?.let { outbound -> item {
                                                Text("📤 Исходящий", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                                                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small) {
                                                    Text(buildString {
                                                        appendLine("URL: ${outbound.targetUrl}")
                                                        appendLine("Модель: " + outbound.modelId)
                                                        appendLine("--- Тело запроса (" + "${outbound.bodySize}B) ---")
                                                        appendLine(outbound.body)
                                                    }, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(8.dp))
                                                }
                                            }}
                                            record.response?.let { resp -> item {
                                                Text("📥 Ответ", fontWeight = FontWeight.Bold, color = if (resp.httpStatus >= 500) MaterialTheme.colorScheme.error else if (resp.httpStatus >= 400) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                                                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small) {
                                                    Text(buildString {
                                                        appendLine("Статус: " + "${resp.httpStatus} | ${resp.elapsedMs}ms")
                                                        appendLine("Tokens: ↑${resp.promptTokens} ↓${resp.completionTokens}")
                                                        appendLine("--- Тело ответа (" + "${resp.bodySize}B) ---")
                                                        appendLine(resp.body)
                                                    }, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(8.dp))
                                                }
                                            }}
                                            record.failover?.let { failover -> item {
                                                Text("🔄 Переключение при сбое", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                                Surface(color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f), shape = MaterialTheme.shapes.small) {
                                                    Text(buildString {
                                                        failover.attempts.forEach { attempt ->
                                                            appendLine("[${attempt.index}] ${attempt.modelId}: ${attempt.error} (${attempt.elapsedMs}ms)")
                                                        }
                                                    }, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(8.dp))
                                                }
                                            }}
                                        }
                                    },
                                    confirmButton = {
                                        val ctx = LocalContext.current
                                        Button(onClick = {
                                            val detailText = buildString {
                                                appendLine("📦 Детали перехвата #" + record.id)
                                                appendLine("Время: " + timeFmt.format(record.timestamp))
                                                record.inbound?.let { inbound ->
                                                    appendLine("\\n📥 Входящий")
                                                    appendLine("Метод: " + "${inbound.method} ${inbound.path}")
                                                    appendLine("Заголовки: " + inbound.headers)
                                                    appendLine("--- Тело запроса (" + "${inbound.bodySize}B) ---")
                                                    appendLine(inbound.body)
                                                }
                                                record.outbound?.let { outbound ->
                                                    appendLine("\\n📤 Исходящий")
                                                    appendLine("URL: ${outbound.targetUrl}")
                                                    appendLine("Модель: " + outbound.modelId)
                                                    appendLine("--- Тело запроса (" + "${outbound.bodySize}B) ---")
                                                    appendLine(outbound.body)
                                                }
                                                record.response?.let { resp ->
                                                    appendLine("\\n📥 Ответ")
                                                    appendLine("Статус: " + "${resp.httpStatus} | ${resp.elapsedMs}ms")
                                                    appendLine("Tokens: ↑${resp.promptTokens} ↓${resp.completionTokens}")
                                                    appendLine("--- Тело ответа (" + "${resp.bodySize}B) ---")
                                                    appendLine(resp.body)
                                                }
                                                record.failover?.let { failover ->
                                                    appendLine("\\n🔄 Переключение при сбое")
                                                    failover.attempts.forEach { attempt ->
                                                        appendLine("[${attempt.index}] ${attempt.modelId}: ${attempt.error} (${attempt.elapsedMs}ms)")
                                                    }
                                                }
                                            }
                                            val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Детали перехвата", detailText))
                                        }) { Text("📋 Копировать") }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        } // end showDebugLogs
    } // end Box
    // 密钥管理全屏覆盖
    if (showKeyManagement) {
        Box(modifier = Modifier.fillMaxSize()) {
            KeyManagementScreen(onDismiss = { showKeyManagement = false })
        }
    }

    // 路由规则管理弹窗
    if (showRoutingRules) {
        RoutingRulesDialog(viewModel = viewModel, onDismiss = { showRoutingRules = false })
    }

    // Разделы, перенесённые из отдельных вкладок → полноэкранные оверлеи
    if (showModelsOverlay) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showModelsOverlay = false }) { Icon(Icons.Default.Close, contentDescription = null) }
                    Text("Модели", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                ModelsScreen(viewModel)
            }
        }
    }
    if (showStatsOverlay) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showStatsOverlay = false }) { Icon(Icons.Default.Close, contentDescription = null) }
                    Text("Статистика", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                StatsScreen(viewModel)
            }
        }
    }
    if (showAboutOverlay) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showAboutOverlay = false }) { Icon(Icons.Default.Close, contentDescription = null) }
                    Text("О программе", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                AboutScreen(viewModel)
            }
        }
    }

    // 代理管理弹窗（AboutScreen 连点触发）
    val showProxyDialog by viewModel.showProxyConfigDialog.collectAsState()
    if (showProxyDialog) {
        ProxyManagementDialog(viewModel = viewModel, onDismiss = { viewModel.hideProxyConfig() })
    }

    // 添加代理弹窗（从 DataManagementScreen 直接添加）
    var showAddProxyDialog by remember { mutableStateOf(false) }
    if (showAddProxyDialog) {
        AddEditProxyDialog(
            title = "Добавить прокси", viewModel = viewModel,
            onDismiss = { showAddProxyDialog = false },
            onConfirm = { profile -> viewModel.addProxy(profile); showAddProxyDialog = false }
        )
    }

    // 重置确认弹窗（需输入"确认重置"）
    if (showResetConfirm) {
        var confirmInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("⚠️ Подтверждение опасной операции", fontWeight = FontWeight.Bold, color = Error) },
            text = {
                Column {
                    Text("Эта операция безвозвратно удалит все данные, включая:\\n• Все настройки провайдеров\\n• Все списки AI-моделей\\n• Всю историю чатов и диалогов\\n• Всю статистику использования токенов\\n\\nОтменить это действие нельзя!", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Введите «Подтвердить сброс», чтобы продолжить:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmInput,
                        onValueChange = { confirmInput = it },
                        label = { Text("Подтвердить сброс") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.resetAllData(); showResetConfirm = false },
                    enabled = confirmInput == "Подтвердить сброс",
                    colors = ButtonDefaults.buttonColors(containerColor = Error)
                ) {
                    Text("Удалить навсегда", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("Отмена") } }
        )
    }

    // 智能添加服务弹窗
    if (showAddServiceDialog) {
        SmartAddServiceDialog(viewModel = viewModel, onDismiss = { showAddServiceDialog = false },
            onSuccess = { showAddServiceDialog = false; scope.launch { snackbarHostState.showSnackbar("✅ Провайдер успешно добавлен!") } })
    }
}

// ============================================================
// 服务模板卡片
// ============================================================
@Composable
private fun ServiceTemplateCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 格式化文件大小 */
private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> String.format("%.1fMB", bytes.toDouble() / (1024 * 1024))
}

// ============================================================
// 智能添加服务弹窗
// ============================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmartAddServiceDialog(viewModel: GatewayViewModel, onDismiss: () -> Unit, onSuccess: () -> Unit) {
    val form by viewModel.providerForm.collectAsState()
    var showApiKey by remember { mutableStateOf(false) }
    val detectedPort = remember(form.baseUrl) { viewModel.extractPortFromUrl(form.baseUrl) }
    LaunchedEffect(detectedPort) { if (detectedPort.isNotBlank() && form.port != detectedPort) viewModel.updateFormField("port", detectedPort) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить сервис (OpenAI-совместимый)", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = form.name, onValueChange = { viewModel.updateFormField("name", it) },
                    label = { Text("Название провайдера") }, placeholder = { Text("Например: Мой Ollama") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = form.baseUrl, onValueChange = {
                    viewModel.updateFormField("baseUrl", it)
                    val port = viewModel.extractPortFromUrl(it)
                    if (port.isNotBlank()) viewModel.updateFormField("port", port)
                }, label = { Text("Адрес API (Base URL)") }, placeholder = { Text("http://192.168.1.100:11434") },
                    supportingText = { if (detectedPort.isNotBlank()) Text("Определён порт: " + detectedPort, color = Online) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = form.port, onValueChange = { viewModel.updateFormField("port", it) },
                    label = { Text("Порт (необязательно)") }, placeholder = { Text("напр. 11434, 8080") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = form.apiKey, onValueChange = { viewModel.updateFormField("apiKey", it) },
                        label = { Text("API-ключ (необязательно)") }, placeholder = { Text("sk-...") }, singleLine = true,
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(if (showApiKey) Icons.Default.Visibility else Icons.Default.VisibilityOff, contentDescription = null) } },
                        modifier = Modifier.weight(1f))
                }

                // ★★★ API 路径选择（自动补全）★★★
                val apiPathOptions = listOf("/v1/chat/completions", "/v1/messages", "/v1/completions", "/v1/embeddings", "/v1/rerank", "/v1/moderations", "/v1/audio/speech", "/v1/images/generations", "/v1/videos", "/chat/completions", "/completions", "/generate")
                var chatPathExpanded by remember { mutableStateOf(false) }
                var chatPathText by remember(form.chatPath) { mutableStateOf(form.chatPath) }
                ExposedDropdownMenuBox(expanded = chatPathExpanded, onExpandedChange = { chatPathExpanded = it }) {
                    OutlinedTextField(
                        value = chatPathText,
                        onValueChange = { v -> chatPathText = v; viewModel.updateFormField("chatPath", v); chatPathExpanded = true },
                        label = { Text("Путь к API чата (пусто = добавить автоматически)") },
                        placeholder = { Text("/v1/chat/completions") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = chatPathExpanded) }
                    )
                    ExposedDropdownMenu(expanded = chatPathExpanded, onDismissRequest = { chatPathExpanded = false }) {
                        val filtered = apiPathOptions.filter { chatPathText.isBlank() || it.contains(chatPathText, ignoreCase = true) }
                        filtered.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option, style = MaterialTheme.typography.bodyMedium) },
                                onClick = { chatPathText = option; viewModel.updateFormField("chatPath", option); chatPathExpanded = false }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text("💡 Введите /c для автодополнения /v1/chat/completions, /m — для /v1/messages и т. д.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // ★★ 最终URL预览 ★★
                val previewBase = form.baseUrl.trimEnd('/')
                val previewPath = if (chatPathText.isBlank()) "/v1/chat/completions" else chatPathText
                val finalPreview = if (previewBase.startsWith("http")) "$previewBase$previewPath" else ""
                if (finalPreview.isNotBlank()) {
                    Text(
                        text = "${"Фактический адрес запроса"}: $finalPreview",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // ★★★ 模型列表接口路径（自动补全）★★★
                val apiPathOptions2 = listOf("/v1/models", "/api/tags", "/v1beta/models", "/models")
                var apiPathExpanded by remember { mutableStateOf(false) }
                var apiPathText by remember(form.apiPath) { mutableStateOf(form.apiPath) }
                ExposedDropdownMenuBox(expanded = apiPathExpanded, onExpandedChange = { apiPathExpanded = it }) {
                    OutlinedTextField(
                        value = apiPathText,
                        onValueChange = { v -> apiPathText = v; viewModel.updateFormField("apiPath", v); apiPathExpanded = true },
                        label = { Text("Путь к API списка моделей") },
                        placeholder = { Text("/v1/models") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = apiPathExpanded) }
                    )
                    ExposedDropdownMenu(expanded = apiPathExpanded, onDismissRequest = { apiPathExpanded = false }) {
                        val filtered = apiPathOptions2.filter { apiPathText.isBlank() || it.contains(apiPathText, ignoreCase = true) }
                        filtered.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option, style = MaterialTheme.typography.bodyMedium) },
                                onClick = { apiPathText = option; viewModel.updateFormField("apiPath", option); apiPathExpanded = false }
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { isTesting = true; testResult = null; viewModel.fetchAvailableModels(form.baseUrl, form.apiKey.ifBlank { null }) },
                        enabled = form.baseUrl.isNotBlank() && !isTesting, modifier = Modifier.weight(1f)) {
                        if (isTesting) { CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(modifier = Modifier.width(4.dp)); Text("Определение...") }
                        else { Icon(Icons.Default.NetworkCheck, contentDescription = null); Spacer(modifier = Modifier.width(4.dp)); Text("Определить список моделей") }
                    }
                }
                val syncResult by viewModel.syncResult.collectAsState()
                LaunchedEffect(syncResult) { isTesting = false; testResult = syncResult }
                if (testResult != null) {
                    Card(colors = CardDefaults.cardColors(containerColor = if (testResult!!.startsWith("✅")) Online.copy(alpha = 0.15f) else if (testResult!!.startsWith("❌")) Error.copy(alpha = 0.15f) else Warning.copy(alpha = 0.15f))) {
                        Text(localizeRuntimeText(testResult!!), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { viewModel.saveProvider(); onSuccess() }, enabled = form.name.isNotBlank() && form.baseUrl.isNotBlank()) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

// ============================================================
// 代理管理弹窗
// ============================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyManagementDialog(viewModel: GatewayViewModel, onDismiss: () -> Unit) {
    val proxyProfiles by viewModel.proxyProfiles.collectAsState()
    val proxyEnabled by viewModel.proxyEnabled.collectAsState()
    val activeProxyId by viewModel.activeProxyId.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<GatewayViewModel.ProxyProfile?>(null) }
    var showSubscriptionDialog by remember { mutableStateOf(false) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var pasteLinkText by remember { mutableStateOf("") }

    // 剪贴板检测
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            if (cm.hasPrimaryClip()) {
                val clipText = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                val detected = viewModel.detectClipboardLink(clipText)
                if (detected != null) {
                    pasteLinkText = detected
                    showPasteDialog = true
                }
            }
        } catch (_: Exception) { }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("⚙️ Управление прокси", fontWeight = FontWeight.Bold)
                Text(if (proxyEnabled) "🟢 Активно" else "🔴 Не активно", style = MaterialTheme.typography.bodySmall, color = if (proxyEnabled) Online else Error)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 订阅按钮（放在内容区确保可点击）
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showSubscriptionDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("📡 Подписка в один тап", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (proxyProfiles.isEmpty()) {
                    Text("Прокси ещё не настроены. Нажмите кнопку ниже, чтобы добавить", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    proxyProfiles.forEach { profile ->
                        ProxyProfileCard(profile = profile, isActive = profile.id == activeProxyId && proxyEnabled,
                            onToggleEnable = { viewModel.toggleProxyEnabled(profile) }, onEdit = { editingProfile = profile },
                            onDelete = { viewModel.deleteProxy(profile) }, onTestSpeed = { viewModel.testProxySpeed(profile) })
                        if (profile != proxyProfiles.last()) HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { showAddDialog = true }) { Icon(Icons.Default.Add, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Добавить прокси") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )

    if (showAddDialog) {
        AddEditProxyDialog(title = "Добавить прокси", viewModel = viewModel, onDismiss = { showAddDialog = false },
            onConfirm = { profile -> viewModel.addProxy(profile); showAddDialog = false })
    }
    editingProfile?.let { profile ->
        AddEditProxyDialog(title = "Изменить прокси", initialProfile = profile, viewModel = viewModel,
            onDismiss = { editingProfile = null }, onConfirm = { updated -> viewModel.updateProxy(updated); editingProfile = null })
    }
    // 订阅弹窗
    if (showSubscriptionDialog) {
        var subUrl by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSubscriptionDialog = false },
            title = { Text("📡 Подписка в один тап", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Введите адрес подписки для автоматической загрузки и массового импорта узлов", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = subUrl, onValueChange = { subUrl = it },
                        label = { Text("URL подписки") }, placeholder = { Text("https://example.com/sub?token=...") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.importSubscription(subUrl); showSubscriptionDialog = false }) { Text("Импортировать") }
            },
            dismissButton = { TextButton(onClick = { showSubscriptionDialog = false }) { Text("Отмена") } }
        )
    }
    // 剪贴板检测弹窗
    if (showPasteDialog) {
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text("📋 Обнаружена ссылка на прокси", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("В буфере обмена обнаружена ссылка на прокси/подписку:", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                        Text(pasteLinkText.take(80) + if (pasteLinkText.length > 80) "..." else "",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Разобрать и импортировать автоматически?", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (pasteLinkText.startsWith("http")) {
                        viewModel.importSubscription(pasteLinkText)
                    } else {
                        viewModel.addProxyFromLink(pasteLinkText)
                    }
                    showPasteDialog = false
                }) { Text("Импортировать сейчас") }
            },
            dismissButton = { TextButton(onClick = { showPasteDialog = false }) { Text("Игнорировать") } }
        )
    }
}

// ============================================================
// 代理卡片
// ============================================================
@Composable
private fun ProxyProfileCard(
    profile: GatewayViewModel.ProxyProfile, isActive: Boolean,
    onToggleEnable: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onTestSpeed: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (isActive) Online.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val icon = when (profile.type.uppercase()) { "HTTP", "HTTPS" -> Icons.Default.Language; "SOCKS5", "SOCKS" -> Icons.Default.Shield; else -> Icons.Default.Dns }
                    Icon(icon, contentDescription = null, tint = if (profile.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(localizeGeneratedName(profile.name), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("${profile.type} · ${profile.host}:${profile.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (profile.username.isNotBlank()) Text("👤 ${profile.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Switch(checked = profile.enabled, onCheckedChange = { onToggleEnable() })
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onTestSpeed, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Speed, contentDescription = "Тест скорости", modifier = Modifier.size(18.dp)) }
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Edit, contentDescription = "Изменить", modifier = Modifier.size(18.dp)) }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = Error, modifier = Modifier.size(18.dp)) }
                if (isActive) {
                    Surface(color = Online.copy(alpha = 0.15f), shape = MaterialTheme.shapes.extraSmall) {
                        Text("Активно", style = MaterialTheme.typography.labelSmall, color = Online, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
            }
        }
    }
}

// ============================================================
// 添加/编辑代理弹窗
// ============================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEditProxyDialog(
    title: String, initialProfile: GatewayViewModel.ProxyProfile? = null,
    viewModel: GatewayViewModel, onDismiss: () -> Unit, onConfirm: (GatewayViewModel.ProxyProfile) -> Unit
) {
    var name by remember { mutableStateOf(initialProfile?.name ?: "") }
    var type by remember { mutableStateOf(initialProfile?.type ?: "HTTP") }
    var host by remember { mutableStateOf(initialProfile?.host ?: "") }
    var port by remember { mutableStateOf((initialProfile?.port ?: 1080).toString()) }
    var username by remember { mutableStateOf(initialProfile?.username ?: "") }
    var password by remember { mutableStateOf(initialProfile?.password ?: "") }
    var showPassword by remember { mutableStateOf(false) }
    var typeExpanded by remember { mutableStateOf(false) }
    val typeOptions = listOf("HTTP", "HTTPS", "SOCKS5", "SOCKS", "VMESS", "SS", "VLESS", "Trojan", "Hysteria2")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Название прокси") }, placeholder = { Text("Например: узел прокси 1") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                // 代理类型选择器
                ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = !typeExpanded }) {
                    OutlinedTextField(value = type, onValueChange = {}, readOnly = true, label = { Text("Тип прокси") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) }, modifier = Modifier.fillMaxWidth().menuAnchor())
                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        typeOptions.forEach { option ->
                            DropdownMenuItem(text = { Text(option) }, onClick = { type = option; typeExpanded = false
                                if (port == "1080" || port == "7890") port = if (option.startsWith("SOCKS")) "1080" else "7890" })
                        }
                    }
                }

                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Адрес прокси-сервера") }, placeholder = { Text("Например: 192.168.1.100") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = port, onValueChange = { port = it.filter { c -> c.isDigit() } }, label = { Text("Порт") },
                    placeholder = { Text(if (type.startsWith("SOCKS")) "1080" else "7890") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())

                HorizontalDivider()

                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Имя пользователя (необязательно)") }, placeholder = { Text("Имя пользователя для аутентификации SOCKS5/HTTP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Пароль (необязательно)") }, placeholder = { Text("Пароль для аутентификации SOCKS5/HTTP") }, singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) { Icon(if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff, contentDescription = null) } },
                    modifier = Modifier.fillMaxWidth())

                if (type.startsWith("SOCKS") && (username.isNotBlank() || password.isNotBlank())) {
                    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), shape = MaterialTheme.shapes.small) {
                        Text("✅ SOCKS5 будет использовать аутентификацию по имени/паролю (RFC 1929)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(GatewayViewModel.ProxyProfile(
                    id = initialProfile?.id ?: java.util.UUID.randomUUID().toString().take(8),
                    name = name.ifBlank { "Безымянный прокси" }, type = type, host = host, port = port.toIntOrNull() ?: 1080,
                    username = username, password = password, enabled = initialProfile?.enabled ?: false))
            }, enabled = host.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

// ============================================================
// 关于我们页面
// ============================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(viewModel: GatewayViewModel = viewModel(factory = GatewayViewModel.Factory())) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val gatewayPort by viewModel.gatewayPort.collectAsState(initial = 8889)
    val proxyEnabled by viewModel.proxyEnabled.collectAsState(initial = false)
    val proxyProfiles by viewModel.proxyProfiles.collectAsState()
    val activeProxyId by viewModel.activeProxyId.collectAsState()
    var clickCount by remember { mutableIntStateOf(0) }
    var lastClickTime by remember { mutableLongStateOf(0L) }

    // 动态获取版本号
    val appVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.versionName}" }
        } catch (_: Exception) { "1.8.0" }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("ℹ️ О нас", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("AiGate v$appVersion — " + "Локальный AI-шлюз", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("📱 Информация о приложении", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Версия: v" + appVersion, style = MaterialTheme.typography.bodyMedium)
                    Text("Идея и вдохновение: QiTong AI Gateway (Apache-2.0)", style = MaterialTheme.typography.bodyMedium)
                    Text("Протокол: OpenAI-совместимый API", style = MaterialTheme.typography.bodyMedium)
                }
            }

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("⚙️ Текущая конфигурация", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Порт шлюза:", style = MaterialTheme.typography.bodyMedium)
                        Text(gatewayPort.toString(), style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Ускорение через прокси:", style = MaterialTheme.typography.bodyMedium)
                        Text(if (proxyEnabled) "✅ Включено" else "❌ Выключено", color = if (proxyEnabled) Online else Error)
                    }
                    // 显示激活的代理详情
                    val activeProxy = if (proxyEnabled) proxyProfiles.find { it.id == activeProxyId } else null
                    if (activeProxy != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Узел прокси:", style = MaterialTheme.typography.bodyMedium)
                            Text("${activeProxy.type} · ${activeProxy.host}:${activeProxy.port}", style = MaterialTheme.typography.bodyMedium, color = Online)
                        }
                        if (activeProxy.username.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Пользователь прокси:", style = MaterialTheme.typography.bodyMedium)
                                Text(activeProxy.username, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    // 流量统计
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("↑ Отправлено:", style = MaterialTheme.typography.bodyMedium)
                        Text(formatTraffic(GatewayForegroundService.trafficUploadBytes.get()), style = MaterialTheme.typography.bodyMedium, color = Online)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("↓ Загружено:", style = MaterialTheme.typography.bodyMedium)
                        Text(formatTraffic(GatewayForegroundService.trafficDownloadBytes.get()), style = MaterialTheme.typography.bodyMedium, color = Online)
                    }
                }
            }

            // ★★★ 崩溃日志卡片 ★★★
            if (CrashHandler.hasCrashLog()) {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Error.copy(alpha = 0.1f))) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Error)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("💥 Обнаружен лог сбоя", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Error)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Во время прошлого запуска приложение аварийно завершилось, лог сохранён.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    val log = CrashHandler.getCrashLog()
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Crash Log", log))
                                    snackbarHostState.showSnackbar("✅ Лог сбоя скопирован")
                                }
                            }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Error)
                                Spacer(Modifier.width(4.dp))
                                Text("Копировать лог", color = Error)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(onClick = { CrashHandler.clearCrashLog() }) {
                            Text("🗑️ Очистить лог", style = MaterialTheme.typography.bodySmall, color = Error.copy(alpha = 0.7f))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            // 隐藏的秘密通道 — 连点3次打开代理管理
            Surface(modifier = Modifier.fillMaxWidth().height(40.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), shape = MaterialTheme.shapes.small,
                onClick = {
                    val now = System.currentTimeMillis()
                    if (now - lastClickTime <= 3000) { clickCount++; if (clickCount >= 3) { viewModel.showProxyConfig(); clickCount = 0 } }
                    else { clickCount = 1 }
                    lastClickTime = now
                }) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("🔧", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                }
            }

            // 代理管理弹窗
            val showProxyDialog by viewModel.showProxyConfigDialog.collectAsState()
            if (showProxyDialog) {
                ProxyManagementDialog(viewModel = viewModel, onDismiss = { viewModel.hideProxyConfig() })
            }
        }
    }
}

// ============================================================
// 路由规则管理弹窗
// ============================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutingRulesDialog(viewModel: GatewayViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var rules by remember { mutableStateOf<List<com.aigate.router.data.model.RoutingRule>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<com.aigate.router.data.model.RoutingRule?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<Long?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        rules = withContext(Dispatchers.IO) { viewModel.getAllRoutingRules() }
        isLoading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔀 Правила маршрутизации", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 规则列表
                if (!isLoading && rules.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Нет правил маршрутизации\nНажмите кнопку ниже, чтобы добавить",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(rules) { rule ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (rule.enabled) MaterialTheme.colorScheme.surface
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 启用开关
                                    Switch(
                                        checked = rule.enabled,
                                        onCheckedChange = { enabled ->
                                            scope.launch {
                                                viewModel.setRoutingRuleEnabled(rule.id, enabled)
                                                rules = withContext(Dispatchers.IO) { viewModel.getAllRoutingRules() }
                                            }
                                        },
                                        modifier = Modifier.size(40.dp, 24.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    // 规则信息
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            rule.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val matchDesc = buildList {
                                            if (rule.pathPattern.isNotBlank()) add("путь:${rule.pathPattern}")
                                            if (rule.modelPattern.isNotBlank()) add("модель:${rule.modelPattern}")
                                            if (rule.apiKeyPattern.isNotBlank()) add("ключ:${rule.apiKeyPattern.take(8)}...")
                                            if (rule.providerId != null) add("провайдер:${rule.providerId}")
                                        }.joinToString(" | ").ifEmpty { "Без условий" }
                                        Text(
                                            matchDesc,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val actionDesc = when (rule.action) {
                                            "block" -> "🚫 Блокировать"
                                            "route" -> "➡️ Маршрут на ${rule.targetModelKey}"
                                            else -> rule.action
                                        }
                                        Text(
                                            actionDesc,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (rule.action == "block") Error else Online
                                        )
                                    }
                                    // 编辑按钮
                                    IconButton(onClick = { editingRule = rule; showAddDialog = true }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Edit, contentDescription = "Изменить", modifier = Modifier.size(16.dp))
                                    }
                                    // 删除按钮
                                    IconButton(onClick = { showDeleteConfirm = rule.id }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Delete, contentDescription = "Удалить", modifier = Modifier.size(16.dp), tint = Error)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                // 添加按钮
                Button(
                    onClick = { editingRule = null; showAddDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Добавить правило")
                }
                // 清空按钮
                if (rules.isNotEmpty()) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                viewModel.clearAllRoutingRules()
                                rules = emptyList()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Error)
                    ) {
                        Text("Очистить все правила")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )

    // 添加/编辑规则弹窗
    if (showAddDialog) {
        AddEditRoutingRuleDialog(
            viewModel = viewModel,
            existingRule = editingRule,
            onDismiss = { showAddDialog = false },
            onConfirm = {
                scope.launch {
                    rules = withContext(Dispatchers.IO) { viewModel.getAllRoutingRules() }
                    showAddDialog = false
                }
            }
        )
    }

    // 删除确认弹窗
    showDeleteConfirm?.let { ruleId ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Подтвердите удаление", fontWeight = FontWeight.Bold) },
            text = { Text("Удалить это правило маршрутизации?") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val rule = rules.find { it.id == ruleId }
                            if (rule != null) {
                                viewModel.deleteRoutingRule(rule)
                                rules = withContext(Dispatchers.IO) { viewModel.getAllRoutingRules() }
                            }
                            showDeleteConfirm = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Error)
                ) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = null }) { Text("Отмена") } }
        )
    }
}

// ============================================================
// 添加/编辑路由规则弹窗
// ============================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEditRoutingRuleDialog(
    viewModel: GatewayViewModel,
    existingRule: com.aigate.router.data.model.RoutingRule?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val isEdit = existingRule != null
    var name by remember { mutableStateOf(existingRule?.name ?: "") }
    var enabled by remember { mutableStateOf(existingRule?.enabled ?: true) }
    var priority by remember { mutableStateOf(existingRule?.priority?.toString() ?: "0") }
    var pathPattern by remember { mutableStateOf(existingRule?.pathPattern ?: "") }
    var modelPattern by remember { mutableStateOf(existingRule?.modelPattern ?: "") }
    var apiKeyPattern by remember { mutableStateOf(existingRule?.apiKeyPattern ?: "") }
    var providerIdText by remember { mutableStateOf(existingRule?.providerId?.toString() ?: "") }
    var targetModelKey by remember { mutableStateOf(existingRule?.targetModelKey ?: "") }
    var action by remember { mutableStateOf(existingRule?.action ?: "route") }
    var blockMessage by remember { mutableStateOf(existingRule?.blockMessage ?: "") }
    var showModelDropdown by remember { mutableStateOf(false) }

    val providers by viewModel.providers.collectAsState()
    val models by viewModel.models.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isEdit) "Изменить правило маршрутизации"
                else "Добавить правило маршрутизации",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 规则名称
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название правила*") },
                    placeholder = { Text("напр.: запросы GPT на модель зрения") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 优先级
                OutlinedTextField(
                    value = priority,
                    onValueChange = { priority = it.filter { c -> c.isDigit() || c == '-' } },
                    label = { Text("Приоритет (меньше = выше)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider()
                Text("Условия совпадения (логика И, пусто = не учитывать)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold)

                // 路径匹配
                OutlinedTextField(
                    value = pathPattern,
                    onValueChange = { pathPattern = it },
                    label = { Text("Шаблон пути") },
                    placeholder = { Text("/v1/chat/completions") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 模型名匹配
                OutlinedTextField(
                    value = modelPattern,
                    onValueChange = { modelPattern = it },
                    label = { Text("Шаблон модели (* — маска)") },
                    placeholder = { Text("gpt-* или *vision*") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // API密钥前缀
                OutlinedTextField(
                    value = apiKeyPattern,
                    onValueChange = { apiKeyPattern = it },
                    label = { Text("Префикс API-ключа") },
                    placeholder = { Text("sk-proj-") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 服务商选择
                OutlinedTextField(
                    value = providerIdText,
                    onValueChange = { providerIdText = it.filter { c -> c.isDigit() } },
                    label = { Text("ID провайдера (пусто = любой)") },
                    placeholder = { Text("Оставьте пустым для любого провайдера") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider()
                Text("Действие при совпадении",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold)

                // 动作选择
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = action == "route",
                        onClick = { action = "route" },
                        label = { Text("Маршрут") }
                    )
                    FilterChip(
                        selected = action == "block",
                        onClick = { action = "block" },
                        label = { Text("Блокировать") }
                    )
                }

                if (action == "route") {
                    OutlinedTextField(
                        value = targetModelKey,
                        onValueChange = { targetModelKey = it },
                        label = { Text("routeKey целевой модели") },
                        placeholder = { Text("providerId:modelId") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    // 快捷选择已有模型
                    if (models.isNotEmpty()) {
                        Text("Нажмите модель для заполнения:", style = MaterialTheme.typography.labelSmall)
                        LazyColumn(modifier = Modifier.heightIn(max = 120.dp)) {
                            items(models) { model ->
                                    Text(
                                    "${model.providerId}:${model.modelId} (${model.displayName})",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        targetModelKey = "${model.providerId}:${model.modelId}"
                                    }.padding(vertical = 2.dp),
                                    color = MaterialTheme.colorScheme.primary
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
                        placeholder = { Text("Запрос отклонён правилом маршрутизации") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsedPriority = priority.toIntOrNull() ?: 0
                    val parsedProviderId = providerIdText.toLongOrNull()
                    val rule = com.aigate.router.data.model.RoutingRule(
                        id = existingRule?.id ?: 0,
                        name = name.trim(),
                        enabled = enabled,
                        priority = parsedPriority,
                        pathPattern = pathPattern.trim(),
                        modelPattern = modelPattern.trim(),
                        apiKeyPattern = apiKeyPattern.trim(),
                        providerId = parsedProviderId,
                        targetModelKey = targetModelKey.trim(),
                        action = action,
                        blockMessage = blockMessage.trim(),
                        createdAt = existingRule?.createdAt ?: System.currentTimeMillis()
                    )
                    scope.launch(Dispatchers.IO) {
                        if (isEdit) {
                            viewModel.updateRoutingRule(rule)
                        } else {
                            viewModel.saveRoutingRule(rule)
                        }
                        withContext(Dispatchers.Main) { onConfirm() }
                    }
                },
                enabled = name.isNotBlank() && (action == "block" || targetModelKey.isNotBlank())
            ) {
                Text(if (isEdit) "Сохранить" else "Добавить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

private fun formatTraffic(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> "%.1fMB".format(bytes.toDouble() / (1024 * 1024))
}