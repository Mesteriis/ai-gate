package com.aigate.router.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контракт входа Claude снят с самого клиента Claude Code. Тест сторожит
 * именно те места, где «исправление на стандарт» ломает вход:
 * JSON-тело обмена, параметр `code=true`, отсутствие google-параметров.
 */
class ClaudeCliAuthTest {

    @Test
    fun `клиент публичный, идентификатор — UUID клиента Claude Code`() {
        assertEquals("9d1c250a-e61b-44d9-88ed-5944d1962f5e", ClaudeCliAuth.CLIENT_ID)
        assertNull(ClaudeCliAuth.config.clientSecret)
    }

    @Test
    fun `обмен кода идёт JSON и со state`() {
        // Token endpoint Anthropic не принимает form-urlencoded, а state ждёт в теле.
        assertTrue(ClaudeCliAuth.config.tokenRequestJson)
        assertTrue(ClaudeCliAuth.config.sendStateInTokenRequest)
    }

    @Test
    fun `параметр code обязателен, google-параметры не отправляются`() {
        assertEquals("true", ClaudeCliAuth.config.extraAuthParams["code"])
        assertFalse(ClaudeCliAuth.config.requestOfflineAccess)
    }

    @Test
    fun `порт редиректа эфемерный, путь callback`() {
        // Фиксированный порт конфликтовал бы с самим CLI; RFC 8252 разрешает любой.
        assertNull(ClaudeCliAuth.config.fixedPort)
        assertEquals("/callback", ClaudeCliAuth.config.redirectPath)
    }

    @Test
    fun `права запрашиваются минимальные`() {
        assertEquals(listOf("user:inference", "user:profile"), ClaudeCliAuth.config.scopes)
        assertFalse(ClaudeCliAuth.config.scopes.any { it.startsWith("org:") })
    }

    @Test
    fun `адреса ведут на вход подпиской и на api anthropic`() {
        assertEquals("https://claude.com/cai/oauth/authorize", ClaudeCliAuth.config.authUrl)
        assertEquals("https://platform.claude.com/v1/oauth/token", ClaudeCliAuth.config.tokenUrl)
        assertEquals("https://api.anthropic.com", ClaudeCliAuth.DEFAULT_BASE_URL)
        assertEquals("/v1/messages", ClaudeCliAuth.CHAT_PATH)
    }

    @Test
    fun `запасной список моделей не пуст и без дублей`() {
        val ids = ClaudeCliAuth.FALLBACK_MODELS.map { it.first }
        assertTrue(ids.isNotEmpty())
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("claude-") })
    }
}
