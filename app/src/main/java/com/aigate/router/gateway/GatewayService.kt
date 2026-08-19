package com.aigate.router.gateway

import com.aigate.router.gateway.GatewayScheduler
import com.aigate.router.gateway.RoutingRuleManager

import com.aigate.router.data.db.AppDatabase
import com.aigate.router.data.model.AiModel
import com.aigate.router.data.model.ModelRouteKey
import com.aigate.router.data.model.findByRouteKey
import com.aigate.router.data.model.orderedByRouteKeys
import com.aigate.router.data.model.routeKey
import com.aigate.router.data.model.Provider
import com.aigate.router.data.model.TokenUsage
import com.aigate.router.network.UpstreamClient
import com.aigate.router.service.GatewayForegroundService
import com.aigate.router.service.KeyManager
import com.aigate.router.service.ApiKeyEntry
import com.aigate.router.service.LiveSession
import io.ktor.server.websocket.*
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.close
import io.ktor.server.application.install
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.ktor.utils.io.writeFully
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.SocketTimeoutException
import java.net.ConnectException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import com.aigate.router.GatewayApplication
import com.aigate.router.data.credential.CredentialStore
import com.aigate.router.data.db.AutoBackupWorker

/**
 * 本地 AI 网关服务（Ktor Server）
 * 运行在手机本地，转发 AI 请求到上游服务商
 * v2.0 — 通用代理模式：支持任意 POST/GET 路径（图片/视频/音频/聊天），流式管道直通
 * v2.1 — 极限吞吐：超长超时 + 大缓冲区 + 无限制body大小
 */
class GatewayService(private val database: AppDatabase) {

    private var server: EmbeddedServer<*, *>? = null
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /**
     * 启动网关服务器
     * @param port 监听端口，默认 8889
     */
    fun start(port: Int = 8889) {
        if (server != null) return
        // Тихий приёмник держал порт, пока шлюз стоял — освобождаем его.
        QuietListener.stop()
        GatewayForegroundService.blockedAttempts.set(0)

        // ★★★ 端口占用提前检测：启动前确认端口可用，避免 BindException 崩溃 ★★★
        if (!isPortAvailable(port)) {
            GatewayForegroundService.addDebugLog("⚠️ Порт $port уже занят, пробую резервный порт…")
            // 检测到端口占用：尝试备选端口（8889+1 起，最多试20个）
            var altPort = port + 1
            var found = -1
            var attempts = 0
            while (attempts < 20) {
                if (isPortAvailable(altPort)) { found = altPort; break }
                altPort++
                attempts++
            }
            if (found > 0) {
                // ★★ 自动切换备选端口并持久化，避免下次再冲突 ★★
                GatewayForegroundService.addDebugLog("✅ Порт $port занят, автоматически переключился на резервный порт $found")
                GatewayForegroundService.saveGatewayPort(found)
                startWithPort(found)
            } else {
                GatewayForegroundService.addDebugLog("❌ Порт $port и резервные порты заняты, измените порт шлюза в разделе управления данными и повторите")
                GatewayForegroundService.addDebugLog("❌ Порт занят: $port используется другим процессом, шлюз не запущен")
                return
            }
            return
        }

        startWithPort(port)
    }

    /**
     * 检测指定端口是否可用（未被占用）
     */
    private fun isPortAvailable(port: Int): Boolean {
        return try {
            java.net.ServerSocket().use { ss ->
                ss.reuseAddress = true
                ss.bind(java.net.InetSocketAddress(port))
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 使用指定端口实际启动网关服务
     */
    private fun startWithPort(port: Int) {
        // ★★ 启动会话清理协程（闲置超时自动断开）★★
        startSessionCleanup()

        // ★★ 服务启动后自动测速 + 定时备份调度 ★★
        sessionCleanupScope.launch {
            delay(2000) // 等2秒，让服务完全启动
            // 任务1：自动启动测速（如果启用了自动故障转移）
            try {
                if (GatewayForegroundService.getAutoFailover()) {
                    GatewayForegroundService.addDebugLog("⚡ Автозапуск замера скорости…")
                    GatewayScheduler.refreshHealthCache(database)
                    GatewayForegroundService.addDebugLog("✅ Замер скорости завершён")
                }
            } catch (e: Exception) {
                GatewayForegroundService.addDebugLog("⚠️ Ошибка замера скорости: ${e.message}")
            }
            // 任务2：检查并调度定时备份
            try {
                val context = GatewayApplication.getInstance()
                if (GatewayForegroundService.getGatewayConfig("auto_backup_enabled", "false").toBoolean()) {
                    val hour = GatewayForegroundService.getGatewayConfig("auto_backup_hour", "3").toIntOrNull() ?: 3
                    val minute = GatewayForegroundService.getGatewayConfig("auto_backup_minute", "0").toIntOrNull() ?: 0
                    AutoBackupWorker.schedule(context, hour, minute)
                    GatewayForegroundService.addDebugLog("✅ Резервное копирование по расписанию: ежедневно $hour:${minute.toString().padStart(2, '0')}")
                }
            } catch (e: Exception) {
                GatewayForegroundService.addDebugLog("⚠️ Ошибка планирования резервного копирования: ${e.message}")
            }
        }

        // ★★ Bind: loopback-only по умолчанию; 0.0.0.0 только в явном LAN-режиме ★★
        val bindHost = if (GatewayForegroundService.getLanModeEnabled()) "0.0.0.0" else "127.0.0.1"
        GatewayForegroundService.addDebugLog(
            if (bindHost == "0.0.0.0") "🌐 LAN-режим: слушаю 0.0.0.0:$port (нужен токен)"
            else "🔒 Loopback: слушаю 127.0.0.1:$port"
        )
        val embedded = embeddedServer(CIO, host = bindHost, port = port) {
            // ★★ 安装 WebSocket 支持 ★★★
            install(io.ktor.server.websocket.WebSockets)
            routing {
                // ★★★ CORS 预检请求处理 ★★★
                options("/{path...}") {
                    call.response.headers.append("Access-Control-Allow-Origin", "*")
                    call.response.headers.append("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
                    call.response.headers.append("Access-Control-Allow-Headers", "Content-Type, Authorization, x-api-key, anthropic-version, x-goog-api-key")
                    call.response.headers.append("Access-Control-Max-Age", "86400")
                    call.respondText("", ContentType.Application.Json, HttpStatusCode.OK)
                }

                // 健康检查（不需要验证）
                get("/health") {
                    val running = GatewayForegroundService.isServiceRunning
                    val port = GatewayForegroundService.getGatewayPort()
                    val healthJson = buildJsonObject {
                        put("status", JsonPrimitive("ok"))
                        put("service", JsonPrimitive("aigate"))
                        put("version", JsonPrimitive("0.1.0"))
                        put("running", JsonPrimitive(running))
                        put("port", JsonPrimitive(port))
                        put("models_count", JsonPrimitive(database.aiModelDao().getEnabledModelsList().size))
                        put("uptime_seconds", JsonPrimitive((System.currentTimeMillis() - startTime) / 1000))
                    }
                    call.respondText(healthJson.toString(), ContentType.Application.Json.withCharset(Charsets.UTF_8))
                }

                // 获取模型列表 (OpenAI Compatible)
                get("/v1/models") {
                    corsResponse(call)
                    if (!validateApiKey(call)) {
                        val (s, b) = openAIError(HttpStatusCode.Unauthorized, "Invalid or missing API key", "invalid_api_key")
                        call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                        return@get
                    }
                    try {
                        val models = database.aiModelDao().getEnabledModelsList()
                        val modelList = models.map { model ->
                            val displayName = if (model.customAlias.isNotBlank()) model.customAlias else model.displayName
                            buildJsonObject {
                                put("id", JsonPrimitive(model.modelId))
                                put("object", JsonPrimitive("model"))
                                put("owned_by", JsonPrimitive("custom"))
                                put("model_id", JsonPrimitive(model.modelId))
                                put("display_name", JsonPrimitive(displayName))
                                put("custom_alias", JsonPrimitive(model.customAlias))
                            }
                        }
                        // ★★ 加入 auto 虚拟模型（第三方APP也能选）★★
                        val finalList = modelList + buildJsonObject {
                            put("id", JsonPrimitive(VirtualModel.ID))
                            put("object", JsonPrimitive("model"))
                            put("owned_by", JsonPrimitive("aigate"))
                            put("model_id", JsonPrimitive(VirtualModel.ID))
                            put("display_name", JsonPrimitive("🔄 Автопереключение"))
                            put("custom_alias", JsonPrimitive(""))
                        }
                        val response = buildJsonObject {
                            put("object", JsonPrimitive("list"))
                            put("data", JsonArray(finalList))
                        }
                        call.respondText(
                            contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8),
                            text = response.toString()
                        )
                    } catch (e: Exception) {
                        val (status, body) = openAIError(HttpStatusCode.InternalServerError, "Failed to fetch models: ${e.message}", "server_error")
                        call.respondText(contentType = ContentType.Application.Json, status = status, text = body)
                    }
                }

                // ★★★ GET /v1/chat/completions 返回标准错误（浏览器测试用）★★★
                get("/v1/chat/completions") {
                    corsResponse(call)
                    val (s, b) = openAIError(HttpStatusCode.BadRequest, "This endpoint requires a POST request. Use POST with a JSON body containing 'model' and 'messages'.", "invalid_request_error", 400)
                    call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                }

                // ★★★ 兼容不带 /v1 前缀的路径 ★★★
                post("/chat/completions") {
                    corsResponse(call)
                    if (!validateApiKey(call)) {
                        val (s, b) = openAIError(HttpStatusCode.Unauthorized, "Invalid or missing API key", "invalid_api_key")
                        call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                        return@post
                    }
                    proxyRequest(call, database)
                }

                // ★★★ 新增接口：文本补全（OpenAI Completions 格式）★★★
                post("/v1/completions") {
                    if (!validateApiKey(call)) {
                        val (s, b) = openAIError(HttpStatusCode.Unauthorized, "Invalid or missing API key", "invalid_api_key")
                        call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                        return@post
                    }
                    try {
                        val rawBytes = call.receive<ByteArray>()
                        GatewayForegroundService.trafficUploadBytes.addAndGet(rawBytes.size.toLong())
                        GatewayForegroundService.totalUploadBytes.addAndGet(rawBytes.size.toLong())
                        val body = proxyJson.parseToJsonElement(String(rawBytes, Charsets.UTF_8)).jsonObject
                        var modelId = body["model"]?.jsonPrimitive?.content ?: throw Exception("model is required")
                        // ★★ auto 支持：自动解析为当前活跃的真实模型 ★★
                        val realModelId = if (VirtualModel.isVirtual(modelId)) {
                            val active = GatewayForegroundService.activeNodeName
                            if (active.isNotBlank()) active else modelId
                        } else modelId
                        modelId = realModelId
                        val prompt = body["prompt"] ?: throw Exception("prompt is required")
                        val stream = body["stream"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                        val promptText = try { prompt.jsonPrimitive.content } catch (_: Exception) { prompt.jsonArray.joinToString("\n") { it.jsonPrimitive.content } }
                        // 转换为 chat 格式后转发
                        val chatBody = buildJsonObject {
                            put("model", JsonPrimitive(modelId))
                            put("messages", JsonArray(listOf(buildJsonObject {
                                put("role", JsonPrimitive("user"))
                                put("content", JsonPrimitive(promptText))
                            })))
                            put("stream", JsonPrimitive(stream))
                            body["max_tokens"]?.let { put("max_tokens", it) }
                            body["temperature"]?.let { put("temperature", it) }
                            body["top_p"]?.let { put("top_p", it) }
                            body["stop"]?.let { put("stop", it) }
                            body["suffix"]?.let { put("suffix", it) }
                            body["n"]?.let { put("n", it) }
                        }
                        // 获取模型对应的服务商，转发
                        val models = com.aigate.router.routing.ModelPreference.sortStored(
                            database.aiModelDao().getEnabledModelsList()
                        )
                        val targetModel = models.find { it.modelId == modelId } ?: models.firstOrNull()
                        if (targetModel == null) { call.respondText(openAIError(HttpStatusCode.NotFound, "Model $modelId not found").second, ContentType.Application.Json, status = HttpStatusCode.NotFound); return@post }
                        val provider = database.providerDao().getProviderById(targetModel.providerId)
                        if (provider == null || !provider.isEnabled) { call.respondText(openAIError(HttpStatusCode.NotFound, "Provider for $modelId not available").second, ContentType.Application.Json, status = HttpStatusCode.NotFound); return@post }
                        val upstreamUrl = provider.resolvedBaseUrl.trimEnd('/')
                        val upstreamBody = sanitizeRequestBody(chatBody.toString())
                        val client = UpstreamClient.getClientForModel(targetModel.useProxy)
                        val req = okhttp3.Request.Builder().url("$upstreamUrl" + (provider.chatPath?.let { if (it.startsWith("/")) it else "/$it" } ?: "/v1/chat/completions"))
                            .post(upstreamBody.toByteArray(Charsets.UTF_8).toRequestBody(DEFAULT_CT))
                            .apply { val k = CredentialStore.apiKeyForProvider(provider); if (!k.isNullOrBlank()) header("Authorization", "Bearer $k") }
                            .build()
                        val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                        val respBody = resp.body?.string() ?: "{}"
                        resp.close()
                        GatewayForegroundService.totalDownloadBytes.addAndGet(respBody.length.toLong())
                        GatewayForegroundService.trafficDownloadBytes.addAndGet(respBody.length.toLong())
                        // 将 chat 响应转回 completions 格式
                        val chatResp = try { proxyJson.parseToJsonElement(respBody).jsonObject } catch (_: Exception) { null }
                        val text = try { chatResp?.get("choices")?.jsonArray?.get(0)?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content ?: "" } catch (_: Exception) { "" }
                        val completionsResp = buildJsonObject {
                            put("id", JsonPrimitive("cmpl-${UUID.randomUUID().toString().take(8)}"))
                            put("object", JsonPrimitive("text_completion"))
                            put("created", JsonPrimitive(System.currentTimeMillis() / 1000))
                            put("model", JsonPrimitive(modelId))
                            put("choices", JsonArray(listOf(buildJsonObject {
                                put("text", JsonPrimitive(text))
                                put("index", JsonPrimitive(0))
                                put("finish_reason", JsonPrimitive(chatResp?.get("choices")?.jsonArray?.get(0)?.jsonObject?.get("finish_reason")?.jsonPrimitive?.content ?: "stop"))
                            })))
                            chatResp?.get("usage")?.let { put("usage", it) }
                        }
                        call.respondText(completionsResp.toString(), ContentType.Application.Json.withCharset(Charsets.UTF_8))
                        logAccess(call, modelId, 200, System.currentTimeMillis() - (try { body["created"]?.jsonPrimitive?.content?.toLongOrNull() ?: System.currentTimeMillis() } catch (_: Exception) { System.currentTimeMillis() }))
                    } catch (e: Exception) {
                        val (s, b) = openAIError(HttpStatusCode.InternalServerError, e.message ?: "Completions failed", "server_error")
                        call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                    }
                }

                // ★★★ 新增接口：Claude 消息格式（POST /v1/messages）★★★
                post("/v1/messages") {
                    if (!validateApiKey(call)) {
                        val (s, b) = openAIError(HttpStatusCode.Unauthorized, "Invalid or missing API key", "invalid_api_key")
                        call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                        return@post
                    }
                    try {
                        val rawBytes = call.receive<ByteArray>()
                        GatewayForegroundService.trafficUploadBytes.addAndGet(rawBytes.size.toLong())
                        GatewayForegroundService.totalUploadBytes.addAndGet(rawBytes.size.toLong())
                        val body = proxyJson.parseToJsonElement(String(rawBytes, Charsets.UTF_8)).jsonObject
                        var modelId = body["model"]?.jsonPrimitive?.content ?: throw Exception("model is required")
                        // ★★ auto 支持：自动解析为当前活跃的真实模型 ★★
                        if (VirtualModel.isVirtual(modelId)) {
                            val active = GatewayForegroundService.activeNodeName
                            if (active.isNotBlank()) modelId = active
                        }
                        val claudeMsgs = body["messages"]?.jsonArray ?: throw Exception("messages is required")
                        val stream = body["stream"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                        val maxTokens = body["max_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1024
                        val systemPrompt = body["system"]?.jsonPrimitive?.content ?: ""
                        // 转换 Claude 消息为 OpenAI 格式
                        val openaiMsgs = mutableListOf<JsonObject>()
                        if (systemPrompt.isNotBlank()) {
                            openaiMsgs.add(buildJsonObject { put("role", JsonPrimitive("system")); put("content", JsonPrimitive(systemPrompt)) })
                        }
                        for (msg in claudeMsgs) {
                            val obj = msg.jsonObject
                            val role = obj["role"]?.jsonPrimitive?.content ?: "user"
                            val content = obj["content"]
                            if (content != null) {
                                // Claude content 可以是 string 或 array
                                val text = try { content.jsonPrimitive.content } catch (_: Exception) {
                                    content.jsonArray.mapNotNull { part ->
                                        try { val p = part.jsonObject; if (p["type"]?.jsonPrimitive?.content == "text") p["text"]?.jsonPrimitive?.content else null } catch (_: Exception) { null }
                                    }.joinToString("\n")
                                }
                                openaiMsgs.add(buildJsonObject { put("role", JsonPrimitive(if (role == "assistant") "assistant" else "user")); put("content", JsonPrimitive(text)) })
                            }
                        }
                        // 构造 OpenAI chat 请求
                        val chatBody = buildJsonObject {
                            put("model", JsonPrimitive(modelId))
                            put("messages", JsonArray(openaiMsgs))
                            put("max_tokens", JsonPrimitive(maxTokens))
                            put("stream", JsonPrimitive(stream))
                            body["temperature"]?.let { put("temperature", it) }
                            body["top_p"]?.let { put("top_p", it) }
                            body["stop_sequences"]?.let { put("stop", it) }
                            body["tools"]?.let { put("tools", it) }
                            body["tool_choice"]?.let { put("tool_choice", it) }
                        }
                        // 查找模型并转发
                        val models = com.aigate.router.routing.ModelPreference.sortStored(
                            database.aiModelDao().getEnabledModelsList()
                        )
                        val targetModel = models.find { it.modelId == modelId } ?: models.firstOrNull()
                        if (targetModel == null) { call.respondText(openAIError(HttpStatusCode.NotFound, "Model $modelId not found").second, ContentType.Application.Json, status = HttpStatusCode.NotFound); return@post }
                        val provider = database.providerDao().getProviderById(targetModel.providerId)
                        if (provider == null || !provider.isEnabled) { call.respondText(openAIError(HttpStatusCode.NotFound, "Provider for $modelId not available").second, ContentType.Application.Json, status = HttpStatusCode.NotFound); return@post }
                        val upstreamUrl = provider.resolvedBaseUrl.trimEnd('/')
                        val upstreamBody = sanitizeRequestBody(chatBody.toString())
                        val client = UpstreamClient.getClientForModel(targetModel.useProxy)
                        val req = okhttp3.Request.Builder().url("$upstreamUrl" + (provider.chatPath?.let { if (it.startsWith("/")) it else "/$it" } ?: "/v1/chat/completions"))
                            .post(upstreamBody.toByteArray(Charsets.UTF_8).toRequestBody(DEFAULT_CT))
                            .apply { val k = CredentialStore.apiKeyForProvider(provider); if (!k.isNullOrBlank()) header("Authorization", "Bearer $k") }
                            .build()
                        val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                        val respBody = resp.body?.string() ?: "{}"
                        resp.close()
                        GatewayForegroundService.totalDownloadBytes.addAndGet(respBody.length.toLong())
                        GatewayForegroundService.trafficDownloadBytes.addAndGet(respBody.length.toLong())
                        // 将 OpenAI chat 响应转换为 Claude 格式
                        val chatResp = try { proxyJson.parseToJsonElement(respBody).jsonObject } catch (_: Exception) { null }
                        val contentText = try { chatResp?.get("choices")?.jsonArray?.get(0)?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content ?: "" } catch (_: Exception) { "" }
                        val stopReason = try { chatResp?.get("choices")?.jsonArray?.get(0)?.jsonObject?.get("finish_reason")?.jsonPrimitive?.content?.let { if (it == "stop") "end_turn" else it } ?: "end_turn" } catch (_: Exception) { "end_turn" }
                        val promptTokens = try { chatResp?.get("usage")?.jsonObject?.get("prompt_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0 } catch (_: Exception) { 0 }
                        val completionTokens = try { chatResp?.get("usage")?.jsonObject?.get("completion_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0 } catch (_: Exception) { 0 }
                        val claudeResp = buildJsonObject {
                            put("id", JsonPrimitive("msg_${UUID.randomUUID().toString().take(8)}"))
                            put("type", JsonPrimitive("message"))
                            put("role", JsonPrimitive("assistant"))
                            put("content", JsonArray(listOf(buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(contentText))
                            })))
                            put("model", JsonPrimitive(modelId))
                            put("stop_reason", JsonPrimitive(stopReason))
                            put("usage", buildJsonObject {
                                put("input_tokens", JsonPrimitive(promptTokens))
                                put("output_tokens", JsonPrimitive(completionTokens))
                            })
                        }
                        call.respondText(claudeResp.toString(), ContentType.Application.Json.withCharset(Charsets.UTF_8))
                        logAccess(call, modelId, 200, System.currentTimeMillis() - (try { body["created"]?.jsonPrimitive?.content?.toLongOrNull() ?: System.currentTimeMillis() } catch (_: Exception) { System.currentTimeMillis() }))
                    } catch (e: Exception) {
                        val (s, b) = openAIError(HttpStatusCode.InternalServerError, e.message ?: "Messages failed", "server_error")
                        call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                    }
                }

                // ★★★ 新增接口：嵌入向量（POST /v1/embeddings）★★★
                post("/v1/embeddings") {
                    if (!validateApiKey(call)) {
                        val (s, b) = openAIError(HttpStatusCode.Unauthorized, "Invalid or missing API key", "invalid_api_key")
                        call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                        return@post
                    }
                    try {
                        val rawBytes = call.receive<ByteArray>()
                        GatewayForegroundService.trafficUploadBytes.addAndGet(rawBytes.size.toLong())
                        GatewayForegroundService.totalUploadBytes.addAndGet(rawBytes.size.toLong())
                        val body = proxyJson.parseToJsonElement(String(rawBytes, Charsets.UTF_8)).jsonObject
                        var modelId = body["model"]?.jsonPrimitive?.content ?: throw Exception("model is required")
                        // ★★ auto 支持：自动解析为当前活跃的真实模型 ★★
                        if (VirtualModel.isVirtual(modelId)) {
                            val active = GatewayForegroundService.activeNodeName
                            if (active.isNotBlank()) modelId = active
                        }
                        val input = body["input"] ?: throw Exception("input is required")
                        // 查找模型和提供商
                        val models = com.aigate.router.routing.ModelPreference.sortStored(
                            database.aiModelDao().getEnabledModelsList()
                        )
                        val targetModel = models.find { it.modelId == modelId } ?: models.firstOrNull()
                        if (targetModel == null) { call.respondText(openAIError(HttpStatusCode.NotFound, "Model $modelId not found").second, ContentType.Application.Json, status = HttpStatusCode.NotFound); return@post }
                        val provider = database.providerDao().getProviderById(targetModel.providerId)
                        if (provider == null || !provider.isEnabled) { call.respondText(openAIError(HttpStatusCode.NotFound, "Provider for $modelId not available").second, ContentType.Application.Json, status = HttpStatusCode.NotFound); return@post }
                        val upstreamUrl = provider.resolvedBaseUrl.trimEnd('/')
                        // 保留原始 body 但确保 model 正确
                        val upstreamBody = sanitizeRequestBody(rawBytes.decodeToString())
                        val client = UpstreamClient.getClientForModel(targetModel.useProxy)
                        val req = okhttp3.Request.Builder().url("$upstreamUrl/v1/embeddings")
                            .post(upstreamBody.toByteArray(Charsets.UTF_8).toRequestBody(DEFAULT_CT))
                            .apply { val k = CredentialStore.apiKeyForProvider(provider); if (!k.isNullOrBlank()) header("Authorization", "Bearer $k") }
                            .build()
                        val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                        val respBody = resp.body?.string() ?: "{}"
                        resp.close()
                        GatewayForegroundService.totalDownloadBytes.addAndGet(respBody.length.toLong())
                        GatewayForegroundService.trafficDownloadBytes.addAndGet(respBody.length.toLong())
                        call.respondText(respBody, ContentType.Application.Json.withCharset(Charsets.UTF_8), status = HttpStatusCode.fromValue(resp.code.takeIf { it > 0 } ?: 200))
                        logAccess(call, modelId, resp.code, System.currentTimeMillis() - (try { body["created"]?.jsonPrimitive?.content?.toLongOrNull() ?: System.currentTimeMillis() } catch (_: Exception) { System.currentTimeMillis() }))
                    } catch (e: Exception) {
                        val (s, b) = openAIError(HttpStatusCode.InternalServerError, e.message ?: "Embeddings failed", "server_error")
                        call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                    }
                }

                // ★★★ 新增接口：重排序（POST /v1/rerank）★★★
                post("/v1/rerank") {
                    if (!validateApiKey(call)) { val (s,b)=openAIError(HttpStatusCode.Unauthorized,"Invalid or missing API key","invalid_api_key"); call.respondText(contentType=ContentType.Application.Json,status=s,text=b); return@post }
                    try { val rawBytes=call.receive<ByteArray>(); GatewayForegroundService.trafficUploadBytes.addAndGet(rawBytes.size.toLong()); GatewayForegroundService.totalUploadBytes.addAndGet(rawBytes.size.toLong())
                        val body=proxyJson.parseToJsonElement(String(rawBytes,Charsets.UTF_8)).jsonObject; var modelId=body["model"]?.jsonPrimitive?.content?:throw Exception("model required")
                        if (VirtualModel.isVirtual(modelId)){val active=GatewayForegroundService.activeNodeName;if(active.isNotBlank())modelId=active}
                        val models=database.aiModelDao().getEnabledModelsList(); val targetModel=models.find{it.modelId==modelId}?:models.firstOrNull()
                        if(targetModel==null){call.respondText(openAIError(HttpStatusCode.NotFound,"Model $modelId not found").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val provider=database.providerDao().getProviderById(targetModel.providerId)
                        if(provider==null||!provider.isEnabled){call.respondText(openAIError(HttpStatusCode.NotFound,"Provider for $modelId not available").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val upstreamUrl=provider.resolvedBaseUrl.trimEnd('/'); val upstreamBody=sanitizeRequestBody(rawBytes.decodeToString())
                        val client=UpstreamClient.getClientForModel(targetModel.useProxy)
                        val req=okhttp3.Request.Builder().url("$upstreamUrl/v1/rerank").post(upstreamBody.toByteArray(Charsets.UTF_8).toRequestBody(DEFAULT_CT)).apply{val k=CredentialStore.apiKeyForProvider(provider);if(!k.isNullOrBlank())header("Authorization","Bearer $k")}.build()
                        val rStartMs=System.currentTimeMillis()
                        val resp=withContext(Dispatchers.IO){client.newCall(req).execute()}; val respBody=resp.body?.string()?:"{}"; resp.close()
                        GatewayForegroundService.totalDownloadBytes.addAndGet(respBody.length.toLong()); GatewayForegroundService.trafficDownloadBytes.addAndGet(respBody.length.toLong())
                        call.respondText(respBody,ContentType.Application.Json.withCharset(Charsets.UTF_8),status=HttpStatusCode.fromValue(resp.code.takeIf{it>0}?:200))
                        logAccess(call,modelId,resp.code,System.currentTimeMillis()-rStartMs)
                    }catch(e:Exception){val(s,b)=openAIError(HttpStatusCode.InternalServerError,e.message?:"Rerank failed","server_error");call.respondText(contentType=ContentType.Application.Json,status=s,text=b)}
                }

                // ★★★ 新增接口：内容审核（POST /v1/moderations）★★★
                post("/v1/moderations") {
                    if (!validateApiKey(call)) { val (s,b)=openAIError(HttpStatusCode.Unauthorized,"Invalid or missing API key","invalid_api_key"); call.respondText(contentType=ContentType.Application.Json,status=s,text=b); return@post }
                    try { val rawBytes=call.receive<ByteArray>(); GatewayForegroundService.trafficUploadBytes.addAndGet(rawBytes.size.toLong()); GatewayForegroundService.totalUploadBytes.addAndGet(rawBytes.size.toLong())
                        val body=proxyJson.parseToJsonElement(String(rawBytes,Charsets.UTF_8)).jsonObject; var modelId=body["model"]?.jsonPrimitive?.content?:""
                        if (VirtualModel.isVirtual(modelId)){val active=GatewayForegroundService.activeNodeName;if(active.isNotBlank())modelId=active}
                        val models=database.aiModelDao().getEnabledModelsList()
                        val targetModel=if(modelId.isNotBlank())models.find{it.modelId==modelId}else{null}
                        val effectiveModel=targetModel?:models.firstOrNull()
                        if(effectiveModel==null){call.respondText(openAIError(HttpStatusCode.NotFound,"No available model").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val provider=database.providerDao().getProviderById(effectiveModel.providerId)
                        if(provider==null||!provider.isEnabled){call.respondText(openAIError(HttpStatusCode.NotFound,"Provider for $modelId not available").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val upstreamUrl=provider.resolvedBaseUrl.trimEnd('/'); val upstreamBody=sanitizeRequestBody(rawBytes.decodeToString())
                        val client=UpstreamClient.getClientForModel(effectiveModel.useProxy)
                        val req=okhttp3.Request.Builder().url("$upstreamUrl/v1/moderations").post(upstreamBody.toByteArray(Charsets.UTF_8).toRequestBody(DEFAULT_CT)).apply{val k=CredentialStore.apiKeyForProvider(provider);if(!k.isNullOrBlank())header("Authorization","Bearer $k")}.build()
                        val mStartMs=System.currentTimeMillis()
                        val resp=withContext(Dispatchers.IO){client.newCall(req).execute()}; val respBody=resp.body?.string()?:"{}"; resp.close()
                        GatewayForegroundService.totalDownloadBytes.addAndGet(respBody.length.toLong()); GatewayForegroundService.trafficDownloadBytes.addAndGet(respBody.length.toLong())
                        call.respondText(respBody,ContentType.Application.Json.withCharset(Charsets.UTF_8),status=HttpStatusCode.fromValue(resp.code.takeIf{it>0}?:200))
                        logAccess(call,modelId,resp.code,System.currentTimeMillis()-mStartMs)
                    }catch(e:Exception){val(s,b)=openAIError(HttpStatusCode.InternalServerError,e.message?:"Moderations failed","server_error");call.respondText(contentType=ContentType.Application.Json,status=s,text=b)}
                }

                // ★★★ 新增接口：文本转语音（POST /v1/audio/speech）★★★
                post("/v1/audio/speech") {
                    if (!validateApiKey(call)) { val (s,b)=openAIError(HttpStatusCode.Unauthorized,"Invalid or missing API key","invalid_api_key"); call.respondText(contentType=ContentType.Application.Json,status=s,text=b); return@post }
                    try { val rawBytes=call.receive<ByteArray>(); GatewayForegroundService.trafficUploadBytes.addAndGet(rawBytes.size.toLong()); GatewayForegroundService.totalUploadBytes.addAndGet(rawBytes.size.toLong())
                        val body=proxyJson.parseToJsonElement(String(rawBytes,Charsets.UTF_8)).jsonObject; var modelId=body["model"]?.jsonPrimitive?.content?:throw Exception("model required")
                        if (VirtualModel.isVirtual(modelId)){val active=GatewayForegroundService.activeNodeName;if(active.isNotBlank())modelId=active}
                        val models=database.aiModelDao().getEnabledModelsList(); val targetModel=models.find{it.modelId==modelId}?:models.firstOrNull()
                        if(targetModel==null){call.respondText(openAIError(HttpStatusCode.NotFound,"Model $modelId not found").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val provider=database.providerDao().getProviderById(targetModel.providerId)
                        if(provider==null||!provider.isEnabled){call.respondText(openAIError(HttpStatusCode.NotFound,"Provider for $modelId not available").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val upstreamUrl=provider.resolvedBaseUrl.trimEnd('/'); val upstreamBody=rawBytes // 保持二进制
                        val client=UpstreamClient.getClientForModel(targetModel.useProxy)
                        val contentType=call.request.headers["Content-Type"]?: "application/json"
                        val req=okhttp3.Request.Builder().url("$upstreamUrl/v1/audio/speech").post(upstreamBody.toRequestBody(contentType.toMediaType())).apply{val k=CredentialStore.apiKeyForProvider(provider);if(!k.isNullOrBlank())header("Authorization","Bearer $k")}.build()
                        val startMs=System.currentTimeMillis()
                        val computedLatency=startMs
                        val resp=withContext(Dispatchers.IO){client.newCall(req).execute()}; val respBytes=resp.body?.bytes()?:ByteArray(0); resp.close()
                        GatewayForegroundService.totalDownloadBytes.addAndGet(respBytes.size.toLong()); GatewayForegroundService.trafficDownloadBytes.addAndGet(respBytes.size.toLong())
                        val respContentType=resp.header("Content-Type")?:"audio/mpeg"
                        call.respondBytesWriter(contentType=ContentType.parse(respContentType)){writeFully(respBytes)}
                        logAccess(call,modelId,resp.code,System.currentTimeMillis()-startMs)
                    }catch(e:Exception){val(s,b)=openAIError(HttpStatusCode.InternalServerError,e.message?:"TTS failed","server_error");call.respondText(contentType=ContentType.Application.Json,status=s,text=b)}
                }

                // ★★★ 新增接口：图像生成（POST /v1/images/generations）★★★
                post("/v1/images/generations") {
                    if (!validateApiKey(call)) { val (s,b)=openAIError(HttpStatusCode.Unauthorized,"Invalid or missing API key","invalid_api_key"); call.respondText(contentType=ContentType.Application.Json,status=s,text=b); return@post }
                    try { val rawBytes=call.receive<ByteArray>(); GatewayForegroundService.trafficUploadBytes.addAndGet(rawBytes.size.toLong()); GatewayForegroundService.totalUploadBytes.addAndGet(rawBytes.size.toLong())
                        var bodyStr=String(rawBytes,Charsets.UTF_8)
                        val body=proxyJson.parseToJsonElement(bodyStr).jsonObject
                        var modelId=body["model"]?.jsonPrimitive?.content?:"dall-e-3"
                        if (VirtualModel.isVirtual(modelId)){
                            val active=GatewayForegroundService.activeNodeName
                            if(active.isNotBlank()) modelId=active
                        }
                        val models=database.aiModelDao().getEnabledModelsList()
                        var targetModel=models.find{it.modelId==modelId}
                        if(targetModel==null){
                            // 兜底：用第一个已启用的模型
                            targetModel=models.firstOrNull()
                        }
                        if(targetModel==null){call.respondText(openAIError(HttpStatusCode.NotFound,"No available model for image generation").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val provider=database.providerDao().getProviderById(targetModel.providerId)
                        if(provider==null||!provider.isEnabled){call.respondText(openAIError(HttpStatusCode.NotFound,"Provider for ${targetModel.modelId} not available").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        // 替换body中的model为真实模型ID
                        bodyStr=replaceModelInBody(bodyStr, targetModel.modelId)
                        val upstreamUrl=provider.resolvedBaseUrl.trimEnd('/')
                        val upstreamBody=sanitizeRequestBody(bodyStr)
                        val client=UpstreamClient.getClientForModel(targetModel.useProxy)
                        val req=okhttp3.Request.Builder().url("$upstreamUrl/v1/images/generations").post(upstreamBody.toByteArray(Charsets.UTF_8).toRequestBody(DEFAULT_CT)).apply{val k=CredentialStore.apiKeyForProvider(provider);if(!k.isNullOrBlank())header("Authorization","Bearer $k")}.build()
                        val imgStartMs=System.currentTimeMillis()
                        val resp=withContext(Dispatchers.IO){client.newCall(req).execute()}; val respBody=resp.body?.string()?:"{}"; resp.close()
                        GatewayForegroundService.totalDownloadBytes.addAndGet(respBody.length.toLong()); GatewayForegroundService.trafficDownloadBytes.addAndGet(respBody.length.toLong())
                        call.respondText(respBody,ContentType.Application.Json.withCharset(Charsets.UTF_8),status=HttpStatusCode.fromValue(resp.code.takeIf{it>0}?:200))
                        logAccess(call,targetModel.modelId,resp.code,System.currentTimeMillis()-imgStartMs)
                    }catch(e:Exception){val(s,b)=openAIError(HttpStatusCode.InternalServerError,e.message?:"Image generation failed","server_error");call.respondText(contentType=ContentType.Application.Json,status=s,text=b)}
                }

                // ★★★ 新增接口：视频生成-同步（POST /v1/videos）★★★
                post("/v1/videos") {
                    if (!validateApiKey(call)) { val (s,b)=openAIError(HttpStatusCode.Unauthorized,"Invalid or missing API key","invalid_api_key"); call.respondText(contentType=ContentType.Application.Json,status=s,text=b); return@post }
                    try { val rawBytes=call.receive<ByteArray>(); GatewayForegroundService.trafficUploadBytes.addAndGet(rawBytes.size.toLong()); GatewayForegroundService.totalUploadBytes.addAndGet(rawBytes.size.toLong())
                        val body=proxyJson.parseToJsonElement(String(rawBytes,Charsets.UTF_8)).jsonObject; var modelId=body["model"]?.jsonPrimitive?.content?:"sora"
                        if (VirtualModel.isVirtual(modelId)){val active=GatewayForegroundService.activeNodeName;if(active.isNotBlank())modelId=active}
                        val models=database.aiModelDao().getEnabledModelsList(); val targetModel=models.find{it.modelId==modelId}?:models.firstOrNull()
                        if(targetModel==null){call.respondText(openAIError(HttpStatusCode.NotFound,"Model $modelId not found").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val provider=database.providerDao().getProviderById(targetModel.providerId)
                        if(provider==null||!provider.isEnabled){call.respondText(openAIError(HttpStatusCode.NotFound,"Provider for $modelId not available").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val upstreamUrl=provider.resolvedBaseUrl.trimEnd('/'); val upstreamBody=rawBytes
                        val client=UpstreamClient.getClientForModel(targetModel.useProxy)
                        val contentType=call.request.headers["Content-Type"]?:"application/json"
                        val req=okhttp3.Request.Builder().url("$upstreamUrl/v1/videos").post(upstreamBody.toRequestBody(contentType.toMediaType())).apply{val k=CredentialStore.apiKeyForProvider(provider);if(!k.isNullOrBlank())header("Authorization","Bearer $k")}.build()
                        val vStartMs=System.currentTimeMillis()
                        val resp=withContext(Dispatchers.IO){client.newCall(req).execute()}; val respBody=resp.body?.string()?:"{}"; resp.close()
                        GatewayForegroundService.totalDownloadBytes.addAndGet(respBody.length.toLong()); GatewayForegroundService.trafficDownloadBytes.addAndGet(respBody.length.toLong())
                        call.respondText(respBody,ContentType.Application.Json.withCharset(Charsets.UTF_8),status=HttpStatusCode.fromValue(resp.code.takeIf{it>0}?:200))
                        logAccess(call,modelId,resp.code,System.currentTimeMillis()-vStartMs)
                    }catch(e:Exception){val(s,b)=openAIError(HttpStatusCode.InternalServerError,e.message?:"Video sync failed","server_error");call.respondText(contentType=ContentType.Application.Json,status=s,text=b)}
                }

                // ★★★ 新增接口：视频生成-异步任务（POST /v1/video/generations）★★★
                post("/v1/video/generations") {
                    if (!validateApiKey(call)) { val (s,b)=openAIError(HttpStatusCode.Unauthorized,"Invalid or missing API key","invalid_api_key"); call.respondText(contentType=ContentType.Application.Json,status=s,text=b); return@post }
                    try { val rawBytes=call.receive<ByteArray>(); GatewayForegroundService.trafficUploadBytes.addAndGet(rawBytes.size.toLong()); GatewayForegroundService.totalUploadBytes.addAndGet(rawBytes.size.toLong())
                        val body=proxyJson.parseToJsonElement(String(rawBytes,Charsets.UTF_8)).jsonObject; var modelId=body["model"]?.jsonPrimitive?.content?:"sora"
                        if (VirtualModel.isVirtual(modelId)){val active=GatewayForegroundService.activeNodeName;if(active.isNotBlank())modelId=active}
                        val models=database.aiModelDao().getEnabledModelsList(); val targetModel=models.find{it.modelId==modelId}?:models.firstOrNull()
                        if(targetModel==null){call.respondText(openAIError(HttpStatusCode.NotFound,"Model $modelId not found").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val provider=database.providerDao().getProviderById(targetModel.providerId)
                        if(provider==null||!provider.isEnabled){call.respondText(openAIError(HttpStatusCode.NotFound,"Provider for $modelId not available").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val upstreamUrl=provider.resolvedBaseUrl.trimEnd('/'); val upstreamBody=sanitizeRequestBody(rawBytes.decodeToString())
                        val client=UpstreamClient.getClientForModel(targetModel.useProxy)
                        val req=okhttp3.Request.Builder().url("$upstreamUrl/v1/video/generations").post(upstreamBody.toByteArray(Charsets.UTF_8).toRequestBody(DEFAULT_CT)).apply{val k=CredentialStore.apiKeyForProvider(provider);if(!k.isNullOrBlank())header("Authorization","Bearer $k")}.build()
                        val vTaskStartMs=System.currentTimeMillis()
                        val resp=withContext(Dispatchers.IO){client.newCall(req).execute()}; val respBody=resp.body?.string()?:"{}"; resp.close()
                        GatewayForegroundService.totalDownloadBytes.addAndGet(respBody.length.toLong()); GatewayForegroundService.trafficDownloadBytes.addAndGet(respBody.length.toLong())
                        call.respondText(respBody,ContentType.Application.Json.withCharset(Charsets.UTF_8),status=HttpStatusCode.fromValue(resp.code.takeIf{it>0}?:200))
                        logAccess(call,modelId,resp.code,System.currentTimeMillis()-vTaskStartMs)
                    }catch(e:Exception){val(s,b)=openAIError(HttpStatusCode.InternalServerError,e.message?:"Video task failed","server_error");call.respondText(contentType=ContentType.Application.Json,status=s,text=b)}
                }

                // ★★★ 新增接口：获取视频任务状态（GET /v1/video/generations/{task_id}）★★★
                get("/v1/video/generations/{task_id}") {
                    if (!validateApiKey(call)) { val (s,b)=openAIError(HttpStatusCode.Unauthorized,"Invalid or missing API key","invalid_api_key"); call.respondText(contentType=ContentType.Application.Json,status=s,text=b); return@get }
                    try { val taskId=call.parameters["task_id"]?:""
                        val body=proxyJson.parseToJsonElement("{}").jsonObject; var modelId="sora"
                        val models=database.aiModelDao().getEnabledModelsList(); val targetModel=models.firstOrNull()
                        if(targetModel==null){call.respondText(openAIError(HttpStatusCode.NotFound,"No available model").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@get}
                        val provider=database.providerDao().getProviderById(targetModel.providerId)
                        if(provider==null||!provider.isEnabled){call.respondText(openAIError(HttpStatusCode.NotFound,"No available provider").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@get}
                        val upstreamUrl=provider.resolvedBaseUrl.trimEnd('/')
                        val client=UpstreamClient.getClientForModel(targetModel.useProxy)
                        val req=okhttp3.Request.Builder().url("$upstreamUrl/v1/video/generations/$taskId").get().apply{val k=CredentialStore.apiKeyForProvider(provider);if(!k.isNullOrBlank())header("Authorization","Bearer $k")}.build()
                        val vGetStartMs=System.currentTimeMillis()
                        val resp=withContext(Dispatchers.IO){client.newCall(req).execute()}; val respBody=resp.body?.string()?:"{}"; resp.close()
                        GatewayForegroundService.totalDownloadBytes.addAndGet(respBody.length.toLong()); GatewayForegroundService.trafficDownloadBytes.addAndGet(respBody.length.toLong())
                        call.respondText(respBody,ContentType.Application.Json.withCharset(Charsets.UTF_8),status=HttpStatusCode.fromValue(resp.code.takeIf{it>0}?:200))
                        logAccess(call,modelId,resp.code,System.currentTimeMillis()-vGetStartMs)
                    }catch(e:Exception){val(s,b)=openAIError(HttpStatusCode.InternalServerError,e.message?:"Video task status failed","server_error");call.respondText(contentType=ContentType.Application.Json,status=s,text=b)}
                }

                // ★★★ Gemini 格式：POST /v1beta/models/{model}:generateContent ★★★
                post("/v1beta/models/{model}:generateContent") {
                    if (!validateApiKey(call)) { val (s,b)=openAIError(HttpStatusCode.Unauthorized,"Invalid or missing API key","invalid_api_key"); call.respondText(contentType=ContentType.Application.Json,status=s,text=b); return@post }
                    try { val rawBytes=call.receive<ByteArray>(); GatewayForegroundService.trafficUploadBytes.addAndGet(rawBytes.size.toLong()); GatewayForegroundService.totalUploadBytes.addAndGet(rawBytes.size.toLong())
                        val geminiModel=call.parameters["model"]?:""
                        var modelId=geminiModel; if (VirtualModel.isVirtual(modelId)){val active=GatewayForegroundService.activeNodeName;if(active.isNotBlank())modelId=active}
                        val models=database.aiModelDao().getEnabledModelsList(); val targetModel=models.find{it.modelId==modelId}?:models.firstOrNull()
                        if(targetModel==null){call.respondText(openAIError(HttpStatusCode.NotFound,"Model $modelId not found").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val provider=database.providerDao().getProviderById(targetModel.providerId)
                        if(provider==null||!provider.isEnabled){call.respondText(openAIError(HttpStatusCode.NotFound,"Provider for $modelId not available").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val upstreamUrl=provider.resolvedBaseUrl.trimEnd('/'); val upstreamBody=rawBytes
                        val client=UpstreamClient.getClientForModel(targetModel.useProxy)
                        val contentType=call.request.headers["Content-Type"]?:"application/json"
                        val req=okhttp3.Request.Builder().url("$upstreamUrl/v1beta/models/$geminiModel:generateContent").post(upstreamBody.toRequestBody(contentType.toMediaType())).apply{val k=CredentialStore.apiKeyForProvider(provider);if(!k.isNullOrBlank())header("Authorization","Bearer $k")}.build()
                        val gStartMs=System.currentTimeMillis()
                        val resp=withContext(Dispatchers.IO){client.newCall(req).execute()}; val respBody=resp.body?.string()?:"{}"; resp.close()
                        GatewayForegroundService.totalDownloadBytes.addAndGet(respBody.length.toLong()); GatewayForegroundService.trafficDownloadBytes.addAndGet(respBody.length.toLong())
                        call.respondText(respBody,ContentType.Application.Json.withCharset(Charsets.UTF_8),status=HttpStatusCode.fromValue(resp.code.takeIf{it>0}?:200))
                        logAccess(call,modelId,resp.code,System.currentTimeMillis()-gStartMs)
                    }catch(e:Exception){val(s,b)=openAIError(HttpStatusCode.InternalServerError,e.message?:"Gemini generate failed","server_error");call.respondText(contentType=ContentType.Application.Json,status=s,text=b)}
                }

                // ★★★ Gemini 引擎嵌入：POST /v1/engines/{model}/embeddings ★★★
                post("/v1/engines/{model}/embeddings") {
                    if (!validateApiKey(call)) { val (s,b)=openAIError(HttpStatusCode.Unauthorized,"Invalid or missing API key","invalid_api_key"); call.respondText(contentType=ContentType.Application.Json,status=s,text=b); return@post }
                    try { val rawBytes=call.receive<ByteArray>(); GatewayForegroundService.trafficUploadBytes.addAndGet(rawBytes.size.toLong()); GatewayForegroundService.totalUploadBytes.addAndGet(rawBytes.size.toLong())
                        val engineModel=call.parameters["model"]?:""
                        var modelId=engineModel; if (VirtualModel.isVirtual(modelId)){val active=GatewayForegroundService.activeNodeName;if(active.isNotBlank())modelId=active}
                        val models=database.aiModelDao().getEnabledModelsList(); val targetModel=models.find{it.modelId==modelId}?:models.firstOrNull()
                        if(targetModel==null){call.respondText(openAIError(HttpStatusCode.NotFound,"Model $modelId not found").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val provider=database.providerDao().getProviderById(targetModel.providerId)
                        if(provider==null||!provider.isEnabled){call.respondText(openAIError(HttpStatusCode.NotFound,"Provider for $modelId not available").second,ContentType.Application.Json,status=HttpStatusCode.NotFound);return@post}
                        val upstreamUrl=provider.resolvedBaseUrl.trimEnd('/'); val upstreamBody=sanitizeRequestBody(rawBytes.decodeToString())
                        val client=UpstreamClient.getClientForModel(targetModel.useProxy)
                        val req=okhttp3.Request.Builder().url("$upstreamUrl/v1/engines/$engineModel/embeddings").post(upstreamBody.toByteArray(Charsets.UTF_8).toRequestBody(DEFAULT_CT)).apply{val k=CredentialStore.apiKeyForProvider(provider);if(!k.isNullOrBlank())header("Authorization","Bearer $k")}.build()
                        val eStartMs=System.currentTimeMillis()
                        val resp=withContext(Dispatchers.IO){client.newCall(req).execute()}; val respBody=resp.body?.string()?:"{}"; resp.close()
                        GatewayForegroundService.totalDownloadBytes.addAndGet(respBody.length.toLong()); GatewayForegroundService.trafficDownloadBytes.addAndGet(respBody.length.toLong())
                        call.respondText(respBody,ContentType.Application.Json.withCharset(Charsets.UTF_8),status=HttpStatusCode.fromValue(resp.code.takeIf{it>0}?:200))
                        logAccess(call,modelId,resp.code,System.currentTimeMillis()-eStartMs)
                    }catch(e:Exception){val(s,b)=openAIError(HttpStatusCode.InternalServerError,e.message?:"Engine embeddings failed","server_error");call.respondText(contentType=ContentType.Application.Json,status=s,text=b)}
                }

                // ★★★ WebSocket 实时语音（/v1/realtime）★★★
                webSocket("/v1/realtime") {
                    val model = call.parameters["model"] ?: "gpt-4o-realtime"
                    GatewayForegroundService.addDebugLog("🔊 WebSocket connected: model=$model")
                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    val text = frame.readText()
                                    GatewayForegroundService.addDebugLog("🔊 WS received: ${text.take(100)}")
                                    // 回显确认
                                    send(Frame.Text("{\"type\":\"response.audio.done\",\"model\":\"$model\"}"))
                                }
                                is Frame.Close -> {
                                    GatewayForegroundService.addDebugLog("🔊 WebSocket closed")
                                    close()
                                }
                                else -> {}
                            }
                        }
                    } catch (_: Exception) { }
                }

                // ★★★ 未实现接口（POST /v1/files + GET /v1/files）★★★
                get("/v1/files") {
                    val (s, b) = openAIError(HttpStatusCode.NotImplemented, "This endpoint is not implemented.", "not_implemented")
                    call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                }

                // === 通用代理转发：拦截所有 /v1/* 请求 ===
                // ★★ 去掉了 runBlocking！Ktor 路由 handler 本身就在协程中
                post("/v1/{path...}") {
                    if (!validateApiKey(call)) {
                        val (s, b) = openAIError(HttpStatusCode.Unauthorized, "Invalid or missing API key", "invalid_api_key")
                        call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                        return@post
                    }
                    try {
                        proxyRequest(call, database)
                    } catch (e: Exception) {
                        val (s, b) = openAIError(HttpStatusCode.InternalServerError, "Internal error: ${e.message}", "server_error")
                        try { call.respondText(contentType = ContentType.Application.Json, status = s, text = b) } catch (_: Exception) {}
                    }
                }
                get("/v1/{path...}") {
                    if (!validateApiKey(call)) {
                        val (s, b) = openAIError(HttpStatusCode.Unauthorized, "Invalid or missing API key", "invalid_api_key")
                        call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                        return@get
                    }
                    try {
                        proxyRequest(call, database)
                    } catch (e: Exception) {
                        val (s, b) = openAIError(HttpStatusCode.InternalServerError, "Internal error: ${e.message}", "server_error")
                        try { call.respondText(contentType = ContentType.Application.Json, status = s, text = b) } catch (_: Exception) {}
                    }
                }
                // 访问日志（需要API密钥验证）
                get("/v1/logs") {
                    if (!validateApiKey(call)) {
                        val (s, b) = openAIError(HttpStatusCode.Unauthorized, "Invalid or missing API key", "invalid_api_key")
                        call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                        return@get
                    }
                    val logs = synchronized(accessLog) { accessLog.toList() }
                    // ★ 返回真实访问日志条目：最近在前，最多 200 条，放在 data 字段
                    val recent = logs.asReversed().take(200)
                    val dataArray = JsonArray(recent.map { entry ->
                        buildJsonObject {
                            entry.forEach { (k, v) ->
                                when (v) {
                                    is Number -> put(k, JsonPrimitive(v))
                                    is Boolean -> put(k, JsonPrimitive(v))
                                    else -> put(k, JsonPrimitive(v.toString()))
                                }
                            }
                        }
                    })
                    val logJson = buildJsonObject {
                        put("total", JsonPrimitive(logs.size))
                        put("data", dataArray)
                    }.toString()
                    call.respondText(logJson, ContentType.Application.Json.withCharset(Charsets.UTF_8))
                }
            }
        }
        server = embedded.start(wait = false)
    }

    fun stop() {
        try {
            server?.stop(1000, 2000)
        } catch (_: Exception) { }
        server = null
        // Если включён тихий приёмник, порт остаётся занят: так видно попытки
        // подключения к остановленному шлюзу.
        QuietListener.start(GatewayForegroundService.getGatewayPort())
    }

    val isRunning: Boolean get() = server != null
}

// ================== 通用代理转发核心 ==================

/** 智能故障转移：模型健康状态缓存 */

// ★ API密钥验证 + 访问日志（顶层，供proxyRequest使用）
private val startTime = System.currentTimeMillis()
private val accessLog = mutableListOf<Map<String, Any>>()
private const val logMaxSize = 1000

private fun validateApiKey(call: ApplicationCall): Boolean {
    val remoteIp = call.request.local.remoteHost ?: ""
    // ★ Loopback (127.0.0.1 / ::1) — всегда без авторизации ★
    if (KeyManager.isLoopback(remoteIp)) return true

    // ★ Не-loopback (LAN): только в LAN-режиме и только по токену ★
    if (!GatewayForegroundService.getLanModeEnabled()) return false
    val authHeader = call.request.headers["Authorization"] ?: return false
    val presented = authHeader.removePrefix("Bearer ").trim()
    if (presented.isEmpty()) return false

    // Пароль LAN-режима = перманентный Bearer-токен (constant-time сравнение)
    val lanToken = GatewayForegroundService.getLanToken()
    if (lanToken.isNotBlank() && java.security.MessageDigest.isEqual(
            presented.toByteArray(Charsets.UTF_8), lanToken.toByteArray(Charsets.UTF_8))) {
        return true
    }
    // Дополнительно принимаем валидные ключи из менеджера ключей (тонкий контроль доступа)
    val entry = KeyManager.validateKey(presented)
    if (entry != null) {
        call.attributes.put(API_KEY_ENTRY_KEY, entry)
        return true
    }
    return false
}

private fun logAccess(call: ApplicationCall, modelId: String, statusCode: Int, durationMs: Long) {
    val entry = mapOf<String, Any>(
        "time" to System.currentTimeMillis(),
        "ip" to (call.request.local.remoteHost ?: ""),
        "method" to (call.request.httpMethod.value ?: ""),
        "path" to (call.parameters.getAll("path")?.joinToString("/") ?: ""),
        "model" to modelId,
        "status" to statusCode,
        "duration_ms" to durationMs
    )
    synchronized(accessLog) {
        accessLog.add(entry)
        if (accessLog.size > logMaxSize) accessLog.removeAt(0)
    }
    // ★★ 记录会话延迟（自适应超时用）★★
    if (durationMs > 0 && modelId != "unknown") {
        val sessionKey = "ip:${call.request.local.remoteHost ?: ""}"
        if (sessionKey.isNotBlank()) {
            recordSessionLatency(sessionKey, durationMs)
        }
    }
}

private val proxyJson = Json { ignoreUnknownKeys = true; prettyPrint = false }
private val DEFAULT_CT = "application/json".toMediaType()
private const val MAX_RETRIES = 3
/**
 * ★ 请求体透传（pass-through）。
 * 旧实现用 StringBuilder + `sb.replace(Regex){...}` 解析为 CharSequence 扩展，返回值被丢弃 →
 * 从未真正钳制 temperature/top_p 等参数（no-op），且会误导。这里改为明确的直通，
 * 保持转发行为不变，避免对上游本就合法的参数做隐式改写。
 */
private fun sanitizeRequestBody(bodyStr: String): String {
    return bodyStr
}

/** 替换请求体中的model字段（用于auto解析后的真实模型ID替换） */
private fun replaceModelInBody(bodyStr: String, newModelId: String): String {
    return try {
        Regex(""""model"\s*:\s*"[^"]*"""").replace(bodyStr) { "\"model\":\"$newModelId\"" }
    } catch (_: Exception) { bodyStr }
}

private suspend fun executeWithRetry(client: okhttp3.OkHttpClient, request: okhttp3.Request, retries: Int = MAX_RETRIES): okhttp3.Response {
    var lastError: Exception? = null
    for (attempt in 1..retries) {
        try {
            // ★★ OkHttp execute() 是阻塞的，用 IO 调度器避免卡死 Ktor
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            // ★ POST 非幂等：只对真正瞬时的状态码(429/502/503/504)重试；其它 4xx/5xx(如 400/401/403)
            //   是确定性的，重试只会浪费时间并可能重复副作用 → 直接返回真实响应给调用方。
            val retryableStatus = response.code == 429 || response.code == 502 || response.code == 503 || response.code == 504
            if (response.isSuccessful || !retryableStatus || attempt == retries) {
                return response
            }
            response.close()
            val waitMs = (attempt * 1000L).coerceAtMost(5000L)
            delay(waitMs)
        } catch (e: SocketTimeoutException) {
            lastError = e
            if (attempt < retries) { delay((attempt * 1000L).coerceAtMost(5000L)) }
        } catch (e: ConnectException) {
            lastError = e
            if (attempt < retries) { delay((attempt * 1500L).coerceAtMost(5000L)) }
        } catch (e: java.io.IOException) {
            // 其它网络 I/O 异常(连接重置/读写失败等)也属于瞬时故障 → 重试
            lastError = e
            if (attempt < retries) { delay((attempt * 1000L).coerceAtMost(5000L)) }
        } catch (e: Exception) {
            // 非网络异常(如参数/状态错误)不是瞬时故障 → 不重试，直接抛出
            throw e
        }
    }
    throw lastError ?: Exception("Request failed after $retries retries")
}

/** OpenAI 标准错误响应 */
private fun corsResponse(call: ApplicationCall) {
    call.response.headers.append("Access-Control-Allow-Origin", "*")
    call.response.headers.append("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
    call.response.headers.append("Access-Control-Allow-Headers", "Content-Type, Authorization, x-api-key, anthropic-version, x-goog-api-key")
}

// ★ OpenAI-совместимый конверт ошибок:
//   - "type" ВСЕГДА выводится из HTTP-статуса (vocabulary OpenAI), не передаётся вызовом
//   - "code" — строка (например "invalid_api_key", "upstream_error", "server_error") или null, НИКОГДА не int
//   - 3-й позиционный аргумент вызова трактуется как строковый "code"
//   - 4-й (legacyCode: Int?) сохранён только для совместимости старых вызовов и игнорируется
private fun openAIError(
    status: HttpStatusCode,
    message: String,
    code: String? = null,
    @Suppress("UNUSED_PARAMETER") legacyCode: Int? = null
): Pair<HttpStatusCode, String> {
    val type = when {
        status.value == 401 -> "authentication_error"
        status.value == 429 -> "rate_limit_error"
        status.value in 500..599 -> "server_error"
        else -> "invalid_request_error"
    }
    val errorJson = buildJsonObject {
        put("error", buildJsonObject {
            put("message", JsonPrimitive(message))
            put("type", JsonPrimitive(type))
            put("param", JsonNull)
            put("code", if (code != null) JsonPrimitive(code) else JsonNull)
        })
    }
    return status to errorJson.toString()
}

/**
 * 从消息content中提取纯文本（支持字符串和多模态数组）
 */
private fun extractTextContent(content: kotlinx.serialization.json.JsonElement?): String {
    if (content == null) return ""
    return try {
        content.jsonPrimitive.content
    } catch (_: Exception) {
        try {
            val array = content.jsonArray
            array.joinToString("\n") { part ->
                try {
                    val obj = part.jsonObject
                    if (obj["type"]?.jsonPrimitive?.content == "text") {
                        obj["text"]?.jsonPrimitive?.content ?: ""
                    } else ""
                } catch (_: Exception) { "" }
            }.trim()
        } catch (_: Exception) { "" }
    }
}

/** 生成 OpenAI 标准 chat.completion 响应（用于回退/测试） */
private fun makeChatCompletionResponse(modelId: String, content: String, stream: Boolean = false): String {
    val id = "chatcmpl-${UUID.randomUUID().toString().take(8)}"
    val created = System.currentTimeMillis() / 1000
    if (stream) {
        return buildJsonObject {
            put("id", JsonPrimitive(id))
            put("object", JsonPrimitive("chat.completion.chunk"))
            put("created", JsonPrimitive(created))
            put("model", JsonPrimitive(modelId))
            put("choices", JsonArray(listOf(buildJsonObject {
                put("index", JsonPrimitive(0))
                put("delta", buildJsonObject {
                    put("role", JsonPrimitive("assistant"))
                    put("content", JsonPrimitive(content))
                })
                put("finish_reason", JsonPrimitive("stop"))
            })))
        }.toString()
    }
    return buildJsonObject {
        put("id", JsonPrimitive(id))
        put("object", JsonPrimitive("chat.completion"))
        put("created", JsonPrimitive(created))
        put("model", JsonPrimitive(modelId))
        put("choices", JsonArray(listOf(buildJsonObject {
            put("index", JsonPrimitive(0))
            put("message", buildJsonObject {
                put("role", JsonPrimitive("assistant"))
                put("content", JsonPrimitive(content))
            })
            put("finish_reason", JsonPrimitive("stop"))
        })))
        put("usage", buildJsonObject {
            put("prompt_tokens", JsonPrimitive(0))
            put("completion_tokens", JsonPrimitive(content.length))
            put("total_tokens", JsonPrimitive(content.length))
        })
    }.toString()
}

// Attribute keys 用于在 call 中传递 modelId / providerId
private val MODEL_ID_KEY = AttributeKey<String>("proxyModelId")
private val PROVIDER_ID_KEY = AttributeKey<Long>("proxyProviderId")
private val API_KEY_ENTRY_KEY = AttributeKey<ApiKeyEntry>("apiKeyEntry")
private val ROUTING_MODIFIED_BODY_KEY = AttributeKey<String>("routingModifiedBody")

private val ApplicationCall.proxyModelId: String? get() = attributes.getOrNull(MODEL_ID_KEY)
private val ApplicationCall.proxyProviderId: Long? get() = attributes.getOrNull(PROVIDER_ID_KEY)
private val ApplicationCall.apiKeyEntry: ApiKeyEntry? get() = attributes.getOrNull(API_KEY_ENTRY_KEY)

/** ★ 获取API密钥标签（用于用量统计） */
private val ApplicationCall.apiKeyLabel: String get() = apiKeyEntry?.label ?: ""

/** ★ 会话记忆：源IP → 最后成功使用的模型ID（并发安全：Ktor 协程与清理协程同时读写）*/
private val sessionModelCache = java.util.concurrent.ConcurrentHashMap<String, String>()

/** ★ 记录会话成功使用的模型 */
private fun recordSessionModel(call: ApplicationCall, modelId: String) {
    val sessionKey = getSessionKey(call)
    if (sessionKey.isNotBlank()) {
        sessionModelCache[sessionKey] = modelId
    }
}

/** ★ 会话标识（优先用 API Key，其次用客户端IP） */
private fun getSessionKey(call: ApplicationCall): String {
        val auth = call.request.headers["Authorization"]
        if (!auth.isNullOrBlank()) {
            val key = auth.removePrefix("Bearer ").trim()
            return "auth:${key.hashCode()}"
        }
        val ip = call.request.local.remoteHost
        if (ip.isNotBlank()) return "ip:$ip"
        return "unknown:${UUID.randomUUID().toString().take(8)}"
    }

// ═══════════════════════════════════════════
// ★★ 会话延迟断开（闲置超时+自适应）★★
// ═══════════════════════════════════════════

/** 会话最后活跃时间戳（并发安全）*/
private val sessionLastActive = java.util.concurrent.ConcurrentHashMap<String, Long>()

/** 会话历史延迟（毫秒），用于自适应计算超时（并发安全：map 用 ConcurrentHashMap，内层列表用 CopyOnWriteArrayList）*/
private val sessionLatencyHistory = java.util.concurrent.ConcurrentHashMap<String, MutableList<Long>>()

/** 默认闲置超时基数（秒） */
private const val SESSION_IDLE_BASE_SECONDS = 30

/** 最大闲置超时（秒） */
private const val SESSION_IDLE_MAX_SECONDS = 120

/** 最小闲置超时（秒） */
private const val SESSION_IDLE_MIN_SECONDS = 10

/** 更新会话活跃时间 */
private fun updateSessionActivity(sessionKey: String) {
    sessionLastActive[sessionKey] = System.currentTimeMillis()
}

/** 记录会话延迟，用于自适应调节超时 */
private fun recordSessionLatency(sessionKey: String, latencyMs: Long) {
    // computeIfAbsent 原子创建；CopyOnWriteArrayList 让 add/removeAt/average 在并发下不抛 CME
    val history = sessionLatencyHistory.computeIfAbsent(sessionKey) { java.util.concurrent.CopyOnWriteArrayList<Long>() }
    history.add(latencyMs)
    // 只保留最近5次
    if (history.size > 5) history.removeAt(0)
}

/** 根据历史延迟计算自适应超时（秒） */
private fun getAdaptiveTimeout(sessionKey: String): Int {
    val history = sessionLatencyHistory[sessionKey]
    if (history.isNullOrEmpty()) return SESSION_IDLE_BASE_SECONDS
    // 取平均延迟
    val avgLatency = history.average().toLong()
    // 超时 = 基数 + 平均延迟*2（转换为秒），确保延迟高的会话有更长超时
    val adaptive = SESSION_IDLE_BASE_SECONDS + (avgLatency * 2 / 1000).toInt()
    return adaptive.coerceIn(SESSION_IDLE_MIN_SECONDS, SESSION_IDLE_MAX_SECONDS)
}

/** 清理过期会话缓存（由协程定期调用） */
private fun cleanupExpiredSessions() {
    val now = System.currentTimeMillis()
    val expiredKeys = mutableListOf<String>()
    sessionLastActive.forEach { (key, lastActive) ->
        val timeoutMs = getAdaptiveTimeout(key) * 1000L
        if (now - lastActive > timeoutMs) {
            expiredKeys.add(key)
        }
    }
    expiredKeys.forEach { key ->
        sessionModelCache.remove(key)
        sessionLastActive.remove(key)
        sessionLatencyHistory.remove(key)
    }
    if (expiredKeys.isNotEmpty()) {
        GatewayForegroundService.addDebugLog("🧹 Очищено просроченных сессий: ${expiredKeys.size}")
    }
}

/** 启动会话清理协程 */
private val sessionCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private fun startSessionCleanup() {
    sessionCleanupScope.launch {
        while (true) {
            delay(10000) // 每10秒检查一次
            cleanupExpiredSessions()
        }
    }
}

// 在 GatewayService 的 start 中调用
// startSessionCleanup() 已在 start() 中调用

/**
 * 通用代理转发：读取请求体 → 查找模型(如果是chat请求) → 转发到上游 → 管道式流回客户端
 * 支持图片/视频/音频等任意 Content-Type，数据不落盘，直接 pipe
 * ★ v3.1.0 新增自动故障转移：请求失败时自动切换到其他可用模型
 * ★ v3.3.2 新增会话记忆：同一会话失败的模型自动跳过，走上次成功的模型
 */
private suspend fun proxyRequest(call: ApplicationCall, database: AppDatabase) {
    // ★★ 所有响应加 CORS 头 ★★
    corsResponse(call)

    // ★ 请求开始 → 清零上一轮会话流量（在计数之前，避免清零本轮） ★
    GatewayForegroundService.resetNotificationTraffic()

    // 1. 读取原始请求体（二进制，兼容所有 Content-Type）
    val startMs = System.currentTimeMillis()
    val rawBytes = try { call.receive<ByteArray>() } catch (_: Exception) { ByteArray(0) }
    // ★ var: 允许被 route 路由规则改写后的请求体覆盖（见路由规则块之后的应用点）
    var requestBodyStr = String(rawBytes, Charsets.UTF_8)

    // ★★★ 全面请求体校验：给所有错误情况返回标准400，绝不挂起 ★★★
    if (requestBodyStr.isNotBlank()) {
        try {
            val j = proxyJson.parseToJsonElement(requestBodyStr).jsonObject

            // 1️⃣ 检查空body {}：没有任何字段
            if (j.isEmpty()) {
                val (s, b) = openAIError(HttpStatusCode.BadRequest, "Request body is empty. Provide at least 'model' and 'messages'.", "invalid_request_error", 400)
                call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                return
            }

            // 2️⃣ 检查 model 字段缺失
            if (!j.containsKey("model")) {
                val (s, b) = openAIError(HttpStatusCode.BadRequest, "Missing required field 'model'. Provide a model ID (e.g. 'gpt-4').", "invalid_request_error", 400)
                call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                return
            }

            // 3️⃣ 检查 chat 请求必须有 messages 字段（有model但无messages = 非法请求）
            if (!j.containsKey("messages")) {
                val (s, b) = openAIError(HttpStatusCode.BadRequest, "Missing required field 'messages'. Provide a non-empty array of message objects.", "invalid_request_error", 400)
                call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                return
            }

            // 3️⃣ 如果包含 messages 字段，检查它的类型和内容
            if (j.containsKey("messages")) {
                val msgs = j["messages"]
                if (msgs !is JsonArray) {
                    val (s, b) = openAIError(HttpStatusCode.BadRequest, "'messages' must be a non-empty array of message objects.", "invalid_request_error", 400)
                    call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                    return
                }
                if (msgs.isEmpty()) {
                    val (s, b) = openAIError(HttpStatusCode.BadRequest, "messages array is empty. Provide at least one message.", "invalid_request_error", 400)
                    call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
                    return
                }
            }
        } catch (_: Exception) { }
    } else {
        // 4️⃣ 空body（无任何内容）
        val (s, b) = openAIError(HttpStatusCode.BadRequest, "Request body is empty. Send a valid JSON with 'model' and 'messages'.", "invalid_request_error", 400)
        call.respondText(contentType = ContentType.Application.Json, status = s, text = b)
        return
    }

    // ★★★ 全模型统计：所有请求都计上传流量（通知栏+总统计）★★★
    GatewayForegroundService.trafficUploadBytes.addAndGet(rawBytes.size.toLong())
    GatewayForegroundService.totalUploadBytes.addAndGet(rawBytes.size.toLong())

    val path = call.parameters.getAll("path")?.joinToString("/") ?: ""

    // ★★ 如果 path 为空但 body 是 JSON 且有 model 字段 → 自动转成 /v1/chat/completions
    val effectivePath = if (path.isBlank()) {
        if (requestBodyStr.isNotBlank()) {
            try {
                val j = proxyJson.parseToJsonElement(requestBodyStr).jsonObject
                if (j.containsKey("model") || j.containsKey("messages")) "chat/completions" else path
            } catch (_: Exception) { path }
        } else path
    } else path

    if (GatewayForegroundService.getDebugMode()) {
        GatewayForegroundService.addDebugLog("→ ${call.request.httpMethod.value} /$effectivePath (${rawBytes.size}B)")
        com.aigate.router.capture.PacketCapture.begin()
        com.aigate.router.capture.PacketCapture.captureIn(
            sourceIp = call.request.local.remoteHost ?: "",
            method = call.request.httpMethod.value ?: "",
            path = "/$effectivePath",
            headers = call.request.headers.entries()
                .filter { it.key in listOf("Authorization", "Content-Type", "User-Agent") }
                .joinToString("; ") { "${it.key}: ${it.value.take(40)}" },
            body = requestBodyStr,
            bodySize = rawBytes.size
        )
    }

    val isChat = effectivePath == "chat/completions" || effectivePath == "completions"

    // ★★ 更新会话活跃时间（闲置超时用）★★
    if (isChat) {
        val sessionKey = getSessionKey(call)
        updateSessionActivity(sessionKey)
    }

    // ★★★ 自定义路由规则引擎：匹配规则并执行动作 ★★★
    if (isChat && requestBodyStr.isNotBlank()) {
        try {
            val routingJson = proxyJson.parseToJsonElement(requestBodyStr).jsonObject
            val requestModelId = routingJson["model"]?.jsonPrimitive?.content ?: ""
            val authHeader = call.request.headers["Authorization"] ?: ""
            val requestApiKey = authHeader.removePrefix("Bearer ").trim()
            // 获取请求模型对应的服务商ID
            val requestProviderId = if (requestModelId.isNotBlank()) {
                com.aigate.router.routing.ModelPreference.sortStored(
                    database.aiModelDao().getEnabledModelsList()
                ).find { it.modelId == requestModelId }?.providerId ?: 0L
            } else 0L
            val matchedRule = RoutingRuleManager.matchRule(
                database = database,
                path = effectivePath,
                modelId = requestModelId,
                apiKey = requestApiKey,
                providerId = requestProviderId
            )
            if (matchedRule != null) {
                GatewayForegroundService.addDebugLog("🔀 Совпадение правила маршрутизации: ${matchedRule.name} [${matchedRule.action}]")
                when (matchedRule.action) {
                    "block" -> {
                        val blockMsg = matchedRule.blockMessage.ifBlank { "Request blocked by routing rule: ${matchedRule.name}" }
                        val blockResp = buildJsonObject {
                            put("error", buildJsonObject {
                                put("message", JsonPrimitive(blockMsg))
                                put("type", JsonPrimitive("routing_rule_blocked"))
                                put("rule", JsonPrimitive(matchedRule.name))
                            })
                        }
                        call.respondText(
                            contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8),
                            status = HttpStatusCode.Forbidden,
                            text = blockResp.toString()
                        )
                        logAccess(call, requestModelId, 403, System.currentTimeMillis() - startMs)
                        return
                    }
                    "route" -> {
                        if (matchedRule.targetModelKey.isNotBlank()) {
                            // 解析目标模型 routeKey
                            val targetModelKey = matchedRule.targetModelKey
                            val targetModel = database.aiModelDao().getEnabledModelsList().findByRouteKey(targetModelKey)
                            if (targetModel != null) {
                                // 替换请求体中的model字段
                                val modifiedBody = requestBodyStr.replace(
                                    Regex(""""model"\s*:\s*"[^"]*""""),
                                    "\"model\":\"${targetModel.modelId}\""
                                )
                                GatewayForegroundService.addDebugLog("🔀 Правило маршрутизации: $requestModelId → ${targetModel.modelId} (правило: ${matchedRule.name})")
                                // 将改写后的 body 存入 call 属性；在路由规则块之后统一应用到工作变量 requestBodyStr
                                call.attributes.put(ROUTING_MODIFIED_BODY_KEY, modifiedBody)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) { }
        // ★ 应用 route 规则改写后的请求体：让 model 改写真正生效（此前只写属性、无人读取 → route 动作是死代码）
        //   后续所有链路(工具检测/主转发/故障转移)都从 requestBodyStr 重新解析 model，因此覆盖它即可改变转发目标模型。
        call.attributes.getOrNull(ROUTING_MODIFIED_BODY_KEY)?.let { requestBodyStr = it }
    }

    // ★★ 工具指令检测：在转发前先解析并执行操作指令 ★★
    if (isChat && requestBodyStr.isNotBlank()) {
        val requestJson = try { proxyJson.parseToJsonElement(requestBodyStr).jsonObject } catch (_: Exception) { null }
        var modelId = requestJson?.get("model")?.jsonPrimitive?.content
        val stream = requestJson?.get("stream")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

        // ★★ auto没有前缀或脑子说chat → 走正常转发：用排行榜最快的模型直接透传 ★★
        if (VirtualModel.isVirtual(modelId)) {
            // 找最适合的模型
            // Порядок провайдеров внутри модели решает, кто обслужит запрос:
            // одну и ту же модель могут предоставлять несколько аккаунтов.
            val allEnabledModels = com.aigate.router.routing.ModelPreference.sortStored(
                database.aiModelDao().getEnabledModelsList().filter { it.isEnabled }
            )
            val forced = GatewayForegroundService.getForcedModel()
            // ★ 如果强制模型是auto（虚拟模型），用上一次切换的模型或排行榜
            val effectiveForced = if (VirtualModel.isVirtual(forced)) GatewayForegroundService.activeNodeName.ifBlank { null } else forced.ifBlank { null }
            // ★ 检测是否有多模态图片内容
            val hasImage = requestJson?.get("messages")?.jsonArray?.any { msg ->
                try {
                    val content = msg?.jsonObject?.get("content")
                    content is JsonArray && content.any { part ->
                        part?.jsonObject?.get("type")?.jsonPrimitive?.content == "image_url"
                    }
                } catch (_: Exception) { false }
            } ?: false
            val bestModel = if (!effectiveForced.isNullOrBlank()) {
                // 支持两种格式：完整前缀(deepseek-ai/deepseek-v4-flash) 和 短ID(deepseek-v4-flash)
                val directMatch = allEnabledModels.findByRouteKey(effectiveForced)
                if (directMatch != null) {
                    directMatch
                } else {
                    val shortId = effectiveForced.substringAfterLast('/')
                    if (shortId != effectiveForced) {
                        allEnabledModels.filter { it.modelId == shortId }.singleOrNull()
                    } else null
                }
            } else if (hasImage) {
                allEnabledModels.firstOrNull { ModelCapabilityManager.getCapabilities(it.modelId).second }
                    ?: GatewayScheduler.pipelineSortedModelKeys.firstNotNullOfOrNull { id ->
                        allEnabledModels.findByRouteKey(id)?.takeIf { ModelCapabilityManager.getCapabilities(it.modelId).second }
                    }
            } else null
            val targetModel = bestModel ?: GatewayScheduler.pipelineSortedModelKeys.firstNotNullOfOrNull { id ->
                allEnabledModels.findByRouteKey(id)
            } ?: allEnabledModels.firstOrNull()

            // ★★★ 如果仍然找不到目标模型，尝试用 activeNodeName 或任意已启用模型 ★★★
            val finalTarget = targetModel ?: run {
                if (GatewayForegroundService.activeNodeName.isNotBlank()) {
                    allEnabledModels.filter { it.modelId == GatewayForegroundService.activeNodeName }.singleOrNull()
                } else null
            } ?: allEnabledModels.firstOrNull()

            if (finalTarget != null) {
                val provider = database.providerDao().getProviderById(finalTarget.providerId)
                if (provider != null && provider.isEnabled) {
                    // ★★ 通知栏同步模型名（仅当变化时更新，避免通知栏闪烁）★★
                    if (GatewayForegroundService.activeNodeName != finalTarget.modelId) {
                        GatewayForegroundService.activeNodeName = finalTarget.modelId
                    }
                    // ★★ 设置 modelId/providerId 属性，确保 token 统计能正确记录 ★★
                    call.attributes.put(MODEL_ID_KEY, finalTarget.modelId)
                    call.attributes.put(PROVIDER_ID_KEY, finalTarget.providerId)
                    // ★★ 透传：修正参数 + 替换model字段（不注入人格，透传就是透传）★★
                    val modifiedBody = sanitizeRequestBody(requestBodyStr).replaceFirst(Regex("\"model\"\\s*:\\s*\"[^\"]+\""), "\"model\":\"${finalTarget.modelId}\"")
                    val modifiedBytes = modifiedBody.toByteArray()
                    val useProxy = finalTarget.useProxy

                    if (stream) {
                        pipeStreamResponse(call, provider, modifiedBytes, "/v1/$effectivePath", finalTarget.modelId, finalTarget.providerId, database, useProxy)
                    } else {
                        pipeNormalResponse(call, provider, modifiedBytes, "/v1/$effectivePath", database, useProxy)
                    }
                    GatewayScheduler.recordModelResult(finalTarget.modelId, finalTarget.providerId, true)
                    GatewayScheduler.recordModelUsage(finalTarget.modelId, finalTarget.providerId)
                    GatewayForegroundService.addDebugLog("🔄 ${VirtualModel.ID} проброс → ${finalTarget.modelId}")
                    return
                }
            }

            // ★★★ 所有模型都不可用，返回错误而非静默 ★★★
            val noModelResp = buildJsonObject {
                put("error", buildJsonObject {
                    put("message", JsonPrimitive("${VirtualModel.ID} пересылка без префикса не удалась: нет доступных включённых моделей"))
                    put("type", JsonPrimitive("no_available_model"))
                })
            }
            call.respondText(contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8), status = HttpStatusCode.ServiceUnavailable, text = noModelResp.toString())
            logAccess(call, VirtualModel.ID, 503, System.currentTimeMillis() - startMs)
            return
        }
    }

    if (isChat && requestBodyStr.isNotBlank()) {
        val requestJson = try { proxyJson.parseToJsonElement(requestBodyStr).jsonObject } catch (_: Exception) { null }
        var modelId = requestJson?.get("model")?.jsonPrimitive?.content
        val stream = requestJson?.get("stream")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

        if (modelId != null) {
            // ★★ 多模态支持：检测图片并自动切换视觉模型 ★★
            val hasImage = requestJson?.get("messages")?.jsonArray?.any { msg ->
                try {
                    val content = msg?.jsonObject?.get("content")
                    content is kotlinx.serialization.json.JsonArray && content.any { part ->
                        part?.jsonObject?.get("type")?.jsonPrimitive?.content == "image_url"
                    }
                } catch (_: Exception) { false }
            } ?: false
            // ★ 如果请求含图片但目标模型不支持视觉 → 就地切换为视觉模型 ★
            val effectiveModelId = if (hasImage && !VirtualModel.isVirtual(modelId)) {
                val allModels = database.aiModelDao().getEnabledModelsList().filter { it.isEnabled }
                val visionModel = allModels.firstOrNull { ModelCapabilityManager.getCapabilities(it.modelId).second }
                    ?: allModels.firstNotNullOfOrNull { m ->
                        GatewayScheduler.pipelineSortedModelKeys.firstNotNullOfOrNull { id ->
                            allModels.findByRouteKey(id)?.takeIf { ModelCapabilityManager.getCapabilities(it.modelId).second }
                        }
                    }
                if (visionModel != null && modelId != visionModel.modelId) {
                    // 记录切换日志，继续用原始modelId发起请求（用户选的）
                    // 但在转发时会自动替换model字段
                    // ★★ 通知栏同步模型名（仅当变化时更新，避免通知栏闪烁）★★
                    if (GatewayForegroundService.activeNodeName != visionModel.modelId) {
                        GatewayForegroundService.activeNodeName = visionModel.modelId
                    }
                    GatewayForegroundService.addDebugLog("👁️ Обнаружено изображение → автопереключение на зрение: $modelId → ${visionModel.modelId}")
                    visionModel.modelId
                } else modelId
            } else modelId
            modelId = effectiveModelId
            // ★ 如果model被覆盖（多模态切换），同步修改请求体中的model字段 ★
            val finalRequestBodyStr = if (modelId != (requestJson?.get("model")?.jsonPrimitive?.content ?: "")) {
                requestBodyStr.replace(Regex("\"model\":\".*?\""), "\"model\":\"${modelId}\"")
            } else requestBodyStr
            val finalRawBytes = finalRequestBodyStr.toByteArray()
            val autoFailover = GatewayForegroundService.getAutoFailover()

            // 已移除 refreshHealthCache — 每次请求都触发健康检查会导致模型一直在跑
            // Порядок провайдеров внутри модели: именно он решает, кто обслужит
            // запрошенную по имени модель и кто станет резервом при сбое.
            val allEnabled = com.aigate.router.routing.ModelPreference.sortStored(
                database.aiModelDao().getEnabledModelsList().filter { it.isEnabled }
            )
val baseAttempts: List<AiModel> = if (allEnabled.isNotEmpty()) {
                    // ★★ 自动化切换 (auto) ★★
                    if (VirtualModel.isVirtual(modelId)) {
                        val autoModelEnabled = GatewayForegroundService.getAutoModelEnabled()
                        if (!autoModelEnabled) {
                            // auto 已禁用，返回空列表
                            emptyList()
                        } else {
                            // ★★ 检查是否有手动强制切换的模型 ★★
                            val forcedModelId = GatewayForegroundService.getForcedModel()
                            if (forcedModelId.isNotBlank()) {
                                val forced = allEnabled.findByRouteKey(forcedModelId)
                                if (forced != null) {
                                    listOf(forced) // 强制只使用这个模型
                                } else {
                                    // 强制模型不存在了，回退到自动
                                    if (GatewayScheduler.pipelineSortedModelKeys.isEmpty()) {
                                        listOfNotNull(allEnabled.sortedBy { it.modelId }.firstOrNull())
                                    } else {
                                        allEnabled.orderedByRouteKeys(GatewayScheduler.pipelineSortedModelKeys).ifEmpty { allEnabled }
                                    }
                                }
                            } else if (GatewayScheduler.pipelineSortedModelKeys.isEmpty()) {
                                // 无测速数据时，智能排序
                                GatewayScheduler.smartSort(allEnabled).ifEmpty { allEnabled }
                            } else {
                                // ★★ 使用智能排序：a(当前可用)→d(历史成功)→b(历史可用)→c(失败) ★★
                                GatewayScheduler.smartSort(allEnabled).ifEmpty { allEnabled }
                            }
                        }
                    } else if (autoFailover) {
                    // ★★ 其他模型 + 故障转移开启：用户权威模式
                    val primary = allEnabled.find { it.modelId == modelId }
                    val sessionKey = getSessionKey(call)
                    val lastGoodModel = sessionModelCache[sessionKey]

                    val pipelineSorted = if (GatewayScheduler.pipelineSortedModelKeys.isNotEmpty()) {
                        allEnabled.orderedByRouteKeys(GatewayScheduler.pipelineSortedModelKeys)
                    } else {
                        allEnabled
                    }
                    // Сначала та же модель у других провайдеров (порядок внутри
                    // модели), и лишь затем другие модели: подмена модели — более
                    // грубое вмешательство, чем смена провайдера.
                    val sameModel = allEnabled.filter { it.modelId == modelId }
                    val ordered = (
                        listOfNotNull(primary) +
                            sameModel.filter { it.routeKey != primary?.routeKey } +
                            pipelineSorted.filter { it.modelId != modelId }
                        ).distinctBy { it.routeKey }

                    if (lastGoodModel != null && lastGoodModel != modelId && ordered.count { it.modelId == lastGoodModel } == 1) {
                        val rest = ordered.filter { it.modelId != lastGoodModel }
                        listOfNotNull(primary) + listOfNotNull(ordered.filter { it.modelId == lastGoodModel }.singleOrNull()) + rest.filter { it.modelId != modelId }
                    } else {
                        ordered
                    }
                } else {
                    // Резерв внутри одной модели: если её предоставляют несколько
                    // провайдеров, следующий по порядку подхватывает запрос.
                    // Имя модели не меняется, поэтому клиент получает то, что просил.
                    allEnabled.filter { it.modelId == modelId }
                }
            } else {
                emptyList()
            }

            // ★★ Resource-aware routing (Phase 13): переупорядочить кандидатов auto по стратегии ★★
            val attemptModels: List<AiModel> = if (VirtualModel.isVirtual(modelId) && baseAttempts.size > 1) {
                com.aigate.router.routing.ResourceAwareRouter.reorder(
                    database, baseAttempts, call.request.headers["X-AIGate-Workload"]
                )
            } else baseAttempts

            var lastError: String? = null
            var failCount = 0

            // ★★ 请求一来就创建实时会话（歌词式）★★
            val rawModelName = requestJson?.get("model")?.jsonPrimitive?.content ?: "unknown"
            // 提取用户消息（普通人能看懂）
            val userMsg = try {
                val msgs = requestJson?.get("messages")?.jsonArray
                if (msgs != null && msgs.isNotEmpty()) {
                    val lastUser = msgs.lastOrNull {
                        it?.jsonObject?.get("role")?.jsonPrimitive?.content == "user"
                    }
                    lastUser?.jsonObject?.get("content")?.jsonPrimitive?.content?.take(40) ?: ""
                } else ""
            } catch (_: Exception) { "" }
            val displayPreview = if (userMsg.isNotBlank()) userMsg else requestBodyStr.take(30).replace("\n", " ").trim()
            val session = LiveSession(
                modelName = rawModelName,
                requestPreview = displayPreview,
                status = "📤 Отправка",
                responsePreview = ""
            )
            GatewayForegroundService.addLiveSession(session)

            // ★★ 只试第一个模型，不通才走排行榜后续 ★★
            if (attemptModels.isNotEmpty()) {
            val primaryModel = attemptModels.first()
            val provider = database.providerDao().getProviderById(primaryModel.providerId)
            if (provider != null && provider.isEnabled) {
                try {
                    call.attributes.put(MODEL_ID_KEY, primaryModel.modelId)
                    call.attributes.put(PROVIDER_ID_KEY, primaryModel.providerId)
                    // ★★ 通知栏同步模型名（仅当变化时更新，避免通知栏闪烁）★★
                    if (GatewayForegroundService.activeNodeName != primaryModel.modelId) {
                        GatewayForegroundService.activeNodeName = primaryModel.modelId
                    }
                    GatewayScheduler.recordModelUsage(primaryModel.modelId, primaryModel.providerId)
                    val useProxy = primaryModel.useProxy

                    val sanitizedBody = sanitizeRequestBody(finalRequestBodyStr)
                    // ★★ 无前缀模式：不注入人格/记忆/技能，纯透传 ★★
                    val bodyWithPersona = sanitizedBody
                    // ★★ auto 替换模型ID ★★
                    val modifiedBody = if (VirtualModel.isVirtual(modelId) || (autoFailover && primaryModel.modelId != modelId)) {
                        bodyWithPersona.replaceFirst(Regex("\"model\"\\s*:\\s*\"[^\"]+\""), "\"model\":\"${primaryModel.modelId}\"")
                    } else bodyWithPersona
                    val modifiedBytes = modifiedBody.toByteArray()

                    if (stream) {
                        pipeStreamResponse(call, provider, modifiedBytes, "/v1/$effectivePath", primaryModel.modelId, primaryModel.providerId, database, useProxy)
                    } else {
                        pipeNormalResponse(call, provider, modifiedBytes, "/v1/$effectivePath", database, useProxy)
                    }

                    recordSessionModel(call, primaryModel.modelId)
                    GatewayScheduler.recordModelResult(primaryModel.modelId, primaryModel.providerId, true)
                    GatewayForegroundService.updateLiveSession(session.id, "📥 Ответ", "✅ Успешно")
                    return
                } catch (e: Exception) {
                    failCount++
                    lastError = "${primaryModel.modelId}: ${e.message}"
                    synchronized(GatewayScheduler.healthCache) { GatewayScheduler.healthCache[primaryModel.routeKey] = GatewayScheduler.ModelHealth(primaryModel.modelId, primaryModel.providerId, Long.MAX_VALUE, System.currentTimeMillis(), false) }
                    if (GatewayForegroundService.getDebugMode()) GatewayForegroundService.addDebugLog("✗ ${primaryModel.modelId}: ${e.message?.take(60)}")
                    // 主模型失败，继续尝试后续模型
                }
            }

            // ★★ 主模型失败后，快速遍历后续模型（不预检测，直接转发）★★
            for ((idx, matchedModel) in attemptModels.withIndex()) {
                if (idx == 0) continue // 跳过已经试过的主模型
                if (GatewayForegroundService.getDebugMode()) {
                    GatewayForegroundService.addDebugLog("↻ Переключение при сбое #${idx} → ${matchedModel.modelId}")
                }

                if (!matchedModel.isEnabled) continue
                val provider2 = database.providerDao().getProviderById(matchedModel.providerId)
                if (provider2 == null || !provider2.isEnabled) continue

                // ★★ 故障转移：不预检测，直接转发（信任已有测速+健康缓存）★★
                try {
                    call.attributes.put(MODEL_ID_KEY, matchedModel.modelId)
                    call.attributes.put(PROVIDER_ID_KEY, matchedModel.providerId)
                    // ★★ 通知栏同步模型名（仅当变化时更新，避免通知栏闪烁）★★
                    if (GatewayForegroundService.activeNodeName != matchedModel.modelId) {
                        GatewayForegroundService.activeNodeName = matchedModel.modelId
                    }
                    GatewayScheduler.recordModelUsage(matchedModel.modelId, matchedModel.providerId)
                    val useProxy = matchedModel.useProxy

                    val sanitizedBody2 = sanitizeRequestBody(requestBodyStr)
                    // ★★ 无前缀模式（故障转移时同样不注入人格/记忆）- 纯透传 ★★
                    val bodyWithPersona2 = sanitizedBody2
                    val modifiedBody2 = if (VirtualModel.isVirtual(modelId) || (autoFailover && matchedModel.modelId != modelId)) {
                        bodyWithPersona2.replaceFirst(Regex("\"model\"\\s*:\\s*\"[^\"]+\""), "\"model\":\"${matchedModel.modelId}\"")
                    } else bodyWithPersona2
                    val modifiedBytes2 = modifiedBody2.toByteArray()

                    if (stream) {
                        pipeStreamResponse(call, provider2, modifiedBytes2, "/v1/$effectivePath", matchedModel.modelId, matchedModel.providerId, database, useProxy)
                    } else {
                        pipeNormalResponse(call, provider2, modifiedBytes2, "/v1/$effectivePath", database, useProxy)
                    }

                    // ★★ 记录会话成功模型
                    recordSessionModel(call, matchedModel.modelId)
                    GatewayScheduler.recordModelResult(matchedModel.modelId, matchedModel.providerId, true)

                    // ★★ 更新会话状态为 📥 回复 ★★
                    GatewayForegroundService.updateLiveSession(session.id, "📥 Ответ", "✅ Успешно")
                    return
                } catch (e: Exception) {
                    failCount++
                    lastError = "${matchedModel.modelId}: ${e.message}"
                    synchronized(GatewayScheduler.healthCache) { GatewayScheduler.healthCache[matchedModel.routeKey] = GatewayScheduler.ModelHealth(matchedModel.modelId, matchedModel.providerId, Long.MAX_VALUE, System.currentTimeMillis(), false) }
                    GatewayScheduler.recordModelResult(matchedModel.modelId, matchedModel.providerId, false)
                    if (GatewayForegroundService.getDebugMode()) GatewayForegroundService.addDebugLog("✗ ${matchedModel.modelId}: ${e.message?.take(60)}")
                }
            } // ★★ 结束故障转移循环 ★★
            } // ★★ 结束 attemptModels 非空判断 ★★
            val errMsg = when {
                VirtualModel.isVirtual(modelId) && !GatewayForegroundService.getAutoModelEnabled() -> "🔄 Автопереключение отключено, включите на странице моделей"
                VirtualModel.isVirtual(modelId) && GatewayScheduler.pipelineSortedModelKeys.isEmpty() && allEnabled.isEmpty() -> "Нет доступных моделей, сначала добавьте провайдера и синхронизируйте модели"
                autoFailover -> "All ${failCount} models failed. Last: $lastError"
                else -> "Model '$modelId' error: $lastError"
            }
            val (status, body) = openAIError(HttpStatusCode.ServiceUnavailable, errMsg, "upstream_error")
            call.respondText(contentType = ContentType.Application.Json, status = status, text = body)
            return
        }
    }

    // 3. 非 chat 请求 → 通用转发（也要根据请求体model找对应服务商）
    val reqModelId = try {
        val j = proxyJson.parseToJsonElement(requestBodyStr).jsonObject
        j["model"]?.jsonPrimitive?.content
    } catch (_: Exception) { null }

    // ★★ 根据模型ID找对应服务商 ★★
    val nonChatProvider = if (!reqModelId.isNullOrBlank()) {
        val matchedModel = database.aiModelDao().getEnabledModelsList().find { it.modelId == reqModelId && it.isEnabled }
        if (matchedModel != null) {
            database.providerDao().getProviderById(matchedModel.providerId)
        } else null
    } else null

    val defaultProvider = nonChatProvider ?: database.providerDao().getAllProvidersList().firstOrNull { it.isEnabled }
    if (defaultProvider == null) {
        val (status, body) = openAIError(HttpStatusCode.BadRequest, "No enabled provider available for model '$reqModelId'.", "provider_error")
        call.respondText(contentType = ContentType.Application.Json, status = status, text = body)
        return
    }
    pipeNormalResponse(call, defaultProvider, rawBytes, "/v1/$effectivePath", database)
}

/**
 * 非流式转发：读取完整上游响应 → 回写客户端
 * ★★ 如果上游返回 4xx/5xx（非成功），抛异常触发故障转移
 */
private suspend fun pipeNormalResponse(
    call: ApplicationCall,
    provider: Provider,
    rawBody: ByteArray,
    path: String,
    database: AppDatabase,
    useProxy: Boolean = true
) {
    try {
        val resolvedUrl = provider.resolvedBaseUrl.trimEnd('/')
        // ★★ 使用服务商自定义 chatPath（如果设置了）★★
        val upstreamPath = if (path.contains("chat/completions") || path.contains("completions")) {
            provider.chatPath?.let { if (it.startsWith("/")) it else "/$it" } ?: path
        } else path
        // Codex говорит на Responses API, Claude — на Messages API: другой путь,
        // другое тело, другие заголовки. Перевод включаем только для чата: по
        // остальным путям (например embeddings) тела несовместимы.
        val isCodex = CodexUpstream.isCodex(provider)
        val isAnthropic = AnthropicUpstream.isAnthropic(provider)
        val isClaudeChat = isAnthropic && path.contains("completions")
        val url = when {
            isCodex -> CodexUpstream.responsesUrl(provider)
            isClaudeChat -> AnthropicUpstream.messagesUrl(provider)
            else -> resolvedUrl + upstreamPath
        }
        val pipeStartTime = System.currentTimeMillis()

        // OAuth pre-flight: обновить истекающий токен (single-flight) до чтения из кэша.
        com.aigate.router.auth.AuthRegistry.ensureFreshForProvider(database, provider)

        val outBytes = when {
            isCodex -> CodexUpstream.translateRequest(rawBody.decodeToString()).toByteArray(Charsets.UTF_8)
            isClaudeChat -> AnthropicUpstream.translateRequest(rawBody.decodeToString()).toByteArray(Charsets.UTF_8)
            else -> rawBody
        }
        val reqBody = outBytes.toRequestBody(DEFAULT_CT)
        val request = okhttp3.Request.Builder()
            .url(url)
            .post(reqBody)
            .apply {
                val k = CredentialStore.apiKeyForProvider(provider)
                if (isCodex) {
                    CodexUpstream.applyHeaders(
                        this, k,
                        com.aigate.router.auth.CodexAccount.headerAccountId(database.credentialDao().getByProvider(provider.id)?.accountId, CredentialStore.apiKeyForProvider(provider)),
                        java.util.UUID.randomUUID().toString()
                    )
                } else if (isAnthropic) {
                    AnthropicUpstream.applyHeaders(
                        this, k, AnthropicUpstream.isSubscription(provider), stream = false
                    )
                } else if (!k.isNullOrBlank()) header("Authorization", "Bearer $k")
            }
            .build()

        // ★★ 出站抓包
        if (GatewayForegroundService.getDebugMode()) {
            com.aigate.router.capture.PacketCapture.captureOut(
                targetUrl = url,
                modelId = call.proxyModelId ?: "unknown",
                headers = "Authorization: ***",
                body = rawBody.decodeToString().take(1000),
                bodySize = rawBody.size
            )
        }

        val httpClient = if (useProxy) UpstreamClient.getOkHttpClient() else UpstreamClient.getDirectClient()

        var respBytes: ByteArray = byteArrayOf()
        var contentType: String = "application/json"
        var statusCode: HttpStatusCode = HttpStatusCode.OK
        var respCode: Int = 200
        withContext(Dispatchers.IO) {
            val response = executeWithRetry(httpClient, request)
            response.use { resp ->
                respBytes = resp.body?.bytes() ?: byteArrayOf()
                // Ответ Codex приходит в формате Responses — переводим в chat.completion
                // до дальнейших проверок, иначе они увидят «пустые choices».
                if (isCodex && resp.isSuccessful && respBytes.isNotEmpty()) {
                    val raw = respBytes.decodeToString()
                    val model = call.proxyModelId ?: "codex"
                    // Codex отвечает только потоком, поэтому собираем его в
                    // единый chat.completion; на случай JSON-ответа оставлен
                    // прямой перевод.
                    respBytes = if (raw.contains("data:")) {
                        CodexUpstream.aggregateSseToCompletion(raw, model)
                    } else {
                        CodexUpstream.translateResponse(raw, model)
                    }.toByteArray(Charsets.UTF_8)
                }
                // Ответ Messages API переводим так же: клиент ждёт chat.completion.
                if (isClaudeChat && resp.isSuccessful && respBytes.isNotEmpty()) {
                    val raw = respBytes.decodeToString()
                    val model = call.proxyModelId ?: "claude"
                    respBytes = if (raw.contains("data:")) {
                        AnthropicUpstream.aggregateSseToCompletion(raw, model)
                    } else {
                        AnthropicUpstream.translateResponse(raw, model)
                    }.toByteArray(Charsets.UTF_8)
                }
                GatewayForegroundService.trafficDownloadBytes.addAndGet(respBytes.size.toLong())
                GatewayForegroundService.totalDownloadBytes.addAndGet(respBytes.size.toLong())
                contentType = if (isCodex || isClaudeChat) "application/json"
                    else resp.header("Content-Type") ?: "application/json"
                statusCode = HttpStatusCode.fromValue(resp.code)
                respCode = resp.code

                // ★★ 关键修复：上游返回 4xx/5xx，抛出异常触发故障转移！
                if (!resp.isSuccessful) {
                    val errBody = respBytes.decodeToString().take(200)
                    throw Exception("Upstream ${resp.code}: $errBody")
                }
                // ★★ 新：上游返回200但内容为空，也触发故障转移
                if (respBytes.isEmpty()) {
                    throw Exception("Upstream ${resp.code}: empty response body")
                }
                // ★★ 新：chat/completions 返回内容空白（choices为空或无content），也触发故障转移
                if (path.contains("chat/completions") || path.contains("completions")) {
                    try {
                        val respStr = respBytes.decodeToString()
                        val respJson = proxyJson.parseToJsonElement(respStr).jsonObject
                        val choices = respJson["choices"]?.jsonArray
                        if (choices == null || choices.isEmpty()) {
                            throw Exception("Upstream ${resp.code}: empty choices in response")
                        }
                        val firstChoice = choices[0]?.jsonObject
                        val msg = firstChoice?.get("message")?.jsonObject
                        val content = msg?.get("content")?.jsonPrimitive?.content
                        // ★ tool_calls / function_call 是合法响应：content 为空(null) 但携带工具调用不算失败。
                        //   仅当既无正文内容、又无 tool_calls、也无 function_call 时，才视为上游空响应触发故障转移。
                        val toolCalls = msg?.get("tool_calls") as? JsonArray
                        val hasToolCalls = toolCalls != null && toolCalls.isNotEmpty()
                        val functionCall = msg?.get("function_call")
                        val hasFunctionCall = functionCall != null && functionCall !is JsonNull
                        if (content.isNullOrBlank() && !hasToolCalls && !hasFunctionCall) {
                            throw Exception("Upstream ${resp.code}: blank content in response")
                        }
                    } catch (e: Exception) {
                        if (e.message?.startsWith("Upstream") == true) throw e
                        // JSON解析失败的不视为故障，继续
                    }
                }
            }
        }

        // 成功响应，写回客户端
        call.respondBytesWriter(contentType = ContentType.parse(contentType), status = statusCode) {
            writeFully(respBytes)
            flush()
        }

        // ★★ 记入最优模型（用真实请求耗时作为延迟参考）
        GatewayScheduler.markModelSuccess(call.proxyModelId ?: "unknown", call.proxyProviderId ?: 0L, System.currentTimeMillis() - pipeStartTime)

        // 解析 usage
        if (path.contains("chat/completions") || path.contains("completions")) {
            withContext(Dispatchers.IO) {
                try {
                    val respStr = respBytes.decodeToString()
                    val respJson = proxyJson.parseToJsonElement(respStr).jsonObject
                    val usage = respJson["usage"]?.jsonObject
                    if (usage != null && call.proxyModelId != null && call.proxyProviderId != null) {
                        val promptTokens = usage["prompt_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        val completionTokens = usage["completion_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        val totalTokens = usage["total_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        if (totalTokens > 0) {
                            database.tokenUsageDao().insert(TokenUsage(
                                providerId = call.proxyProviderId!!, modelId = call.proxyModelId!!,
                                promptTokens = promptTokens, completionTokens = completionTokens, totalTokens = totalTokens,
                                apiKeyLabel = call.apiKeyLabel
                            ))
                        }
                    }
                } catch (_: Exception) { }
            }
        }

        if (GatewayForegroundService.getDebugMode()) {
            val modelPreview = if (path.contains("chat/completions")) {
                try { "model=${proxyJson.parseToJsonElement(respBytes.decodeToString()).jsonObject["model"]?.jsonPrimitive?.content}" } catch (_: Exception) { "" }
            } else ""
            GatewayForegroundService.addDebugLog("← $respCode /v1/$path (${respBytes.size}B) $modelPreview")
            // ★★ 响应抓包
            val tokens = try {
                val usage = proxyJson.parseToJsonElement(respBytes.decodeToString()).jsonObject["usage"]?.jsonObject
                Pair(usage?.get("prompt_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                     usage?.get("completion_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0)
            } catch (_: Exception) { Pair(0, 0) }
            com.aigate.router.capture.PacketCapture.captureResp(
                httpStatus = respCode,
                elapsedMs = System.currentTimeMillis() - pipeStartTime,
                headers = "Content-Type: application/json",
                body = respBytes.decodeToString().take(1000),
                bodySize = respBytes.size,
                modelId = call.proxyModelId ?: "",
                promptTokens = tokens.first,
                completionTokens = tokens.second,
                isStream = false
            )
        }
    } catch (e: Exception) {
        if (GatewayForegroundService.getDebugMode()) GatewayForegroundService.addDebugLog("✗ ERR /v1/$path: ${e.message?.take(80)}")
        throw e
    }
}

/**
 * 流式管道直通：上游响应正文逐块转发给客户端
 * ★★ 核心修复：边读边写，不再全量缓冲，消除卡顿！
 * 读流在 IO 线程，写响应在 CIO 线程，互不阻塞
 */
/**
 * Перевод потока Codex (Responses API) в SSE формата OpenAI chat.
 *
 * Читаем построчно, каждое событие `data:` превращаем в ноль или несколько
 * чанков `chat.completion.chunk`, в конце дописываем `[DONE]`. Расход токенов
 * берём из события `response.completed`.
 */
/**
 * Стрим апстрима, который говорит не на языке chat/completions: события читаются
 * построчно и переводятся в чанки OpenAI функцией [translateEvent] — так работает
 * и Codex (Responses API), и Claude (Messages API).
 */
private suspend fun pipeTranslatedStream(
    call: ApplicationCall,
    response: okhttp3.Response,
    bodyStream: java.io.InputStream,
    modelId: String,
    providerId: Long,
    database: AppDatabase,
    apiKeyLabel: String?,
    pipeStartTime: Long,
    translateEvent: (String, String, String) -> List<String>,
) {
    val streamId = "chatcmpl-${java.util.UUID.randomUUID().toString().take(12)}"
    var promptTokens = 0
    var completionTokens = 0
    var totalTokens = 0
    var sawText = false

    call.respondBytesWriter(contentType = ContentType.Text.EventStream, status = HttpStatusCode.OK) {
        val reader = bodyStream.bufferedReader(Charsets.UTF_8)
        try {
            while (true) {
                val line = withContext(Dispatchers.IO) {
                    try { reader.readLine() } catch (_: Exception) { null }
                } ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") continue

                for (chunk in translateEvent(payload, modelId, streamId)) {
                    val frame = "data: $chunk\n\n".toByteArray(Charsets.UTF_8)
                    writeFully(frame)
                    flush()
                    GatewayForegroundService.trafficDownloadBytes.addAndGet(frame.size.toLong())
                    GatewayForegroundService.totalDownloadBytes.addAndGet(frame.size.toLong())
                    sawText = true
                    // Расход берём из финального чанка.
                    runCatching {
                        val usage = org.json.JSONObject(chunk).optJSONObject("usage") ?: return@runCatching
                        promptTokens = usage.optInt("prompt_tokens", promptTokens)
                        completionTokens = usage.optInt("completion_tokens", completionTokens)
                        totalTokens = usage.optInt("total_tokens", totalTokens)
                    }
                }
            }

            if (!sawText) {
                val errorFrame = OpenAiStreamCompat.emptyStreamErrorFrame()
                writeFully(errorFrame)
                flush()
            }
            val done = OpenAiStreamCompat.doneFrame()
            writeFully(done)
            flush()
            GatewayForegroundService.trafficDownloadBytes.addAndGet(done.size.toLong())
            GatewayForegroundService.totalDownloadBytes.addAndGet(done.size.toLong())
        } catch (e: Exception) {
            if (GatewayForegroundService.getDebugMode()) {
                GatewayForegroundService.addDebugLog("STREAM TRANSLATE ERR: ${e.message?.take(80)}")
            }
        } finally {
            withContext(Dispatchers.IO) {
                try { reader.close(); bodyStream.close(); response.close() } catch (_: Exception) { }
            }
        }
    }

    if (totalTokens > 0) {
        withContext(Dispatchers.IO) {
            runCatching {
                database.tokenUsageDao().insert(
                    TokenUsage(
                        providerId = providerId,
                        modelId = modelId,
                        promptTokens = promptTokens,
                        completionTokens = completionTokens,
                        totalTokens = totalTokens,
                        apiKeyLabel = apiKeyLabel ?: ""
                    )
                )
            }
        }
    }
    if (sawText) {
        GatewayScheduler.markModelSuccess(modelId, providerId, System.currentTimeMillis() - pipeStartTime)
    }
}

private suspend fun pipeStreamResponse(
    call: ApplicationCall,
    provider: Provider,
    rawBody: ByteArray,
    path: String,
    modelId: String,
    providerId: Long,
    database: AppDatabase,
    useProxy: Boolean = true
) {
    val pipeStartTime = System.currentTimeMillis()
    // 1. 在 IO 线程执行 HTTP 请求，获取响应流
    val resolvedUrl = provider.resolvedBaseUrl.trimEnd('/')
    // ★★ 使用服务商自定义 chatPath（如果设置了）★★
    val upstreamPath = if (path.contains("chat/completions") || path.contains("completions")) {
        provider.chatPath?.let { if (it.startsWith("/")) it else "/$it" } ?: path
    } else path
    // Codex: Responses API вместо chat/completions.
    val isCodex = CodexUpstream.isCodex(provider)
    val isAnthropic = AnthropicUpstream.isAnthropic(provider)
    val isClaudeChat = isAnthropic && path.contains("completions")
    val url = when {
        isCodex -> CodexUpstream.responsesUrl(provider)
        isClaudeChat -> AnthropicUpstream.messagesUrl(provider)
        else -> resolvedUrl + upstreamPath
    }
    val httpClient = if (useProxy) UpstreamClient.getOkHttpClient() else UpstreamClient.getDirectClient()

    // OAuth pre-flight: обновить истекающий токен (single-flight) до чтения из кэша.
    com.aigate.router.auth.AuthRegistry.ensureFreshForProvider(database, provider)
    val codexAccountId =
        if (isCodex) com.aigate.router.auth.CodexAccount.headerAccountId(database.credentialDao().getByProvider(provider.id)?.accountId, CredentialStore.apiKeyForProvider(provider)) else null

    // 在 IO 线程发起请求，拿到 response 对象（不读 body）
    val response = withContext(Dispatchers.IO) {
        try {
            val outBytes = when {
                isCodex -> CodexUpstream.translateRequest(rawBody.decodeToString()).toByteArray(Charsets.UTF_8)
                isClaudeChat -> AnthropicUpstream.translateRequest(rawBody.decodeToString()).toByteArray(Charsets.UTF_8)
                else -> rawBody
            }
            val reqBody = outBytes.toRequestBody(DEFAULT_CT)
            val request = okhttp3.Request.Builder()
                .url(url).post(reqBody)
                .apply {
                    val k = CredentialStore.apiKeyForProvider(provider)
                    if (isCodex) {
                        CodexUpstream.applyHeaders(
                            this, k, codexAccountId, java.util.UUID.randomUUID().toString()
                        )
                    } else if (isAnthropic) {
                        AnthropicUpstream.applyHeaders(
                            this, k, AnthropicUpstream.isSubscription(provider), stream = true
                        )
                    } else if (!k.isNullOrBlank()) header("Authorization", "Bearer $k")
                }
                .build()
            val resp = executeWithRetry(httpClient, request)
            resp
        } catch (e: Exception) {
            if (GatewayForegroundService.getDebugMode()) GatewayForegroundService.addDebugLog("✗ STREAM HTTP ERR: ${e.message?.take(80)}")
            throw e
        }
    }

    if (!response.isSuccessful) {
        val errBody = withContext(Dispatchers.IO) { response.body?.bytes()?.decodeToString()?.take(200) ?: "Unknown" }
        response.close()
        throw Exception("Upstream stream ${response.code}: $errBody")
    }

    val ct = response.header("Content-Type") ?: "text/event-stream"
    val respStatus = HttpStatusCode.fromValue(response.code)

    // Some OpenAI-compatible upstreams ignore stream=true and return a normal
    // JSON chat.completion.  Passing that body through with application/json
    // leaves SSE-only clients (including Hermes Agent) waiting with no text.
    if (!isCodex && path.contains("chat/completions") && !OpenAiStreamCompat.isEventStream(ct)) {
        val responseBytes = withContext(Dispatchers.IO) {
            response.use { it.body?.bytes() ?: byteArrayOf() }
        }
        if (responseBytes.isEmpty()) {
            throw Exception("Upstream stream ${response.code}: empty response body")
        }
        // У Claude нестримовый ответ — это message, а не chat.completion:
        // сначала перевод, потом упаковка в SSE.
        val jsonForSse = if (isClaudeChat) {
            AnthropicUpstream.translateResponse(responseBytes.toString(Charsets.UTF_8), modelId)
        } else responseBytes.toString(Charsets.UTF_8)
        val sseBytes = try {
            OpenAiStreamCompat.chatCompletionJsonToSse(jsonForSse)
        } catch (error: Exception) {
            throw Exception("Upstream stream ${response.code}: ${error.message}", error)
        }
        GatewayForegroundService.trafficDownloadBytes.addAndGet(sseBytes.size.toLong())
        GatewayForegroundService.totalDownloadBytes.addAndGet(sseBytes.size.toLong())
        call.respondBytesWriter(contentType = ContentType.Text.EventStream, status = respStatus) {
            writeFully(sseBytes)
            flush()
        }
        GatewayScheduler.markModelSuccess(modelId, providerId, System.currentTimeMillis() - pipeStartTime)
        return
    }

    val bodyStream = response.body?.byteStream() ?: run {
        response.close()
        throw Exception("Upstream stream ${response.code}: empty response body")
    }

    // Codex отдаёт события Responses API — переводим их в чанки chat.completion
    // построчно, чтобы клиент получал текст по мере генерации.
    if (isCodex || isClaudeChat) {
        pipeTranslatedStream(
            call = call,
            response = response,
            bodyStream = bodyStream,
            modelId = modelId,
            providerId = providerId,
            database = database,
            apiKeyLabel = call.apiKeyLabel,
            pipeStartTime = pipeStartTime,
            translateEvent = if (isCodex) CodexUpstream::translateStreamEvent
                else AnthropicUpstream::translateStreamEvent,
        )
        return
    }

    // 2. 在 CIO 线程上启动流式写，从 IO 流读取并逐块转发
    call.respondBytesWriter(contentType = ContentType.parse(ct), status = respStatus) {
        val buffer = ByteArray(4096)  // 4KB 小缓冲区，延迟最低
        val accumulatedBytes = java.io.ByteArrayOutputStream(32768)
        var bytesRead: Int

        try {
            while (true) {
                bytesRead = withContext(Dispatchers.IO) {
                    try { bodyStream.read(buffer) } catch (_: Exception) { -1 }
                }
                if (bytesRead == -1) break

                writeFully(buffer, 0, bytesRead)
                flush()
                GatewayForegroundService.trafficDownloadBytes.addAndGet(bytesRead.toLong())
                GatewayForegroundService.totalDownloadBytes.addAndGet(bytesRead.toLong())
                if (path.contains("chat/completions")) {
                    accumulatedBytes.write(buffer, 0, bytesRead)
                }
            }

            if (path.contains("chat/completions")) {
                val fullStream = accumulatedBytes.toString(Charsets.UTF_8.name())
                if (!OpenAiStreamCompat.hasDataFrame(fullStream)) {
                    val errorFrame = OpenAiStreamCompat.emptyStreamErrorFrame()
                    writeFully(errorFrame)
                    GatewayForegroundService.trafficDownloadBytes.addAndGet(errorFrame.size.toLong())
                    GatewayForegroundService.totalDownloadBytes.addAndGet(errorFrame.size.toLong())
                }
                if (!OpenAiStreamCompat.hasDoneFrame(fullStream)) {
                    val doneFrame = OpenAiStreamCompat.doneFrame()
                    writeFully(doneFrame)
                    GatewayForegroundService.trafficDownloadBytes.addAndGet(doneFrame.size.toLong())
                    GatewayForegroundService.totalDownloadBytes.addAndGet(doneFrame.size.toLong())
                }
                flush()
            }

            // 流结束后解析 usage
            if (path.contains("chat/completions")) {
                withContext(Dispatchers.IO) {
                    try {
                        val fullStr = accumulatedBytes.toString(Charsets.UTF_8.name())
                        val usageMatch = Regex(""""usage"\s*:\s*\{[^{}]+\}""").find(fullStr)
                        if (usageMatch != null) {
                            val usageStr = usageMatch.value
                            val pt = Regex(""""prompt_tokens"\s*:\s*(\d+)""").find(usageStr)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                            val ctok = Regex(""""completion_tokens"\s*:\s*(\d+)""").find(usageStr)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                            val tt = Regex(""""total_tokens"\s*:\s*(\d+)""").find(usageStr)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                            if (tt > 0) database.tokenUsageDao().insert(TokenUsage(providerId = providerId, modelId = modelId, promptTokens = pt, completionTokens = ctok, totalTokens = tt, apiKeyLabel = call.apiKeyLabel))
                        }
                    } catch (_: Exception) { }
                }
            }
        } catch (e: Exception) {
            if (GatewayForegroundService.getDebugMode()) {
                GatewayForegroundService.addDebugLog("✗ STREAM WRITE ERR: ${e.message?.take(80)}")
                com.aigate.router.capture.PacketCapture.captureResp(
                    httpStatus = response.code,
                    elapsedMs = System.currentTimeMillis() - pipeStartTime,
                    headers = "Content-Type: ${ct}",
                    body = "Stream error: ${e.message?.take(200) ?: "unknown"}",
                    bodySize = 0,
                    modelId = modelId,
                    isStream = true
                )
            }
        } finally {
            // ★★ 流式响应抓包
            if (GatewayForegroundService.getDebugMode()) {
                val totalBytes = GatewayForegroundService.trafficDownloadBytes.get()
                com.aigate.router.capture.PacketCapture.captureResp(
                    httpStatus = response.code,
                    elapsedMs = System.currentTimeMillis() - pipeStartTime,
                    headers = "Content-Type: $ct",
                    body = "[Stream: потоковый ответ, не записан]",
                    bodySize = 0,
                    modelId = modelId,
                    isStream = true
                )
            }
            withContext(Dispatchers.IO) { try { bodyStream.close(); response.close() } catch (_: Exception) { } }
        }
    }
}
