package com.aigate.router.gateway.local

import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Мост между проводным форматом OpenAI и локальными движками.
 *
 * У локального бэкенда нет ответа провайдера, который можно было бы перевести,
 * как это делает CodexUpstream: движок отдаёт голый поток токенов. Поэтому
 * здесь собрана вторая половина работы — разбор запроса клиента и сборка
 * кадров `chat.completion.chunk` / `chat.completion` вокруг [LocalDelta].
 *
 * Файл намеренно без Android-зависимостей: формат ответа проверяется обычными
 * JVM-тестами. Хвост SSE (`data: [DONE]`) и кадр пустого потока не дублируются —
 * их отдаёт `OpenAiStreamCompat`.
 */
object LocalOpenAi {

    /**
     * Тело `/v1/chat/completions` → запрос к движку.
     *
     * Неизвестные поля запроса не тащим: локальный движок всё равно понимает
     * только модель, диалог, лимит и температуру.
     *
     * @throws IllegalArgumentException если тело не является JSON-объектом
     */
    fun parseChatRequest(bodyJson: String): LocalChatRequest {
        val src = try {
            JSONObject(bodyJson)
        } catch (e: JSONException) {
            throw IllegalArgumentException("Тело запроса не является корректным JSON", e)
        }

        val messages = src.optJSONArray("messages") ?: JSONArray()
        val parsed = ArrayList<ChatMsg>(messages.length())
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val text = contentToText(m.opt("content"))
            // Сообщение без текста (пустой content или одни картинки) движку
            // ничего не даёт, а шаблон промпта из-за него ломается.
            if (text.isBlank()) continue
            parsed += ChatMsg(role = m.optString("role", "user").ifBlank { "user" }, text = text)
        }

        return LocalChatRequest(
            modelId = src.optString("model").trim(),
            messages = parsed,
            maxTokens = optPositiveInt(src, "max_tokens"),
            temperature = optFiniteDouble(src, "temperature"),
        )
    }

    /**
     * Один кадр потока. [deltaText] == null означает служебный кадр (закрытие
     * потока), поэтому `delta` уходит пустым объектом, а не с пустой строкой:
     * клиенты отличают «нет текста» от «пустой текст».
     *
     * `usage` появляется только когда известны оба счётчика — половина
     * статистики вводит клиента в заблуждение сильнее, чем её отсутствие.
     */
    fun chunkJson(
        id: String,
        model: String,
        deltaText: String?,
        finishReason: String?,
        promptTokens: Int? = null,
        completionTokens: Int? = null,
    ): String = JSONObject().apply {
        put("id", id)
        put("object", "chat.completion.chunk")
        put("created", System.currentTimeMillis() / 1000)
        put("model", model)
        put("choices", JSONArray().put(JSONObject().apply {
            put("index", 0)
            put("delta", if (deltaText == null) JSONObject() else JSONObject().apply {
                put("role", "assistant")
                put("content", deltaText)
            })
            put("finish_reason", finishReason ?: JSONObject.NULL)
        }))
        if (promptTokens != null && completionTokens != null) {
            put("usage", usageJson(promptTokens, completionTokens))
        }
    }.toString()

    /** Обычный (нестримовый) ответ для клиента, который не просил поток. */
    fun completionJson(
        id: String,
        model: String,
        text: String,
        finishReason: String,
        promptTokens: Int,
        completionTokens: Int,
    ): String = JSONObject().apply {
        put("id", id)
        put("object", "chat.completion")
        put("created", System.currentTimeMillis() / 1000)
        put("model", model)
        put("choices", JSONArray().put(JSONObject().apply {
            put("index", 0)
            put("message", JSONObject().apply {
                put("role", "assistant")
                put("content", text)
            })
            put("finish_reason", finishReason)
        }))
        put("usage", usageJson(promptTokens, completionTokens))
    }.toString()

    /**
     * Конверт ошибки в форме OpenAI. Поля `param` и `code` присутствуют всегда,
     * пусть и пустыми: клиенты шлюза читают их без проверки на наличие ключа
     * (см. openAIError в GatewayService).
     */
    fun errorJson(message: String, type: String = "invalid_request_error"): String =
        JSONObject().apply {
            put("error", JSONObject().apply {
                put("message", message)
                put("type", type)
                put("param", JSONObject.NULL)
                put("code", JSONObject.NULL)
            })
        }.toString()

    /** Идентификатор ответа: клиенту важна только его уникальность в сессии. */
    fun newId(prefix: String = "chatcmpl-local"): String =
        "$prefix-${UUID.randomUUID().toString().take(12)}"

    private fun usageJson(promptTokens: Int, completionTokens: Int): JSONObject =
        JSONObject().apply {
            put("prompt_tokens", promptTokens)
            put("completion_tokens", completionTokens)
            put("total_tokens", promptTokens + completionTokens)
        }

    /** Контент сообщения бывает строкой или массивом частей — сводим к тексту. */
    private fun contentToText(content: Any?): String = when (content) {
        null, JSONObject.NULL -> ""
        is String -> content
        is JSONArray -> buildString {
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                val t = part.optString("text").ifBlank { part.optString("input_text") }
                if (t.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(t)
                }
            }
        }
        else -> content.toString()
    }

    /**
     * Ноль и мусор в лимите равнозначны его отсутствию: с `max_tokens = 0`
     * движок вернул бы пустой ответ вместо генерации.
     */
    private fun optPositiveInt(src: JSONObject, key: String): Int? {
        if (!src.has(key) || src.isNull(key)) return null
        return src.optInt(key, 0).takeIf { it > 0 }
    }

    private fun optFiniteDouble(src: JSONObject, key: String): Double? {
        if (!src.has(key) || src.isNull(key)) return null
        return src.optDouble(key, Double.NaN).takeIf { !it.isNaN() }
    }
}
