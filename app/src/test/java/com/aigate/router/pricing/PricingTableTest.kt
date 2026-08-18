package com.aigate.router.pricing

import com.aigate.router.routing.RouteStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PricingTableTest {

    @Test
    fun `exact model match returns bundled price`() {
        val p = PricingTable.bundledFor("openai", "gpt-4o-mini")
        assertNotNull(p)
        assertEquals(0.15, p!!.inputPer1M, 0.0001)
        assertEquals(0.60, p.outputPer1M, 0.0001)
        assertEquals("bundled", p.source)
    }

    @Test
    fun `case insensitive match`() {
        assertNotNull(PricingTable.bundledFor("OpenAI", "GPT-4o"))
    }

    @Test
    fun `ollama wildcard is free`() {
        val p = PricingTable.bundledFor("ollama", "llama3.1:8b")
        assertNotNull(p)
        assertEquals(0.0, p!!.inputPer1M, 0.0001)
        assertEquals(0.0, p.outputPer1M, 0.0001)
    }

    @Test
    fun `prefix match on base model name`() {
        // "gpt-4o-2024-08-06" should resolve to the "gpt-4o" entry
        val p = PricingTable.bundledFor("openai", "gpt-4o-2024-08-06")
        assertNotNull(p)
        assertEquals("gpt-4o", p!!.modelId)
    }

    @Test
    fun `unknown model returns null - never a fake price`() {
        assertNull(PricingTable.bundledFor("openai", "totally-unknown-model-xyz"))
        assertNull(PricingTable.bundledFor("no-such-provider", "gpt-4o"))
    }

    @Test
    fun `route strategy parsing is lenient with fallback`() {
        assertEquals(RouteStrategy.CHEAP, RouteStrategy.fromName("cheap"))
        assertEquals(RouteStrategy.QUOTA, RouteStrategy.fromName("QUOTA"))
        assertEquals(RouteStrategy.AUTO, RouteStrategy.fromName(null))
        assertEquals(RouteStrategy.AUTO, RouteStrategy.fromName("garbage"))
    }

    @Test
    fun `bundled table has no duplicate keys`() {
        val keys = PricingTable.BUNDLED.map { it.providerType to it.modelId }
        assertTrue(keys.size == keys.toSet().size)
    }
}
