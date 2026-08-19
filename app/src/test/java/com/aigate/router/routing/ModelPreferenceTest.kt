package com.aigate.router.routing

import com.aigate.router.data.model.AiModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Порядок провайдеров внутри одной модели. Клиент просит модель по имени, а
 * предоставлять её могут несколько провайдеров (например два аккаунта Codex) —
 * порядок решает, кто обслужит запрос и кто станет резервом.
 */
class ModelPreferenceTest {

    private fun model(providerId: Long, modelId: String) =
        AiModel(id = providerId * 100, providerId = providerId, modelId = modelId, displayName = modelId)

    @Test
    fun `preferred provider goes first within the same model`() {
        val candidates = listOf(model(1, "gpt-5.4"), model(2, "gpt-5.4"), model(3, "gpt-5.4"))

        val ordered = ModelPreference.sort(candidates, preferences = mapOf("gpt-5.4" to listOf(3L, 1L)))

        assertEquals(listOf(3L, 1L, 2L), ordered.map { it.providerId })
    }

    @Test
    fun `models without preference keep their original order`() {
        val candidates = listOf(model(2, "a"), model(1, "a"))
        assertEquals(listOf(2L, 1L), ModelPreference.sort(candidates, emptyMap()).map { it.providerId })
    }

    @Test
    fun `preference of one model does not disturb another`() {
        val candidates = listOf(
            model(1, "alpha"), model(2, "alpha"),
            model(1, "beta"), model(2, "beta"),
        )

        val ordered = ModelPreference.sort(candidates, mapOf("alpha" to listOf(2L)))

        assertEquals(
            listOf(2L to "alpha", 1L to "alpha", 1L to "beta", 2L to "beta"),
            ordered.map { it.providerId to it.modelId },
        )
    }

    @Test
    fun `interleaved list keeps the order between different models`() {
        // База отдаёт модели в порядке провайдеров, поэтому строки одной модели
        // идут не подряд: порядок внутри модели не должен перемешивать модели.
        val candidates = listOf(
            model(1, "alpha"), model(1, "beta"),
            model(2, "alpha"), model(2, "beta"),
        )

        val ordered = ModelPreference.sort(candidates, mapOf("alpha" to listOf(2L, 1L)))

        assertEquals(
            listOf(2L to "alpha", 1L to "alpha", 1L to "beta", 2L to "beta"),
            ordered.map { it.providerId to it.modelId },
        )
    }

    @Test
    fun `two models with their own order do not interleave`() {
        // База отдаёт строки по провайдерам, поэтому одна модель идёт не подряд.
        // Порядок внутри модели не должен перемешивать сами модели между собой.
        val candidates = listOf(
            model(1, "alpha"), model(1, "beta"),
            model(2, "alpha"), model(2, "beta"),
        )

        val ordered = ModelPreference.sort(
            candidates,
            mapOf("alpha" to listOf(2L, 1L), "beta" to listOf(2L, 1L)),
        )

        assertEquals(
            listOf(2L to "alpha", 1L to "alpha", 2L to "beta", 1L to "beta"),
            ordered.map { it.providerId to it.modelId },
        )
    }

    @Test
    fun `providers missing from the preference land after the listed ones`() {
        val candidates = listOf(model(1, "m"), model(2, "m"), model(3, "m"))
        val ordered = ModelPreference.sort(candidates, mapOf("m" to listOf(2L)))
        assertEquals(listOf(2L, 1L, 3L), ordered.map { it.providerId })
    }

    @Test
    fun `stale provider ids in preference are ignored`() {
        val candidates = listOf(model(1, "m"), model(2, "m"))
        val ordered = ModelPreference.sort(candidates, mapOf("m" to listOf(99L, 2L)))
        assertEquals(listOf(2L, 1L), ordered.map { it.providerId })
    }

    @Test
    fun `moving a provider inside the model rewrites only its own order`() {
        val order = ModelPreference.move(listOf(1L, 2L, 3L), providerId = 3L, delta = -1)
        assertEquals(listOf(1L, 3L, 2L), order)
    }

    @Test
    fun `move at the edge changes nothing`() {
        assertEquals(listOf(1L, 2L), ModelPreference.move(listOf(1L, 2L), providerId = 1L, delta = -1))
        assertEquals(listOf(1L, 2L), ModelPreference.move(listOf(1L, 2L), providerId = 2L, delta = 1))
    }

    @Test
    fun `serialization round trip keeps the order`() {
        val encoded = ModelPreference.encode(listOf(7L, 3L, 11L))
        assertEquals(listOf(7L, 3L, 11L), ModelPreference.decode(encoded))
    }

    @Test
    fun `garbage in stored preference does not break decoding`() {
        assertEquals(emptyList<Long>(), ModelPreference.decode(""))
        assertEquals(listOf(5L), ModelPreference.decode("5,мусор,,"))
    }
}
