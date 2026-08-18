package com.aigate.router.quota

import com.aigate.router.quota.adapters.CodexQuotaProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodexQuotaProviderTest {

    private val adapter = CodexQuotaProvider()
    private val now = 1_787_000_000_000L

    @Test
    fun `parses real codex usage response`() {
        val body = """
            {"user_id":"user-x","email":"a@b.com","plan_type":"pro",
             "rate_limit":{"allowed":true,"limit_reached":false,
               "primary_window":{"used_percent":95,"limit_window_seconds":604800,"reset_after_seconds":138220,"reset_at":1787209285},
               "secondary_window":null}}
        """.trimIndent()
        val snap = adapter.parseQuota(body, poolId = 3, now = now)!!
        assertEquals(3, snap.poolId)
        assertEquals(95.0, snap.used!!, 0.001)
        assertEquals(5.0, snap.remaining!!, 0.001)
        assertEquals(100.0, snap.limit!!, 0.001)
        assertEquals(QuotaUnit.PERCENT.name, snap.unit)
        assertEquals(QuotaSource.PROVIDER_API.name, snap.source)
        assertEquals(1787209285L * 1000, snap.resetsAt)
    }

    @Test
    fun `falls back to secondary window`() {
        val body = """{"rate_limit":{"primary_window":null,"secondary_window":{"used_percent":40,"reset_after_seconds":3600}}}"""
        val snap = adapter.parseQuota(body, poolId = 1, now = now)!!
        assertEquals(40.0, snap.used!!, 0.001)
        assertEquals(now + 3600_000, snap.resetsAt)
    }

    @Test
    fun `no rate_limit yields null - honest unavailable`() {
        assertNull(adapter.parseQuota("""{"email":"a@b.com","plan_type":"pro"}""", poolId = 1, now = now))
        assertNull(adapter.parseQuota("""{"rate_limit":{"primary_window":null,"secondary_window":null}}""", poolId = 1, now = now))
        assertNull(adapter.parseQuota("not json", poolId = 1, now = now))
    }
}
