package com.aigate.router.gateway

import com.aigate.router.auth.ClaudeCliAuth
import com.aigate.router.data.model.Provider
import org.json.JSONArray
import org.json.JSONObject

/**
 * Anthropic говорит не на `/v1/chat/completions`, а на Messages API:
 * `POST {base}/v1/messages`. Клиенты шлюза присылают обычный OpenAI-chat,
 * поэтому здесь живёт двусторонний перевод — как [CodexUpstream] для Codex:
 *
 *  запрос:  chat/completions → messages
 *  ответ:   message → chat.completion
 *  стрим:   события `content_block_delta`/`message_delta` → `chat.completion.chunk`
 *
 * Отличия формата, из-за которых прямой прокси не работает: роли `system` в
 * массиве сообщений нет (она отдельным полем), `max_tokens` обязателен,
 * ответ отдаёт `content` блоками, а причина остановки называется `stop_reason`.
 */
object AnthropicUpstream {

    const val MESSAGES_PATH = "/v1/messages"

    /**
     * Значение `max_tokens`, когда клиент его не передал: в Messages API это
     * обязательное поле, а в chat/completions — нет. Ограничение выбрано с
     * запасом на длинный ответ и заведомо ниже лимита любой модели Claude.
     */
    const val DEFAULT_MAX_TOKENS = 8192

    /** Провайдеры, чей апстрим — Messages API: подписка Claude и ключ Anthropic. */
    fun isAnthropic(provider: Provider): Boolean {
        val t = provider.type.trim().lowercase()
        return t == "anthropic" || t == ClaudeCliAuth.PROVIDER_TYPE
    }

    /** Аутентификация подпиской (OAuth), а не ключом API. */
    fun isSubscription(provider: Provider): Boolean =
        provider.type.trim().equals(ClaudeCliAuth.PROVIDER_TYPE, ignoreCase = true)

    fun messagesUrl(provider: Provider): String =
        provider.resolvedBaseUrl.trimEnd('/') + MESSAGES_PATH

    /**
     * chat/completions → messages.
     *
     * Системные сообщения уходят в поле `system`. Подряд идущие сообщения одной
     * роли склеиваются: Messages API требует чередования user/assistant.
     * Ответы инструментов приводятся к тексту от роли user — иначе запрос
     * отклоняется, а терять содержимое нельзя.
     *
     * [systemPrefix] — первый системный блок, которого ждёт подписка. Когда он
     * задан, `system` уходит массивом блоков: первым он, вторым — система от
     * клиента, чтобы её не потерять.
     */
    fun translateRequest(chatJson: String, systemPrefix: String? = null): String {
        val src = JSONObject(chatJson)
        val messages = src.optJSONArray("messages") ?: JSONArray()

        val system = StringBuilder()
        val out = JSONArray()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role", "user")
            val text = contentToText(m.opt("content"))
            if (text.isBlank()) continue
            if (role == "system" || role == "developer") {
                if (system.isNotEmpty()) system.append("\n\n")
                system.append(text)
                continue
            }
            val target = if (role == "assistant") "assistant" else "user"
            val last = if (out.length() > 0) out.optJSONObject(out.length() - 1) else null
            if (last != null && last.optString("role") == target) {
                last.put("content", last.optString("content") + "\n\n" + text)
            } else {
                out.put(JSONObject().apply {
                    put("role", target)
                    put("content", text)
                })
            }
        }

        return JSONObject().apply {
            put("model", src.optString("model"))
            put("messages", out)
            when {
                systemPrefix != null -> put("system", JSONArray().apply {
                    put(textBlock(systemPrefix))
                    if (system.isNotEmpty()) put(textBlock(system.toString()))
                })
                system.isNotEmpty() -> put("system", system.toString())
            }
            put("max_tokens", maxTokens(src))
            if (src.optBoolean("stream", false)) put("stream", true)
            src.opt("temperature")?.let { if (it != JSONObject.NULL) put("temperature", it) }
            src.opt("top_p")?.let { if (it != JSONObject.NULL) put("top_p", it) }
            stopSequences(src.opt("stop"))?.let { put("stop_sequences", it) }
        }.toString()
    }

    private fun textBlock(text: String): JSONObject =
        JSONObject().apply {
            put("type", "text")
            put("text", text)
        }

    private fun maxTokens(src: JSONObject): Int {
        val explicit = listOf("max_tokens", "max_completion_tokens", "max_output_tokens")
            .firstNotNullOfOrNull { key ->
                src.opt(key)?.takeIf { it != JSONObject.NULL }?.let { (it as? Number)?.toInt() }
            }
        return explicit?.takeIf { it > 0 } ?: DEFAULT_MAX_TOKENS
    }

    /** `stop` в OpenAI — строка или массив; в Anthropic всегда массив. */
    private fun stopSequences(stop: Any?): JSONArray? = when (stop) {
        null, JSONObject.NULL -> null
        is String -> stop.takeIf { it.isNotBlank() }?.let { JSONArray().put(it) }
        is JSONArray -> stop.takeIf { it.length() > 0 }
        else -> null
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

    /** message → chat.completion. Блоки `thinking` клиенту не отдаём. */
    fun translateResponse(messageJson: String, model: String): String {
        val src = runCatching { JSONObject(messageJson) }.getOrNull()
            ?: return errorCompletion(model, "Ответ Anthropic не является JSON")
        src.optJSONObject("error")?.let {
            return errorCompletion(model, it.optString("message").ifBlank { "Anthropic вернул ошибку" })
        }
        return JSONObject().apply {
            put("id", src.optString("id").ifBlank { "chatcmpl-claude" })
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", src.optString("model").ifBlank { model })
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("message", JSONObject().apply {
                    put("role", "assistant")
                    put("content", extractText(src.optJSONArray("content")))
                })
                put("finish_reason", finishReason(src.optString("stop_reason")))
            }))
            src.optJSONObject("usage")?.let { put("usage", translateUsage(it)) }
        }.toString()
    }

    private fun extractText(content: JSONArray?): String {
        if (content == null) return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") != "text") continue
            sb.append(block.optString("text"))
        }
        return sb.toString()
    }

    /** `stop_reason` Anthropic → `finish_reason` OpenAI. */
    private fun finishReason(stopReason: String?): String = when (stopReason) {
        "max_tokens" -> "length"
        "tool_use" -> "tool_calls"
        else -> "stop"
    }

    private fun translateUsage(usage: JSONObject): JSONObject {
        val prompt = inputTokens(usage)
        val completion = usage.optInt("output_tokens", 0)
        return JSONObject().apply {
            put("prompt_tokens", prompt)
            put("completion_tokens", completion)
            put("total_tokens", prompt + completion)
        }
    }

    /**
     * Входные токены: кроме `input_tokens` Anthropic отдельно считает токены,
     * прочитанные из кэша и записанные в него. Игнорировать их — занижать расход.
     */
    private fun inputTokens(usage: JSONObject): Int =
        usage.optInt("input_tokens", 0) +
            usage.optInt("cache_read_input_tokens", 0) +
            usage.optInt("cache_creation_input_tokens", 0)

    private fun errorCompletion(model: String, message: String): String =
        JSONObject().apply {
            put("id", "chatcmpl-claude-error")
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", model)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("message", JSONObject().apply {
                    put("role", "assistant")
                    put("content", message)
                })
                put("finish_reason", "stop")
            }))
        }.toString()

    /**
     * Собрать поток Messages в один `chat.completion` — для клиента, который
     * просил нестримовый ответ, когда наверх ушёл поток.
     */
    fun aggregateSseToCompletion(sse: String, model: String): String {
        val text = StringBuilder()
        var finish = "stop"
        var promptTokens = 0
        var completionTokens = 0
        var errorMessage: String? = null
        var responseModel = model

        sse.lineSequence().forEach { line ->
            if (!line.startsWith("data:")) return@forEach
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") return@forEach
            val ev = runCatching { JSONObject(payload) }.getOrNull() ?: return@forEach
            when (ev.optString("type")) {
                "message_start" -> ev.optJSONObject("message")?.let { msg ->
                    msg.optString("model").takeIf { it.isNotBlank() }?.let { responseModel = it }
                    msg.optJSONObject("usage")?.let { promptTokens = inputTokens(it) }
                }
                "content_block_delta" -> textDelta(ev)?.let { text.append(it) }
                "message_delta" -> {
                    ev.optJSONObject("delta")?.optString("stop_reason")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { finish = finishReason(it) }
                    ev.optJSONObject("usage")?.let { completionTokens = it.optInt("output_tokens", completionTokens) }
                }
                "error" -> errorMessage = ev.optJSONObject("error")?.optString("message")
                    ?.takeIf { it.isNotBlank() } ?: "Anthropic вернул ошибку"
            }
        }

        errorMessage?.let { return errorCompletion(model, it) }

        return JSONObject().apply {
            put("id", "chatcmpl-${java.util.UUID.randomUUID().toString().take(12)}")
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", responseModel)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("message", JSONObject().apply {
                    put("role", "assistant")
                    put("content", text.toString())
                })
                put("finish_reason", finish)
            }))
            put("usage", JSONObject().apply {
                put("prompt_tokens", promptTokens)
                put("completion_tokens", completionTokens)
                put("total_tokens", promptTokens + completionTokens)
            })
        }.toString()
    }

    /** Текст дельты; блоки размышлений (`thinking_delta`) пропускаем. */
    private fun textDelta(ev: JSONObject): String? {
        val delta = ev.optJSONObject("delta") ?: return null
        if (delta.optString("type") != "text_delta") return null
        return delta.optString("text").takeIf { it.isNotEmpty() }
    }

    /**
     * Одно SSE-событие Messages → строки SSE в формате OpenAI chat.
     * Возвращает пустой список для событий, которые клиенту не нужны
     * (`ping`, `content_block_start`, `message_stop`).
     *
     * @param dataJson содержимое строки `data:` события
     */
    fun translateStreamEvent(
        dataJson: String,
        model: String,
        id: String,
        promptTokens: Int = 0,
    ): List<String> {
        val ev = runCatching { JSONObject(dataJson) }.getOrNull() ?: return emptyList()
        return when (ev.optString("type")) {
            "content_block_delta" -> {
                val delta = textDelta(ev) ?: return emptyList()
                listOf(chunk(id, model, JSONObject().apply {
                    put("role", "assistant")
                    put("content", delta)
                }, null))
            }

            "message_delta" -> {
                val finish = finishReason(ev.optJSONObject("delta")?.optString("stop_reason"))
                val out = ev.optJSONObject("usage")?.optInt("output_tokens")
                listOf(chunk(id, model, JSONObject(), finish, out?.let {
                    JSONObject().apply {
                        put("prompt_tokens", promptTokens)
                        put("completion_tokens", it)
                        put("total_tokens", promptTokens + it)
                    }
                }))
            }

            "error" -> {
                val msg = ev.optJSONObject("error")?.optString("message")
                    ?.takeIf { it.isNotBlank() } ?: "Anthropic вернул ошибку"
                listOf(
                    chunk(id, model, JSONObject().apply {
                        put("role", "assistant")
                        put("content", msg)
                    }, null),
                    chunk(id, model, JSONObject(), "stop"),
                )
            }

            else -> emptyList()
        }
    }

    /**
     * Переводчик потока с памятью. Входные токены Anthropic присылает один раз —
     * в `message_start`, а расход уходит клиенту и в учёт в финальном чанке.
     * Без этой памяти prompt_tokens в потоке были бы нулевыми, и стоимость
     * запроса считалась бы только по ответу.
     */
    fun streamTranslator(): (String, String, String) -> List<String> {
        var promptTokens = 0
        return { dataJson, model, id ->
            val ev = runCatching { JSONObject(dataJson) }.getOrNull()
            if (ev?.optString("type") == "message_start") {
                ev.optJSONObject("message")?.optJSONObject("usage")
                    ?.let { promptTokens = inputTokens(it) }
            }
            translateStreamEvent(dataJson, model, id, promptTokens)
        }
    }

    private fun chunk(
        id: String,
        model: String,
        delta: JSONObject,
        finishReason: String?,
        usage: JSONObject? = null,
    ): String = JSONObject().apply {
        put("id", id)
        put("object", "chat.completion.chunk")
        put("created", System.currentTimeMillis() / 1000)
        put("model", model)
        put("choices", JSONArray().put(JSONObject().apply {
            put("index", 0)
            put("delta", delta)
            put("finish_reason", finishReason ?: JSONObject.NULL)
        }))
        usage?.let { put("usage", it) }
    }.toString()

    /**
     * Диагностика отказа: Anthropic сообщает в заголовках, КАКОЕ окно лимита
     * упёрлось (`anthropic-ratelimit-unified-*`). Без этого 429 с пустым
     * сообщением не отличить от запрета клиенту.
     */
    fun logRateLimitHeaders(response: okhttp3.Response) {
        val relevant = response.headers.names()
            .filter { it.startsWith("anthropic-ratelimit", ignoreCase = true) }
            .joinToString(", ") { "$it=${response.header(it)}" }
        if (relevant.isNotEmpty()) {
            android.util.Log.w("ClaudeQuota", "HTTP ${response.code}: $relevant")
        } else {
            android.util.Log.w("ClaudeQuota", "HTTP ${response.code}: заголовков лимита нет")
        }
    }

    /**
     * Заголовки Messages API. Подписка (OAuth) идёт как `Authorization: Bearer`
     * плюс бета-флаг, ключ API — как `x-api-key`. Способы не смешиваются.
     */
    fun applyHeaders(
        builder: okhttp3.Request.Builder,
        token: String?,
        subscription: Boolean,
        stream: Boolean,
        sessionId: String? = null,
    ) {
        token?.takeIf { it.isNotBlank() }?.let {
            if (subscription) {
                builder.header("Authorization", "Bearer $it")
                builder.header(ClaudeCliAuth.BETA_HEADER, ClaudeCliAuth.BETA_OAUTH)
                // Идентичность клиента подписки: без неё крупные модели отвечают
                // отказом, не связанным с квотой (см. ClaudeCliAuth).
                builder.header(ClaudeCliAuth.APP_HEADER, ClaudeCliAuth.APP)
                builder.header("User-Agent", ClaudeCliAuth.USER_AGENT)
                sessionId?.let { id -> builder.header(ClaudeCliAuth.SESSION_HEADER, id) }
            } else {
                builder.header("x-api-key", it)
            }
        }
        builder.header(ClaudeCliAuth.VERSION_HEADER, ClaudeCliAuth.VERSION)
        builder.header("Accept", if (stream) "text/event-stream" else "application/json")
    }
}
