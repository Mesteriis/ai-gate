package com.aigate.router.quota.adapters

import android.util.Log
import com.aigate.router.data.credential.CredentialStore
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.data.model.Provider
import com.aigate.router.data.model.QuotaSnapshot
import com.aigate.router.data.model.ResourcePool
import com.aigate.router.quota.QuotaSource
import com.aigate.router.quota.QuotaUnit
import com.aigate.router.quota.RemoteQuotaProvider
import com.aigate.router.service.GatewayForegroundService
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
            val fromKey = runCatching { requestAuthKey(key, pool.id) }
                .onFailure { Log.w(TAG, "auth/key не отвечает: ${it.message}") }
                .getOrNull()

            // Лимит есть только у ключей с заданным потолком. У обычного счёта
            // его нет, и остаток по этому ответу не посчитать — тогда спрашиваем
            // баланс счёта отдельно. Раньше на этом месте показывался ноль:
            // формально это был ответ провайдера, а по сути — пустая строка.
            if (fromKey != null && fromKey.limit != null) return@withContext fromKey

            val credits = runCatching { requestCredits(key, pool.id, fromKey?.used) }
                .onFailure { Log.w(TAG, "credits не отвечают: ${it.message}") }
                .getOrNull()
            if (credits != null) return@withContext credits

            if (fromKey == null) {
                GatewayForegroundService.addDebugLog("OpenRouter: баланс не получен — ключ отклонён или сеть недоступна")
            }
            fromKey
        }
    }

    /** Лимит самого ключа: есть не у всех, зато не требует прав на счёт. */
    private fun requestAuthKey(key: String, poolId: Long): QuotaSnapshot? {
        val request = Request.Builder()
            .url(AUTH_KEY_URL)
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "auth/key → HTTP ${resp.code}: ${body.take(200)}")
                return null
            }
            return if (body.isBlank()) null else parse(body, poolId)
        }
    }

    /**
     * Баланс счёта: сколько кредитов куплено и сколько израсходовано. Это и
     * есть та цифра, которую пользователь видит в личном кабинете.
     */
    private fun requestCredits(key: String, poolId: Long, usedFallback: Double?): QuotaSnapshot? {
        val request = Request.Builder()
            .url(CREDITS_URL)
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "credits → HTTP ${resp.code}: ${body.take(200)}")
                return null
            }
            return parseCredits(body, poolId, usedFallback)
        }
    }

    /** Разбор баланса счёта. Публично для юнит-тестов. */
    fun parseCredits(
        body: String,
        poolId: Long,
        usedFallback: Double? = null,
        now: Long = System.currentTimeMillis(),
    ): QuotaSnapshot? {
        val data = runCatching { JSONObject(body).optJSONObject("data") }.getOrNull() ?: return null
        val purchased = data.optDoubleOrNull("total_credits")
        val spent = data.optDoubleOrNull("total_usage") ?: usedFallback
        if (purchased == null && spent == null) return null
        return QuotaSnapshot(
            poolId = poolId,
            used = spent,
            remaining = if (purchased != null && spent != null) (purchased - spent).coerceAtLeast(0.0) else null,
            limit = purchased,
            unit = QuotaUnit.USD.name,
            resetsAt = null, // купленные кредиты не сгорают по расписанию
            updatedAt = now,
            source = QuotaSource.PROVIDER_API.name,
        )
    }

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        if (has(name) && !isNull(name)) optDouble(name) else null

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
        private const val TAG = "OpenRouterQuota"
        const val AUTH_KEY_URL = "https://openrouter.ai/api/v1/auth/key"
        const val CREDITS_URL = "https://openrouter.ai/api/v1/credits"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
