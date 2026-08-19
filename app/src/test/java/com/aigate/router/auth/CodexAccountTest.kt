package com.aigate.router.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Тарифы подписки: подписи и прейскурантные цены. */
class CodexAccountTest {

    @Test
    fun `known plans get human labels`() {
        assertEquals("Plus", CodexAccount.planLabel("plus"))
        assertEquals("Pro", CodexAccount.planLabel("pro"))
        assertEquals("Team", CodexAccount.planLabel("team"))
        assertEquals("Free", CodexAccount.planLabel("free"))
    }

    @Test
    fun `plan label tolerates case and plan suffix`() {
        assertEquals("Plus", CodexAccount.planLabel("PLUS"))
        assertEquals("Pro", CodexAccount.planLabel("pro_plan"))
    }

    @Test
    fun `unknown plan is shown as-is rather than hidden`() {
        assertEquals("Startup", CodexAccount.planLabel("startup"))
    }

    @Test
    fun `blank plan has no label and no price`() {
        assertNull(CodexAccount.planLabel(null))
        assertNull(CodexAccount.planLabel(""))
        assertNull(CodexAccount.listPriceUsd(null))
    }

    @Test
    fun `list prices are provided for known plans only`() {
        assertEquals(20.0, CodexAccount.listPriceUsd("plus")!!, 0.001)
        assertEquals(200.0, CodexAccount.listPriceUsd("pro")!!, 0.001)
        assertEquals(0.0, CodexAccount.listPriceUsd("free")!!, 0.001)
        assertNull("для неизвестного тарифа цену не выдумываем", CodexAccount.listPriceUsd("startup"))
    }

    @Test
    fun `stored email is never used as account header`() {
        // Старая версия сохраняла в это поле почту; в заголовке аккаунта она бессмысленна.
        assertNull(CodexAccount.headerAccountId("user@example.com", null))
        assertEquals("acc_123", CodexAccount.headerAccountId("acc_123", null))
        assertNull(CodexAccount.headerAccountId(null, null))
        assertNull(CodexAccount.headerAccountId("", null))
    }
}
