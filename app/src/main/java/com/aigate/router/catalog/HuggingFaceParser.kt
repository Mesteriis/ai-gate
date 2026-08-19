package com.aigate.router.catalog

import org.json.JSONArray

/*
 * Разбор ответов публичного API Hugging Face. Как и парсер реестра Ollama,
 * работает только со строками: сеть выше, тесты — обычные JVM-тесты.
 */

/**
 * Репозиторий из поиска моделей.
 *
 * [downloads] и [likes] нужны не для украшения списка, а как единственный
 * доступный признак живой модели: HF полон заброшенных и сломанных конвертаций,
 * и сортировка по загрузкам — самый дешёвый фильтр качества.
 */
data class HfRepo(
    val repoId: String,
    val downloads: Long,
    val likes: Long,
    val tags: List<String>,
    val pipelineTag: String?,
)

/**
 * Файл в дереве репозитория.
 *
 * [sha256] может отсутствовать: у мелких файлов, лежащих в git напрямую (без
 * LFS), контрольной суммы в ответе нет — тогда проверять целостность загрузки
 * нечем, и это должно быть видно вызывающему коду, а не подменяться пустой
 * строкой.
 */
data class HfFile(
    val path: String,
    val sizeBytes: Long,
    val sha256: String?,
)

object HuggingFaceParser {

    /** Форматы, которые умеют поднять локальные движки: llama.cpp и LiteRT-LM. */
    private val MODEL_EXTENSIONS = listOf(".gguf", ".litertlm")

    /**
     * Многочастный шард вида `model-00001-of-00003.gguf`. Такие файлы
     * отбрасываются: каждая часть — кусок модели на десятки гигабайт, целиком
     * в телефон она не влезает, а склейка частей перед запуском требует
     * свободного места под ещё одну копию. Показывать в каталоге то, что
     * заведомо не запустится, нельзя.
     */
    private val SHARD_PART = Regex("""-\d{5}-of-\d{5}\.gguf$""", RegexOption.IGNORE_CASE)

    /**
     * Ответ `https://huggingface.co/api/models?filter=gguf&search=...&sort=downloads`.
     *
     * Записи без идентификатора пропускаются: без него репозиторий нечем
     * открыть и нечего скачивать. Остальные отсутствующие поля дают нули,
     * пустой список и null — неполный ответ поиска не повод терять всю выдачу.
     */
    fun parseSearch(json: String): List<HfRepo> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()

        val result = ArrayList<HfRepo>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            // Поле называется "id" в поиске и "modelId" в некоторых ответах API.
            val repoId = obj.optString("id").ifBlank { obj.optString("modelId") }
            if (repoId.isBlank()) continue

            val tagsArray = obj.optJSONArray("tags")
            val tags = ArrayList<String>(tagsArray?.length() ?: 0)
            if (tagsArray != null) {
                for (j in 0 until tagsArray.length()) {
                    val tag = (tagsArray.opt(j) as? String)?.trim().orEmpty()
                    if (tag.isNotEmpty()) tags += tag
                }
            }

            result += HfRepo(
                repoId = repoId,
                downloads = obj.optLong("downloads", 0L),
                likes = obj.optLong("likes", 0L),
                tags = tags,
                pipelineTag = obj.optString("pipeline_tag").takeIf { it.isNotBlank() },
            )
        }
        return result
    }

    /**
     * Ответ `/api/models/{repo}/tree/main`.
     *
     * В дереве лежат README, конфиги и картинки — оставляем только файлы
     * поддерживаемых форматов, чтобы пользователь не выбирал из каталога то,
     * что не является моделью.
     */
    fun parseTree(json: String): List<HfFile> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()

        val result = ArrayList<HfFile>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            if (obj.optString("type") != "file") continue

            val path = obj.optString("path")
            if (path.isBlank()) continue
            val lower = path.lowercase()
            if (MODEL_EXTENSIONS.none { lower.endsWith(it) }) continue
            if (SHARD_PART.containsMatchIn(path)) continue

            // У LFS-файлов поле size — размер указателя (сотня байт), настоящий
            // размер лежит в lfs.size. Взять верхнее поле значит показать
            // пользователю «модель на 135 байт» и не проверить место на диске.
            val lfs = obj.optJSONObject("lfs")
            val lfsSize = lfs?.optLong("size", -1L) ?: -1L
            val size = if (lfsSize >= 0L) lfsSize else obj.optLong("size", 0L)

            result += HfFile(
                path = path,
                sizeBytes = size,
                sha256 = lfs?.optString("oid")?.takeIf { it.isNotBlank() },
            )
        }
        return result
    }
}
