package com.aigate.router.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Экономия локальных моделей: без эталонной цены цифра не выдумывается. */
class LocalSavingsTest {

    @Test
    fun `savings are local tokens priced by the cheapest cloud model`() {
        val result = LocalSavings.compute(
            localPromptTokens = 1_000_000,
            localCompletionTokens = 1_000_000,
            cheapestInputPer1M = 0.25,
            cheapestOutputPer1M = 0.75,
            referenceModel = "cheap-model",
        )
        assertEquals(1.00, result.savedUsd, 0.001)
        assertEquals("cheap-model", result.referenceModel)
        assertEquals(2_000_000L, result.localTokens)
    }

    @Test
    fun `input and output are priced separately`() {
        val result = LocalSavings.compute(
            localPromptTokens = 2_000_000,
            localCompletionTokens = 500_000,
            cheapestInputPer1M = 0.10,
            cheapestOutputPer1M = 2.00,
            referenceModel = "m",
        )
        // 2 * 0.10 + 0.5 * 2.00 = 1.20
        assertEquals(1.20, result.savedUsd, 0.001)
    }

    @Test
    fun `without a reference price there is no number`() {
        val result = LocalSavings.compute(1_000, 1_000, null, null, null)
        assertEquals(0.0, result.savedUsd, 0.0001)
        assertNull(result.referenceModel)
    }

    @Test
    fun `partial price data is not enough`() {
        val result = LocalSavings.compute(1_000, 1_000, 0.5, null, "m")
        assertEquals(0.0, result.savedUsd, 0.0001)
        assertNull(result.referenceModel)
    }

    @Test
    fun `no local usage means no savings`() {
        val result = LocalSavings.compute(0, 0, 0.25, 0.75, "m")
        assertEquals(0.0, result.savedUsd, 0.0001)
        assertEquals(0L, result.localTokens)
    }
}
