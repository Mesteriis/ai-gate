package com.aigate.router.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.aigate.router.capture.PacketCapture
import com.aigate.router.capture.PacketRecord
import com.aigate.router.ui.design.AppScaffold
import com.aigate.router.ui.design.ConfirmDialog
import com.aigate.router.ui.design.EmptyState
import com.aigate.router.ui.design.EntityCard
import com.aigate.router.ui.design.Fmt
import com.aigate.router.ui.design.FormSheet
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.StatusChip
import com.aigate.router.ui.design.StatusTone
import com.aigate.router.ui.util.rememberTicker
import com.aigate.router.ui.viewmodel.GatewayViewModel
import kotlinx.coroutines.launch

/** Фильтр списка перехваченных записей. */
private enum class CaptureFilter(val label: String) {
    All("Все"),
    Ok("200"),
    Errors("Ошибки"),
}

/**
 * «Отладочные логи перехвата» — отдельный раздел с рабочим системным «назад».
 * Раньше это был самодельный полноэкранный оверлей на boolean-флаге: системная
 * кнопка «назад» закрывала приложение целиком.
 */
@Composable
internal fun SettingsCaptureScreen(
    viewModel: GatewayViewModel,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val debugMode by viewModel.debugMode.collectAsState()

    var filterText by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(CaptureFilter.All) }
    var detail by remember { mutableStateOf<PacketRecord?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    // PacketCapture.records — обычный список, не Flow: обновляем по тикеру,
    // пока раздел открыт.
    val tick by rememberTicker(2_000L)
    val records = remember(tick, filterText, filter) {
        PacketCapture.records.asReversed().filter { record ->
            val status = record.response?.httpStatus ?: 0
            val matchesFilter = when (filter) {
                CaptureFilter.All -> true
                CaptureFilter.Ok -> status == 200
                CaptureFilter.Errors -> status >= 400 || record.failover != null
            }
            val matchesText = filterText.isBlank() ||
                record.summary.contains(filterText, ignoreCase = true) ||
                record.outbound?.body?.contains(filterText, ignoreCase = true) == true ||
                record.inbound?.body?.contains(filterText, ignoreCase = true) == true
            matchesFilter && matchesText
        }
    }

    AppScaffold(
        title = "Логи перехвата",
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        modifier = Modifier.fillMaxSize(),
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .padding(horizontal = Gateway.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Gateway.spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gateway.spacing.sm),
            ) {
                Button(onClick = { viewModel.toggleDebugMode() }) {
                    Text(if (debugMode) "Остановить перехват" else "Начать перехват")
                }
                StatusChip(
                    text = if (debugMode) "запись" else "выключен",
                    tone = if (debugMode) StatusTone.Success else StatusTone.Neutral,
                    withDot = true,
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { confirmClear = true },
                    enabled = records.isNotEmpty(),
                ) { Text("Очистить") }
            }

            OutlinedTextField(
                value = filterText,
                onValueChange = { filterText = it },
                label = { Text("Поиск") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                CaptureFilter.entries.forEachIndexed { index, item ->
                    SegmentedButton(
                        selected = filter == item,
                        onClick = { filter = item },
                        shape = SegmentedButtonDefaults.itemShape(index, CaptureFilter.entries.size),
                        label = { Text(item.label, maxLines = 1) },
                    )
                }
            }

            if (records.isEmpty()) {
                EmptyState(icon = Icons.Outlined.BugReport, text = "Записей нет")
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Gateway.spacing.xs),
                    contentPadding = PaddingValues(bottom = Gateway.spacing.xxl),
                ) {
                    items(records, key = { it.id }) { record ->
                        val status = record.response?.httpStatus ?: 0
                        EntityCard(
                            title = record.summary,
                            subtitle = "${Fmt.time(record.timestamp)} · " +
                                (record.outbound?.modelId ?: "модель неизвестна"),
                            statusText = statusLabel(record),
                            statusTone = when {
                                status >= 400 -> StatusTone.Error
                                record.failover != null -> StatusTone.Warning
                                status == 0 -> StatusTone.Neutral
                                else -> StatusTone.Success
                            },
                            onClick = { detail = record },
                        )
                    }
                }
            }
        }
    }

    detail?.let { record ->
        val dump = remember(record.id) { recordDump(record) }
        FormSheet(
            title = "Запись ${record.id}",
            onDismiss = { detail = null },
            confirmText = "Копировать",
            dismissText = "Закрыть",
            onConfirm = {
                val clipboard = context
                    .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Запись перехвата", dump))
                detail = null
                scope.launch { snackbarHostState.showSnackbar("Запись скопирована") }
            },
        ) {
            Surface(
                color = Gateway.colors.surfaceContainerLow,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = dump,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.padding(Gateway.spacing.sm),
                )
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Очистить записи?",
            message = "Все перехваченные запросы будут удалены из памяти.",
            confirmText = "Очистить",
            onConfirm = { PacketCapture.clear() },
            onDismiss = { confirmClear = false },
        )
    }
}

/** Короткий статус записи для чипа. */
private fun statusLabel(record: PacketRecord): String {
    val status = record.response?.httpStatus ?: 0
    return when {
        record.failover != null -> "переключение"
        status == 0 -> "в процессе"
        else -> status.toString()
    }
}

/** Полный текстовый дамп записи — он же уходит в буфер обмена. */
private fun recordDump(record: PacketRecord): String = buildString {
    appendLine("Запись ${record.id}")
    appendLine("Время: ${Fmt.dateTime(record.timestamp)}")
    record.inbound?.let { inbound ->
        appendLine()
        appendLine("Входящий запрос")
        appendLine("${inbound.method} ${inbound.path}")
        appendLine("Заголовки: ${inbound.headers}")
        appendLine("Тело (${inbound.bodySize} Б):")
        appendLine(inbound.body)
    }
    record.outbound?.let { outbound ->
        appendLine()
        appendLine("Исходящий запрос")
        appendLine("URL: ${outbound.targetUrl}")
        appendLine("Модель: ${outbound.modelId}")
        appendLine("Тело (${outbound.bodySize} Б):")
        appendLine(outbound.body)
    }
    record.response?.let { resp ->
        appendLine()
        appendLine("Ответ")
        appendLine("Статус: ${resp.httpStatus} · ${Fmt.latency(resp.elapsedMs)}")
        appendLine("Токены: ${resp.promptTokens} на вход, ${resp.completionTokens} на выход")
        appendLine("Тело (${resp.bodySize} Б):")
        appendLine(resp.body)
    }
    record.failover?.let { failover ->
        appendLine()
        appendLine("Переключение при сбое")
        failover.attempts.forEach { attempt ->
            appendLine(
                "${attempt.index}. ${attempt.modelId}: ${attempt.error} " +
                    "(${Fmt.latency(attempt.elapsedMs)})",
            )
        }
    }
}
