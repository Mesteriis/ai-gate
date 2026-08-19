package com.aigate.router.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aigate.router.data.model.AiModel
import com.aigate.router.data.model.Provider
import com.aigate.router.data.model.routeKey
import com.aigate.router.routing.ModelPreference
import com.aigate.router.ui.design.AppCard
import com.aigate.router.ui.design.CardTone
import com.aigate.router.ui.design.EmptyState
import com.aigate.router.ui.design.Fmt
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.ProviderAvatar
import com.aigate.router.ui.design.SectionHeader
import com.aigate.router.ui.design.StatusChip
import com.aigate.router.ui.design.StatusTone
import com.aigate.router.ui.viewmodel.GatewayViewModel

/**
 * Модели, сгруппированные ПО МОДЕЛИ, а не по провайдеру.
 *
 * Клиент присылает имя модели, поэтому важен вопрос «кто её обслужит»: одну и ту
 * же модель могут предоставлять несколько аккаунтов, и порядок внутри группы
 * задаёт, кто первый, а кто резерв. Прежняя группировка по провайдеру на этот
 * вопрос не отвечала.
 */
@Composable
fun ModelsByModelSection(viewModel: GatewayViewModel) {
    val models by viewModel.models.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val speeds by viewModel.latestSpeedHistory.collectAsState()
    var query by remember { mutableStateOf("") }
    // Счётчик заставляет пересчитать порядок после его изменения: он живёт
    // в конфиге, а не в базе, и сам об изменении не сообщает.
    var orderVersion by remember { mutableIntStateOf(0) }
    var detailFor by remember { mutableStateOf<AiModel?>(null) }

    val providersById = remember(providers) { providers.associateBy { it.id } }
    val groups = remember(models, query, orderVersion) {
        buildGroups(models = models, query = query)
    }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Gateway.spacing.sm),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Поиск модели") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (groups.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.SmartToy,
                text = if (query.isBlank()) "Моделей пока нет" else "Ничего не найдено",
            )
            return@Column
        }

        groups.forEach { group ->
            SectionHeader(
                title = group.title,
                action = {
                    Text(
                        text = "${group.rows.size} " +
                            Fmt.plural(group.rows.size.toLong(), "провайдер", "провайдера", "провайдеров"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            group.rows.forEachIndexed { index, model ->
                ModelProviderRow(
                    model = model,
                    provider = providersById[model.providerId],
                    speedText = speeds.firstOrNull { it.modelKey == model.routeKey && it.success }
                        ?.let { "${Fmt.latency(it.ttftMs)} · ${"%.0f".format(it.tps)} ток/с" },
                    // Порядок имеет смысл только когда модель есть у нескольких.
                    canReorder = group.rows.size > 1,
                    isFirst = index == 0,
                    isLast = index == group.rows.lastIndex,
                    onMove = { delta ->
                        val current = group.rows.map { it.providerId }
                        ModelPreference.saveOrder(
                            modelId = group.modelId,
                            order = ModelPreference.move(current, model.providerId, delta),
                        )
                        orderVersion++
                    },
                    onToggle = { viewModel.toggleModelEnabled(model) },
                    onClick = { detailFor = model },
                )
            }
        }
    }

    detailFor?.let { model ->
        ModelDetailSheet(
            model = model,
            provider = providersById[model.providerId],
            onDismiss = { detailFor = null },
        )
    }
}

/** Одна модель у одного провайдера: кто её даёт, как быстро, включена ли. */
@Composable
private fun ModelProviderRow(
    model: AiModel,
    provider: Provider?,
    speedText: String?,
    canReorder: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onMove: (Int) -> Unit,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    AppCard(
        tone = if (model.isEnabled) CardTone.Plain else CardTone.Raised,
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderAvatar(
                name = provider?.name.orEmpty(),
                type = provider?.type.orEmpty(),
                size = 28.dp,
            )
            Spacer(Modifier.width(Gateway.spacing.sm))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // Имя провайдера, а не его тип: аккаунтов одного типа
                        // может быть несколько, и тип их не различает.
                        text = provider?.name ?: "провайдер удалён",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isFirst && canReorder) {
                        Spacer(Modifier.width(Gateway.spacing.sm))
                        StatusChip(text = "первый", tone = StatusTone.Info)
                    }
                }
                Text(
                    text = listOfNotNull(model.customAlias.takeIf { it.isNotBlank() }, speedText)
                        .joinToString(" · ")
                        .ifBlank { "замера нет" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (canReorder) {
                IconButton(onClick = { onMove(-1) }, enabled = !isFirst) {
                    Icon(Icons.Default.ArrowUpward, "Выше", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { onMove(1) }, enabled = !isLast) {
                    Icon(Icons.Default.ArrowDownward, "Ниже", modifier = Modifier.size(18.dp))
                }
            }
            Switch(checked = model.isEnabled, onCheckedChange = { onToggle() })
        }
    }
}

/** Группа: одна модель и провайдеры, которые её предоставляют, в нужном порядке. */
private data class ModelGroup(
    val modelId: String,
    val title: String,
    val rows: List<AiModel>,
)

private fun buildGroups(models: List<AiModel>, query: String): List<ModelGroup> {
    val needle = query.trim().lowercase()
    val filtered = if (needle.isEmpty()) models else models.filter {
        it.modelId.lowercase().contains(needle) ||
            it.displayName.lowercase().contains(needle) ||
            it.customAlias.lowercase().contains(needle)
    }
    return filtered.groupBy { it.modelId }
        .map { (modelId, rows) ->
            val order = ModelPreference.orderFor(modelId, rows.map { it.providerId })
            ModelGroup(
                modelId = modelId,
                title = rows.first().displayName.ifBlank { modelId },
                rows = rows.sortedBy { order.indexOf(it.providerId) },
            )
        }
        .sortedBy { it.title.lowercase() }
}
