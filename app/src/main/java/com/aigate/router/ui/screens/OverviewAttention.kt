package com.aigate.router.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.diag.ConnectivityCheck
import com.aigate.router.notify.QuotaTriggers
import com.aigate.router.ui.design.AppCard
import com.aigate.router.ui.design.CardTone
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.SectionHeader
import com.aigate.router.ui.design.StatusTone
import com.aigate.router.ui.design.accent
import kotlinx.coroutines.launch

/**
 * «Требует внимания»: то, на что стоит отреагировать сейчас. Блок появляется
 * только при наличии поводов — пустых заголовков на экране быть не должно.
 */
@Composable
fun AttentionBlock(
    alerts: List<QuotaTriggers.Alert>,
    gatewayStopped: Boolean,
    blockedAttempts: Int,
) {
    val hasAttempts = gatewayStopped && blockedAttempts > 0
    if (alerts.isEmpty() && !hasAttempts) return

    SectionHeader("Требует внимания")
    AppCard(tone = CardTone.Raised) {
        Column(verticalArrangement = Arrangement.spacedBy(Gateway.spacing.sm)) {
            if (hasAttempts) {
                AttentionRow(
                    tone = StatusTone.Warning,
                    title = "Шлюз остановлен",
                    detail = "попыток подключения: $blockedAttempts",
                )
            }
            alerts.forEach { alert ->
                AttentionRow(
                    tone = when (alert.kind) {
                        QuotaTriggers.Kind.LOW_QUOTA,
                        QuotaTriggers.Kind.LOW_BALANCE,
                        QuotaTriggers.Kind.EXHAUST_BEFORE_RESET -> StatusTone.Error
                        QuotaTriggers.Kind.SURPLUS -> StatusTone.Warning
                        QuotaTriggers.Kind.RESET -> StatusTone.Success
                    },
                    title = alert.title,
                    detail = alert.body,
                )
            }
        }
    }
}

@Composable
private fun AttentionRow(tone: StatusTone, title: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = when (tone) {
                StatusTone.Error -> Icons.Outlined.ErrorOutline
                StatusTone.Success -> Icons.Outlined.CheckCircle
                else -> Icons.Outlined.WarningAmber
            },
            contentDescription = null,
            tint = tone.accent(),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(Gateway.spacing.sm))
        Column(Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Проверка связи одним тапом: результат раскрывается списком шагов, поэтому
 * видно, на каком именно звене оборвалась цепочка.
 */
@Composable
fun ConnectivityCheckCard(db: AppDatabase, port: Int) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var steps by remember { mutableStateOf<List<ConnectivityCheck.Step>>(emptyList()) }

    AppCard(tone = CardTone.Plain) {
        Column(verticalArrangement = Arrangement.spacedBy(Gateway.spacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (steps.isEmpty()) "Проверка связи" else ConnectivityCheck.summary(steps),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (running) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = {
                        running = true
                        scope.launch {
                            steps = ConnectivityCheck.run(db, port)
                            running = false
                        }
                    }) { Text(if (steps.isEmpty()) "Проверить" else "Проверить снова") }
                }
            }
            steps.forEach { step ->
                AttentionRow(
                    tone = when (step.state) {
                        ConnectivityCheck.State.OK -> StatusTone.Success
                        ConnectivityCheck.State.WARN -> StatusTone.Warning
                        ConnectivityCheck.State.FAIL -> StatusTone.Error
                    },
                    title = step.title,
                    detail = step.detail,
                )
            }
        }
    }
}
