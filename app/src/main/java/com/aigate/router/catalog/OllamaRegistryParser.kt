package com.aigate.router.catalog

import org.json.JSONObject

/*
 * Разбор ответов публичного реестра Ollama (registry.ollama.ai). Здесь только
 * работа со строками: сеть живёт слоем выше, а парсер обязан быть проверяемым
 * JVM-тестом без устройства и без интернета.
 */

/**
 * Реестр Ollama отдаёт OCI-подобный манифест, где веса модели — лишь один из
 * слоёв (рядом лежат шаблон промпта, лицензия, параметры и системный текст).
 *
 * Разделение размеров принципиально: [modelSizeBytes] — это то, что движок
 * поднимет в память, по нему считается, влезет ли модель в RAM телефона, а
 * [totalSizeBytes] — сколько байт придётся скачать и сохранить, по нему
 * проверяется свободное место на диске. Путать их нельзя: у мелких моделей
 * разница невелика, но лицензия и шаблон всё равно занимают место на диске,
 * не занимая памяти.
 */
data class OllamaManifest(
    val modelDigest: String,
    val modelSizeBytes: Long,
    val configDigest: String?,
    val totalSizeBytes: Long,
)

object OllamaRegistryParser {

    /** mediaType слоя с весами; остальные слои (.template, .license, .params, .system) — метаданные. */
    private const val MODEL_LAYER_MEDIA_TYPE = "application/vnd.ollama.image.model"

    /**
     * Манифест `/v2/library/<name>/manifests/<tag>`.
     *
     * Возвращает null на битом JSON и на манифесте без слоя весов: скачивать
     * набор шаблонов без самой модели бессмысленно, и лучше честно показать
     * «модель недоступна», чем начать загрузку, которая ничего не даст.
     */
    fun parseManifest(json: String): OllamaManifest? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val layers = root.optJSONArray("layers") ?: return null

        var modelDigest: String? = null
        var modelSize = 0L
        var totalSize = 0L
        for (i in 0 until layers.length()) {
            val layer = layers.optJSONObject(i) ?: continue
            val size = layer.optLong("size", 0L)
            // В сумму идут все слои, включая неизвестные типы: скачивается манифест целиком.
            totalSize += size
            if (layer.optString("mediaType") != MODEL_LAYER_MEDIA_TYPE) continue
            val digest = layer.optString("digest").takeIf { it.isNotBlank() } ?: continue
            // Слоёв модели в манифесте один; если реестр когда-нибудь пришлёт
            // несколько, берём первый — он и есть основной файл весов.
            if (modelDigest == null) {
                modelDigest = digest
                modelSize = size
            }
        }

        val digest = modelDigest ?: return null
        val configDigest = root.optJSONObject("config")
            ?.optString("digest")
            ?.takeIf { it.isNotBlank() }
        return OllamaManifest(
            modelDigest = digest,
            modelSizeBytes = modelSize,
            configDigest = configDigest,
            totalSizeBytes = totalSize,
        )
    }

    /**
     * Список тегов `/v2/library/<name>/tags/list`.
     *
     * Битый или чужой ответ даёт пустой список, а не исключение: отсутствие
     * вариантов модели — обычная ситуация каталога, а не сбой приложения.
     */
    fun parseTagsList(json: String): List<String> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val tags = root.optJSONArray("tags") ?: return emptyList()

        val result = ArrayList<String>(tags.length())
        for (i in 0 until tags.length()) {
            // Только строки: числовой или вложенный элемент не является тегом,
            // а приведение через toString() протащило бы мусор в UI.
            val tag = (tags.opt(i) as? String)?.trim().orEmpty()
            if (tag.isNotEmpty()) result += tag
        }
        return result
    }
}
