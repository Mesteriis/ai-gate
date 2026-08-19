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
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import com.aigate.router.data.model.LocalModel
import com.aigate.router.data.model.Provider
import com.aigate.router.ui.design.AppCard
import com.aigate.router.ui.design.CardTone
import com.aigate.router.ui.design.ConfirmDialog
import com.aigate.router.ui.design.EmptyState
import com.aigate.router.ui.design.Fmt
import com.aigate.router.ui.design.EntityCard
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.HelpSection
import com.aigate.router.ui.design.ProviderAvatar
import com.aigate.router.ui.design.SectionHeader
import com.aigate.router.ui.design.SettingsRow
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
        "Локальные модели",
        "Модель можно скачать на устройство и запускать её без сети и без ключей: " +
            "раздел открывается строкой над переключателем разделов. " +
            "На устройствах без подходящего движка строка не показывается — там эта " +
            "возможность просто отсутствует.",
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
}

/**
 * «Ресурсы» — единый раздел вместо трёх, разбросанных по табам и подменю
 * настроек: провайдеры, их модели и лимиты живут рядом.
 */
@Composable
fun ResourcesHubScreen(
    viewModel: GatewayViewModel,
    modifier: Modifier = Modifier,
    onOpenLocalModels: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(ResourceTab.Providers) }
    val localEnginesSupported by viewModel.localEnginesSupported.collectAsState()
    val localModels by viewModel.localModels.collectAsState()
    val storageStats by viewModel.storageStats.collectAsState()

    Column(
        modifier = modifier
            .padding(horizontal = Gateway.spacing.lg)
            .padding(bottom = Gateway.spacing.md),
        verticalArrangement = Arrangement.spacedBy(Gateway.spacing.md),
    ) {
        // Строки нет совсем, а не выключенной: без движка на устройстве скачанный
        // файл модели запустить нечем, и вход в раздел был бы дорогой в тупик.
        if (localEnginesSupported) {
            val readyCount = localModels.count { it.state == LocalModel.STATE_READY }
            Surface(
                color = Gateway.colors.surfaceContainerLow,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SettingsRow(
                    title = "Локальные модели",
                    icon = Icons.Outlined.Storage,
                    valueText = if (readyCount == 0) {
                        "ничего не загружено"
                    } else {
                        "$readyCount на устройстве · " +
                            (storageStats?.let { Fmt.bytes(it.modelsBytes) } ?: "—")
                    },
                    onClick = onOpenLocalModels,
                )
            }
        }

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
        }
    }
}

@Composable
private fun ProvidersSection(viewModel: GatewayViewModel) {
    val providers by viewModel.providers.collectAsState()
    val models by viewModel.models.collectAsState()
    val showAdd by viewModel.showAddProviderDialog.collectAsState()
    val editProvider by viewModel.showEditProviderDialog.collectAsState()
    var showConnect by remember { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<Provider?>(null) }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Gateway.spacing.sm),
    ) {
        // Подключение — первое действие раздела; вход через браузер стоит здесь
        // же, потому что провайдер с OAuth-сессией — такой же поставщик.
        Button(
            onClick = { showConnect = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Gateway.spacing.sm))
            Text("Подключить провайдера")
        }

        if (providers.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Hub,
                text = "Провайдеров пока нет",
                actionText = "Подключить",
                onAction = { showConnect = true },
            )
        } else {
            providers.forEach { provider ->
                val modelCount = models.count { it.providerId == provider.id }
                EntityCard(
                    // Имя задаёт владелец, и оно же показывается везде: тип
                    // одинаков у нескольких аккаунтов и их не различает.
                    title = provider.name,
                    subtitle = provider.resolvedBaseUrl,
                    leading = { ProviderAvatar(name = provider.name, type = provider.type) },
                    statusText = if (provider.isEnabled) "Включён" else "Выключен",
                    statusTone = if (provider.isEnabled) StatusTone.Success else StatusTone.Neutral,
                    dimmed = !provider.isEnabled,
                    onClick = { sheetFor = provider },
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
                            text = "$modelCount " +
                                Fmt.plural(modelCount.toLong(), "модель", "модели", "моделей"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { viewModel.syncModels(provider) }) {
                            Icon(
                                Icons.Default.Sync,
                                contentDescription = "Синхронизировать модели",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    sheetFor?.let { provider ->
        ProviderSheet(
            provider = provider,
            modelCount = models.count { it.providerId == provider.id },
            onDismiss = { sheetFor = null },
            onEdit = {
                sheetFor = null
                viewModel.showEditProvider(provider)
            },
            onDelete = {
                sheetFor = null
                viewModel.deleteProvider(provider)
            },
            onSyncModels = {
                sheetFor = null
                viewModel.syncModels(provider)
            },
        )
    }

    if (showConnect) {
        ConnectProviderSheet(
            viewModel = viewModel,
            onDismiss = { showConnect = false },
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
            onSave = { updated, key -> viewModel.updateProvider(updated, key) },
        )
    }
}

@Composable
private fun ModelsSection(viewModel: GatewayViewModel) {
    // Группировка по модели: важно, кто обслужит запрошенную модель, а не
    // какому провайдеру она принадлежит.
    ModelsByModelSection(viewModel)
}

