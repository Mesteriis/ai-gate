package com.aigate.router.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контракт перевода chat/completions ↔ Responses API (Codex).
 * Формат зафиксирован по реальному Codex CLI.
 */
class CodexUpstreamTest {

    @Test
    fun `system role becomes instructions and messages become input`() {
        val chat = """
            {"model":"gpt-5.6-sol","stream":true,
             "messages":[{"role":"system","content":"Ты помощник"},
                         {"role":"user","content":"Привет"}]}
        """.trimIndent()

        val out = JSONObject(CodexUpstream.translateRequest(chat))

        assertEquals("gpt-5.6-sol", out.getString("model"))
        assertEquals("Ты помощник", out.getString("instructions"))
        // Codex принимает только поток: 400 «Stream must be set to true».
        assertTrue(out.getBoolean("stream"))
        assertFalse("диалог не должен сохраняться у провайдера", out.getBoolean("store"))
        // messages больше нет — Responses принимает input
        assertFalse(out.has("messages"))
        val input = out.getJSONArray("input")
        assertEquals(1, input.length())
        val first = input.getJSONObject(0)
        assertEquals("user", first.getString("role"))
        val part = first.getJSONArray("content").getJSONObject(0)
        assertEquals("input_text", part.getString("type"))
        assertEquals("Привет", part.getString("text"))
    }

    @Test
    fun `assistant history uses output_text part type`() {
        val chat = """
            {"model":"m","messages":[{"role":"assistant","content":"было"},
                                     {"role":"user","content":"дальше"}]}
        """.trimIndent()

        val input = JSONObject(CodexUpstream.translateRequest(chat)).getJSONArray("input")

        assertEquals("output_text", input.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("type"))
        assertEquals("input_text", input.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("type"))
    }

    @Test
    fun `multipart content is flattened to text`() {
        val chat = """
            {"model":"m","messages":[{"role":"user","content":[
                {"type":"text","text":"строка 1"},{"type":"text","text":"строка 2"}]}]}
        """.trimIndent()

        val text = JSONObject(CodexUpstream.translateRequest(chat))
            .getJSONArray("input").getJSONObject(0)
            .getJSONArray("content").getJSONObject(0).getString("text")

        assertEquals("строка 1\nстрока 2", text)
    }

    @Test
    fun `upstream is always asked to stream even when client did not`() {
        val out = JSONObject(CodexUpstream.translateRequest("""{"model":"m","stream":false,"messages":[]}"""))
        assertTrue("бэкенд Codex отклоняет нестримовые запросы", out.getBoolean("stream"))
    }

    @Test
    fun `sse stream is aggregated into a single completion`() {
        val sse = """
            data: {"type":"response.created","response":{}}

            data: {"type":"response.output_text.delta","delta":"раб"}

            data: {"type":"response.output_text.delta","delta":"отает"}

            data: {"type":"response.completed","response":{"usage":{"input_tokens":15,"output_tokens":6}}}

            data: [DONE]
        """.trimIndent()

        val out = JSONObject(CodexUpstream.aggregateSseToCompletion(sse, "gpt-x"))

        assertEquals("chat.completion", out.getString("object"))
        assertEquals("gpt-x", out.getString("model"))
        val choice = out.getJSONArray("choices").getJSONObject(0)
        assertEquals("работает", choice.getJSONObject("message").getString("content"))
        assertEquals("stop", choice.getString("finish_reason"))
        assertEquals(21, out.getJSONObject("usage").getInt("total_tokens"))
    }

    @Test
    fun `aggregation prefers full text from final event over concatenated deltas`() {
        val sse = """
            data: {"type":"response.output_text.delta","delta":"частич"}

            data: {"type":"response.completed","response":{"output":[{"type":"message","content":[{"type":"output_text","text":"полный ответ"}]}]}}
        """.trimIndent()

        val text = JSONObject(CodexUpstream.aggregateSseToCompletion(sse, "m"))
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")

        assertEquals("полный ответ", text)
    }

    @Test
    fun `aggregation surfaces upstream error text`() {
        val sse = """data: {"type":"response.failed","response":{"error":{"message":"квота исчерпана"}}}"""

        val text = JSONObject(CodexUpstream.aggregateSseToCompletion(sse, "m"))
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")

        assertEquals("квота исчерпана", text)
    }

    @Test
    fun `aggregation of empty stream yields empty content not a crash`() {
        val out = JSONObject(CodexUpstream.aggregateSseToCompletion("", "m"))
        assertEquals("", out.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content"))
    }

    @Test
    fun `max_tokens maps to max_output_tokens`() {
        val out = JSONObject(CodexUpstream.translateRequest("""{"model":"m","max_tokens":256,"messages":[]}"""))
        assertEquals(256, out.getInt("max_output_tokens"))
        assertFalse(out.has("max_tokens"))
    }

    @Test
    fun `response output_text is collected and reasoning skipped`() {
        val responses = """
            {"id":"resp_1","model":"gpt-5.6-sol","status":"completed",
             "output":[{"type":"reasoning","content":[{"type":"output_text","text":"не показывать"}]},
                       {"type":"message","role":"assistant",
                        "content":[{"type":"output_text","text":"ответ "},
                                   {"type":"output_text","text":"модели"}]}],
             "usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}
        """.trimIndent()

        val out = JSONObject(CodexUpstream.translateResponse(responses, "fallback"))

        assertEquals("chat.completion", out.getString("object"))
        assertEquals("resp_1", out.getString("id"))
        assertEquals("gpt-5.6-sol", out.getString("model"))
        val message = out.getJSONArray("choices").getJSONObject(0)
        assertEquals("ответ модели", message.getJSONObject("message").getString("content"))
        assertEquals("stop", message.getString("finish_reason"))
        val usage = out.getJSONObject("usage")
        assertEquals(10, usage.getInt("prompt_tokens"))
        assertEquals(5, usage.getInt("completion_tokens"))
        assertEquals(15, usage.getInt("total_tokens"))
    }

    @Test
    fun `incomplete response reports length finish reason`() {
        val out = JSONObject(CodexUpstream.translateResponse("""{"id":"r","status":"incomplete","output":[]}""", "m"))
        assertEquals("length", out.getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
    }

    @Test
    fun `malformed upstream body does not throw`() {
        val out = JSONObject(CodexUpstream.translateResponse("не json", "m"))
        assertEquals("chat.completion", out.getString("object"))
    }

    @Test
    fun `text delta becomes chat chunk`() {
        val chunks = CodexUpstream.translateStreamEvent(
            """{"type":"response.output_text.delta","delta":"часть"}""", "m", "id-1"
        )

        assertEquals(1, chunks.size)
        val chunk = JSONObject(chunks[0])
        assertEquals("chat.completion.chunk", chunk.getString("object"))
        assertEquals("id-1", chunk.getString("id"))
        val choice = chunk.getJSONArray("choices").getJSONObject(0)
        assertEquals("часть", choice.getJSONObject("delta").getString("content"))
        assertTrue(choice.isNull("finish_reason"))
    }

    @Test
    fun `completed event closes stream with usage`() {
        val chunks = CodexUpstream.translateStreamEvent(
            """{"type":"response.completed","response":{"usage":{"input_tokens":3,"output_tokens":7}}}""",
            "m", "id-1"
        )

        val chunk = JSONObject(chunks.single())
        assertEquals("stop", chunk.getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
        assertEquals(10, chunk.getJSONObject("usage").getInt("total_tokens"))
    }

    @Test
    fun `failed event surfaces message then closes`() {
        val chunks = CodexUpstream.translateStreamEvent(
            """{"type":"response.failed","response":{"error":{"message":"квота исчерпана"}}}""",
            "m", "id-1"
        )

        assertEquals(2, chunks.size)
        assertEquals(
            "квота исчерпана",
            JSONObject(chunks[0]).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("delta").getString("content")
        )
        assertEquals(
            "stop",
            JSONObject(chunks[1]).getJSONArray("choices").getJSONObject(0).getString("finish_reason")
        )
    }

    @Test
    fun `noise events are dropped`() {
        listOf(
            """{"type":"response.created","response":{}}""",
            """{"type":"response.reasoning_summary.delta","delta":"думаю"}""",
            """{"type":"response.output_item.added"}""",
        ).forEach {
            assertTrue("событие $it не должно доходить до клиента",
                CodexUpstream.translateStreamEvent(it, "m", "id").isEmpty())
        }
    }
}
