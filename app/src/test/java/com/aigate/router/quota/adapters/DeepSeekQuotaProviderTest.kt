package com.aigate.router.quota.adapters

import com.aigate.router.data.model.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Разбор баланса DeepSeek: суммы приходят строками, лимита и сброса нет. */
class DeepSeekQuotaProviderTest {

    private val adapter = DeepSeekQuotaProvider()

    @Test
    fun `balance is read from the usd account`() {
        val body = """
            {"is_available":true,"balance_infos":[
              {"currency":"CNY","total_balance":"110.00","granted_balance":"10.00","topped_up_balance":"100.00"},
              {"currency":"USD","total_balance":"12.34","granted_balance":"0.00","topped_up_balance":"12.34"}
            ]}
        """.trimIndent()

        val snap = adapter.parse(body, poolId = 7, now = 1_000L)!!

        assertEquals(7L, snap.poolId)
        assertEquals(12.34, snap.remaining!!, 0.001)
        assertEquals("USD", snap.unit)
        // Баланс — не квота: лимита и сброса у него нет, а расход провайдер не сообщает.
        assertNull(snap.limit)
        assertNull(snap.resetsAt)
        assertNull(snap.used)
    }

    @Test
    fun `single non-usd account is still reported`() {
        val body = """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"88.50"}]}"""
        val snap = adapter.parse(body, poolId = 1)!!
        assertEquals(88.50, snap.remaining!!, 0.001)
        assertEquals("CNY", snap.unit)
    }

    @Test
    fun `garbage and empty responses yield nothing`() {
        listOf("", "не json", "{}", """{"balance_infos":[]}""", """{"balance_infos":[{"currency":"USD"}]}""")
            .forEach { assertNull("ответ «$it» не должен давать снимок", adapter.parse(it, poolId = 1)) }
    }

    @Test
    fun `adapter applies to deepseek providers only`() {
        fun provider(url: String) = Provider(name = "p", type = "OpenAI Compatible", baseUrl = url)
        assertTrue(adapter.appliesTo(provider("https://api.deepseek.com")))
        assertTrue(adapter.appliesTo(provider("https://API.DeepSeek.com/v1")))
        assertTrue(!adapter.appliesTo(provider("https://api.openai.com")))
    }
}
