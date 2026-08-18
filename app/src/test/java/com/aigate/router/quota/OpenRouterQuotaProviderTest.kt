package com.aigate.router.quota

import com.aigate.router.quota.adapters.OpenRouterQuotaProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenRouterQuotaProviderTest {

    private val adapter = OpenRouterQuotaProvider()
    private val now = 1_700_000_000_000L

    @Test
    fun `parses usage limit and remaining as real provider data`() {
        val body = """
            {"data":{"label":"sk-or-x","usage":1.25,"limit":10.0,"is_free_tier":false,"limit_remaining":8.75}}
        """.trimIndent()
        val snap = adapter.parse(body, poolId = 7, now = now)!!
        assertEquals(7, snap.poolId)
        assertEquals(1.25, snap.used!!, 0.0001)
        assertEquals(10.0, snap.limit!!, 0.0001)
        assertEquals(8.75, snap.remaining!!, 0.0001)
        assertEquals(QuotaUnit.USD.name, snap.unit)
        assertEquals(QuotaSource.PROVIDER_API.name, snap.source)
    }

    @Test
    fun `null limit (unlimited) yields null remaining - not a fake number`() {
        val body = """{"data":{"label":"sk","usage":3.0,"limit":null,"limit_remaining":null}}"""
        val snap = adapter.parse(body, poolId = 1, now = now)!!
        assertEquals(3.0, snap.used!!, 0.0001)
        assertNull(snap.limit)
        assertNull(snap.remaining)
    }

    @Test
    fun `remaining derived from limit minus usage when field absent`() {
        val body = """{"data":{"usage":2.0,"limit":10.0}}"""
        val snap = adapter.parse(body, poolId = 1, now = now)!!
        assertEquals(8.0, snap.remaining!!, 0.0001)
    }

    @Test
    fun `missing data object returns null`() {
        assertNull(adapter.parse("""{"error":"unauthorized"}""", poolId = 1, now = now))
    }
}
