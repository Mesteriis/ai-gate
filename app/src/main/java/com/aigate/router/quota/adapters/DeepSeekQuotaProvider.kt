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
 * Баланс DeepSeek: `GET /user/balance` с ключом провайдера.
 *
 * Ответ — `{"is_available":bool,"balance_infos":[{"currency":"USD",
 * "total_balance":"…","granted_balance":"…","topped_up_balance":"…"}]}`.
 * Суммы приходят строками, поэтому разбираются как строки, а не как числа.
 *
 * Это баланс, а не квота: сброса нет, лимит неизвестен (сколько было
 * пополнено изначально, провайдер не сообщает), поэтому `limit` остаётся null,
 * и UI показывает сумму на счету, а не проценты.
 */
class DeepSeekQuotaProvider(
    private val client: OkHttpClient = defaultClient()
) : RemoteQuotaProvider {

    override fun appliesTo(provider: Provider): Boolean =
        provider.baseUrl.contains("deepseek.com", ignoreCase = true)

    override suspend fun fetch(db: AppDatabase, provider: Provider, pool: ResourcePool): QuotaSnapshot? {
        val key = CredentialStore.apiKeyForProvider(provider) ?: return null
        if (key.isBlank()) return null

        val base = provider.resolvedBaseUrl.trimEnd('/').ifBlank { "https://api.deepseek.com" }
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$base/user/balance")
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
                null // сеть или разбор не удались — честный null, а не выдуманный баланс
            }
        }
    }

    /** Разбор ответа DeepSeek. Публично для юнит-тестов. */
    fun parse(body: String, poolId: Long, now: Long = System.currentTimeMillis()): QuotaSnapshot? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val infos = root.optJSONArray("balance_infos") ?: return null
        // Предпочитаем счёт в долларах; если его нет — берём первый доступный.
        var chosen: JSONObject? = null
        for (i in 0 until infos.length()) {
            val info = infos.optJSONObject(i) ?: continue
            if (info.optString("currency").equals("USD", ignoreCase = true)) {
                chosen = info
                break
            }
            if (chosen == null) chosen = info
        }
        val info = chosen ?: return null
        val total = info.optString("total_balance").toDoubleOrNull() ?: return null
        val currency = info.optString("currency").ifBlank { "USD" }

        return QuotaSnapshot(
            poolId = poolId,
            // Сколько потрачено, провайдер не сообщает: у баланса это неизвестно.
            used = null,
            remaining = total,
            limit = null,
            unit = if (currency.equals("USD", ignoreCase = true)) QuotaUnit.USD.name else currency,
            resetsAt = null, // баланс не сбрасывается по расписанию
            updatedAt = now,
            source = QuotaSource.PROVIDER_API.name,
        )
    }

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
