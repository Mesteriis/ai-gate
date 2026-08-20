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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aigate.router.data.model.LocalModel
import com.aigate.router.data.model.Provider
import com.aigate.router.ui.design.AppCard
import com.aigate.router.ui.design.CardTone
import com.aigate.router.ui.design.EmptyState
import com.aigate.router.ui.design.Fmt
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.HelpSection
import com.aigate.router.ui.design.ProviderAvatar
import com.aigate.router.ui.design.StatusChip
import com.aigate.router.ui.design.StatusTone
import com.aigate.router.ui.design.appear
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
            "раздел открывается карточкой над переключателем разделов. " +
            "На устройствах без подходящего движка карточки нет — там эта " +
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

/** Потолок задержки появления: дальше волна перестаёт читаться и просто тормозит. */
private const val PROVIDER_APPEAR_MAX = 6

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
        // Витрины нет совсем, а не выключенной: без движка на устройстве скачанный
        // файл модели запустить нечем, и вход в раздел был бы дорогой в тупик.
        if (localEnginesSupported) {
            LocalModelsHero(
                readyCount = localModels.count { it.state == LocalModel.STATE_READY },
                occupiedBytes = storageStats?.modelsBytes,
                onOpen = onOpenLocalModels,
                modifier = Modifier.appear(index = 0),
            )
        }

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().appear(index = 1)) {
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

/**
 * Витрина локальных моделей — единственная Hero-карточка экрана. Раздел важнее
 * переключателя под ним: модели на устройстве работают без сети и без ключей,
 * поэтому вход в них выглядит как главный объект, а не как строка настроек.
 */
@Composable
private fun LocalModelsHero(
    readyCount: Int,
    occupiedBytes: Long?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val empty = readyCount == 0
    AppCard(modifier = modifier, tone = CardTone.Hero, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Storage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(Gateway.spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Локальные модели",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(Gateway.spacing.xs))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        // Число набирается крупнее фразы: у пустого раздела
                        // главное значение — это текст, и в размере цифры он
                        // не поместился бы в строку даже на узком экране.
                        text = if (empty) "Ничего не загружено" else "$readyCount",
                        style = if (empty) MaterialTheme.typography.headlineSmall
                        else MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (!empty) {
                        Spacer(Modifier.width(Gateway.spacing.xs))
                        Text(
                            text = Fmt.plural(
                                readyCount.toLong(),
                                "модель",
                                "модели",
                                "моделей",
                            ) + " на устройстве",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // Прижимаем подпись к базовой линии числа, как в MetricTile.
                            modifier = Modifier.padding(bottom = Gateway.spacing.xs),
                        )
                    }
                }
                if (!empty) {
                    Text(
                        // Прочерк, а не ноль: пока размер не посчитан, «0 Б»
                        // читалось бы как «файлы места не занимают».
                        text = "занято " + (occupiedBytes?.let { Fmt.bytes(it) } ?: "—"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            modifier = Modifier.fillMaxWidth().appear(index = 0),
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
            providers.forEachIndexed { index, provider ->
                ProviderCard(
                    provider = provider,
                    modelCount = models.count { it.providerId == provider.id },
                    onOpen = { sheetFor = provider },
                    onToggle = { viewModel.toggleProviderEnabled(provider) },
                    onSync = { viewModel.syncModels(provider) },
                    // Задержку упираем в потолок: у владельца десятка провайдеров
                    // волна входа иначе тянулась бы почти секунду.
                    modifier = Modifier.appear(
                        index = (index + 1).coerceAtMost(PROVIDER_APPEAR_MAX),
                    ),
                )
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

/**
 * Карточка провайдера. Тип вынесен надстрочной подписью, а не в заголовок: он
 * одинаков у нескольких аккаунтов и их не различает, различает имя владельца.
 * Число моделей набрано крупно, потому что именно оно отвечает на главный
 * вопрос списка — даёт ли этот провайдер шлюзу хоть что-нибудь.
 */
@Composable
private fun ProviderCard(
    provider: Provider,
    modelCount: Int,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimmed = !provider.isEnabled
    val titleColor = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant
    else MaterialTheme.colorScheme.onSurface
    AppCard(
        modifier = modifier,
        tone = if (dimmed) CardTone.Raised else CardTone.Plain,
        onClick = onOpen,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderAvatar(name = provider.name, type = provider.type)
            Spacer(Modifier.width(Gateway.spacing.md))
            Column(Modifier.weight(1f)) {
                // Тип показываем только когда он добавляет смысл: у провайдера
                // с именем по бренду подпись «openai» под «OpenAI» была бы
                // тавтологией, а у своего сервера тип — единственная подсказка,
                // по какому протоколу он отвечает.
                provider.type
                    .takeIf { !provider.name.equals(it, ignoreCase = true) }
                    ?.let { type ->
                        Text(
                            text = type,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = titleColor,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(Gateway.spacing.sm))
                    StatusChip(
                        text = if (provider.isEnabled) "Включён" else "Выключен",
                        tone = if (provider.isEnabled) StatusTone.Success else StatusTone.Neutral,
                        withDot = true,
                    )
                }
                Text(
                    text = provider.resolvedBaseUrl,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(Gateway.spacing.sm))
            Switch(checked = provider.isEnabled, onCheckedChange = { onToggle() })
        }
        Spacer(Modifier.size(Gateway.spacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$modelCount " +
                    Fmt.plural(modelCount.toLong(), "модель", "модели", "моделей"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSync) {
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

@Composable
private fun ModelsSection(viewModel: GatewayViewModel) {
    // Группировка по модели: важно, кто обслужит запрошенную модель, а не
    // какому провайдеру она принадлежит.
    ModelsByModelSection(viewModel)
}

