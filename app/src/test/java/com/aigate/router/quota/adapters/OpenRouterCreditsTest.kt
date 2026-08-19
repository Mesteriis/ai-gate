package com.aigate.router.quota.adapters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Баланс счёта OpenRouter. Раньше остаток брался из ответа про ключ, где
 * лимита у обычного счёта нет вовсе, и строка ресурса показывала ноль вместо
 * настоящих денег.
 */
class OpenRouterCreditsTest {

    private val provider = OpenRouterQuotaProvider()

    @Test
    fun `balance is purchased minus spent`() {
        val snapshot = provider.parseCredits(
            """{"data":{"total_credits":25.0,"total_usage":4.25}}""",
            poolId = 1L,
        )

        assertEquals(25.0, snapshot?.limit)
        assertEquals(4.25, snapshot?.used)
        assertEquals(20.75, snapshot?.remaining!!, 1e-9)
    }

    @Test
    fun `overspent account shows zero rather than a negative balance`() {
        val snapshot = provider.parseCredits(
            """{"data":{"total_credits":5.0,"total_usage":7.5}}""",
            poolId = 1L,
        )

        assertEquals(0.0, snapshot?.remaining!!, 1e-9)
    }

    @Test
    fun `spending falls back to the value from the key response`() {
        // Счёт отвечает без расхода, зато он известен из ответа про ключ:
        // выбрасывать уже полученную цифру незачем.
        val snapshot = provider.parseCredits(
            """{"data":{"total_credits":10.0}}""",
            poolId = 1L,
            usedFallback = 2.0,
        )

        assertEquals(2.0, snapshot?.used)
        assertEquals(8.0, snapshot?.remaining!!, 1e-9)
    }

    @Test
    fun `response without figures gives nothing instead of a made-up zero`() {
        assertNull(provider.parseCredits("""{"data":{}}""", poolId = 1L))
        assertNull(provider.parseCredits("""{}""", poolId = 1L))
        assertNull(provider.parseCredits("не json", poolId = 1L))
    }

    @Test
    fun `purchased credits alone still give a limit`() {
        val snapshot = provider.parseCredits("""{"data":{"total_credits":12.0}}""", poolId = 1L)

        assertEquals(12.0, snapshot?.limit)
        assertNull("остаток без расхода посчитать нельзя", snapshot?.remaining)
    }
}
