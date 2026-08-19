package com.aigate.router.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Перевод chat/completions ↔ Messages API. Проверяем именно те различия
 * форматов, из-за которых прямой прокси к Anthropic не работает.
 */
class AnthropicUpstreamTest {

    @Test
    fun `первый системный блок подписки идёт до системы клиента`() {
        // Подписка отдаёт крупные модели только с этим блоком, а система от
        // клиента при этом не должна пропадать — она идёт вторым блоком.
        val out = JSONObject(
            AnthropicUpstream.translateRequest(
                """{"model":"m","messages":[
                    {"role":"system","content":"будь краток"},
                    {"role":"user","content":"привет"}]}""",
                systemPrefix = "Я клиент",
            )
        )
        val system = out.getJSONArray("system")
        assertEquals(2, system.length())
        assertEquals("text", system.getJSONObject(0).getString("type"))
        assertEquals("Я клиент", system.getJSONObject(0).getString("text"))
        assertEquals("будь краток", system.getJSONObject(1).getString("text"))
    }

    @Test
    fun `без системы клиента блок подписки идёт один`() {
        val out = JSONObject(
            AnthropicUpstream.translateRequest(
                """{"model":"m","messages":[{"role":"user","content":"привет"}]}""",
                systemPrefix = "Я клиент",
            )
        )
        assertEquals(1, out.getJSONArray("system").length())
    }

    @Test
    fun `системная роль уходит в поле system, а не в messages`() {
        val out = JSONObject(
            AnthropicUpstream.translateRequest(
                """{"model":"claude-x","messages":[
                    {"role":"system","content":"будь краток"},
                    {"role":"user","content":"привет"}]}"""
            )
        )
        assertEquals("будь краток", out.getString("system"))
        val msgs = out.getJSONArray("messages")
        assertEquals(1, msgs.length())
        assertEquals("user", msgs.getJSONObject(0).getString("role"))
    }

    @Test
    fun `max_tokens обязателен и берётся из запроса, иначе значение по умолчанию`() {
        val explicit = JSONObject(
            AnthropicUpstream.translateRequest("""{"model":"m","max_tokens":42,"messages":[{"role":"user","content":"a"}]}""")
        )
        assertEquals(42, explicit.getInt("max_tokens"))

        val implicit = JSONObject(
            AnthropicUpstream.translateRequest("""{"model":"m","messages":[{"role":"user","content":"a"}]}""")
        )
        assertEquals(AnthropicUpstream.DEFAULT_MAX_TOKENS, implicit.getInt("max_tokens"))
    }

    @Test
    fun `подряд идущие сообщения одной роли склеиваются`() {
        // Messages API требует чередования user/assistant и отвечает 400 на повтор.
        val out = JSONObject(
            AnthropicUpstream.translateRequest(
                """{"model":"m","messages":[
                    {"role":"user","content":"раз"},
                    {"role":"user","content":"два"},
                    {"role":"assistant","content":"ответ"}]}"""
            )
        )
        val msgs = out.getJSONArray("messages")
        assertEquals(2, msgs.length())
        assertEquals("раз\n\nдва", msgs.getJSONObject(0).getString("content"))
        assertEquals("assistant", msgs.getJSONObject(1).getString("role"))
    }

    @Test
    fun `stream не выставляется, когда клиент его не просил`() {
        val out = JSONObject(
            AnthropicUpstream.translateRequest("""{"model":"m","messages":[{"role":"user","content":"a"}]}""")
        )
        assertTrue(!out.has("stream"))
    }

    @Test
    fun `stop приводится к массиву stop_sequences`() {
        val out = JSONObject(
            AnthropicUpstream.translateRequest("""{"model":"m","stop":"КОНЕЦ","messages":[{"role":"user","content":"a"}]}""")
        )
        assertEquals("КОНЕЦ", out.getJSONArray("stop_sequences").getString(0))
    }

    @Test
    fun `ответ переводится в chat_completion с расходом токенов`() {
        val out = JSONObject(
            AnthropicUpstream.translateResponse(
                """{"id":"msg_1","model":"claude-x","content":[
                     {"type":"thinking","thinking":"скрытое"},
                     {"type":"text","text":"готово"}],
                   "stop_reason":"end_turn",
                   "usage":{"input_tokens":10,"output_tokens":3}}""",
                model = "запрошенная"
            )
        )
        val choice = out.getJSONArray("choices").getJSONObject(0)
        assertEquals("готово", choice.getJSONObject("message").getString("content"))
        assertEquals("stop", choice.getString("finish_reason"))
        assertEquals("claude-x", out.getString("model"))
        val usage = out.getJSONObject("usage")
        assertEquals(10, usage.getInt("prompt_tokens"))
        assertEquals(3, usage.getInt("completion_tokens"))
        assertEquals(13, usage.getInt("total_tokens"))
    }

    @Test
    fun `обрыв по лимиту токенов отдаётся как length`() {
        val out = JSONObject(
            AnthropicUpstream.translateResponse(
                """{"id":"m","content":[{"type":"text","text":"…"}],"stop_reason":"max_tokens"}""",
                model = "m"
            )
        )
        assertEquals("length", out.getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
    }

    @Test
    fun `дельта текста превращается в чанк, служебные события отбрасываются`() {
        val chunks = AnthropicUpstream.translateStreamEvent(
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"при"}}""",
            model = "m", id = "id1"
        )
        assertEquals(1, chunks.size)
        val delta = JSONObject(chunks[0]).getJSONArray("choices").getJSONObject(0)
        assertEquals("при", delta.getJSONObject("delta").getString("content"))
        assertTrue(delta.isNull("finish_reason"))

        // Размышления клиенту не отдаём, ping и старт блока — тоже.
        assertTrue(
            AnthropicUpstream.translateStreamEvent(
                """{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"…"}}""",
                "m", "id1"
            ).isEmpty()
        )
        assertTrue(AnthropicUpstream.translateStreamEvent("""{"type":"ping"}""", "m", "id1").isEmpty())
    }

    @Test
    fun `message_delta закрывает поток и несёт расход`() {
        val chunks = AnthropicUpstream.translateStreamEvent(
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}""",
            model = "m", id = "id1"
        )
        assertEquals(1, chunks.size)
        val obj = JSONObject(chunks[0])
        assertEquals("stop", obj.getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
        assertEquals(7, obj.getJSONObject("usage").getInt("completion_tokens"))
    }

    @Test
    fun `поток собирается в один ответ для нестримового клиента`() {
        val sse = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_2","model":"claude-y","usage":{"input_tokens":5}}}

            event: content_block_delta
            data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"при"}}

            event: content_block_delta
            data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"вет"}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}

            event: message_stop
            data: {"type":"message_stop"}
        """.trimIndent()

        val out = JSONObject(AnthropicUpstream.aggregateSseToCompletion(sse, model = "m"))
        assertEquals("привет", out.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
        assertEquals("claude-y", out.getString("model"))
        assertEquals(5, out.getJSONObject("usage").getInt("prompt_tokens"))
        assertEquals(2, out.getJSONObject("usage").getInt("completion_tokens"))
    }

    @Test
    fun `ошибка апстрима доходит до клиента текстом, а не пустым ответом`() {
        val out = JSONObject(
            AnthropicUpstream.aggregateSseToCompletion(
                """data: {"type":"error","error":{"type":"overloaded_error","message":"перегружено"}}""",
                model = "m"
            )
        )
        assertEquals(
            "перегружено",
            out.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        )
        assertNull(out.optJSONObject("usage"))
    }

    @Test
    fun `в потоке входные токены доходят до финального чанка`() {
        // Anthropic присылает их один раз, в message_start: без памяти расход
        // считался бы только по ответу.
        val translate = AnthropicUpstream.streamTranslator()
        translate(
            """{"type":"message_start","message":{"usage":{"input_tokens":120,"cache_read_input_tokens":30}}}""",
            "m", "id1",
        )
        val chunks = translate(
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":8}}""",
            "m", "id1",
        )
        val usage = JSONObject(chunks.single()).getJSONObject("usage")
        assertEquals(150, usage.getInt("prompt_tokens"))
        assertEquals(8, usage.getInt("completion_tokens"))
        assertEquals(158, usage.getInt("total_tokens"))
    }

    @Test
    fun `токены из кэша учитываются во входных`() {
        val out = JSONObject(
            AnthropicUpstream.translateResponse(
                """{"id":"m","content":[{"type":"text","text":"x"}],
                   "usage":{"input_tokens":10,"cache_read_input_tokens":90,"output_tokens":5}}""",
                model = "m",
            )
        )
        assertEquals(100, out.getJSONObject("usage").getInt("prompt_tokens"))
    }
}
