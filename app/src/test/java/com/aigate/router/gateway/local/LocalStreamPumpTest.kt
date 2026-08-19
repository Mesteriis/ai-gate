package com.aigate.router.gateway.local

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Формат кадров локального потока. Клиенты шлюза (в том числе Codex CLI) ждут
 * ровно OpenAI-совместимый SSE, поэтому проверяется и обёртка кадра, и его JSON.
 */
class LocalStreamPumpTest {

    private val pump = LocalStreamPump(modelId = "gemma-3n-e2b", streamId = "chatcmpl-local-1")

    /** Кадр без обёртки `data: ` клиентом не читается — снимаем её здесь же. */
    private fun payloadOf(frame: ByteArray): JSONObject {
        val text = frame.toString(Charsets.UTF_8)
        assertTrue("кадр начинается с data: — иначе клиент его пропустит", text.startsWith("data: "))
        assertTrue("кадр закрывается пустой строкой, иначе SSE не разделится", text.endsWith("\n\n"))
        return JSONObject(text.removePrefix("data: ").trim())
    }

    @Test
    fun `токен становится одним чанком chat completion`() {
        val frames = pump.frameFor(LocalDelta.Token("часть"))

        assertEquals(1, frames.size)
        val chunk = payloadOf(frames.single())
        assertEquals("chat.completion.chunk", chunk.getString("object"))
        assertEquals("chatcmpl-local-1", chunk.getString("id"))
        assertEquals("gemma-3n-e2b", chunk.getString("model"))
        val choice = chunk.getJSONArray("choices").getJSONObject(0)
        assertEquals("часть", choice.getJSONObject("delta").getString("content"))
        assertTrue("поток ещё не закрыт", choice.isNull("finish_reason"))
    }

    @Test
    fun `пустой токен не порождает кадра`() {
        // Кадр без содержимого клиенту ничего не даёт, но тратит соединение.
        assertTrue(pump.frameFor(LocalDelta.Token("")).isEmpty())
    }

    @Test
    fun `пробельный токен доходит до клиента`() {
        // Пробелы и переводы строк — часть ответа модели: если их отбрасывать,
        // текст склеится в одно слово.
        val chunk = payloadOf(pump.frameFor(LocalDelta.Token(" ")).single())

        assertEquals(" ", chunk.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("delta").getString("content"))
    }

    @Test
    fun `завершение закрывает поток причиной и расходом токенов`() {
        val frames = pump.frameFor(LocalDelta.Done(finishReason = "stop", promptTokens = 11, completionTokens = 4))

        assertEquals(1, frames.size)
        val chunk = payloadOf(frames.single())
        assertEquals("chatcmpl-local-1", chunk.getString("id"))
        val choice = chunk.getJSONArray("choices").getJSONObject(0)
        assertEquals("stop", choice.getString("finish_reason"))
        assertFalse("в закрывающем кадре текста уже нет", choice.getJSONObject("delta").has("content"))
        val usage = chunk.getJSONObject("usage")
        assertEquals(11, usage.getInt("prompt_tokens"))
        assertEquals(4, usage.getInt("completion_tokens"))
        assertEquals(15, usage.getInt("total_tokens"))
    }

    @Test
    fun `обрыв по лимиту сохраняет причину length`() {
        // Клиент отличает оборванный ответ от полного только по finish_reason.
        val chunk = payloadOf(
            pump.frameFor(LocalDelta.Done("length", promptTokens = 8, completionTokens = 256)).single()
        )

        assertEquals("length", chunk.getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
        assertEquals(264, chunk.getJSONObject("usage").getInt("total_tokens"))
    }

    @Test
    fun `движок без счётчиков отдаёт нули а не выдуманный расход`() {
        val usage = payloadOf(pump.frameFor(LocalDelta.Done("stop", 0, 0)).single()).getJSONObject("usage")

        assertEquals(0, usage.getInt("prompt_tokens"))
        assertEquals(0, usage.getInt("completion_tokens"))
        assertEquals(0, usage.getInt("total_tokens"))
    }

    @Test
    fun `последний кадр потока это DONE`() {
        assertEquals("data: [DONE]\n\n", pump.doneFrame().toString(Charsets.UTF_8))
    }

    @Test
    fun `пустой поток закрывается кадром ошибки а не тишиной`() {
        // Иначе клиент получит успешный пустой ответ и не поймёт, что движок
        // не сгенерировал ничего.
        val error = payloadOf(pump.emptyErrorFrame())

        assertEquals("error", error.getString("type"))
        assertTrue(error.getJSONObject("error").getString("message").isNotBlank())
    }
}
