package com.aigate.router.routing

import com.aigate.router.data.model.AiModel
import com.aigate.router.service.GatewayForegroundService

/**
 * Порядок провайдеров ВНУТРИ одной модели.
 *
 * Клиент присылает имя модели, а предоставлять её могут несколько провайдеров —
 * например два аккаунта Codex с одинаковым `gpt-5.4`. Раньше выбор падал на
 * произвольную запись из базы, а порядок задавался на уровне провайдера, что не
 * отвечало на вопрос «кто обслужит именно эту модель».
 *
 * Порядок хранится в конфиге (`model_order_<modelId>`), схема базы не меняется:
 * в ней включён destructive fallback, и новая колонка стёрла бы данные.
 */
object ModelPreference {

    private fun key(modelId: String) = "model_order_$modelId"

    /**
     * Сортировка кандидатов: у каждой модели свой порядок провайдеров.
     *
     * Порядок САМИХ моделей сохраняется — группа привязана к первому появлению
     * модели в исходном списке. Иначе сортировка по одному лишь номеру внутри
     * группы перемешивала бы разные модели между собой (база отдаёт строки по
     * провайдерам, поэтому одна модель идёт не подряд).
     */
    fun sort(candidates: List<AiModel>, preferences: Map<String, List<Long>>): List<AiModel> {
        if (candidates.isEmpty()) return candidates
        val groupAnchor = HashMap<String, Int>()
        candidates.forEachIndexed { index, model ->
            groupAnchor.putIfAbsent(model.modelId, index)
        }
        val original = candidates.withIndex().associate { (i, m) -> m.id to i }
        return candidates.sortedWith(
            compareBy(
                { groupAnchor[it.modelId] ?: Int.MAX_VALUE },
                { rank(it, preferences) },
                { original[it.id] ?: Int.MAX_VALUE },
            )
        )
    }

    /**
     * Позиция провайдера в порядке этой модели. Неупомянутые провайдеры идут
     * после перечисленных, сохраняя исходный порядок между собой.
     */
    private fun rank(model: AiModel, preferences: Map<String, List<Long>>): Int {
        val order = preferences[model.modelId] ?: return Int.MAX_VALUE
        val index = order.indexOf(model.providerId)
        return if (index >= 0) index else Int.MAX_VALUE
    }

    /** Сдвинуть провайдера на одну позицию внутри модели. */
    fun move(order: List<Long>, providerId: Long, delta: Int): List<Long> {
        val from = order.indexOf(providerId)
        if (from < 0) return order
        val to = from + delta
        if (to < 0 || to > order.lastIndex) return order
        return order.toMutableList().apply {
            removeAt(from)
            add(to, providerId)
        }
    }

    fun encode(order: List<Long>): String = order.joinToString(",")

    fun decode(raw: String): List<Long> =
        raw.split(',').mapNotNull { it.trim().toLongOrNull() }

    // ---- Хранение ---------------------------------------------------------

    /**
     * Сохранённый порядок для модели. Провайдеры, которых нет в списке, но
     * которые модель предоставляют, дописываются в конец — так новый аккаунт
     * появляется в списке сам, не ломая уже заданный порядок.
     */
    fun orderFor(modelId: String, availableProviderIds: List<Long>): List<Long> {
        val stored = decode(GatewayForegroundService.getGatewayConfig(key(modelId), ""))
            .filter { it in availableProviderIds }
        return stored + availableProviderIds.filterNot { it in stored }
    }

    fun saveOrder(modelId: String, order: List<Long>) {
        GatewayForegroundService.saveGatewayConfig(key(modelId), encode(order))
    }

    /** Порядки всех моделей из переданного набора — для сортировки кандидатов. */
    fun preferencesFor(models: List<AiModel>): Map<String, List<Long>> =
        models.groupBy { it.modelId }
            .mapValues { (modelId, rows) -> orderFor(modelId, rows.map { it.providerId }) }

    /** Отсортировать список моделей по сохранённым порядкам. */
    fun sortStored(candidates: List<AiModel>): List<AiModel> =
        sort(candidates, preferencesFor(candidates))
}
