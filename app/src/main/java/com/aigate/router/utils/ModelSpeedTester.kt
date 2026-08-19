package com.aigate.router.utils

import android.util.Log
import com.aigate.router.data.model.SpeedMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 模型测速器 — 三指标精准采集
 * TTFT: Time To First Token（首字延迟）
 * TPS: Tokens Per Second
 * totalMs: 总耗时
 * 兼容 SSE 流式 + 非 SSE 完整 JSON 响应
 */
class ModelSpeedTester(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // Жёсткий потолок на весь вызов. withTimeoutOrNull не прерывает
        // блокирующий execute(), а readTimeout молчит, пока сервер хоть что-то
        // шлёт — без callTimeout один зависший апстрим вешал обход замеров
        // навсегда (наблюдалось на устройстве).
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
) {
    suspend fun measure(
        modelId: String,
        baseUrl: String,
        apiKey: String?,
        chatPath: String? = null,
        prompt: String = DEFAULT_PROMPT,
        /** Провайдер отвечает по Responses API (Codex), а не chat/completions. */
        useResponsesApi: Boolean = false,
        /** Идентификатор аккаунта ChatGPT — обязателен для Codex. */
        accountId: String? = null,
        /** Провайдер отвечает по Messages API (Anthropic), а не chat/completions. */
        useMessagesApi: Boolean = false,
        /** Подписка Claude: заголовки и системный блок идентичности клиента (см. ClaudeCliAuth). */
        claudeSubscription: Boolean = false
    ): SpeedMetrics = withContext(Dispatchers.IO) {
        val path = when {
            useResponsesApi -> com.aigate.router.gateway.CodexUpstream.RESPONSES_PATH
            useMessagesApi && chatPath == null -> com.aigate.router.gateway.AnthropicUpstream.MESSAGES_PATH
            chatPath != null -> if (chatPath.startsWith("/")) chatPath else "/$chatPath"
            else -> "/v1/chat/completions"
        }
        val url = baseUrl.trimEnd('/') + path
        val chatPayload = buildPayload(modelId, prompt)
        val body = when {
            useResponsesApi -> com.aigate.router.gateway.CodexUpstream.translateRequest(chatPayload)
            useMessagesApi -> com.aigate.router.gateway.AnthropicUpstream.translateRequest(
                chatPayload,
                systemPrefix = if (claudeSubscription) com.aigate.router.auth.ClaudeCliAuth.IDENTITY_PROMPT else null,
            )
            else -> chatPayload
        }
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_TYPE))
            .apply {
                when {
                    useResponsesApi -> com.aigate.router.gateway.CodexUpstream.applyHeaders(
                        this, apiKey, accountId, java.util.UUID.randomUUID().toString()
                    )
                    useMessagesApi -> com.aigate.router.gateway.AnthropicUpstream.applyHeaders(
                        this, apiKey, claudeSubscription, stream = true,
                        sessionId = java.util.UUID.randomUUID().toString(),
                    )
                    else -> if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey")
                }
            }
            .build()
        // События Messages/Responses переводятся в чанки OpenAI, дальше разбор общий.
        val anthropicTranslate =
            if (useMessagesApi) com.aigate.router.gateway.AnthropicUpstream.streamTranslator() else null
        val translate: ((String) -> List<String>)? = when {
            anthropicTranslate != null -> { data -> anthropicTranslate(data, modelId, SPEED_CHUNK_ID) }
            useResponsesApi -> { data ->
                com.aigate.router.gateway.CodexUpstream.translateStreamEvent(data, modelId, SPEED_CHUNK_ID)
            }
            else -> null
        }

        // Начало замера в логе: если модель зависнет, по последней строке видно кто.
        Log.i(TAG, "Замер $modelId → $url")
        val t0 = System.currentTimeMillis()
        var tFirst: Long? = null
        var tEnd: Long = 0L
        var tokenCount = 0
        var firstContent = ""

        try {
            // ★ 总超时 30 秒
            val result = withTimeoutOrNull(30000L) {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val errBody = resp.body?.string()?.take(200) ?: "unknown"
                        Log.w(TAG, "Замер скорости не удался HTTP ${resp.code}: $errBody")
                        return@use SpeedMetrics(
                            ttftMs = -1, tps = 0.0, totalMs = -1, tokenCount = 0, measuredAt = System.currentTimeMillis()
                        )
                    }

                    // ★ 检测响应格式：SSE 还是完整 JSON
                    val contentType = resp.header("Content-Type", "") ?: ""
                    // Бэкенд Codex всегда стримит, но Content-Type у него не
                    // text/event-stream — без поправки поток уходил в JSON-ветку.
                    val isSSE = "text/event-stream" in contentType || useResponsesApi

                    if (isSSE) {
                        // ★ SSE 流式解析
                        val source = resp.body!!.source()
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: continue
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data == "[DONE]" || data == "{\"done\":true}") break

                            val delta = (
                                if (translate != null) translate(data).firstNotNullOfOrNull(::parseDelta)
                                else parseDelta(data)
                                ) ?: continue
                            if (delta.isNotEmpty()) {
                                if (tFirst == null) {
                                    tFirst = System.currentTimeMillis()
                                    firstContent = delta
                                }
                                tokenCount += estimateTokens(delta)
                                tEnd = System.currentTimeMillis()
                            }
                        }
                        if (tEnd == 0L) tEnd = System.currentTimeMillis()
                    } else {
                        // ★ 非 SSE 格式：完整 JSON 响应
                        // Полный ответ Messages/Responses приводится к chat/completions,
                        // разбор общий.
                        val rawBody = resp.body!!.string()
                        val fullBody = when {
                            useMessagesApi -> runCatching {
                                com.aigate.router.gateway.AnthropicUpstream.translateResponse(rawBody, modelId)
                            }.getOrDefault(rawBody)
                            useResponsesApi -> runCatching {
                                com.aigate.router.gateway.CodexUpstream.translateResponse(rawBody, modelId)
                            }.getOrDefault(rawBody)
                            else -> rawBody
                        }
                        val jsonObj = try { JSONObject(fullBody) } catch (_: Exception) { null }
                        val message = jsonObj?.optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("message")
                        val content = message?.optString("content", "") ?: ""

                        if (content.isNotEmpty()) {
                            tFirst = System.currentTimeMillis()
                            tEnd = tFirst!!
                            tokenCount = estimateTokens(content)
                            firstContent = content
                        } else {
                            Log.w(TAG, "Замер скорости $modelId: не удалось разобрать формат ответа: ${fullBody.take(200)}")
                            return@use SpeedMetrics(
                                ttftMs = -1, tps = 0.0, totalMs = -1, tokenCount = 0, measuredAt = System.currentTimeMillis()
                            )
                        }
                    }
                }

                SpeedMetrics(
                    ttftMs = if (tFirst != null) (tFirst!! - t0) else -1,
                    tps = if (tEnd > 0 && tokenCount > 0) {
                        val decodeMs = if (tFirst != null && tEnd > tFirst!!) (tEnd - tFirst!!).toDouble() else 0.0
                        if (decodeMs > 0) tokenCount / (decodeMs / 1000.0) else 0.0
                    } else 0.0,
                    totalMs = if (tEnd > 0) tEnd - t0 else -1,
                    tokenCount = tokenCount,
                    measuredAt = System.currentTimeMillis()
                )
            }

            // ★ 超时处理
            if (result == null) {
                Log.w(TAG, "Таймаут замера скорости(30s): $modelId")
                return@withContext SpeedMetrics(
                    ttftMs = -1, tps = 0.0, totalMs = -1, tokenCount = 0, measuredAt = System.currentTimeMillis()
                )
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Сбой замера скорости: ${e.message}")
            SpeedMetrics(
                ttftMs = -1, tps = 0.0, totalMs = -1, tokenCount = 0, measuredAt = System.currentTimeMillis()
            )
        }
    }

    private fun parseDelta(jsonStr: String): String? = try {
        val obj = JSONObject(jsonStr)
        val choices = obj.optJSONArray("choices") ?: return null
        val choice = choices.optJSONObject(0) ?: return null
        val delta = choice.optJSONObject("delta")
        delta?.optString("content", null)
    } catch (_: Exception) { null }

    private fun estimateTokens(text: String): Int {
        val chinese = text.count { it in '\u4e00'..'\u9fa5' }
        val other = text.length - chinese
        return (chinese * 0.65 + other / 4.0).toInt().coerceAtLeast(1)
    }

    private fun buildPayload(modelId: String, prompt: String): String = JSONObject().apply {
        put("model", modelId)
        put("stream", true)
        put("max_tokens", 200)
        // Температуру не передаём: Responses API Codex отвечает на неё 400
        // «Unsupported parameter», новейшие модели Claude — 400 «deprecated»,
        // а на скорость она не влияет.
        put("messages", org.json.JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        })
    }.toString()

    companion object {
        private const val TAG = "ModelSpeedTester"
        /** Идентификатор чанков переводчика потока — в метрики он не попадает. */
        private const val SPEED_CHUNK_ID = "speed-test"
        const val DEFAULT_PROMPT = "Расскажите о себе одним предложением."
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}