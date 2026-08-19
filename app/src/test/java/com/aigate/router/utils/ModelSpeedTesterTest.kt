package com.aigate.router.utils

import com.aigate.router.auth.ClaudeCliAuth
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Замер провайдера Anthropic ходит на Messages API, а не chat/completions:
 * другое тело, другие заголовки, другой формат потока. Без этого замер
 * подписки Claude был обречён — OpenAI-тело на /v1/messages отклоняется.
 */
class ModelSpeedTesterTest {

    private fun anthropicSse(): String = listOf(
        """data: {"type":"message_start","message":{"usage":{"input_tokens":10}}}""",
        """data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Привет, я отвечаю на замер."}}""",
        """data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":6}}""",
        """data: {"type":"message_stop"}""",
    ).joinToString("\n\n", postfix = "\n\n")

    @Test
    fun `подписка Claude - тело Messages, заголовки идентичности, поток разобран`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(anthropicSse()),
        )
        server.start()
        try {
            val metrics = ModelSpeedTester().measure(
                modelId = "claude-opus-4-6",
                baseUrl = server.url("/").toString(),
                apiKey = "sk-oauth-token",
                chatPath = "/v1/messages",
                useMessagesApi = true,
                claudeSubscription = true,
            )

            assertTrue("TTFT должен быть замерен: $metrics", metrics.ttftMs >= 0)
            assertTrue("токены ответа должны быть посчитаны", metrics.tokenCount > 0)

            val recorded = server.takeRequest()
            assertEquals("/v1/messages", recorded.path)
            assertEquals("Bearer sk-oauth-token", recorded.getHeader("Authorization"))
            assertEquals(ClaudeCliAuth.APP, recorded.getHeader(ClaudeCliAuth.APP_HEADER))
            assertEquals(ClaudeCliAuth.USER_AGENT, recorded.getHeader("User-Agent"))

            val body = JSONObject(recorded.body.readUtf8())
            assertEquals("claude-opus-4-6", body.getString("model"))
            assertTrue(body.getBoolean("stream"))
            // Новейшие модели Claude отвечают 400 на температуру — зонд её не шлёт.
            assertTrue(!body.has("temperature"))
            val system = body.getJSONArray("system")
            assertEquals(ClaudeCliAuth.IDENTITY_PROMPT, system.getJSONObject(0).getString("text"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `ключ Anthropic - x-api-key без заголовков подписки`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(anthropicSse()),
        )
        server.start()
        try {
            ModelSpeedTester().measure(
                modelId = "claude-opus-4-6",
                baseUrl = server.url("/").toString(),
                apiKey = "sk-ant-key",
                chatPath = "/v1/messages",
                useMessagesApi = true,
                claudeSubscription = false,
            )

            val recorded = server.takeRequest()
            assertEquals("sk-ant-key", recorded.getHeader("x-api-key"))
            assertNull(recorded.getHeader("Authorization"))
            assertNull(recorded.getHeader(ClaudeCliAuth.APP_HEADER))
            // Без подписки представляться Claude Code нельзя — системного блока нет.
            assertNull(JSONObject(recorded.body.readUtf8()).optJSONArray("system"))
        } finally {
            server.shutdown()
        }
    }
}
