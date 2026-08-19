package com.aigate.router.catalog

import android.content.Context
import com.aigate.router.capability.CapabilityGate
import com.aigate.router.capability.DeviceCapabilityProvider
import com.aigate.router.capability.DeviceCaps
import com.aigate.router.capability.GateResult
import com.aigate.router.capability.ModelDemand
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.data.model.LocalModel
import com.aigate.router.gateway.local.EngineKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

/*
 * Каталог моделей для экрана установки: что показать пользователю и что
 * разрешить скачать.
 *
 * Сеть живёт в ModelRegistrySearch, разбор — в парсерах, а здесь принимается
 * единственное решение, ради которого этот слой и существует: ЖЁСТКОЕ СКРЫТИЕ
 * вариантов, которые устройство не потянет.
 *
 * Почему скрытие, а не предупреждение. Список моделей — это обещание: всё, что
 * в нём есть, телефон запустит. Показать модель со значком «может не влезть»
 * значит переложить на человека расчёт, который он не может сделать (он не
 * знает ни размера KV-кеша, ни бюджета памяти приложения), и почти наверняка
 * получить убитый системой процесс на середине первой же генерации — Android
 * не даёт поймать нехватку памяти при выделении буферов движка. Поэтому
 * непроходной вариант в список НЕ попадает, а попадает в счётчик скрытых:
 * пользователь видит, что каталог шире, но не видит ловушки.
 */
object ModelCatalogRepository {

    /** Откуда берётся запись каталога. */
    enum class CatalogSource { Ollama, HuggingFace }

    /**
     * Один скачиваемый файл модели.
     *
     * [ref] однозначно задаёт файл внутри [CatalogEntry.repo]: тег для Ollama
     * («4b»), путь файла для Hugging Face («Qwen3-4B-Q4_K_M.gguf»). Вместе с
     * источником и репозиторием это тот же ключ, что и у записи в базе
     * скачанных моделей, поэтому [downloaded] считается прямым сравнением.
     *
     * [sha256] может отсутствовать: у курируемых тегов Ollama сумма известна
     * только из манифеста, а у мелких файлов HF без LFS её нет вовсе.
     */
    data class CatalogVariant(
        val ref: String,
        val quant: String,
        val sizeBytes: Long,
        val paramsB: Double?,
        val sha256: String?,
        val engine: EngineKind,
        val downloaded: Boolean,
    )

    /**
     * Семейство моделей: одна карточка в списке.
     *
     * [hiddenVariantsCount] — сколько вариантов этого же семейства отсеял гейт
     * устройства. Число показывается пользователю («ещё 3 варианта не подходят
     * устройству»), чтобы отсутствие привычного размера в списке не выглядело
     * поломкой каталога.
     */
    data class CatalogEntry(
        val id: String,
        val source: CatalogSource,
        val repo: String,
        val displayName: String,
        val description: String?,
        val downloads: Long?,
        val paramsLabel: String?,
        val variants: List<CatalogVariant>,
        val hiddenVariantsCount: Int,
        val anyVariantDownloaded: Boolean,
    )

    /**
     * Итог поиска. [hiddenCount] считает ВСЕ отсеянные варианты, включая те,
     * что принадлежали записям, выпавшим из выдачи целиком: иначе на слабом
     * устройстве пустой список выглядел бы как «ничего не нашлось», хотя на
     * самом деле не подошло ничего из найденного.
     */
    data class SearchResult(
        val entries: List<CatalogEntry>,
        val hiddenCount: Int,
    )

    /**
     * Точные данные для скачивания, полученные перед самой загрузкой.
     *
     * [sha256] пустой строкой означает, что источник контрольной суммы не дал,
     * — проверять целостность будет нечем, и это должно быть видно очереди
     * загрузок, а не подменяться выдуманным значением.
     */
    data class ResolvedDownload(
        val sizeBytes: Long,
        val sha256: String,
        val fileName: String,
    )

    private const val CURATED_ASSET = "ollama_catalog.json"

    /** Тег Ollama по умолчанию, если пользователь ввёл имя без двоеточия. */
    private const val DEFAULT_OLLAMA_TAG = "latest"

    /**
     * Окно контекста, с которым модель попадёт в базу: столько же стоит по
     * умолчанию у LocalModel. Гейт обязан считать память под тот же контекст,
     * с которым модель потом запустится, иначе проверка ничего не гарантирует.
     */
    private const val CATALOG_CONTEXT_TOKENS = 4096

    /** Сколько репозиториев HF раскрываем деревьями: дальше выдача уже мусорная. */
    private const val HF_TREE_FANOUT = 12

    /**
     * Одновременных запросов дерева. Три, а не двенадцать: HF отвечает 429 на
     * веер запросов с одного адреса, и тогда пользователь не увидит вообще
     * ничего вместо неполного, но рабочего списка.
     */
    private const val HF_TREE_CONCURRENCY = 3

    private const val SEARCH_CACHE_LIMIT = 50
    private const val TREE_CACHE_LIMIT = 100

    /**
     * Похоже ли введённое на прямое имя модели Ollama: `qwen3`, `qwen3:4b`.
     * Проверка нужна, чтобы не дёргать реестр на каждую фразу поиска: запрос
     * «модель для перевода» именем быть не может, а «qwen3» — может.
     */
    private val DIRECT_NAME_RE = Regex("""^[A-Za-z0-9][A-Za-z0-9._-]*(:[A-Za-z0-9._-]+)?$""")

    /**
     * Кэшируются сырые ответы сети, а не готовая выдача.
     *
     * Готовый [SearchResult] держит флаги «скачано» и результат гейта, и они
     * меняются без всякой сети — после удаления модели или после того, как
     * устройство освободило память. Кэш готовой выдачи показывал бы кнопку
     * «Скачать» у уже скачанной модели, поэтому кэш живёт ровно на границе
     * сети, а политика применяется заново при каждом поиске.
     */
    private val searchCache = LruCache<String, List<HfRepo>>(SEARCH_CACHE_LIMIT)
    private val treeCache = LruCache<String, List<HfFile>>(TREE_CACHE_LIMIT)

    /**
     * Курируемый ассет читается один раз за жизнь процесса: файл лежит в apk и
     * измениться до переустановки не может, а разбор JSON на каждый ввод буквы
     * в поиске — заметная работа на слабом телефоне.
     */
    @Volatile
    private var curatedCatalog: List<CuratedOllamaCatalog.CuratedModel>? = null

    /**
     * Поиск по каталогу с применением гейта устройства.
     *
     * [litertOnly] переключает движок: у Hugging Face это другой запрос (модели
     * LiteRT-LM лежат у отдельного автора) и другое расширение файлов. Для
     * [CatalogSource.Ollama] флаг не значит ничего — реестр Ollama раздаёт
     * только GGUF.
     *
     * @throws RegistryRateLimited если каталог попросил подождать
     */
    suspend fun search(
        context: Context,
        query: String,
        source: CatalogSource,
        litertOnly: Boolean = false,
    ): SearchResult {
        val app = context.applicationContext
        val caps = DeviceCapabilityProvider.current(app)
        val downloaded = downloadedKeys(app)
        return when (source) {
            CatalogSource.Ollama -> searchOllama(app, query, caps, downloaded)
            CatalogSource.HuggingFace -> searchHuggingFace(query, litertOnly, caps, downloaded)
        }
    }

    /**
     * Точные размер, контрольная сумма и имя файла — и БЛОКИРУЮЩАЯ проверка
     * перед скачиванием.
     *
     * Проверка повторяется здесь, а не берётся из результата поиска, по двум
     * причинам. Во-первых, между показом списка и нажатием кнопки проходит
     * время, и место на диске могло кончиться. Во-вторых, в списке размер
     * Ollama был приблизительным (из курируемого ассета), а решение о
     * скачивании нельзя принимать по оценке — только по настоящему манифесту.
     *
     * @return готовые к скачиванию данные либо отказ с русской причиной
     */
    suspend fun resolveExact(
        context: Context,
        entry: CatalogEntry,
        variant: CatalogVariant,
    ): Result<ResolvedDownload> {
        val caps = DeviceCapabilityProvider.current(context.applicationContext)
        return when (entry.source) {
            CatalogSource.Ollama -> resolveOllama(entry, variant, caps)
            CatalogSource.HuggingFace -> resolveHuggingFace(entry, variant, caps)
        }
    }

    /** Значение поля `source` в базе для записи каталога. */
    fun dbSource(source: CatalogSource): String = when (source) {
        CatalogSource.Ollama -> LocalModel.SOURCE_OLLAMA
        CatalogSource.HuggingFace -> LocalModel.SOURCE_HF
    }

    /**
     * Имя варианта для списка скачанных моделей: `qwen3:4b` у Ollama и имя
     * файла без расширения у Hugging Face. Собирается здесь, чтобы название в
     * каталоге и название в списке моделей шлюза не разошлись.
     */
    fun variantDisplayName(entry: CatalogEntry, variant: CatalogVariant): String = when (entry.source) {
        CatalogSource.Ollama -> "${entry.repo}:${variant.ref}"
        CatalogSource.HuggingFace -> variant.ref.substringAfterLast('/').substringBeforeLast('.')
    }

    // ── Ollama ────────────────────────────────────────────────────────────

    /**
     * Основной путь для Ollama — курируемый список из ассетов, а не запасной.
     *
     * У реестра нет ни публичного поиска, ни списка тегов (`/tags/list`
     * отвечает 404), поэтому «найти модель» технически невозможно: можно только
     * знать имя заранее. Курируемый файл и есть это знание, а прямой запрос
     * манифеста — дополнение для тех, кто знает имя, которого нет в списке.
     */
    private suspend fun searchOllama(
        context: Context,
        query: String,
        caps: DeviceCaps,
        downloaded: Set<String>,
    ): SearchResult {
        val matches = CuratedOllamaCatalog.search(curatedModels(context), query)

        var hidden = 0
        val entries = ArrayList<CatalogEntry>(matches.size)
        for (model in matches) {
            val gated = model.tags.map { tag ->
                val variant = CatalogVariant(
                    ref = tag.tag,
                    quant = tag.quant,
                    sizeBytes = tag.approxSizeBytes,
                    // Число параметров у тега может отсутствовать (эмбеддинги),
                    // тогда пробуем вытащить его из самого тега: «4b» — это оно.
                    paramsB = tag.paramsB ?: ModelNameHeuristics.parseParamsB(tag.tag),
                    // Настоящий digest известен только из манифеста, а он
                    // запрашивается перед скачиванием.
                    sha256 = null,
                    engine = EngineKind.GGUF,
                    downloaded = isDownloaded(downloaded, LocalModel.SOURCE_OLLAMA, model.name, tag.tag),
                )
                variant to fits(caps, variant)
            }
            hidden += gated.count { !it.second }
            val visible = gated.filter { it.second }.map { it.first }
            if (visible.isEmpty()) continue

            entries += CatalogEntry(
                id = "${LocalModel.SOURCE_OLLAMA}/${model.name}",
                source = CatalogSource.Ollama,
                repo = model.name,
                displayName = model.name,
                description = model.description.takeIf { it.isNotBlank() },
                // Реестр Ollama не публикует счётчик загрузок — ноль здесь был
                // бы неправдой, поэтому поля просто нет.
                downloads = null,
                paramsLabel = paramsLabel(visible),
                variants = visible,
                hiddenVariantsCount = gated.count { !it.second },
                anyVariantDownloaded = visible.any { it.downloaded },
            )
        }

        // Имя, которого нет в курируемом списке, — ещё не повод отказать:
        // пользователь мог прочитать про модель на сайте Ollama, а манифест
        // отвечает по любому существующему имени.
        if (entries.isEmpty() && looksLikeDirectName(query)) {
            val direct = directOllamaEntry(query, caps, downloaded)
            if (direct != null) {
                hidden += direct.hiddenVariantsCount
                if (direct.variants.isNotEmpty()) entries += direct
            }
        }

        return SearchResult(entries = entries, hiddenCount = hidden)
    }

    /** Запрос манифеста по точному имени: `qwen3` или `qwen3:4b`. */
    private suspend fun directOllamaEntry(
        query: String,
        caps: DeviceCaps,
        downloaded: Set<String>,
    ): CatalogEntry? {
        val raw = query.trim()
        val name = raw.substringBefore(':')
        val tag = raw.substringAfter(':', DEFAULT_OLLAMA_TAG).ifBlank { DEFAULT_OLLAMA_TAG }
        val manifest = ModelRegistrySearch.ollamaManifest(name, tag) ?: return null

        val variant = CatalogVariant(
            ref = tag,
            quant = ModelNameHeuristics.parseQuant(tag).orEmpty(),
            // Размер слоя весов, а не всего манифеста: остальные слои (шаблон,
            // лицензия, параметры) весят килобайты и тонут в гигабайтном
            // запасе места, который держит CapabilityGate.
            sizeBytes = manifest.modelSizeBytes,
            paramsB = ModelNameHeuristics.parseParamsB(tag) ?: ModelNameHeuristics.parseParamsB(name),
            sha256 = digestHex(manifest.modelDigest),
            engine = EngineKind.GGUF,
            downloaded = isDownloaded(downloaded, LocalModel.SOURCE_OLLAMA, name, tag),
        )
        val visible = fits(caps, variant)
        return CatalogEntry(
            id = "${LocalModel.SOURCE_OLLAMA}/$name",
            source = CatalogSource.Ollama,
            repo = name,
            displayName = "$name:$tag",
            description = "Найдено прямым запросом к реестру Ollama",
            downloads = null,
            paramsLabel = paramsLabel(listOf(variant)),
            variants = if (visible) listOf(variant) else emptyList(),
            hiddenVariantsCount = if (visible) 0 else 1,
            anyVariantDownloaded = visible && variant.downloaded,
        )
    }

    private suspend fun resolveOllama(
        entry: CatalogEntry,
        variant: CatalogVariant,
        caps: DeviceCaps,
    ): Result<ResolvedDownload> {
        // Отказ по частоте запросов здесь превращается в Result, а не летит
        // выше: поиск вправе бросить исключение (экран покажет его целиком), а
        // нажатие «Скачать» обязано ответить понятной причиной на месте кнопки.
        val fetched = try {
            ModelRegistrySearch.ollamaManifest(entry.repo, variant.ref)
        } catch (rateLimited: RegistryRateLimited) {
            return Result.failure(rateLimited)
        }
        val manifest = fetched ?: return Result.failure(
            IllegalStateException(
                "Реестр Ollama не отдал сведения о модели ${entry.repo}:${variant.ref}"
            )
        )
        val demand = ModelDemand(
            fileSizeBytes = manifest.modelSizeBytes,
            paramsB = variant.paramsB,
            contextTokens = CATALOG_CONTEXT_TOKENS,
            engine = EngineKind.GGUF,
        )
        blockReason(caps, demand)?.let { return Result.failure(IllegalStateException(it)) }

        return Result.success(
            ResolvedDownload(
                sizeBytes = manifest.modelSizeBytes,
                sha256 = digestHex(manifest.modelDigest).orEmpty(),
                fileName = ollamaFileName(entry.repo, variant.ref),
            )
        )
    }

    /**
     * Имя файла на диске для модели Ollama. Реестр отдаёт слой по digest, и
     * своего имени у файла нет — собираем его из имени и тега, заменяя всё,
     * что не годится для файловой системы.
     */
    private fun ollamaFileName(repo: String, ref: String): String {
        val safe = "$repo-$ref".replace(Regex("""[^A-Za-z0-9._-]"""), "-")
        return "$safe.gguf"
    }

    /** Реестр отдаёт digest как `sha256:<hex>`, а очереди загрузок нужен hex. */
    private fun digestHex(digest: String): String? =
        digest.substringAfter(':', digest).trim().takeIf { it.isNotEmpty() }

    // ── Hugging Face ──────────────────────────────────────────────────────

    private suspend fun searchHuggingFace(
        query: String,
        litertOnly: Boolean,
        caps: DeviceCaps,
        downloaded: Set<String>,
    ): SearchResult {
        val cacheKey = "${if (litertOnly) "litert" else "gguf"}|${query.trim().lowercase()}"
        val repos = searchCache.get(cacheKey) ?: ModelRegistrySearch.hfSearch(query, litertOnly)
            .also { if (it.isNotEmpty()) searchCache.put(cacheKey, it) }
        if (repos.isEmpty()) return SearchResult(emptyList(), 0)

        val head = repos.take(HF_TREE_FANOUT)
        val limiter = Semaphore(HF_TREE_CONCURRENCY)
        val trees = coroutineScope {
            head.map { repo ->
                async {
                    val cached = treeCache.get(repo.repoId)
                    if (cached != null) {
                        repo to cached
                    } else {
                        val tree = limiter.withPermit { ModelRegistrySearch.hfTree(repo.repoId) }
                        if (tree != null) treeCache.put(repo.repoId, tree)
                        repo to tree.orEmpty()
                    }
                }
            }.awaitAll()
        }

        val engine = if (litertOnly) EngineKind.LITERT else EngineKind.GGUF
        val extension = if (litertOnly) ".litertlm" else ".gguf"

        var hidden = 0
        val entries = ArrayList<CatalogEntry>(trees.size)
        for ((repo, files) in trees) {
            val gated = files
                .filter { it.path.endsWith(extension, ignoreCase = true) }
                .map { file ->
                    val variant = CatalogVariant(
                        ref = file.path,
                        quant = ModelNameHeuristics.parseQuant(file.path).orEmpty(),
                        sizeBytes = file.sizeBytes,
                        // Число параметров ищем сначала в имени файла, потом в
                        // имени репозитория: у файлов вида `model-Q4_K_M.gguf`
                        // размер модели указан только в имени репозитория.
                        paramsB = ModelNameHeuristics.parseParamsB(file.path)
                            ?: ModelNameHeuristics.parseParamsB(repo.repoId),
                        sha256 = file.sha256,
                        engine = engine,
                        downloaded = isDownloaded(downloaded, LocalModel.SOURCE_HF, repo.repoId, file.path),
                    )
                    variant to fits(caps, variant)
                }
            if (gated.isEmpty()) continue
            hidden += gated.count { !it.second }
            val visible = gated.filter { it.second }.map { it.first }
            if (visible.isEmpty()) continue

            entries += CatalogEntry(
                id = "${LocalModel.SOURCE_HF}/${repo.repoId}",
                source = CatalogSource.HuggingFace,
                repo = repo.repoId,
                displayName = repo.repoId.substringAfterLast('/'),
                // Поиск HF описания не отдаёт, а придумывать его нельзя —
                // показываем автора: по нему видно, чья это конвертация.
                description = repo.repoId.substringBefore('/').takeIf { it != repo.repoId },
                downloads = repo.downloads,
                paramsLabel = paramsLabel(visible),
                variants = visible,
                hiddenVariantsCount = gated.count { !it.second },
                anyVariantDownloaded = visible.any { it.downloaded },
            )
        }

        return SearchResult(entries = entries, hiddenCount = hidden)
    }

    /**
     * У Hugging Face размер и контрольная сумма уже точные — они пришли из
     * дерева репозитория, где размер лежит в поле lfs.size. Второй запрос
     * ничего бы не уточнил и только приблизил бы отказ 429, поэтому здесь
     * остаётся одна проверка гейта.
     */
    private fun resolveHuggingFace(
        entry: CatalogEntry,
        variant: CatalogVariant,
        caps: DeviceCaps,
    ): Result<ResolvedDownload> {
        if (variant.sizeBytes <= 0L) {
            return Result.failure(
                IllegalStateException(
                    "Размер файла ${variant.ref} в ${entry.repo} неизвестен, скачивание отменено"
                )
            )
        }
        val demand = ModelDemand(
            fileSizeBytes = variant.sizeBytes,
            paramsB = variant.paramsB,
            contextTokens = CATALOG_CONTEXT_TOKENS,
            engine = variant.engine,
        )
        blockReason(caps, demand)?.let { return Result.failure(IllegalStateException(it)) }

        return Result.success(
            ResolvedDownload(
                sizeBytes = variant.sizeBytes,
                sha256 = variant.sha256.orEmpty(),
                fileName = variant.ref.substringAfterLast('/'),
            )
        )
    }

    // ── Общее ─────────────────────────────────────────────────────────────

    /**
     * Проходит ли вариант в список.
     *
     * Неизвестный или нулевой размер — отказ: гейт считает по размеру файла, и
     * при нуле он пропустил бы что угодно. Лучше не показать модель, размер
     * которой каталог не назвал, чем показать её без всякой проверки.
     */
    private fun fits(caps: DeviceCaps, variant: CatalogVariant): Boolean {
        if (variant.sizeBytes <= 0L) return false
        return CapabilityGate.catalogFits(
            caps,
            ModelDemand(
                fileSizeBytes = variant.sizeBytes,
                paramsB = variant.paramsB,
                contextTokens = CATALOG_CONTEXT_TOKENS,
                engine = variant.engine,
            ),
        )
    }

    /** Причина отказа перед скачиванием на русском либо null, если можно. */
    private fun blockReason(caps: DeviceCaps, demand: ModelDemand): String? =
        when (val result = CapabilityGate.downloadCheck(caps, demand)) {
            is GateResult.Ok -> null
            is GateResult.NoRam -> result.reasonRu
            is GateResult.NoDisk -> result.reasonRu
            is GateResult.NoAbi -> result.reasonRu
        }

    /**
     * Ключи уже известных базе моделей. Запись в состоянии «в очереди» тоже
     * считается скачанной: уникальный ключ source+repo+ref не даст поставить ту
     * же модель второй раз, и кнопка «Скачать» у неё была бы обманом.
     */
    private suspend fun downloadedKeys(context: Context): Set<String> = runCatching {
        AppDatabase.getInstance(context).localModelDao().getAll()
            .mapTo(HashSet()) { key(it.source, it.repo, it.ref) }
    }.getOrElse { emptySet() }

    private fun isDownloaded(known: Set<String>, source: String, repo: String, ref: String): Boolean =
        key(source, repo, ref) in known

    private fun key(source: String, repo: String, ref: String): String = "$source|$repo|$ref"

    /**
     * Подпись размера семейства для карточки. Показывается только тогда, когда
     * все видимые варианты одного размера: у Ollama в одной карточке лежат
     * 0.6b, 1.7b и 4b, и подписать её одним числом значило бы соврать.
     */
    private fun paramsLabel(variants: List<CatalogVariant>): String? {
        val values = variants.mapNotNull { it.paramsB }.distinct()
        val single = values.singleOrNull() ?: return null
        val tenths = (single * 10.0).roundToLong()
        return if (tenths % 10 == 0L) "${tenths / 10}B" else "${tenths / 10},${tenths % 10}B"
    }

    private fun looksLikeDirectName(query: String): Boolean {
        val raw = query.trim()
        return raw.isNotEmpty() && DIRECT_NAME_RE.matches(raw)
    }

    private suspend fun curatedModels(context: Context): List<CuratedOllamaCatalog.CuratedModel> {
        curatedCatalog?.let { return it }
        val parsed = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(CURATED_ASSET).bufferedReader().use { it.readText() }
            }.map { CuratedOllamaCatalog.parse(it) }.getOrElse { emptyList() }
        }
        curatedCatalog = parsed
        return parsed
    }

    /**
     * Кэш ответов сети на время жизни процесса.
     *
     * LinkedHashMap с accessOrder = true вытесняет давно не спрошенное, а не
     * давно положенное: пользователь возвращается к тем же запросам («qwen»,
     * «gemma»), и вытеснять их по возрасту записи значило бы ходить в сеть за
     * тем, что только что показывали. Доступ синхронизирован, потому что
     * деревья HF запрашиваются параллельно.
     */
    private class LruCache<K : Any, V : Any>(private val maxEntries: Int) {

        private val entries = object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean =
                size > maxEntries
        }

        @Synchronized
        fun get(key: K): V? = entries[key]

        @Synchronized
        fun put(key: K, value: V) {
            entries[key] = value
        }
    }
}
