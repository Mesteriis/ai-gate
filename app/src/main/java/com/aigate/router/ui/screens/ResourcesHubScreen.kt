package com.aigate.router.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aigate.router.data.model.AiModel
import com.aigate.router.data.model.Provider
import com.aigate.router.ui.design.AppCard
import com.aigate.router.ui.design.CardTone
import com.aigate.router.ui.design.ConfirmDialog
import com.aigate.router.ui.design.EmptyState
import com.aigate.router.ui.design.EntityCard
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.HelpSection
import com.aigate.router.ui.design.ProviderAvatar
import com.aigate.router.ui.design.SectionHeader
import com.aigate.router.ui.design.StatusTone
import com.aigate.router.ui.viewmodel.GatewayViewModel

/** Справка раздела «Ресурсы» — вместо подсказок на экране. */
internal val resourcesHelp = listOf(
    HelpSection(
        "Провайдеры",
        "Провайдер — источник моделей: облачный API или локальный сервер. " +
            "После добавления синхронизируйте список моделей, чтобы шлюз узнал, что доступно.",
    ),
    HelpSection(
        "Порядок провайдеров",
        "Порядок задаёт приоритет при переключении: чем выше провайдер, тем раньше шлюз " +
            "попробует его модели.",
    ),
    HelpSection(
        "Модели",
        "Выключенная модель остаётся в списке, но шлюз её не использует. " +
            "Псевдоним позволяет обращаться к модели своим именем из внешнего приложения.",
    ),
    HelpSection(
        "Квота, баланс и бесплатные модели",
        "Это три разные вещи. Квота подписки расходуется и сбрасывается по периоду. " +
            "Баланс — оплаченные заранее деньги, они уменьшаются и сами не восстанавливаются. " +
            "Локальные модели бесплатны: у них нет ни остатка, ни сброса. " +
            "Прочерк означает, что данных нет — шлюз не подставляет ноль вместо неизвестного значения.",
    ),
)

private enum class ResourceTab(val label: String) {
    Providers("Провайдеры"),
    Models("Модели"),
    Quotas("Лимиты"),
}

/**
 * «Ресурсы» — единый раздел вместо трёх, разбросанных по табам и подменю
 * настроек: провайдеры, их модели и лимиты живут рядом.
 */
@Composable
fun ResourcesHubScreen(
    viewModel: GatewayViewModel,
    modifier: Modifier = Modifier,
    onOpenQuotas: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(ResourceTab.Providers) }

    Column(
        modifier = modifier
            .padding(horizontal = Gateway.spacing.lg)
            .padding(bottom = Gateway.spacing.md),
        verticalArrangement = Arrangement.spacedBy(Gateway.spacing.md),
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ResourceTab.entries.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = tab == item,
                    onClick = { tab = item },
                    shape = SegmentedButtonDefaults.itemShape(index, ResourceTab.entries.size),
                    label = { Text(item.label, maxLines = 1) },
                )
            }
        }

        when (tab) {
            ResourceTab.Providers -> ProvidersSection(viewModel)
            ResourceTab.Models -> ModelsSection(viewModel)
            ResourceTab.Quotas -> QuotasSection(onOpenQuotas)
        }
    }
}

@Composable
private fun ProvidersSection(viewModel: GatewayViewModel) {
    val providers by viewModel.providers.collectAsState()
    val models by viewModel.models.collectAsState()
    val showAdd by viewModel.showAddProviderDialog.collectAsState()
    val editProvider by viewModel.showEditProviderDialog.collectAsState()
    var pendingDelete by remember { mutableStateOf<Provider?>(null) }
    var showConnect by remember { mutableStateOf(false) }
    val sorted = remember(providers) { providers.sortedBy { it.orderIndex } }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Gateway.spacing.sm),
    ) {
        // Подключение — первое действие раздела, а не пункт, спрятанный в лимитах.
        Button(
            onClick = { showConnect = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Gateway.spacing.sm))
            Text("Подключить провайдера")
        }

        if (sorted.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Hub,
                text = "Провайдеров пока нет",
                actionText = "Подключить",
                onAction = { showConnect = true },
            )
        } else {
            sorted.forEachIndexed { index, provider ->
                val modelCount = models.count { it.providerId == provider.id }
                EntityCard(
                    title = provider.name,
                    subtitle = provider.resolvedBaseUrl,
                    leading = { ProviderAvatar(name = provider.name, type = provider.type) },
                    statusText = if (provider.isEnabled) "Включён" else "Выключен",
                    statusTone = if (provider.isEnabled) StatusTone.Success else StatusTone.Neutral,
                    dimmed = !provider.isEnabled,
                    trailing = {
                        Switch(
                            checked = provider.isEnabled,
                            onCheckedChange = { viewModel.toggleProviderEnabled(provider) },
                        )
                    },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "$modelCount ${modelsWord(modelCount)} · ${provider.type}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // Порядок меняется прямо в строке: без long-press и без
                        // диалога, который раньше закрывался после каждого шага.
                        IconButton(
                            onClick = { viewModel.moveProvider(provider, -1) },
                            enabled = index > 0,
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Выше", modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = { viewModel.moveProvider(provider, 1) },
                            enabled = index < sorted.lastIndex,
                        ) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Ниже", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { viewModel.syncModels(provider) }) {
                            Icon(
                                Icons.Default.Sync,
                                contentDescription = "Синхронизировать модели",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(onClick = { viewModel.showEditProvider(provider) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Изменить", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { pendingDelete = provider }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Удалить",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.size(Gateway.spacing.sm))
            Button(
                onClick = { viewModel.showAddProvider() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Gateway.spacing.sm))
                Text("Добавить провайдера")
            }
        }
    }

    // Удаление провайдера уносит его модели и историю — спрашиваем подтверждение.
    pendingDelete?.let { provider ->
        ConfirmDialog(
            title = "Удалить провайдера?",
            message = "«${provider.name}» и его модели будут удалены. Действие необратимо.",
            confirmText = "Удалить",
            onConfirm = { viewModel.deleteProvider(provider) },
            onDismiss = { pendingDelete = null },
        )
    }

    if (showConnect) {
        ConnectProviderSheet(
            viewModel = viewModel,
            onDismiss = { showConnect = false },
            // «Свой сервис» — единственный путь, где адрес и путь вводятся руками.
            onManual = { viewModel.showAddProvider() },
        )
    }
    if (showAdd) {
        AddProviderSheet(viewModel = viewModel, onDismiss = { viewModel.hideAddProvider() })
    }
    editProvider?.let { provider ->
        EditProviderSheet(
            provider = provider,
            onDismiss = { viewModel.hideEditProvider() },
            onSave = { updated, apiKey -> viewModel.updateProvider(updated, apiKey) },
        )
    }
}

@Composable
private fun ModelsSection(viewModel: GatewayViewModel) {
    // Каталог моделей уже реализован (поиск, пакетный тест, псевдонимы) —
    // переиспользуем его здесь, а не дублируем.
    ModelsScreen(viewModel)
}

@Composable
private fun QuotasSection(onOpenQuotas: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Gateway.spacing.md)) {
        SectionHeader("Лимиты и цены")
        AppCard(tone = CardTone.Raised, onClick = onOpenQuotas) {
            Text(
                text = "Квоты подписок, балансы, свои бюджеты, цены моделей и CLI-сессии",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(Gateway.spacing.sm))
            TextButton(onClick = onOpenQuotas) { Text("Открыть") }
        }
    }
}

private fun modelsWord(count: Int): String = when {
    count % 10 == 1 && count % 100 != 11 -> "модель"
    count % 10 in 2..4 && count % 100 !in 12..14 -> "модели"
    else -> "моделей"
}
