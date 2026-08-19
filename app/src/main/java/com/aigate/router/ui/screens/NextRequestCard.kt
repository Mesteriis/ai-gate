package com.aigate.router.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aigate.router.data.model.routeKey
import com.aigate.router.gateway.GatewayScheduler
import com.aigate.router.ui.design.AppCard
import com.aigate.router.ui.design.CardTone
import com.aigate.router.ui.design.Fmt
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.ProviderAvatar
import com.aigate.router.ui.design.StatusChip
import com.aigate.router.ui.design.StatusTone
import com.aigate.router.ui.viewmodel.GatewayViewModel

/**
 * Кто обслужит следующий запрос и почему именно он. Раньше это было
 * неочевидно: рейтинг скорости, принудительный выбор и пресеты жили на разных
 * экранах, и предсказать маршрут запроса было нельзя.
 */
@Composable
fun NextRequestCard(viewModel: GatewayViewModel, ticker: Long) {
    val models by viewModel.enabledModels.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val forced by viewModel.forcedModelKey.collectAsState()
    val speeds by viewModel.latestSpeedHistory.collectAsState()

    // Лучшая модель живёт в планировщике и меняется после замеров.
    val best by produceState(initialValue = GatewayScheduler.getBestModel(), ticker) {
        value = GatewayScheduler.getBestModel()
    }

    val chosenKey = forced.takeIf { it.isNotBlank() } ?: best
    val model = models.firstOrNull { it.routeKey == chosenKey } ?: models.firstOrNull()
    val provider = providers.firstOrNull { it.id == model?.providerId }
    val reason = when {
        forced.isNotBlank() -> "выбрана вручную"
        best != null && model?.routeKey == best -> "быстрейшая по замерам"
        model != null -> "первая доступная: замеров ещё нет"
        else -> null
    }

    AppCard(tone = CardTone.Raised) {
        Column(verticalArrangement = Arrangement.spacedBy(Gateway.spacing.sm)) {
            Text(
                text = "Следующий запрос обслужит",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (model == null) {
                Text(
                    text = "Нет включённых моделей",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderAvatar(
                    name = provider?.name.orEmpty(),
                    type = provider?.type.orEmpty(),
                    size = 28.dp,
                )
                Spacer(Modifier.width(Gateway.spacing.sm))
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        text = model.customAlias.ifBlank { model.displayName },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val speed = speeds.firstOrNull { it.modelKey == model.routeKey && it.success }
                    Text(
                        text = listOfNotNull(
                            provider?.name,
                            speed?.let { Fmt.latency(it.ttftMs) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            reason?.let {
                StatusChip(
                    text = it,
                    tone = if (forced.isNotBlank()) StatusTone.Info else StatusTone.Neutral,
                )
            }
        }
    }
}
