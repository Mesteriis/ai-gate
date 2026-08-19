package com.aigate.router.gateway

import com.aigate.router.auth.CodexHeaders
import com.aigate.router.data.model.Provider
import org.json.JSONArray
import org.json.JSONObject

/**
 * Codex (подписка ChatGPT) говорит НЕ на языке `/v1/chat/completions`, а на
 * Responses API: `POST {base}/responses`. Клиенты шлюза при этом присылают
 * обычный OpenAI-chat, поэтому здесь живёт двусторонний перевод:
 *
 *  запрос:  chat/completions → responses
 *  ответ:   responses → chat.completion
 *  стрим:   события `response.*` → чанки `chat.completion.chunk`
 *
 * Формат сверен с реальным Codex CLI: обязательны заголовки
 * `ChatGPT-Account-ID`, `OpenAI-Beta: responses=experimental` и `originator`,
 * а в теле — `input` (вместо `messages`), `instructions` (вместо системной
 * роли) и `store=false`.
 */
object CodexUpstream {

    const val RESPONSES_PATH = "/responses"

    fun isCodex(provider: Provider): Boolean = provider.type.equals("codex", ignoreCase = true)

    /** Полный URL Responses API для провайдера. */
    fun responsesUrl(provider: Provider): String =
        provider.resolvedBaseUrl.trimEnd('/') + RESPONSES_PATH

    /**
     * chat/completions → responses.
     *
     * Системные сообщения переносятся в `instructions` (в Responses нет роли
     * system), остальные — в `input` с типизированным контентом. Неизвестные
     * поля не тащим: бэкенд отвечает 400 на посторонние ключи.
     */
    fun translateRequest(chatJson: String): String {
        val src = JSONObject(chatJson)
        val messages = src.optJSONArray("messages") ?: JSONArray()

        val instructions = StringBuilder()
        val input = JSONArray()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role", "user")
            val text = contentToText(m.opt("content"))
            if (text.isBlank()) continue
            if (role == "system" || role == "developer") {
                if (instructions.isNotEmpty()) instructions.append("\n\n")
                instructions.append(text)
                continue
            }
            // Роль assistant несёт уже сгенерированный текст — у него свой тип части.
            val partType = if (role == "assistant") "output_text" else "input_text"
            input.put(
                JSONObject().apply {
                    put("role", role)
                    put("content", JSONArray().put(JSONObject().apply {
                        put("type", partType)
                        put("text", text)
                    }))
                }
            )
        }

        return JSONObject().apply {
            put("model", src.optString("model"))
            put("input", input)
            if (instructions.isNotEmpty()) put("instructions", instructions.toString())
            // Бэкенд Codex отвечает 400 «Stream must be set to true» на
            // нестримовый запрос, поэтому наверх всегда идёт поток. Клиенту,
            // который просил обычный ответ, поток собирается обратно в JSON.
            put("stream", true)
            // Не сохранять диалог на стороне провайдера.
            put("store", false)
            // Параметры выборки и лимит ответа не передаются: бэкенд Codex стал
            // отвечать 400 «Unsupported parameter» на temperature и
            // max_output_tokens (проверено на устройстве 2026-08-19), и запрос
            // клиента с безобидным max_tokens умирал целиком. Терять весь ответ
            // из-за необязательного параметра нельзя — он просто опускается.
        }.toString()
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
     * responses → chat.completion. Текст собирается из частей `output_text`;
     * рассуждения (`reasoning`) в ответ клиенту не попадают.
     */
    fun translateResponse(responsesJson: String, model: String): String {
        val src = runCatching { JSONObject(responsesJson) }.getOrNull()
            ?: return errorCompletion(model, "Ответ Codex не является JSON")
        val text = extractOutputText(src)
        val usage = src.optJSONObject("usage")
        val finish = if (src.optString("status") == "incomplete") "length" else "stop"

        return JSONObject().apply {
            put("id", src.optString("id").ifBlank { "chatcmpl-codex" })
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", src.optString("model").ifBlank { model })
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("message", JSONObject().apply {
                    put("role", "assistant")
                    put("content", text)
                })
                put("finish_reason", finish)
            }))
            usage?.let { put("usage", translateUsage(it)) }
        }.toString()
    }

    /** Токены в Responses называются иначе, чем в chat/completions. */
    private fun translateUsage(usage: JSONObject): JSONObject {
        val prompt = usage.optInt("input_tokens", usage.optInt("prompt_tokens", 0))
        val completion = usage.optInt("output_tokens", usage.optInt("completion_tokens", 0))
        val total = usage.optInt("total_tokens", prompt + completion)
        return JSONObject().apply {
            put("prompt_tokens", prompt)
            put("completion_tokens", completion)
            put("total_tokens", total)
        }
    }

    private fun extractOutputText(src: JSONObject): String {
        src.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        val output = src.optJSONArray("output") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") == "reasoning") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") != "output_text") continue
                sb.append(part.optString("text"))
            }
        }
        return sb.toString()
    }

    private fun errorCompletion(model: String, message: String): String =
        JSONObject().apply {
            put("id", "chatcmpl-codex-error")
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
     * Собрать поток Responses в один `chat.completion` — для клиентов,
     * которые просили нестримовый ответ.
     */
    fun aggregateSseToCompletion(sse: String, model: String): String {
        val text = StringBuilder()
        var finish = "stop"
        var usage: JSONObject? = null
        var errorMessage: String? = null

        sse.lineSequence().forEach { line ->
            if (!line.startsWith("data:")) return@forEach
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") return@forEach
            val ev = runCatching { JSONObject(payload) }.getOrNull() ?: return@forEach
            when (ev.optString("type")) {
                "response.output_text.delta" -> text.append(ev.optString("delta"))
                "response.completed" -> {
                    usage = ev.optJSONObject("response")?.optJSONObject("usage")
                    // Полный текст в финальном событии надёжнее склейки дельт.
                    ev.optJSONObject("response")?.let { r ->
                        val full = extractOutputText(r)
                        if (full.isNotBlank()) { text.setLength(0); text.append(full) }
                    }
                }
                "response.incomplete" -> {
                    finish = "length"
                    usage = ev.optJSONObject("response")?.optJSONObject("usage")
                }
                "response.failed", "error" -> {
                    errorMessage = ev.optJSONObject("response")?.optJSONObject("error")?.optString("message")
                        ?: ev.optString("message").ifBlank { "Codex вернул ошибку" }
                }
            }
        }

        errorMessage?.let { return errorCompletion(model, it) }

        return JSONObject().apply {
            put("id", "chatcmpl-${java.util.UUID.randomUUID().toString().take(12)}")
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", model)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("message", JSONObject().apply {
                    put("role", "assistant")
                    put("content", text.toString())
                })
                put("finish_reason", finish)
            }))
            usage?.let { put("usage", translateUsage(it)) }
        }.toString()
    }

    /**
     * Одно SSE-событие Responses → строки SSE в формате OpenAI chat.
     * Возвращает пустой список для событий, которые клиенту не нужны.
     *
     * @param dataJson содержимое строки `data:` события
     */
    fun translateStreamEvent(dataJson: String, model: String, id: String): List<String> {
        val ev = runCatching { JSONObject(dataJson) }.getOrNull() ?: return emptyList()
        return when (ev.optString("type")) {
            "response.output_text.delta" -> {
                val delta = ev.optString("delta")
                if (delta.isEmpty()) emptyList()
                else listOf(chunk(id, model, JSONObject().apply {
                    put("role", "assistant")
                    put("content", delta)
                }, null))
            }

            "response.completed", "response.incomplete" -> {
                val finish = if (ev.optString("type") == "response.incomplete") "length" else "stop"
                val usage = ev.optJSONObject("response")?.optJSONObject("usage")
                listOf(chunk(id, model, JSONObject(), finish, usage?.let { translateUsage(it) }))
            }

            "response.failed", "error" -> {
                val msg = ev.optJSONObject("response")?.optJSONObject("error")?.optString("message")
                    ?: ev.optString("message").ifBlank { "Codex вернул ошибку" }
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

    /** Заголовки, без которых бэкенд Codex отвечает 4xx. */
    fun applyHeaders(
        builder: okhttp3.Request.Builder,
        token: String?,
        accountId: String?,
        sessionId: String,
    ) {
        token?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        accountId?.takeIf { it.isNotBlank() }?.let { builder.header(CodexHeaders.ACCOUNT_ID, it) }
        builder.header(CodexHeaders.OPENAI_BETA, CodexHeaders.OPENAI_BETA_RESPONSES)
        builder.header("originator", CodexHeaders.ORIGINATOR)
        builder.header("User-Agent", CodexHeaders.USER_AGENT)
        builder.header(CodexHeaders.SESSION_ID, sessionId)
        builder.header("Accept", "text/event-stream")
    }
}
