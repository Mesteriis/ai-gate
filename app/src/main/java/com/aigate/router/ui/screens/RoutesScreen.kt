package com.aigate.router.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import com.aigate.router.service.GatewayForegroundService
import com.aigate.router.routing.RouteStrategy
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aigate.router.GatewayApplication
import com.aigate.router.data.model.RoutingRule
import com.aigate.router.data.model.routeKey
import com.aigate.router.pricing.CostCalculator
import com.aigate.router.ui.design.AppCard
import com.aigate.router.ui.design.CardTone
import com.aigate.router.ui.design.EmptyState
import com.aigate.router.ui.design.EntityCard
import com.aigate.router.ui.design.Fmt
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.HelpSection
import com.aigate.router.ui.design.SectionHeader
import com.aigate.router.ui.design.StatusTone
import com.aigate.router.ui.design.appear
import com.aigate.router.ui.design.charts.BarDatum
import com.aigate.router.ui.design.charts.HorizontalBarChart
import com.aigate.router.ui.viewmodel.GatewayViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Справка экрана «Маршруты» — сюда переехали подзаголовок и навигационная
 * подсказка, которые раньше висели текстом прямо на экране.
 */
internal val routesHelp: List<HelpSection> = listOf(
    HelpSection(
        "Пресеты маршрутизации",
        "Именованные пресеты — надстройка поверх правил маршрутизации. Активен ровно один " +
            "вариант: «Авто» означает, что пресет не выбран и шлюз идёт по общему порядку правил.",
    ),
    HelpSection(
        "Чем отличаются пресеты",
        "«Скорость» — быстрейшая доступная модель по времени до первого токена. " +
            "«Экономия» — минимальная стоимость по прайсингу, когда он доступен. " +
            "«Локально» — только локальные модели (Ollama и подобные). " +
            "Какой провайдер обслужит конкретную модель, задаётся порядком внутри модели " +
            "в разделе «Ресурсы».",
    ),
    HelpSection(
        "Данные под пресетом",
        "В карточке активного пресета показаны те же величины, по которым он сравнивает модели: " +
            "время до первого токена из последнего теста скорости и цена за 1M токенов из таблицы " +
            "цен. Пустая карточка означает, что таких измерений или цен ещё нет.",
    ),
    HelpSection(
        "Модель вручную",
        "Выбранная вручную модель используется вместо автоматического рейтинга, пока выбор не " +
            "сброшен кнопкой «Сбросить».",
    ),
    HelpSection(
        "Тонкая настройка",
        "Отдельные правила маршрутизации — путь, шаблон модели, префикс ключа, блокировка — " +
            "настраиваются в правилах маршрутизации в настройках.",
    ),
)

/** Величина, по которой пресет сравнивает модели, — она же рисуется в его карточке. */
private enum class RouteBasis { Latency, Price, None }

/**
 * Именованный пресет маршрутизации. За каждым стоит RoutingRule с name == [id],
 * поэтому идентификаторы и записываемые поля правила менять нельзя.
 */
private data class RoutePreset(
    val id: String,
    val name: String,
    val criterion: String,
    val icon: ImageVector,
    val basis: RouteBasis,
)

private val routePresets = listOf(
    RoutePreset(
        id = "route:fast",
        name = "Скорость",
        criterion = "по времени первого токена",
        icon = Icons.Outlined.Bolt,
        basis = RouteBasis.Latency,
    ),
    RoutePreset(
        id = "route:cheap",
        name = "Экономия",
        criterion = "по цене за 1M токенов",
        icon = Icons.Outlined.Savings,
        basis = RouteBasis.Price,
    ),
    RoutePreset(
        id = "route:offline",
        name = "Локально",
        criterion = "только локальные модели",
        icon = Icons.Outlined.Dns,
        basis = RouteBasis.None,
    ),
)

private val presetIds: Set<String> = routePresets.map { it.id }.toSet()

/** Сколько моделей показывать в ручном списке до раскрытия. */
private const val MANUAL_LIST_LIMIT = 8

/** Ручной список идёт шестым блоком экрана — с этого места продолжается волна входа. */
private const val MANUAL_APPEAR_BASE = 6

/** Потолок задержки: дальше волна перестаёт читаться и превращается в ожидание. */
private const val APPEAR_INDEX_MAX = 10

/** Доступная модель с человеческим именем и измеримыми характеристиками. */
private data class ModelChoice(
    val routeKey: String,
    val modelId: String,
    val providerId: Long,
    val name: String,
    val providerName: String,
    /** Время до первого токена последнего удачного замера; null — замера нет. */
    val ttftMs: Long?,
    /** Цена за 1M токенов (вход + выход); null — цена неизвестна. */
    val pricePer1M: Double?,
)

private fun ModelChoice.details(): String = buildList {
    if (providerName.isNotBlank()) add(providerName)
    add(ttftMs?.let { Fmt.latency(it) } ?: "нет замера")
    pricePer1M?.let { add("${Fmt.usd(it)} / 1M") }
}.joinToString(" · ")

/**
 * «Маршруты» — выбор пресета маршрутизации (взаимоисключающий, поэтому радио,
 * а не четыре независимых переключателя) с данными, которые обосновывают выбор,
 * и ручное закрепление модели. Инструкции живут в [routesHelp].
 */
/**
 * Переключение на другую модель, когда запрошенная не отвечает. Резерв внутри
 * одной модели работает всегда; этот переключатель разрешает уходить и на
 * другие модели, то есть отвечать не тем, что просил клиент.
 */
@Composable
private fun FailoverRow(viewModel: GatewayViewModel, modifier: Modifier = Modifier) {
    val enabled by viewModel.autoFailover.collectAsState()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Переключаться на другую модель", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (enabled) "разрешено" else "только резерв внутри той же модели",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = { viewModel.toggleAutoFailover() })
    }
}

/**
 * Стратегия автовыбора модели. Переехала из таба «Лимиты»: это про
 * маршрутизацию, а не про ресурсы.
 */
@Composable
private fun StrategyRow(modifier: Modifier = Modifier) {
    var strategy by remember {
        mutableStateOf(
            RouteStrategy.fromName(
                GatewayForegroundService.getGatewayConfig(RouteStrategy.CONFIG_KEY, RouteStrategy.AUTO.name)
            )
        )
    }
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Gateway.spacing.sm),
    ) {
        RouteStrategy.entries.forEach { item ->
            FilterChip(
                selected = strategy == item,
                onClick = {
                    strategy = item
                    GatewayForegroundService.saveGatewayConfig(RouteStrategy.CONFIG_KEY, item.name)
                },
                label = { Text(strategyLabel(item)) },
            )
        }
    }
}

/** Подписи стратегий. «Качество» убрано: порядок задаётся внутри модели. */
private fun strategyLabel(strategy: RouteStrategy): String = when (strategy) {
    RouteStrategy.AUTO -> "Авто"
    RouteStrategy.FAST -> "Быстрее"
    RouteStrategy.CHEAP -> "Дешевле"
    RouteStrategy.OFFLINE -> "Локально"
    RouteStrategy.QUOTA -> "По остатку квоты"
}

@Composable
fun RoutesScreen(viewModel: GatewayViewModel, modifier: Modifier = Modifier) {
    val db = remember { GatewayApplication.getInstance().database }
    // Состояние правил берём из Flow: Room сам присылает новое значение после
    // записи, поэтому оптимистичные копии и reload по таймеру не нужны.
    val rules by remember { db.routingRuleDao().getAllRules() }.collectAsState(initial = emptyList())
    val latestSpeed by viewModel.latestSpeedHistory.collectAsState()
    val enabledModels by viewModel.enabledModels.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val forcedKey by viewModel.forcedModelKey.collectAsState()

    // Резолв цены ходит в БД по каждой модели (точное совпадение → wildcard →
    // базовое имя → встроенная таблица), поэтому считаем в IO.
    val prices by produceState(initialValue = emptyMap<String, Double>(), enabledModels, providers) {
        val providerTypes = providers.associate { it.id to it.type }
        value = withContext(Dispatchers.IO) {
            enabledModels.mapNotNull { model ->
                val type = providerTypes[model.providerId] ?: return@mapNotNull null
                val price = CostCalculator.priceFor(db, type, model.modelId) ?: return@mapNotNull null
                model.routeKey to (price.inputPer1M + price.outputPer1M)
            }.toMap()
        }
    }

    val choices = remember(enabledModels, latestSpeed, providers, prices) {
        val measuredByKey = latestSpeed.associateBy { it.modelKey }
        val providerNames = providers.associate { it.id to it.name }
        enabledModels.map { model ->
            val measured = measuredByKey[model.routeKey]?.takeIf { it.success }
            ModelChoice(
                routeKey = model.routeKey,
                modelId = model.modelId,
                providerId = model.providerId,
                name = model.customAlias.ifBlank { model.displayName },
                providerName = providerNames[model.providerId].orEmpty(),
                ttftMs = measured?.ttftMs?.takeIf { it > 0 },
                pricePer1M = prices[model.routeKey],
            )
        }.sortedWith(compareBy<ModelChoice>({ it.ttftMs ?: Long.MAX_VALUE }, { it.name }))
    }

    val activePreset = routePresets.firstOrNull { preset ->
        rules.any { it.name == preset.id && it.enabled }
    }

    /**
     * Ровно один активный пресет: включаем целевое правило и гасим остальные.
     * Поля правила (action/modelPattern/priority) сохранены как были — их читает
     * RoutingRuleManager.
     */
    fun selectPreset(target: RoutePreset?) {
        if (target?.id == activePreset?.id) return
        rules.filter { it.enabled && it.name in presetIds && it.name != target?.id }
            .forEach { viewModel.setRoutingRuleEnabled(it.id, false) }
        val preset = target ?: return
        val existing = rules.firstOrNull { it.name == preset.id }
        if (existing == null) {
            viewModel.saveRoutingRule(
                RoutingRule(
                    name = preset.id,
                    enabled = true,
                    action = "route",
                    modelPattern = "*",
                    priority = routePresets.indexOf(preset),
                )
            )
        } else if (!existing.enabled) {
            viewModel.setRoutingRuleEnabled(existing.id, true)
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gateway.spacing.lg)
            .padding(bottom = Gateway.spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Gateway.spacing.md),
    ) {
        SectionHeader("Пресет маршрутизации")

        PresetOption(
            icon = Icons.Outlined.AutoAwesome,
            name = "Авто",
            criterion = "без пресета",
            selected = activePreset == null,
            basis = RouteBasis.None,
            choices = emptyList(),
            onSelect = { selectPreset(null) },
            modifier = Modifier.appear(index = 0),
        )

        routePresets.forEachIndexed { index, preset ->
            PresetOption(
                icon = preset.icon,
                name = preset.name,
                criterion = preset.criterion,
                selected = preset.id == activePreset?.id,
                basis = preset.basis,
                choices = choices,
                onSelect = { selectPreset(preset) },
                modifier = Modifier.appear(index = index + 1),
            )
        }

        SectionHeader("Автопереключение при сбое")
        FailoverRow(viewModel, modifier = Modifier.appear(index = 4))

        SectionHeader("Стратегия при автовыборе")
        StrategyRow(modifier = Modifier.appear(index = 5))

        if (forcedKey.isNotBlank()) {
            SectionHeader(
                title = "Модель вручную",
                action = {
                    TextButton(onClick = { viewModel.clearForcedModel() }) { Text("Сбросить") }
                },
            )
        } else {
            SectionHeader("Модель вручную")
        }

        if (choices.isEmpty()) {
            EmptyState(icon = Icons.Outlined.Dns, text = "Нет доступных моделей")
        } else {
            // Список не ленивый (он внутри verticalScroll), поэтому длинный
            // каталог моделей раскрывается по требованию, а не строится целиком.
            var expanded by remember { mutableStateOf(false) }
            val head = choices.take(MANUAL_LIST_LIMIT)
            val tail = choices.drop(MANUAL_LIST_LIMIT)
            head.forEachIndexed { index, choice ->
                ManualModelCard(
                    choice = choice,
                    selected = choice.routeKey == forcedKey,
                    onPick = { viewModel.forceModel(choice.modelId, choice.providerId) },
                    modifier = Modifier.appear(
                        index = (MANUAL_APPEAR_BASE + index).coerceAtMost(APPEAR_INDEX_MAX),
                    ),
                )
            }
            if (tail.isNotEmpty()) {
                AnimatedVisibility(
                    visible = expanded,
                    // Хвост каталога выезжает вместе с ростом высоты: без этого
                    // список дёргался бы на десятки строк за один кадр.
                    enter = fadeIn(tween(Gateway.motion.normal)) +
                        expandVertically(tween(Gateway.motion.normal)),
                    exit = fadeOut(tween(Gateway.motion.normal)) +
                        shrinkVertically(tween(Gateway.motion.normal)),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Gateway.spacing.md)) {
                        // Своего appear у этих карточек нет: их появление уже
                        // ведёт раскрытие, две анимации спорили бы друг с другом.
                        tail.forEach { choice ->
                            ManualModelCard(
                                choice = choice,
                                selected = choice.routeKey == forcedKey,
                                onPick = { viewModel.forceModel(choice.modelId, choice.providerId) },
                            )
                        }
                    }
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Свернуть" else "Ещё ${tail.size}")
                }
            }
        }
    }
}

/** Вариант пресета: выделенная карточка + радио, у активного — его данные. */
@Composable
private fun PresetOption(
    icon: ImageVector,
    name: String,
    criterion: String,
    selected: Boolean,
    basis: RouteBasis,
    choices: List<ModelChoice>,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Знак пресета переходит в брендовый цвет, а не переключается кадром: выбор
    // одного из четырёх вариантов должен читаться как перетекание выделения.
    val iconTint by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(Gateway.motion.normal, easing = Gateway.motion.emphasized),
        label = "preset-icon",
    )
    AppCard(
        modifier = modifier,
        tone = if (selected) CardTone.Accent else CardTone.Plain,
        onClick = onSelect,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(Gateway.spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = criterion,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(Gateway.spacing.sm))
            RadioButton(selected = selected, onClick = onSelect)
        }
        // Данные пресета не появляются рывком: карточка дорастает до графика,
        // поэтому соседние карточки съезжают плавно, а не перескакивают.
        AnimatedVisibility(
            visible = selected && basis != RouteBasis.None,
            enter = fadeIn(tween(Gateway.motion.normal)) +
                expandVertically(tween(Gateway.motion.normal)),
            exit = fadeOut(tween(Gateway.motion.fast)) +
                shrinkVertically(tween(Gateway.motion.normal)),
        ) {
            Column {
                Spacer(Modifier.size(Gateway.spacing.md))
                BasisChart(basis = basis, choices = choices)
            }
        }
    }
}

/**
 * Строка ручного выбора модели. Вынесена из экрана, потому что рисуется дважды:
 * в видимой части списка и в раскрываемом хвосте.
 */
@Composable
private fun ManualModelCard(
    choice: ModelChoice,
    selected: Boolean,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Повторный выбор активной модели не пишется: это была бы запись без
    // изменения, а сброс делается отдельной кнопкой «Сбросить».
    val pick = { if (!selected) onPick() }
    EntityCard(
        title = choice.name,
        modifier = modifier,
        subtitle = choice.details(),
        statusText = if (selected) "Активна" else null,
        statusTone = if (selected) StatusTone.Success else null,
        onClick = pick,
        trailing = { RadioButton(selected = selected, onClick = pick) },
    )
}

/** Сравнение доступных моделей по той величине, которой руководствуется пресет. */
@Composable
private fun BasisChart(basis: RouteBasis, choices: List<ModelChoice>) {
    when (basis) {
        RouteBasis.Latency -> {
            val data = choices.mapNotNull { choice ->
                choice.ttftMs?.let { BarDatum(choice.name, it.toFloat()) }
            }
            if (data.isEmpty()) {
                EmptyState(icon = Icons.Outlined.Speed, text = "Нет замеров скорости")
            } else {
                BasisCaption("Первый токен · меньше — лучше")
                HorizontalBarChart(
                    data = data,
                    valueLabel = { Fmt.latency(it.toLong()) },
                    maxBars = 6,
                )
            }
        }

        RouteBasis.Price -> {
            val data = choices.mapNotNull { choice ->
                choice.pricePer1M?.let { BarDatum(choice.name, it.toFloat()) }
            }
            if (data.isEmpty()) {
                EmptyState(icon = Icons.Outlined.Payments, text = "Нет цен на модели")
            } else {
                BasisCaption("Цена за 1M токенов (вход + выход) · меньше — лучше")
                HorizontalBarChart(
                    data = data,
                    valueLabel = { Fmt.usd(it.toDouble()) },
                    maxBars = 6,
                )
            }
        }

        RouteBasis.None -> Unit
    }
}

/** Подпись величины графика — единица измерения, а не подсказка пользователю. */
@Composable
private fun BasisCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.size(Gateway.spacing.sm))
}
