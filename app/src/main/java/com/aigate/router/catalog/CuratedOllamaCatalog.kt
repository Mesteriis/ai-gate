package com.aigate.router.catalog

import org.json.JSONArray
import org.json.JSONObject

/**
 * Курируемый каталог популярных моделей Ollama.
 *
 * У реестра Ollama нет публичного API поиска: страница библиотеки — это HTML,
 * который ломается при каждом редизайне сайта. Поэтому список пригодных для
 * телефона семейств зашит в ассет `assets/ollama_catalog.json`, а модель вне
 * списка пользователь всё равно может поставить — по точному имени она ищется
 * прямым запросом манифеста.
 *
 * Здесь живёт только разбор и поиск: чтение ассета делает Android-слой, чтобы
 * парсер проверялся обычными JVM-тестами вместе с самим файлом каталога.
 */
object CuratedOllamaCatalog {

    /**
     * Один тег модели (`qwen3:4b`). [approxSizeBytes] — грубая оценка для
     * предупреждения «не влезет в память» до скачивания; точный размер берётся
     * из манифеста, поэтому расхождение в сотни мегабайт здесь допустимо.
     * [paramsB] может отсутствовать: у эмбеддингов и экзотических сборок число
     * параметров не публикуется, и подставлять выдуманное нельзя.
     */
    data class CuratedTag(
        val tag: String,
        val quant: String,
        val paramsB: Double?,
        val approxSizeBytes: Long,
    )

    /** Семейство моделей: одно имя и несколько размерных тегов. */
    data class CuratedModel(
        val name: String,
        val description: String,
        val tags: List<CuratedTag>,
    )

    /**
     * Разбор ассета. Битый или чужой JSON даёт пустой список, а не падение:
     * каталог — вспомогательная подсказка, и экран установки модели должен
     * оставаться рабочим даже без него (имя можно ввести руками).
     */
    fun parse(json: String): List<CuratedModel> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val models = root.optJSONArray("models") ?: return emptyList()
        val result = ArrayList<CuratedModel>(models.length())
        for (i in 0 until models.length()) {
            val item = models.optJSONObject(i) ?: continue
            val name = item.optString("name").trim()
            if (name.isEmpty()) continue
            result += CuratedModel(
                name = name,
                description = item.optString("description").trim(),
                tags = parseTags(item.optJSONArray("tags")),
            )
        }
        return result
    }

    private fun parseTags(tags: JSONArray?): List<CuratedTag> {
        if (tags == null) return emptyList()
        val result = ArrayList<CuratedTag>(tags.length())
        for (i in 0 until tags.length()) {
            val item = tags.optJSONObject(i) ?: continue
            val tag = item.optString("tag").trim()
            if (tag.isEmpty()) continue
            result += CuratedTag(
                tag = tag,
                quant = item.optString("quant").trim(),
                // optDouble вернёт NaN на отсутствующем поле — это «неизвестно»,
                // а не ноль параметров.
                paramsB = item.optDouble("paramsB").takeIf { !it.isNaN() },
                approxSizeBytes = item.optLong("approxSizeBytes", 0L),
            )
        }
        return result
    }

    /**
     * Поиск по каталогу. Пользователь чаще помнит начало имени («qwen», «gemma»),
     * чем точный тег, поэтому совпадения в начале имени поднимаются наверх, а
     * попадания в середину имени и в описание остаются ниже: иначе запрос
     * «llama» показал бы tinyllama раньше самой llama3.2.
     */
    fun search(models: List<CuratedModel>, query: String): List<CuratedModel> {
        val q = query.trim()
        if (q.isEmpty()) return models
        val (prefix, rest) = models
            .filter { it.name.contains(q, ignoreCase = true) || it.description.contains(q, ignoreCase = true) }
            .partition { it.name.startsWith(q, ignoreCase = true) }
        val byName: Comparator<CuratedModel> = compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        return prefix.sortedWith(byName) + rest.sortedWith(byName)
    }
}
