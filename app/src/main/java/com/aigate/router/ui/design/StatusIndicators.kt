package com.aigate.router.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Тон статуса — единственный способ красить состояния в UI.
 * Заменяет эмодзи 🔴🟢✅❌⏳ и ручные Online.copy(alpha=…) чипы.
 */
enum class StatusTone { Success, Warning, Error, Info, Neutral }

@Composable
fun StatusTone.accent(): Color = when (this) {
    StatusTone.Success -> Gateway.colors.success
    StatusTone.Warning -> Gateway.colors.warning
    StatusTone.Error -> MaterialTheme.colorScheme.error
    StatusTone.Info -> Gateway.colors.info
    StatusTone.Neutral -> Gateway.colors.offline
}

@Composable
fun StatusTone.container(): Color = when (this) {
    StatusTone.Success -> Gateway.colors.successContainer
    StatusTone.Warning -> Gateway.colors.warningContainer
    StatusTone.Error -> Gateway.colors.errorContainer
    StatusTone.Info -> Gateway.colors.infoContainer
    StatusTone.Neutral -> Gateway.colors.surfaceContainerHigh
}

@Composable
fun StatusTone.onContainer(): Color = when (this) {
    StatusTone.Success -> Gateway.colors.onSuccessContainer
    StatusTone.Warning -> Gateway.colors.onWarningContainer
    StatusTone.Error -> Gateway.colors.onErrorContainer
    StatusTone.Info -> Gateway.colors.onInfoContainer
    StatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Точка-индикатор состояния (онлайн/офлайн/деградация) — 8dp. */
@Composable
fun StatusDot(tone: StatusTone, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(8.dp)
            .background(tone.accent(), CircleShape)
    )
}

/** Компактный статус-чип: «Работает», «Критично», «Нет данных». */
@Composable
fun StatusChip(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    withDot: Boolean = false,
) {
    Surface(
        color = tone.container(),
        contentColor = tone.onContainer(),
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            if (withDot) {
                StatusDot(tone)
                androidx.compose.foundation.layout.Spacer(Modifier.size(5.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}
