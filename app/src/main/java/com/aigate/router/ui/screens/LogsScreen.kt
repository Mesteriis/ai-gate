package com.aigate.router.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aigate.router.service.GatewayForegroundService
import com.aigate.router.ui.design.AppCard
import com.aigate.router.ui.design.CardTone
import com.aigate.router.ui.design.EmptyState
import com.aigate.router.ui.design.Fmt
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.StatusDot
import com.aigate.router.ui.design.StatusTone
import com.aigate.router.ui.util.rememberTicker
import com.aigate.router.ui.viewmodel.GatewayViewModel

/**
 * Тонкая обёртка над сегментом «Журнал» — чтобы прежняя точка входа
 * продолжала работать, пока навигация переключается на ActivityScreen.
 */
@Composable
fun LogsScreen(viewModel: GatewayViewModel, modifier: Modifier = Modifier) {
    JournalSegment(viewModel = viewModel, modifier = modifier.fillMaxSize())
}

/** Уровень строки журнала, восстановленный по её маркеру. */
private enum class LogLevel(val title: String) {
    Error("Ошибки"),
    Warning("Предупреждения"),
    Info("Информация"),
}

// Маркеры, которые GatewayForegroundService.addDebugLog ставит в начало строки.
// Разбираем их, чтобы дать строке цветовую метку и фильтр по уровню; сами строки
// журнала приходят из сервиса и отображаются как есть. Символы заданы кодами,
// чтобы в UI-коде не было ни одного знака-картинки.
private const val CODE_CROSS_MARK = 0x274C
private const val CODE_BALLOT_X = 0x2717
private const val CODE_WARNING_SIGN = 0x26A0

private fun levelOf(line: String): LogLevel = when {
    line.any { it.code == CODE_CROSS_MARK || it.code == CODE_BALLOT_X } -> LogLevel.Error
    line.any { it.code == CODE_WARNING_SIGN } -> LogLevel.Warning
    else -> LogLevel.Info
}

private fun LogLevel.tone(): StatusTone = when (this) {
    LogLevel.Error -> StatusTone.Error
    LogLevel.Warning -> StatusTone.Warning
    LogLevel.Info -> StatusTone.Neutral
}

/**
 * Сегмент «Журнал»: строки активности шлюза, новые сверху. Моноширинный шрифт
 * сохранён — строки выровнены по времени и трафику; счётчики трафика показаны
 * иконками направления вместо текстовых стрелок.
 */
@Composable
internal fun JournalSegment(viewModel: GatewayViewModel, modifier: Modifier = Modifier) {
    val ticker by rememberTicker(2_000L)
    val logs = remember(ticker) { viewModel.getDebugLogs().reversed() }
    val uploadBytes = remember(ticker) { GatewayForegroundService.trafficUploadBytes.get() }
    val downloadBytes = remember(ticker) { GatewayForegroundService.trafficDownloadBytes.get() }

    var levelFilter by remember { mutableStateOf<LogLevel?>(null) }
    val counts = remember(logs) { logs.groupingBy { levelOf(it) }.eachCount() }
    val problemLevels = remember(counts) {
        listOf(LogLevel.Error, LogLevel.Warning).filter { (counts[it] ?: 0) > 0 }
    }
    // Если строки выбранного уровня вытеснены из буфера, фильтр сам отпускает:
    // иначе пользователь остаётся с пустым списком и без чипа, чтобы его снять.
    val activeFilter = levelFilter?.takeIf { it in problemLevels }
    val shown = remember(logs, activeFilter) {
        activeFilter?.let { level -> logs.filter { levelOf(it) == level } } ?: logs
    }

    Column(
        modifier = modifier.padding(horizontal = Gateway.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Gateway.spacing.md),
    ) {
        AppCard(tone = CardTone.Raised) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TrafficCounter(Icons.Outlined.ArrowUpward, Fmt.bytes(uploadBytes))
                    Spacer(Modifier.width(Gateway.spacing.lg))
                    TrafficCounter(Icons.Outlined.ArrowDownward, Fmt.bytes(downloadBytes))
                }
                Text(
                    text = "${logs.size} строк",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (problemLevels.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Gateway.spacing.sm)) {
                FilterChip(
                    selected = activeFilter == null,
                    onClick = { levelFilter = null },
                    label = { Text("Все") },
                )
                problemLevels.forEach { level ->
                    FilterChip(
                        selected = activeFilter == level,
                        onClick = { levelFilter = if (activeFilter == level) null else level },
                        label = { Text("${level.title} ${counts[level] ?: 0}") },
                    )
                }
            }
        }

        if (shown.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(Icons.AutoMirrored.Outlined.Article, "Журнал пуст")
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Gateway.spacing.xs),
                contentPadding = PaddingValues(bottom = Gateway.spacing.lg),
            ) {
                items(shown) { line -> LogLine(line) }
            }
        }
    }
}

/** Одна строка журнала: метка уровня точкой + моноширинный текст. */
@Composable
private fun LogLine(line: String) {
    val level = levelOf(line)
    Surface(
        color = Gateway.colors.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = Gateway.spacing.md,
                vertical = Gateway.spacing.sm,
            ),
            verticalAlignment = Alignment.Top,
        ) {
            if (level == LogLevel.Info) {
                Spacer(Modifier.size(8.dp))
            } else {
                StatusDot(
                    tone = level.tone(),
                    modifier = Modifier.padding(top = Gateway.spacing.xs),
                )
            }
            Spacer(Modifier.width(Gateway.spacing.sm))
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Счётчик трафика: направление — иконкой, значение — единым форматтером. */
@Composable
private fun TrafficCounter(icon: ImageVector, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Gateway.spacing.xs))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
