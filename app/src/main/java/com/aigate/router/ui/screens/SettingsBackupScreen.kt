package com.aigate.router.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.data.db.AutoBackupWorker
import com.aigate.router.data.db.BackupManager
import com.aigate.router.service.GatewayForegroundService
import com.aigate.router.ui.design.AppScaffold
import com.aigate.router.ui.design.ConfirmDialog
import com.aigate.router.ui.design.EmptyState
import com.aigate.router.ui.design.EntityCard
import com.aigate.router.ui.design.Fmt
import com.aigate.router.ui.design.FormSheet
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.SectionHeader
import com.aigate.router.ui.viewmodel.GatewayViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Резервные копии» — отдельный раздел вместо карточки с восемью кнопками в
 * общем скролле настроек. Формат копии (.qtbk), расписание через WorkManager,
 * история и экспорт сохранены; описания формата переехали в [settingsHelp].
 */
@Composable
internal fun SettingsBackupScreen(
    viewModel: GatewayViewModel,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var autoEnabled by remember {
        mutableStateOf(
            GatewayForegroundService.getGatewayConfig("auto_backup_enabled", "false").toBoolean(),
        )
    }
    var hour by remember {
        mutableIntStateOf(
            GatewayForegroundService.getGatewayConfig("auto_backup_hour", "3").toIntOrNull() ?: 3,
        )
    }
    var minute by remember {
        mutableIntStateOf(
            GatewayForegroundService.getGatewayConfig("auto_backup_minute", "0").toIntOrNull() ?: 0,
        )
    }
    var showTimeSheet by remember { mutableStateOf(false) }

    var reload by remember { mutableIntStateOf(0) }
    var history by remember { mutableStateOf<List<BackupManager.BackupMetadata>>(emptyList()) }
    var pendingRestore by remember { mutableStateOf<BackupManager.BackupMetadata?>(null) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(reload) {
        history = withContext(Dispatchers.IO) {
            BackupManager(AppDatabase.getInstance(context)).getBackupHistory()
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) pendingImport = uri }

    fun applySchedule(enabled: Boolean) {
        GatewayForegroundService.saveGatewayConfig("auto_backup_enabled", enabled.toString())
        if (enabled) {
            GatewayForegroundService.saveGatewayConfig("auto_backup_hour", hour.toString())
            GatewayForegroundService.saveGatewayConfig("auto_backup_minute", minute.toString())
            AutoBackupWorker.schedule(context, hour, minute)
        } else {
            AutoBackupWorker.cancel(context)
        }
    }

    AppScaffold(
        title = "Резервные копии",
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        modifier = Modifier.fillMaxSize(),
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Gateway.spacing.lg)
                .padding(bottom = Gateway.spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Gateway.spacing.md),
        ) {
            SectionHeader("Расписание")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Копировать автоматически", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = autoEnabled,
                    onCheckedChange = { enabled ->
                        autoEnabled = enabled
                        applySchedule(enabled)
                    },
                )
            }
            OutlinedButton(
                onClick = { showTimeSheet = true },
                enabled = autoEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Время копирования: %02d:%02d".format(hour, minute)) }

            SectionHeader("Копия")
            Button(
                onClick = {
                    scope.launch {
                        val message = withContext(Dispatchers.IO) { createBackup(context) }
                        reload++
                        snackbarHostState.showSnackbar(message)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Создать копию сейчас") }
            OutlinedButton(
                onClick = { filePicker.launch("*/*") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Восстановить из файла") }
            // getBackupJson() создаёт новый .qtbk и возвращает путь к нему —
            // поэтому обе кнопки экспортируют копию, а не «выгружают JSON».
            Row(horizontalArrangement = Arrangement.spacedBy(Gateway.spacing.sm)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            viewModel.getBackupJson()
                                .onSuccess { path ->
                                    val clipboard = context
                                        .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText("Резервная копия", path),
                                    )
                                    reload++
                                    snackbarHostState.showSnackbar("Путь к копии скопирован")
                                }
                                .onFailure { e ->
                                    snackbarHostState.showSnackbar("Не удалось экспортировать: ${e.message}")
                                }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Экспортировать") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            viewModel.getBackupJson()
                                .onSuccess { path ->
                                    reload++
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        putExtra(Intent.EXTRA_TEXT, path)
                                        type = "text/plain"
                                    }
                                    context.startActivity(Intent.createChooser(send, "Поделиться копией"))
                                }
                                .onFailure { e ->
                                    snackbarHostState.showSnackbar("Не удалось экспортировать: ${e.message}")
                                }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Поделиться") }
            }
            OutlinedButton(
                onClick = {
                    AutoBackupWorker.scheduleTest(context)
                    scope.launch {
                        snackbarHostState.showSnackbar("Тестовое копирование запланировано через 10 секунд")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Тестовое копирование") }

            SectionHeader("История")
            if (history.isEmpty()) {
                EmptyState(icon = Icons.Outlined.Archive, text = "Копий пока нет")
            } else {
                history.forEach { meta ->
                    EntityCard(
                        title = meta.fileName,
                        subtitle = "${Fmt.bytes(meta.sizeBytes)} · ${Fmt.dateTime(meta.createdAt)}",
                        leadingIcon = Icons.Outlined.Description,
                        onClick = { pendingRestore = meta },
                    )
                    Spacer(Modifier.size(Gateway.spacing.xs))
                }
            }
        }
    }

    if (showTimeSheet) {
        BackupTimeSheet(
            initialHour = hour,
            initialMinute = minute,
            onDismiss = { showTimeSheet = false },
            onConfirm = { newHour, newMinute ->
                hour = newHour
                minute = newMinute
                GatewayForegroundService.saveGatewayConfig("auto_backup_hour", hour.toString())
                GatewayForegroundService.saveGatewayConfig("auto_backup_minute", minute.toString())
                if (autoEnabled) AutoBackupWorker.schedule(context, hour, minute)
                showTimeSheet = false
            },
        )
    }

    // Восстановление перезаписывает всю базу — раньше это происходило от одного
    // тапа по элементу списка, без подтверждения.
    pendingRestore?.let { meta ->
        ConfirmDialog(
            title = "Восстановить из копии?",
            message = "Текущие данные будут заменены содержимым «${meta.fileName}». " +
                "Действие необратимо.",
            confirmText = "Восстановить",
            onConfirm = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        BackupManager(AppDatabase.getInstance(context))
                            .importFromFile(File(meta.filePath))
                    }
                    snackbarHostState.showSnackbar(
                        result.fold(
                            onSuccess = { "Данные восстановлены" },
                            onFailure = { "Не удалось восстановить: ${it.message}" },
                        ),
                    )
                }
            },
            onDismiss = { pendingRestore = null },
        )
    }

    pendingImport?.let { uri ->
        ConfirmDialog(
            title = "Восстановить из файла?",
            message = "Текущие данные будут заменены содержимым выбранного файла. " +
                "Действие необратимо.",
            confirmText = "Восстановить",
            onConfirm = {
                scope.launch {
                    val message = withContext(Dispatchers.IO) { restoreFromUri(context, viewModel, uri) }
                    reload++
                    snackbarHostState.showSnackbar(message)
                }
            },
            onDismiss = { pendingImport = null },
        )
    }
}

/** Время автокопирования — системный TimePicker вместо двух числовых полей. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupTimeSheet(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true,
    )
    FormSheet(
        title = "Время копирования",
        onDismiss = onDismiss,
        confirmText = "Сохранить",
        onConfirm = { onConfirm(state.hour, state.minute) },
    ) {
        TimePicker(state = state)
    }
}

/** Создание копии в каталоге копий; возвращает готовое сообщение для snackbar. */
private suspend fun createBackup(context: Context): String {
    val manager = BackupManager(AppDatabase.getInstance(context))
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val file = File(manager.getBackupDir(), "backup_$stamp.qtbk")
    return manager.exportToFile(file).fold(
        onSuccess = { "Копия создана: ${file.name}" },
        onFailure = { "Не удалось создать копию: ${it.message}" },
    )
}

/** Восстановление из выбранного пользователем файла. */
private suspend fun restoreFromUri(
    context: Context,
    viewModel: GatewayViewModel,
    uri: Uri,
): String {
    val temp = File(context.cacheDir, "restore_temp.qtbk")
    val copied = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } != null
    }
    copied.exceptionOrNull()?.let { return "Не удалось прочитать файл: ${it.message}" }
    if (copied.getOrDefault(false) == false) return "Не удалось прочитать файл"
    return viewModel.restoreFromFile(temp.absolutePath).fold(
        onSuccess = { "Данные восстановлены" },
        onFailure = { "Не удалось восстановить: ${it.message}" },
    )
}
