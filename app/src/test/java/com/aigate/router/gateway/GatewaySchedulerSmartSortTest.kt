package com.aigate.router.gateway

import com.aigate.router.data.model.AiModel
import com.aigate.router.data.model.routeKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [GatewayScheduler.smartSort] is pure (in-memory history + the speed-ranking
 * list, no Android/Room/Ktor). We seed the ranking and record healthy history
 * so "known-fast" models land in the top tier and are ordered by their ranking
 * position, while models with no history/ranking fall to the bottom tier.
 */
class GatewaySchedulerSmartSortTest {

    private fun model(providerId: Long, modelId: String) = AiModel(
        id = 0,
        providerId = providerId,
        modelId = modelId,
        displayName = modelId,
    )

    // Provider ids/model ids unique to this test so leftover singleton state
    // from other tests cannot match these route keys.
    private val fastFirst = model(9101, "alpha")   // ranking index 0 (fastest)
    private val fastSecond = model(9101, "beta")    // ranking index 1
    private val unknown = model(9102, "zeta")       // no ranking, no history

    @Before
    fun seed() {
        // Speed ranking: beta is faster than alpha; zeta is absent.
        GatewayScheduler.pipelineSortedModelKeys = listOf(
            fastFirst.routeKey, // "9101::alpha"
            fastSecond.routeKey, // "9101::beta"
        )
        // Healthy history promotes ranked models into the top tier.
        GatewayScheduler.recordModelResult(fastFirst.modelId, fastFirst.providerId, success = true)
        GatewayScheduler.recordModelResult(fastSecond.modelId, fastSecond.providerId, success = true)
    }

    @After
    fun reset() {
        GatewayScheduler.pipelineSortedModelKeys = emptyList()
    }

    @Test
    fun rankedHealthyModelsSortAheadOfUnknownModel() {
        // Input deliberately scrambled.
        val sorted = GatewayScheduler.smartSort(listOf(unknown, fastFirst, fastSecond))

        // Both ranked+healthy models precede the unknown one, and within the top
        // tier they follow the speed ranking (alpha at index 0 before beta).
        assertEquals(
            listOf(fastFirst.routeKey, fastSecond.routeKey, unknown.routeKey),
            sorted.map { it.routeKey },
        )
    }
}
