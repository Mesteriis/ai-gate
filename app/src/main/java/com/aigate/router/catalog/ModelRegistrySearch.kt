package com.aigate.router.catalog

import com.aigate.router.network.UpstreamClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/*
 * Сетевой слой каталога моделей: реестр Ollama и публичный API Hugging Face.
 *
 * Здесь нет ни одной строчки политики — ни проверки устройства, ни скрытия
 * неподходящих вариантов, ни кэша. Разделение нужно, чтобы правила показа
 * можно было менять, не трогая работу с сетью, а разбор ответов оставался в
 * парсерах, проверяемых обычными JVM-тестами. Отсюда возвращаются только
 * готовые модели данных парсеров либо null/пустой список.
 */

/**
 * Каталог ответил «слишком часто».
 *
 * Отдельный тип, а не общий null, потому что это единственная сетевая ошибка,
 * которая лечится ожиданием: остальные сбои означают «каталог недоступен», а
 * здесь надо сказать пользователю, что запросов было слишком много, и дать
 * повторить позже. Наследование от IOException позволяет ловить её вместе с
 * прочими сетевыми сбоями там, где разница неважна.
 */
class RegistryRateLimited(message: String) : IOException(message)

object ModelRegistrySearch {

    /** Реестр Ollama: OCI-совместимый, но без поиска и без списка тегов. */
    private const val OLLAMA_REGISTRY = "https://registry.ollama.ai/v2/library"

    private const val HF_API = "https://huggingface.co/api/models"

    /**
     * Автор моделей для LiteRT-LM. Отдельного фильтра по формату у HF нет, а
     * файлы .litertlm за пределами этого аккаунта практически не встречаются,
     * поэтому поиск движка LiteRT — это поиск по автору.
     */
    private const val LITERT_AUTHOR = "litert-community"

    /**
     * Каталог обязан отвечать быстро: пользователь ждёт список на экране, а не
     * поток генерации. Общий прямой клиент живёт с readTimeout = 0 ради
     * бесконечных SSE-ответов, и с ним зависший каталог держал бы экран
     * бесконечно — поэтому таймаут чтения переопределяется.
     */
    private const val READ_TIMEOUT_SECONDS = 15L

    /**
     * Реестр отдаёт манифест в одном из нескольких OCI-типов и выбирает его по
     * заголовку Accept. Без списка типов ответ приходит в форме, которую парсер
     * манифеста не разберёт.
     */
    private const val MANIFEST_ACCEPT =
        "application/vnd.oci.image.manifest.v1+json," +
            "application/vnd.docker.distribution.manifest.v2+json," +
            "application/json"

    private const val JSON_ACCEPT = "application/json"

    /** Верхняя граница выдачи поиска: больше сотни HF всё равно не отдаёт. */
    private const val MAX_SEARCH_LIMIT = 100

    /**
     * Клиент собирается один раз поверх прямого: прокси каталогу не нужен —
     * это публичные реестры, а не провайдер пользователя, и гонять их через
     * чужой прокси значит показывать третьей стороне, что человек ищет.
     */
    private val client: OkHttpClient by lazy {
        UpstreamClient.getDirectClient().newBuilder()
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Манифест конкретного тега: `registry.ollama.ai/v2/library/<name>/manifests/<tag>`.
     *
     * Единственный способ узнать точный размер и контрольную сумму модели
     * Ollama до скачивания — других полей у реестра нет.
     *
     * @return разобранный манифест либо null, если реестр недоступен, ответил
     *   ошибкой или прислал манифест без слоя весов
     * @throws RegistryRateLimited при HTTP 429
     */
    suspend fun ollamaManifest(name: String, tag: String): OllamaManifest? = withContext(Dispatchers.IO) {
        val cleanName = name.trim()
        val cleanTag = tag.trim()
        if (cleanName.isEmpty() || cleanTag.isEmpty()) return@withContext null

        val url = OLLAMA_REGISTRY.toHttpUrl().newBuilder()
            .addPathSegment(cleanName)
            .addPathSegment("manifests")
            .addPathSegment(cleanTag)
            .build()
        val body = fetch(url, MANIFEST_ACCEPT) ?: return@withContext null
        OllamaRegistryParser.parseManifest(body)
    }

    /**
     * Список тегов модели: `registry.ollama.ai/v2/library/<name>/tags/list`.
     *
     * ВАЖНО: на август 2026 этот эндпоинт отвечает 404 — публичного списка
     * тегов и публичного поиска у реестра Ollama нет. Запрос всё равно
     * выполняется: реестр OCI-совместимый, и если тот же адрес когда-нибудь
     * откроют, каталог начнёт получать настоящие теги без правок кода.
     *
     * До тех пор null — обычный, а не исключительный ответ, и вызывающий
     * ОБЯЗАН иметь запасной путь (курируемый список из ассетов и прямой запрос
     * манифеста по точному имени). Считать null ошибкой и показывать
     * «каталог недоступен» нельзя: недоступного здесь ничего нет.
     *
     * @return непустой список тегов либо null, если ответа нет
     * @throws RegistryRateLimited при HTTP 429
     */
    suspend fun ollamaTags(name: String): List<String>? = withContext(Dispatchers.IO) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return@withContext null

        val url = OLLAMA_REGISTRY.toHttpUrl().newBuilder()
            .addPathSegment(cleanName)
            .addPathSegment("tags")
            .addPathSegment("list")
            .build()
        val body = fetch(url, JSON_ACCEPT) ?: return@withContext null
        // Пустой разбор — это чужой или битый ответ, а не «модель без тегов»:
        // отдавать пустой список значит соврать вызывающему, что запасной путь
        // не нужен.
        OllamaRegistryParser.parseTagsList(body).takeIf { it.isNotEmpty() }
    }

    /**
     * Поиск репозиториев Hugging Face.
     *
     * Сортировка по загрузкам не украшение, а фильтр качества: HF полон
     * заброшенных и сломанных конвертаций, и число скачиваний — единственный
     * дешёвый признак живой модели.
     *
     * [litertOnly] меняет не фильтр формата, а автора: у HF нет фильтра по
     * `.litertlm`, зато все пригодные сборки лежат у litert-community.
     *
     * @return найденные репозитории; пустой список и при отсутствии совпадений,
     *   и при недоступном каталоге — разницу видно по [hfTree] и по тому, что
     *   UI показывает одно и то же сообщение
     * @throws RegistryRateLimited при HTTP 429
     */
    suspend fun hfSearch(query: String, litertOnly: Boolean, limit: Int = 30): List<HfRepo> =
        withContext(Dispatchers.IO) {
            val builder = HF_API.toHttpUrl().newBuilder()
                .addQueryParameter("sort", "downloads")
                .addQueryParameter("direction", "-1")
                .addQueryParameter("limit", limit.coerceIn(1, MAX_SEARCH_LIMIT).toString())
            if (litertOnly) {
                builder.addQueryParameter("author", LITERT_AUTHOR)
            } else {
                builder.addQueryParameter("filter", "gguf")
            }
            // Пустой search HF воспринимает как «покажи топ», и это осмысленный
            // ответ: пользователь открыл каталог, ещё ничего не набрав.
            query.trim().takeIf { it.isNotEmpty() }?.let { builder.addQueryParameter("search", it) }

            val body = fetch(builder.build(), JSON_ACCEPT) ?: return@withContext emptyList()
            HuggingFaceParser.parseSearch(body)
        }

    /**
     * Дерево файлов репозитория: `/api/models/<repo>/tree/main`.
     *
     * Единственное место, где HF отдаёт размеры файлов, — в поиске их нет.
     * Поэтому без этого запроса нельзя ни оценить, влезет ли модель, ни
     * показать вес варианта.
     *
     * @return файлы моделей либо null, если репозиторий недоступен; пустой
     *   список означает «репозиторий есть, но моделей в нём нет» — это разные
     *   вещи, и путать их нельзя
     * @throws RegistryRateLimited при HTTP 429
     */
    suspend fun hfTree(repoId: String): List<HfFile>? = withContext(Dispatchers.IO) {
        val cleanRepo = repoId.trim().trim('/')
        if (cleanRepo.isEmpty()) return@withContext null

        val url = HF_API.toHttpUrl().newBuilder()
            // addPathSegments разбивает "автор/модель" по слэшу и кодирует
            // каждый сегмент отдельно — склейка строкой сломалась бы на именах
            // с пробелами и юникодом.
            .addPathSegments(cleanRepo)
            .addPathSegments("tree/main")
            .build()
        val body = fetch(url, JSON_ACCEPT) ?: return@withContext null
        HuggingFaceParser.parseTree(body)
    }

    /**
     * Один GET к каталогу.
     *
     * Любая сетевая ошибка и любой неуспешный код, кроме 429, дают null:
     * каталог — вспомогательный сервис, и падать из-за него приложение не
     * должно, а UI покажет «Каталог недоступен». 429 выделен, потому что это
     * единственный случай, когда пользователю есть что сделать — подождать.
     */
    private fun fetch(url: HttpUrl, accept: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.code == 429) {
                    throw RegistryRateLimited("Слишком много запросов к каталогу, попробуйте позже")
                }
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        } catch (rateLimited: RegistryRateLimited) {
            throw rateLimited
        } catch (io: IOException) {
            null
        }
    }
}
