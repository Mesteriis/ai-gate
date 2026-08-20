package com.aigate.router.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.aigate.router.catalog.ModelCatalogRepository.CatalogEntry
import com.aigate.router.catalog.ModelCatalogRepository.CatalogSource
import com.aigate.router.catalog.ModelCatalogRepository.CatalogVariant
import com.aigate.router.data.model.LocalModel
import com.aigate.router.download.LocalModelsRepository
import com.aigate.router.ui.design.AppCard
import com.aigate.router.ui.design.CardTone
import com.aigate.router.ui.design.EmptyState
import com.aigate.router.ui.design.EntityCard
import com.aigate.router.ui.design.Fmt
import com.aigate.router.ui.design.Gateway
import com.aigate.router.ui.design.HelpSection
import com.aigate.router.ui.design.QuotaBar
import com.aigate.router.ui.design.SectionHeader
import com.aigate.router.ui.design.StatusTone
import com.aigate.router.ui.design.appear
import com.aigate.router.quota.ResourcePressure
import com.aigate.router.ui.viewmodel.GatewayViewModel
import com.aigate.router.ui.viewmodel.GatewayViewModel.CatalogSearchState
import kotlinx.coroutines.delay

/** Справка раздела «Локальные модели» — вместо подсказок на экране. */
internal val localModelsHelp: List<HelpSection> = listOf(
    HelpSection(
        "Каталог и совместимость",
        "Поиск идёт сразу по двум реестрам: Ollama и Hugging Face. " +
            "В выдачу попадают только те варианты, которые помещаются в память этого " +
            "устройства, — остальные скрыты, и счётчик под результатами говорит, сколько " +
            "их отсеяно. Так список остаётся обещанием: всё, что в нём видно, телефон " +
            "запустит.",
    ),
    HelpSection(
        "Загрузка и место",
        "Файлы моделей весят гигабайты, поэтому качаются по одной и в фоне: за ходом " +
            "видно в уведомлении, приложение можно свернуть. Загрузку разрешено " +
            "приостановить и продолжить с того же места. Готовая модель появляется в " +
            "списке «Модели» и работает без сети и без ключей. Удаление освобождает " +
            "место сразу — файл стирается с устройства.",
    ),
)

/**
 * «Локальные модели» — каталог для скачивания и всё, что уже лежит на диске.
 *
 * Разделы идут в порядке работы: сначала поиск, затем то, что качается прямо
 * сейчас, потом выдача каталога и уже скачанное. Активные загрузки стоят выше
 * результатов намеренно: пока идёт гигабайтная закачка, это главное состояние
 * экрана, и уводить его вниз за длинную выдачу нельзя.
 */
@Composable
fun LocalModelsScreen(
    viewModel: GatewayViewModel,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf(CatalogSource.Ollama) }
    // Третий источник — те же репозитории HuggingFace, но другой запрос:
    // модели .litertlm лежат у автора litert-community и по фильтру GGUF
    // не находятся вовсе.
    var litertOnly by rememberSaveable { mutableStateOf(false) }

    val searchState by viewModel.catalogSearch.collectAsState()
    val localModels by viewModel.localModels.collectAsState()
    val stats by viewModel.storageStats.collectAsState()

    var variantsFor by remember { mutableStateOf<CatalogEntry?>(null) }
    var detailsFor by remember { mutableStateOf<LocalModel?>(null) }

    // Пауза после ввода: реестры отвечают 429 на запрос с каждой буквы, и тогда
    // пользователь не увидит вообще ничего вместо неполной, но рабочей выдачи.
    LaunchedEffect(query, source, litertOnly) {
        delay(SEARCH_DEBOUNCE_MS)
        viewModel.searchCatalog(query, source, litertOnly)
    }

    val active = localModels.filter { it.state != LocalModel.STATE_READY }
    val ready = localModels.filter { it.state == LocalModel.STATE_READY }
    // Занятое место меняется, когда загрузка дошла до конца. Событию «файл
    // дописан» экран не подписан, зато появление модели в готовых — тот же факт.
    LaunchedEffect(ready.size) { viewModel.refreshStorageStats() }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Gateway.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Gateway.spacing.sm),
        contentPadding = PaddingValues(bottom = Gateway.spacing.xxl),
    ) {
        item {
            SearchBlock(
                query = query,
                source = source,
                litertOnly = litertOnly,
                onQueryChange = { query = it },
                onSourceChange = { newSource, newLitert ->
                    source = newSource
                    litertOnly = newLitert
                },
                onSearch = { viewModel.searchCatalog(query, source, litertOnly) },
            )
        }

        if (active.isNotEmpty()) {
            item { SectionHeader("Загрузки") }
            items(active, key = { it.id }) { model ->
                DownloadRow(
                    model = model,
                    onPause = { viewModel.pauseDownload(model.id) },
                    onResume = { viewModel.resumeDownload(model.id) },
                    onCancel = { viewModel.cancelDownload(model.id) },
                )
            }
        }

        when (val state = searchState) {
            CatalogSearchState.Idle -> Unit

            CatalogSearchState.Loading -> item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Gateway.spacing.xl),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }

            is CatalogSearchState.Error -> item {
                EmptyState(
                    icon = Icons.Outlined.CloudOff,
                    text = state.message,
                    actionText = "Повторить",
                    onAction = { viewModel.searchCatalog(query, source) },
                )
            }

            is CatalogSearchState.Results -> {
                if (state.result.entries.isEmpty()) {
                    item { EmptyState(icon = Icons.Outlined.SearchOff, text = "Ничего не найдено") }
                } else {
                    itemsIndexed(
                        items = state.result.entries,
                        key = { _, entry -> entry.id },
                    ) { index, entry ->
                        EntityCard(
                            title = entry.displayName,
                            subtitle = entryMeta(entry),
                            statusText = if (entry.anyVariantDownloaded) "загружена" else null,
                            statusTone = if (entry.anyVariantDownloaded) StatusTone.Success else null,
                            onClick = { variantsFor = entry },
                            modifier = Modifier.appear(index.coerceAtMost(APPEAR_STAGGER_LIMIT)),
                        )
                    }
                }
                if (state.result.hiddenCount > 0) {
                    item {
                        Text(
                            text = "скрыто несовместимых: ${state.result.hiddenCount}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item { SectionHeader("Загруженные") }
        item { StorageCard(stats) }
        if (ready.isEmpty()) {
            item { EmptyState(icon = Icons.Outlined.SmartToy, text = "Загруженных моделей нет") }
        } else {
            itemsIndexed(items = ready, key = { _, model -> model.id }) { index, model ->
                EntityCard(
                    title = model.displayName,
                    subtitle = listOf(
                        model.quant,
                        Fmt.bytes(model.sizeBytes),
                        engineLabel(model.engine),
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                    onClick = { detailsFor = model },
                    modifier = Modifier.appear(index.coerceAtMost(APPEAR_STAGGER_LIMIT)),
                )
            }
        }
    }

    variantsFor?.let { entry ->
        CatalogVariantSheet(
            entry = entry,
            onDownload = { viewModel.downloadModel(entry, it) },
            onDismiss = { variantsFor = null },
        )
    }

    detailsFor?.let { model ->
        DownloadedModelSheet(
            model = model,
            onDelete = { viewModel.deleteLocalModel(model) },
            onDismiss = { detailsFor = null },
        )
    }
}

/** Пауза между вводом и запросом к реестру. */
private const val SEARCH_DEBOUNCE_MS = 400L

/**
 * Предел ступени появления карточек. Дальше задержка index*stagger превратилась
 * бы в ожидание, а карточки всё равно за краем экрана.
 */
private const val APPEAR_STAGGER_LIMIT = 6

/** Человеческое имя движка: в базе он лежит расширением файла, а не названием. */
internal fun engineLabel(engine: String): String = when (engine) {
    "litertlm" -> "LiteRT"
    else -> "llama.cpp"
}

/** Поиск и выбор реестра: источник ровно один, выдачи из двух не смешиваются. */
@Composable
private fun SearchBlock(
    query: String,
    source: CatalogSource,
    litertOnly: Boolean,
    onQueryChange: (String) -> Unit,
    onSourceChange: (CatalogSource, Boolean) -> Unit,
    onSearch: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Gateway.spacing.sm)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("Поиск моделей") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Gateway.spacing.sm)) {
            FilterChip(
                selected = source == CatalogSource.Ollama,
                onClick = { onSourceChange(CatalogSource.Ollama, false) },
                label = { Text("Ollama") },
            )
            FilterChip(
                selected = source == CatalogSource.HuggingFace && !litertOnly,
                onClick = { onSourceChange(CatalogSource.HuggingFace, false) },
                label = { Text("HuggingFace") },
            )
            // Отдельный выбор, а не подпункт HuggingFace: репозитории те же,
            // но запрос другой, и без него модели .litertlm не найти вовсе.
            FilterChip(
                selected = source == CatalogSource.HuggingFace && litertOnly,
                onClick = { onSourceChange(CatalogSource.HuggingFace, true) },
                label = { Text("LiteRT") },
            )
        }
    }
}

/**
 * Строка активной загрузки. Отмена доступна в любом состоянии, включая ошибку:
 * висящая красная строка, которую нельзя убрать, — худшее из состояний экрана.
 */
@Composable
private fun DownloadRow(
    model: LocalModel,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    AppCard(tone = CardTone.Raised) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when (model.state) {
                LocalModel.STATE_DOWNLOADING -> IconButton(onClick = onPause) {
                    Icon(
                        Icons.Default.Pause,
                        contentDescription = "Приостановить загрузку",
                        modifier = Modifier.size(18.dp),
                    )
                }

                LocalModel.STATE_PAUSED -> IconButton(onClick = onResume) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Продолжить загрузку",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Отменить загрузку",
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Spacer(Modifier.size(Gateway.spacing.sm))
        // Доля показывается только там, где она известна и осмысленна: в очереди
        // и на проверке файла байты не растут, а полоса на 40% врала бы о ходе.
        val indeterminate = model.sizeBytes <= 0L ||
            model.state == LocalModel.STATE_QUEUED ||
            model.state == LocalModel.STATE_VERIFYING
        if (indeterminate) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            // Байты приходят порциями на тик воркера: без анимации полоса
            // прыгала бы рывками, поэтому доезжает основной длительностью.
            val fraction = (model.downloadedBytes.toFloat() / model.sizeBytes.toFloat())
                .coerceIn(0f, 1f)
            val progress by animateFloatAsState(
                targetValue = fraction,
                animationSpec = tween(Gateway.motion.normal, easing = Gateway.motion.emphasized),
                label = "download",
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.size(Gateway.spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = downloadStateText(model),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (model.state == LocalModel.STATE_ERROR) {
                TextButton(onClick = onResume) { Text("Повторить") }
            }
        }
    }
}

private fun downloadStateText(model: LocalModel): String = when (model.state) {
    LocalModel.STATE_QUEUED -> "в очереди"
    LocalModel.STATE_VERIFYING -> "проверка файла"
    LocalModel.STATE_PAUSED -> "приостановлено"
    LocalModel.STATE_ERROR -> "ошибка загрузки: ${model.errorMessage}"
    else -> "${Fmt.bytes(model.downloadedBytes)} из ${Fmt.bytes(model.sizeBytes)}"
}

/**
 * Место на диске — витрина раздела: сколько устройство отдало моделям, это то
 * число, за которым сюда возвращаются, поэтому тон Hero и крупное значение.
 * Единственная Hero-карточка экрана.
 *
 * Полоса считается от суммы «занято моделями + свободно»: весь раздел брать
 * нельзя, в него входят система и чужие данные, освободить которые пользователь
 * всё равно не может.
 */
@Composable
private fun StorageCard(stats: LocalModelsRepository.StorageStats?) {
    AppCard(tone = CardTone.Hero) {
        Text(
            text = "Занято моделями",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(Gateway.spacing.xs))
        Text(
            text = stats?.let { Fmt.bytes(it.modelsBytes) } ?: "—",
            style = MaterialTheme.typography.displayLarge,
        )
        Text(
            text = "свободно ${stats?.let { Fmt.bytes(it.freeBytes) } ?: "—"}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(Gateway.spacing.sm))
        QuotaBar(
            fractionUsed = if (stats != null && stats.totalBytes > 0L) {
                stats.modelsBytes.toFloat() / stats.totalBytes.toFloat()
            } else 0f,
            // Давление здесь не считается: место на диске — не квота с периодом
            // и порогом, серая полоса без данных честнее выдуманного статуса.
            pressure = if (stats == null) null else ResourcePressure.NORMAL,
        )
    }
}

/**
 * Подпись записи каталога. Пропущенный кусок именно пропускается: прочерк вместо
 * счётчика загрузок Ollama выглядел бы как «загрузок ноль», хотя реестр этого
 * числа просто не публикует.
 */
private fun entryMeta(entry: CatalogEntry): String {
    val variants = entry.variants.size
    return listOfNotNull(
        entry.paramsLabel,
        variants.takeIf { it > 0 }?.let {
            "$it ${Fmt.plural(it.toLong(), "вариант", "варианта", "вариантов")}"
        },
        sizeRange(entry.variants)?.let { if (entry.source == CatalogSource.Ollama) "≈$it" else it },
        entry.downloads?.let { "${Fmt.compact(it)} загрузок" },
    ).joinToString(" · ")
}

/** Разброс размеров семейства: «2,1–4,8 ГБ», а при одинаковых — один размер. */
private fun sizeRange(variants: List<CatalogVariant>): String? {
    val sizes = variants.map { it.sizeBytes }.filter { it > 0L }
    if (sizes.isEmpty()) return null
    val min = sizes.min()
    val max = sizes.max()
    if (min == max) return Fmt.bytes(min)
    val low = Fmt.bytes(min)
    val high = Fmt.bytes(max)
    // Единица пишется один раз, когда она одна у обоих концов диапазона.
    return if (low.substringAfterLast(' ') == high.substringAfterLast(' ')) {
        "${low.substringBeforeLast(' ')}–$high"
    } else {
        "$low–$high"
    }
}
