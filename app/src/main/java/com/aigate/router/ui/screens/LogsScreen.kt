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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aigate.router.service.GatewayForegroundService
import com.aigate.router.ui.design.AppCard
import com.aigate.router.ui.design.CardTone
import com.aigate.router.ui.design.EmptyState
import com.aigate.router.ui.design.Fmt
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.StatusDot
import com.aigate.router.ui.design.StatusTone
import com.aigate.router.ui.design.animatedValue
import com.aigate.router.ui.design.appear
import com.aigate.router.ui.design.fadingEdge
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

// Маркеры, которые GatewayForegroundService.addDebugLog ставит в начало
// сообщения (после метки времени). Разбираем их, чтобы дать строке тон и
// фильтр по уровню; символы заданы кодами, чтобы в UI-коде не было ни одного
// знака-картинки.
private const val CODE_CROSS_MARK = 0x274C
private const val CODE_BALLOT_X = 0x2717
private const val CODE_WARNING_SIGN = 0x26A0

// Селектор начертания эмодзи — часть строк несёт его хвостом за маркером.
private const val CODE_VARIATION_SELECTOR = 0xFE0F

/** Сколько первых строк входят ступенчато при смене фильтра. */
private const val APPEAR_ROWS = 12

/** Тон строки журнала, восстановленный по её статус-маркеру. */
private fun logLevel(line: String): StatusTone = when {
    line.any { it.code == CODE_CROSS_MARK || it.code == CODE_BALLOT_X } -> StatusTone.Error
    line.any { it.code == CODE_WARNING_SIGN } -> StatusTone.Warning
    else -> StatusTone.Neutral
}

/** Название уровня для чипа-фильтра. */
private fun StatusTone.filterTitle(): String = when (this) {
    StatusTone.Error -> "Ошибки"
    StatusTone.Warning -> "Предупреждения"
    else -> "Информация"
}

/**
 * Срезает статус-маркер из текста строки: уровень уже показан точкой слева,
 * а сам знак в моноширинной строке лишь ломает выравнивание.
 */
private fun stripStatusMark(line: String): String {
    val index = line.indexOfFirst {
        it.code == CODE_CROSS_MARK || it.code == CODE_BALLOT_X || it.code == CODE_WARNING_SIGN
    }
    if (index < 0) return line
    var end = index + 1
    if (end < line.length && line[end].code == CODE_VARIATION_SELECTOR) end++
    if (end < line.length && line[end] == ' ') end++
    return line.removeRange(index, end)
}

/**
 * Сегмент «Журнал»: строки активности шлюза, новые сверху. Моноширинный шрифт
 * сохранён — строки выровнены по времени и трафику; счётчики трафика поданы
 * в стиле MetricTile: eyebrow-подпись и значение под ней.
 */
@Composable
internal fun JournalSegment(viewModel: GatewayViewModel, modifier: Modifier = Modifier) {
    val ticker by rememberTicker(2_000L)
    val logs = remember(ticker) { viewModel.getDebugLogs().reversed() }
    val uploadBytes = remember(ticker) { GatewayForegroundService.trafficUploadBytes.get() }
    val downloadBytes = remember(ticker) { GatewayForegroundService.trafficDownloadBytes.get() }
    // Счётчики обновляются тиком раз в 2 секунды: без анимации крупное значение
    // подменялось бы рывком, поэтому байты доезжают до нового значения.
    val uploadShown = animatedValue(uploadBytes.toFloat(), key = "upload")
    val downloadShown = animatedValue(downloadBytes.toFloat(), key = "download")

    var levelFilter by remember { mutableStateOf<StatusTone?>(null) }
    val counts = remember(logs) { logs.groupingBy { logLevel(it) }.eachCount() }
    val problemLevels = remember(counts) {
        listOf(StatusTone.Error, StatusTone.Warning).filter { (counts[it] ?: 0) > 0 }
    }
    // Если строки выбранного уровня вытеснены из буфера, фильтр сам отпускает:
    // иначе пользователь остаётся с пустым списком и без чипа, чтобы его снять.
    val activeFilter = levelFilter?.takeIf { it in problemLevels }
    val shown = remember(logs, activeFilter) {
        activeFilter?.let { level -> logs.filter { logLevel(it) == level } } ?: logs
    }

    Column(
        modifier = modifier.padding(horizontal = Gateway.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Gateway.spacing.md),
    ) {
        AppCard(tone = CardTone.Raised) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                JournalStat(
                    label = "Отправлено",
                    value = Fmt.bytes(uploadShown.toLong()),
                    modifier = Modifier.weight(1f),
                )
                JournalStat(
                    label = "Получено",
                    value = Fmt.bytes(downloadShown.toLong()),
                    modifier = Modifier.weight(1f),
                )
                // Число строк не весовое: оно короткое, и лишняя доля ширины
                // только оторвала бы его от правого края карточки.
                JournalStat(label = "Строк", value = logs.size.toString())
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
                        label = { Text("${level.filterTitle()} ${counts[level] ?: 0}") },
                    )
                }
            }
        }

        if (shown.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(Icons.AutoMirrored.Outlined.Article, "Журнал пуст")
            }
        } else {
            // key(activeFilter) пересоздаёт список при смене фильтра: иначе
            // LazyColumn переиспользует композиции строк по индексу, и подмена
            // содержимого проходила бы совсем без движения.
            key(activeFilter) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        // Мягкая кромка стирает содержимое смешиванием DstIn, а
                        // без offscreen-слоя оно стёрло бы и фон под списком.
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .fadingEdge(atTop = true),
                    verticalArrangement = Arrangement.spacedBy(Gateway.spacing.xs),
                    contentPadding = PaddingValues(
                        // Отступ сверху равен кромке: в покое первая строка не
                        // должна выглядеть подтаявшей, растворяется она в прокрутке.
                        top = Gateway.spacing.md,
                        bottom = Gateway.spacing.lg,
                    ),
                ) {
                    itemsIndexed(shown) { index, line ->
                        // Волной входят только первые строки: остальные всё равно
                        // за краем экрана, а задержка index*stagger превратилась бы
                        // в ожидание вместо моторики.
                        LogLine(
                            line = line,
                            modifier = if (index < APPEAR_ROWS) Modifier.appear(index) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

/** Одна строка журнала: метка уровня точкой + моноширинный текст без маркера. */
@Composable
private fun LogLine(line: String, modifier: Modifier = Modifier) {
    val level = logLevel(line)
    Surface(
        color = Gateway.colors.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = Gateway.spacing.md,
                vertical = Gateway.spacing.sm,
            ),
            verticalAlignment = Alignment.Top,
        ) {
            if (level == StatusTone.Neutral) {
                Spacer(Modifier.size(8.dp))
            } else {
                StatusDot(
                    tone = level,
                    modifier = Modifier.padding(top = Gateway.spacing.xs),
                )
            }
            Spacer(Modifier.width(Gateway.spacing.sm))
            Text(
                text = stripStatusMark(line),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Мини-метрика шапки журнала по анатомии MetricTile: eyebrow-подпись сверху,
 * крупное значение под ней. Ступень ниже, чем в MetricTile: в карточке их три
 * в ряд, и headlineLarge не оставил бы места самому длинному значению.
 */
@Composable
private fun JournalStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 1,
        )
    }
}
