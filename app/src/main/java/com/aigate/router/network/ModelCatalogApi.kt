package com.aigate.router.network

import android.util.Log
import com.aigate.router.data.model.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Список моделей спрашиваем У ПРОВАЙДЕРА, а не задаём в приложении: захардкоженные
 * перечни расходятся с реальностью, и запросы к несуществующим моделям падают.
 *
 * У каждого семейства свой контракт:
 *  - OpenAI-совместимые и Ollama: `GET /v1/models`, Bearer, ответ `{data:[{id}]}`
 *  - Anthropic: `GET /v1/models`, заголовки `x-api-key` + `anthropic-version`,
 *    ответ `{data:[{id, display_name}]}`
 *  - Google Gemini: `GET /v1beta/models?key=…`, ответ `{models:[{name, displayName,
 *    supportedGenerationMethods}]}` — берём только те, что умеют generateContent.
 *
 * Codex сюда не входит: у него отдельный бэкенд (см. `auth/CodexModelsApi`).
 */
object ModelCatalogApi {

    private const val TAG = "ModelCatalogApi"

    data class RemoteModelInfo(
        val id: String,
        val displayName: String,
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** Семейство провайдера — от него зависят путь, авторизация и разбор ответа. */
    enum class Family { OPENAI_COMPATIBLE, ANTHROPIC, GEMINI }

    fun familyOf(provider: Provider): Family {
        val t = provider.type.lowercase()
        return when {
            t.contains("anthropic") || t.contains("claude") -> Family.ANTHROPIC
            t.contains("gemini") || t.contains("google") -> Family.GEMINI
            else -> Family.OPENAI_COMPATIBLE
        }
    }

    /**
     * Запросить модели. Возвращает null, если провайдер список не отдал —
     * прежние модели в этом случае остаются нетронутыми.
     */
    suspend fun fetch(provider: Provider, apiKey: String?): List<RemoteModelInfo>? =
        withContext(Dispatchers.IO) {
            val base = provider.resolvedBaseUrl.trimEnd('/')
            if (base.isBlank()) return@withContext null
            val family = familyOf(provider)
            val url = when (family) {
                Family.ANTHROPIC -> "$base/v1/models"
                Family.GEMINI -> "$base/v1beta/models" +
                    (apiKey?.takeIf { it.isNotBlank() }?.let { "?key=$it" } ?: "")
                Family.OPENAI_COMPATIBLE -> "$base/v1/models"
            }

            val builder = Request.Builder().url(url).get().header("Accept", "application/json")
            when (family) {
                Family.ANTHROPIC -> {
                    apiKey?.takeIf { it.isNotBlank() }?.let { builder.header("x-api-key", it) }
                    builder.header("anthropic-version", ANTHROPIC_VERSION)
                }
                // Ключ Gemini уходит в query — в заголовке он не принимается.
                Family.GEMINI -> Unit
                Family.OPENAI_COMPATIBLE ->
                    apiKey?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
            }

            runCatching {
                client.newCall(builder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "models ${provider.type} → HTTP ${resp.code}")
                        return@use null
                    }
                    val body = resp.body?.string().orEmpty()
                    parse(body, family).takeIf { it.isNotEmpty() }
                }
            }.onFailure { Log.w(TAG, "models ${provider.type} failed: ${it.message}") }.getOrNull()
        }

    internal fun parse(body: String, family: Family): List<RemoteModelInfo> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        return when (family) {
            Family.GEMINI -> {
                val arr = root.optJSONArray("models") ?: return emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val m = arr.optJSONObject(i) ?: return@mapNotNull null
                    // Только модели, умеющие генерацию контента: эмбеддинги в чат не годятся.
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    val supportsChat = methods == null || (0 until methods.length())
                        .any { methods.optString(it).equals("generateContent", ignoreCase = true) }
                    if (!supportsChat) return@mapNotNull null
                    val id = m.optString("name").removePrefix("models/")
                    if (id.isBlank()) null
                    else RemoteModelInfo(id, m.optString("displayName").ifBlank { id })
                }
            }

            Family.ANTHROPIC, Family.OPENAI_COMPATIBLE -> {
                val arr = root.optJSONArray("data") ?: root.optJSONArray("models")
                    ?: return emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val m = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = m.optString("id").ifBlank { m.optString("name") }
                    if (id.isBlank()) null
                    else RemoteModelInfo(id, m.optString("display_name").ifBlank { id })
                }
            }
        }
    }

    private const val ANTHROPIC_VERSION = "2023-06-01"
}
