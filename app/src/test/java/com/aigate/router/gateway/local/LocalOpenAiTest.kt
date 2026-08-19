package com.aigate.router.gateway.local

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контракт проводного формата локальных движков: то, что видит клиент шлюза,
 * должно быть неотличимо от ответа настоящего OpenAI-провайдера.
 */
class LocalOpenAiTest {

    @Test
    fun `string content is taken as is`() {
        val req = LocalOpenAi.parseChatRequest(
            """{"model":"gemma-3n","messages":[{"role":"system","content":"Ты помощник"},
                                              {"role":"user","content":"Привет"}]}"""
        )

        assertEquals("gemma-3n", req.modelId)
        assertEquals(2, req.messages.size)
        assertEquals(ChatMsg("system", "Ты помощник"), req.messages[0])
        assertEquals(ChatMsg("user", "Привет"), req.messages[1])
    }

    @Test
    fun `multipart content is flattened to text`() {
        val req = LocalOpenAi.parseChatRequest(
            """{"model":"m","messages":[{"role":"user","content":[
                {"type":"text","text":"строка 1"},{"type":"text","text":"строка 2"}]}]}"""
        )

        assertEquals("строка 1\nстрока 2", req.messages.single().text)
    }

    @Test
    fun `messages without text are skipped`() {
        // Пустой content и картинка без подписи движку ничего не дают.
        val req = LocalOpenAi.parseChatRequest(
            """{"model":"m","messages":[{"role":"user","content":""},
                                        {"role":"user","content":[{"type":"image_url","image_url":{"url":"x"}}]},
                                        {"role":"user","content":null},
                                        {"role":"user","content":"остался один"}]}"""
        )

        assertEquals("остался один", req.messages.single().text)
    }

    @Test
    fun `missing sampling fields stay null instead of defaults`() {
        // Подставить своё значение вместо системного нельзя: часть движков
        // не даёт управлять выборкой вовсе.
        val req = LocalOpenAi.parseChatRequest("""{"model":"m","messages":[]}""")

        assertNull(req.maxTokens)
        assertNull(req.temperature)
        assertTrue(req.messages.isEmpty())
    }

    @Test
    fun `sampling fields are read when present`() {
        val req = LocalOpenAi.parseChatRequest(
            """{"model":"m","max_tokens":256,"temperature":0.7,"messages":[]}"""
        )

        assertEquals(256, req.maxTokens)
        assertEquals(0.7, req.temperature!!, 0.0001)
    }

    @Test
    fun `битый JSON отвергается сообщением на русском`() {
        val error = runCatching { LocalOpenAi.parseChatRequest("не json") }.exceptionOrNull()

        assertTrue("ожидалось IllegalArgumentException, получено $error", error is IllegalArgumentException)
        assertTrue(
            "сообщение об ошибке показывается пользователю и должно быть на русском",
            error!!.message.orEmpty().contains("Тело запроса")
        )
    }

    @Test
    fun `token chunk carries assistant delta and open finish reason`() {
        val chunk = JSONObject(LocalOpenAi.chunkJson("id-1", "gemma-3n", "часть", null))

        assertEquals("chat.completion.chunk", chunk.getString("object"))
        assertEquals("id-1", chunk.getString("id"))
        assertEquals("gemma-3n", chunk.getString("model"))
        assertTrue("created — эпоха в секундах", chunk.getLong("created") > 1_700_000_000L)
        val choice = chunk.getJSONArray("choices").getJSONObject(0)
        assertEquals(0, choice.getInt("index"))
        assertEquals("assistant", choice.getJSONObject("delta").getString("role"))
        assertEquals("часть", choice.getJSONObject("delta").getString("content"))
        assertTrue(choice.isNull("finish_reason"))
        assertFalse("usage не отдаётся, пока генерация не закончена", chunk.has("usage"))
    }

    @Test
    fun `closing chunk has empty delta and finish reason`() {
        val chunk = JSONObject(LocalOpenAi.chunkJson("id-1", "m", null, "stop"))

        val choice = chunk.getJSONArray("choices").getJSONObject(0)
        assertEquals("пустой объект, а не пустая строка", 0, choice.getJSONObject("delta").length())
        assertEquals("stop", choice.getString("finish_reason"))
    }

    @Test
    fun `usage is added only together with both counters`() {
        val withUsage = JSONObject(
            LocalOpenAi.chunkJson("id-1", "m", null, "length", promptTokens = 12, completionTokens = 30)
        )
        val usage = withUsage.getJSONObject("usage")
        assertEquals(12, usage.getInt("prompt_tokens"))
        assertEquals(30, usage.getInt("completion_tokens"))
        assertEquals(42, usage.getInt("total_tokens"))
        assertEquals("length", withUsage.getJSONArray("choices").getJSONObject(0).getString("finish_reason"))

        val halfKnown = JSONObject(LocalOpenAi.chunkJson("id-1", "m", null, "stop", promptTokens = 12))
        assertFalse("половина статистики хуже её отсутствия", halfKnown.has("usage"))
    }

    @Test
    fun `completion carries message instead of delta`() {
        val out = JSONObject(
            LocalOpenAi.completionJson("id-2", "gemma-3n", "готовый ответ", "stop", 8, 4)
        )

        assertEquals("chat.completion", out.getString("object"))
        assertEquals("id-2", out.getString("id"))
        assertEquals("gemma-3n", out.getString("model"))
        val choice = out.getJSONArray("choices").getJSONObject(0)
        assertFalse(choice.has("delta"))
        assertEquals("assistant", choice.getJSONObject("message").getString("role"))
        assertEquals("готовый ответ", choice.getJSONObject("message").getString("content"))
        assertEquals("stop", choice.getString("finish_reason"))
        assertEquals(12, out.getJSONObject("usage").getInt("total_tokens"))
    }

    @Test
    fun `error envelope repeats the gateway shape`() {
        val error = JSONObject(LocalOpenAi.errorJson("Модель не найдена")).getJSONObject("error")

        assertEquals("Модель не найдена", error.getString("message"))
        assertEquals("invalid_request_error", error.getString("type"))
        assertTrue("клиенты читают param без проверки на наличие ключа", error.isNull("param"))
        assertTrue(error.isNull("code"))

        val custom = JSONObject(LocalOpenAi.errorJson("Движок занят", "server_error")).getJSONObject("error")
        assertEquals("server_error", custom.getString("type"))
    }

    @Test
    fun `new ids share the prefix but differ`() {
        val first = LocalOpenAi.newId()
        val second = LocalOpenAi.newId()

        assertTrue(first.startsWith("chatcmpl-local-"))
        assertFalse("идентификаторы ответов не должны совпадать", first == second)
        assertTrue(LocalOpenAi.newId("chatcmpl-nano").startsWith("chatcmpl-nano-"))
    }
}
