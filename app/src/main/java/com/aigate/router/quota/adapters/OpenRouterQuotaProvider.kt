package com.aigate.router.quota.adapters

import com.aigate.router.data.credential.CredentialStore
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.data.model.Provider
import com.aigate.router.data.model.QuotaSnapshot
import com.aigate.router.data.model.ResourcePool
import com.aigate.router.quota.QuotaSource
import com.aigate.router.quota.QuotaUnit
import com.aigate.router.quota.RemoteQuotaProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Реальный адаптер квоты для OpenRouter.
 *
 * Использует ДОКУМЕНТИРОВАННЫЙ публичный endpoint `GET /api/v1/auth/key`, который по
 * тому же Bearer-ключу возвращает `usage` (израсходовано, USD), `limit` (лимит кредитов,
 * может быть null = без лимита) и `limit_remaining`. Это честный source=PROVIDER_API.
 *
 * Матчинг по host в baseUrl (OpenRouter обычно настраивают как provider type "openai").
 */
class OpenRouterQuotaProvider(
    private val client: OkHttpClient = defaultClient()
) : RemoteQuotaProvider {

    override fun appliesTo(provider: Provider): Boolean =
        provider.baseUrl.contains("openrouter.ai", ignoreCase = true)

    override suspend fun fetch(db: AppDatabase, provider: Provider, pool: ResourcePool): QuotaSnapshot? {
        val key = CredentialStore.apiKeyForProvider(provider) ?: return null
        if (key.isBlank()) return null

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://openrouter.ai/api/v1/auth/key")
                    .header("Authorization", "Bearer $key")
                    .header("Accept", "application/json")
                    .get()
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) return@withContext null
                    parse(body, pool.id)
                }
            } catch (_: Exception) {
                null // сеть/парсинг не удались — честный null, не подделка
            }
        }
    }

    /** Разбор ответа OpenRouter в QuotaSnapshot. Публично для юнит-тестов. */
    fun parse(body: String, poolId: Long, now: Long = System.currentTimeMillis()): QuotaSnapshot? {
        val data = JSONObject(body).optJSONObject("data") ?: return null
        val used = if (data.has("usage") && !data.isNull("usage")) data.optDouble("usage") else null
        val limit = if (data.has("limit") && !data.isNull("limit")) data.optDouble("limit") else null
        val remaining = when {
            data.has("limit_remaining") && !data.isNull("limit_remaining") -> data.optDouble("limit_remaining")
            limit != null && used != null -> (limit - used).coerceAtLeast(0.0)
            else -> null
        }
        return QuotaSnapshot(
            poolId = poolId,
            used = used,
            remaining = remaining,
            limit = limit,
            unit = QuotaUnit.USD.name,
            resetsAt = null, // кредиты OpenRouter не сбрасываются периодически
            updatedAt = now,
            source = QuotaSource.PROVIDER_API.name
        )
    }

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
