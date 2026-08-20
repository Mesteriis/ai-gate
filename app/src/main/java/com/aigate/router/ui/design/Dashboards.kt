package com.aigate.router.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aigate.router.ui.design.charts.Sparkline

/**
 * Блоки дашборда по паттерну «карточка с живой строкой-выводом»: фильтр периода
 * над карточками, витринная hero-метрика и карточка графика, чья шапка меняется
 * при выборе отметки. Экраны собирают дашборд из этих блоков и не изобретают
 * собственные шапки/чипы — иначе редизайн расползается по копиям.
 */

/**
 * Фильтр периода над карточками дашборда — единственный переключатель
 * «7/14/30 дней». [rangeLabel] — подпись фактического диапазона дат справа
 * (например «5–19 авг»), чтобы «14 дней» не приходилось расшифровывать в уме.
 */
@Composable
fun PeriodFilter(
    selectedDays: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    options: List<Int> = listOf(7, 14, 30),
    rangeLabel: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Gateway.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { days ->
            FilterChip(
                selected = days == selectedDays,
                onClick = { onSelect(days) },
                // Цвета не переопределяем: выбранный чип берёт secondaryContainer
                // из темы, который после редизайна стал брендовым.
                label = { Text("$days дней") },
            )
        }
        if (rangeLabel != null) {
            Spacer(Modifier.weight(1f))
            Text(
                text = rangeLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Витринная метрика на [CardTone.Hero]: одно главное число периода крупно,
 * рядом спарклайн тренда, ниже — сравнение с прошлым периодом и пояснения.
 * [deltaPercent] показывается StatusChip-ом со знаком («+13% …»), [deltaCaption]
 * — хвост подписи («к прошлым 14 дням»). [subLines] — строки-пояснения.
 */
@Composable
fun HeroCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    deltaPercent: Int? = null,
    deltaCaption: String? = null,
    sparkline: List<Float>? = null,
    subLines: List<String> = emptyList(),
) {
    AppCard(modifier = modifier, tone = CardTone.Hero) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Gateway.spacing.xs))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
            )
            unit?.let {
                Spacer(Modifier.width(Gateway.spacing.xs))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Прижимаем единицу к базовой линии числа, как в MetricTile.
                    modifier = Modifier.padding(bottom = Gateway.spacing.xs),
                )
            }
            if (sparkline != null) {
                Spacer(Modifier.weight(1f))
                Sparkline(
                    values = sparkline,
                    height = 30.dp,
                    modifier = Modifier
                        .width(96.dp)
                        // Совмещаем нижний край спарклайна с базовой линией числа.
                        .padding(bottom = Gateway.spacing.xs),
                )
            }
        }
        if (deltaPercent != null) {
            Spacer(Modifier.height(Gateway.spacing.sm))
            val deltaText = buildString {
                // Знак пишем всегда: «+13%» и «-13%» читаются одинаково быстро,
                // а «13%» без знака заставляет гадать о направлении.
                append("%+d%%".format(deltaPercent))
                if (!deltaCaption.isNullOrBlank()) {
                    append(' ')
                    append(deltaCaption)
                }
            }
            // Рост расхода — не «успех» и не «ошибка», поэтому нейтральный Info.
            StatusChip(text = deltaText, tone = StatusTone.Info)
        }
        if (subLines.isNotEmpty()) {
            Spacer(Modifier.height(Gateway.spacing.sm))
            subLines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Карточка графика с «живой строкой-выводом»: [eyebrow] — о чём график,
 * [readMain]/[readSub] — вывод по данным, который экран меняет при выборе
 * отметки на графике (поэтому это параметры, а не статичный заголовок).
 * [headerAction] — элемент справа в шапке, обычно StatusChip.
 */
@Composable
fun ChartCard(
    eyebrow: String,
    readMain: String,
    modifier: Modifier = Modifier,
    readSub: String? = null,
    tone: CardTone = CardTone.Plain,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppCard(modifier = modifier, tone = tone) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            // weight(fill = false): длинный readMain обрезается многоточием,
            // а не выталкивает headerAction за край карточки.
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    text = eyebrow,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = readMain,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                readSub?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            headerAction?.let {
                Spacer(Modifier.width(Gateway.spacing.sm))
                it()
            }
        }
        Spacer(Modifier.height(Gateway.spacing.sm))
        content()
    }
}

/**
 * Ряд из нескольких итогов «подпись — значение» под графиком (обычно три:
 * последнее/среднее/замеров). Стиль совпадает со сводкой на экране статистики,
 * чтобы итоги читались одинаково на всех дашбордах.
 */
@Composable
fun StatTriple(items: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        items.forEach { (label, value) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
