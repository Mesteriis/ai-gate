package com.aigate.router.gateway

import com.aigate.router.data.credential.CredentialStore
import com.aigate.router.service.GatewayForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 9维能力管理器 — 权威标签来源
 * 支持：tool_call / vision / thinking / audio_in / audio_out / video / image_gen / embeddings / realtime
 */
object ModelCapabilityManager {
    private const val KEY_CAPABILITIES = "capabilities_json_v2"
    private const val KEY_AUTO_DETECT = "capabilities_auto_detect"
    private val json = Json { ignoreUnknownKeys = true }

    // 能力字段缩写映射（节省存储）
    private val KEY_MAP = mapOf(
        "tool_call" to "t", "vision" to "v", "thinking" to "th",
        "audio_in" to "ai", "audio_out" to "ao", "video" to "vi",
        "image_gen" to "ig", "embeddings" to "em", "realtime" to "rt"
    )

    /** 获取模型9维能力（兼容旧 Triple 接口） */
    fun getCapabilities(modelId: String): Triple<Boolean, Boolean, Boolean> {
        val caps = loadModel(modelId)
        return Triple(caps.toolCall, caps.vision, caps.imageGen)
    }

    /** 获取完整9维能力 */
    fun getFullCapabilities(modelId: String): com.aigate.router.data.model.ModelCapabilities {
        return loadModel(modelId)
    }

    /** 合并本地标签 + 远程 /v1/models 返回的 capabilities 字段 */
    fun mergeRemoteCapabilities(modelId: String, remoteCaps: List<String>) {
        val current = loadModel(modelId)
        val merged = com.aigate.router.data.model.ModelCapabilities.fromKeys(remoteCaps)
        // 远程为权威，覆盖本地
        saveModel(modelId, merged)
    }

    /** 从本地 assets/model_capabilities.json 加载兜底标签 */
    fun loadFallback(context: android.content.Context) {
        try {
            val jsonStr = context.assets.open("model_capabilities.json").bufferedReader().readText()
            val map = json.parseToJsonElement(jsonStr).jsonObject
            val all = loadAll().toMutableMap()
            for ((modelId, capArray) in map.entries) {
                val keys = capArray.jsonArray.map { it.jsonPrimitive.content }
                all[modelId] = com.aigate.router.data.model.ModelCapabilities.fromKeys(keys)
            }
            saveAll(all)
        } catch (e: Exception) {
            // 静默
        }
    }

    /** 设置模型能力 */
    fun setCapabilities(modelId: String, toolCall: Boolean, vision: Boolean, imageGen: Boolean) {
        val caps = com.aigate.router.data.model.ModelCapabilities(toolCall = toolCall, vision = vision, imageGen = imageGen)
        saveModel(modelId, caps)
    }

    // ── 探测（后台静默） ──────────────────────────────

    fun probeModel(modelId: String, resolvedUrl: String, apiKey: String?, chatPath: String? = null) {
        val current = loadModel(modelId)
        // 简单探测工具和视觉
        var tools = current.toolCall
        var vision = current.vision
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(3000, TimeUnit.MILLISECONDS)
                .readTimeout(3000, TimeUnit.MILLISECONDS)
                .build()
            val baseUrl = resolvedUrl.trimEnd('/')
            val ct = "application/json".toMediaType()
            val path = chatPath?.let { if (it.startsWith("/")) it else "/$it" } ?: "/v1/chat/completions"
            if (!tools) {
                val body = """{"model":"$modelId","messages":[{"role":"user","content":"hi"}],"tools":[{"type":"function","function":{"name":"t","description":"t","parameters":{"type":"object","properties":{}}}}],"max_tokens":1}"""
                val resp = client.newCall(okhttp3.Request.Builder().url("$baseUrl$path").post(body.toByteArray().toRequestBody(ct)).apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }.build()).execute()
                tools = resp.code != 501 && resp.code != 404; resp.close()
            }
            if (!vision) {
                val body = """{"model":"$modelId","messages":[{"role":"user","content":[{"type":"text","text":"hi"},{"type":"image_url","image_url":{"url":"data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="}}]}],"max_tokens":1}"""
                val resp = client.newCall(okhttp3.Request.Builder().url("$baseUrl$path").post(body.toByteArray().toRequestBody(ct)).apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }.build()).execute()
                vision = resp.code != 501 && resp.code != 404 && resp.code != 400; resp.close()
            }
        } catch (_: Exception) { }
        if (tools != current.toolCall || vision != current.vision) {
            saveModel(modelId, current.copy(toolCall = tools, vision = vision))
        }
    }

    // ── 持久化 ──────────────────────────────────────────

    private fun loadModel(modelId: String): com.aigate.router.data.model.ModelCapabilities {
        val all = loadAll()
        return all[modelId] ?: com.aigate.router.data.model.ModelCapabilities()
    }

    private fun saveModel(modelId: String, caps: com.aigate.router.data.model.ModelCapabilities) {
        val all = loadAll().toMutableMap()
        all[modelId] = caps
        saveAll(all)
    }

    private fun loadAll(): Map<String, com.aigate.router.data.model.ModelCapabilities> {
        try {
            val str = GatewayForegroundService.getGatewayConfig(KEY_CAPABILITIES, "{}")
            if (str.isBlank()) return emptyMap()
            val obj = json.parseToJsonElement(str).jsonObject
            return obj.entries.associate { (k, v) ->
                val j = v.jsonObject
                k to com.aigate.router.data.model.ModelCapabilities(
                    toolCall   = j["t"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    vision     = j["v"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    thinking   = j["th"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    audioIn    = j["ai"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    audioOut   = j["ao"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    video      = j["vi"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    imageGen   = j["ig"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    embeddings = j["em"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    realtime   = j["rt"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                )
            }
        } catch (_: Exception) { return emptyMap() }
    }

    private fun saveAll(map: Map<String, com.aigate.router.data.model.ModelCapabilities>) {
        try {
            val jsonObj = buildJsonObject {
                map.forEach { (k, v) ->
                    put(k, buildJsonObject {
                        put("t", v.toolCall); put("v", v.vision); put("th", v.thinking)
                        put("ai", v.audioIn); put("ao", v.audioOut); put("vi", v.video)
                        put("ig", v.imageGen); put("em", v.embeddings); put("rt", v.realtime)
                    })
                }
            }
            GatewayForegroundService.saveGatewayConfig(KEY_CAPABILITIES, jsonObj.toString())
        } catch (_: Exception) { }
    }

    // ==================== 后台静默探针系统 ====================
    private var probeJob: kotlinx.coroutines.Job? = null

    /** ★★ 启动后台静默探针：对所有启用模型进行能力探测，不打扰用户 ★★ */
    fun startSilentProbe(database: com.aigate.router.data.db.AppDatabase, scope: kotlinx.coroutines.CoroutineScope) {
        probeJob?.cancel()
        probeJob = scope.launch {
            while (true) {
                try {
                    // 获取所有已启用的模型
                    val models = withContext(Dispatchers.IO) {
                        database.aiModelDao().getEnabledModelsList().filter { it.isEnabled }
                    }
                    val providers = withContext(Dispatchers.IO) {
                        database.providerDao().getAllProvidersOnce()
                    }
                    val providerMap = providers.associateBy { it.id }

                    for (model in models) {
                        val provider = providerMap[model.providerId] ?: continue
                        // 只探测未探全的模型
                        val (t, v, g) = getCapabilities(model.modelId)
                        if (t && v && g) continue
                        
                        probeModel(model.modelId, provider.resolvedBaseUrl, CredentialStore.apiKeyForProvider(provider), provider.chatPath)
                        kotlinx.coroutines.delay(500) // 每个模型间隔500ms，避免并发
                    }
                } catch (_: Exception) { }
                // 每次循环间隔 30 分钟
                kotlinx.coroutines.delay(30 * 60 * 1000L)
            }
        }
    }

    /** ★★ 停止后台静默探针 ★★ */
    fun stopSilentProbe() {
        probeJob?.cancel()
        probeJob = null
    }
}
