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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Расход Cursor: админ-API `POST {base}/teams/spend`.
 *
 * Ключ админ-API передаётся basic-аутентификацией (ключ — имя пользователя,
 * пароль пустой). В ответе — расход участников в центах, override лимита в
 * долларах и начало платёжного цикла:
 * `{"teamMemberSpend":[{"spendCents":…,"hardLimitOverrideDollars":…}],
 *   "subscriptionCycleStart":<epoch ms>}`.
 *
 * Cursor не даёт публичного чат-API, поэтому такой провайдер не обслуживает
 * запросы — он нужен только для строки расхода в «Ресурсах».
 *
 * Ответ разбирается защитно: формат админ-API не зафиксирован публичной
 * спецификацией, поэтому при неожидаемой структуре адаптер возвращает null и
 * ПИШЕТ первые символы тела в лог — без этого причину молчания не найти.
 */
class CursorQuotaProvider(
    private val client: OkHttpClient = defaultClient()
) : RemoteQuotaProvider {

    override fun appliesTo(provider: Provider): Boolean =
        provider.baseUrl.contains("cursor.com", ignoreCase = true) ||
            provider.type.contains("cursor", ignoreCase = true)

    override suspend fun fetch(db: AppDatabase, provider: Provider, pool: ResourcePool): QuotaSnapshot? {
        val key = CredentialStore.apiKeyForProvider(provider)?.takeIf { it.isNotBlank() } ?: return null
        val base = provider.resolvedBaseUrl.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$base$SPEND_PATH")
                    // Basic: ключ вместо имени пользователя, пароль пустой.
                    .header("Authorization", okhttp3.Credentials.basic(key, ""))
                    .header("Accept", "application/json")
                    .post("{}".toRequestBody(JSON))
                    .build()
                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "spend → HTTP ${resp.code}: ${body.take(200)}")
                        return@withContext null
                    }
                    parse(body, pool.id) ?: run {
                        Log.w(TAG, "spend: неожидаемый формат ответа: ${body.take(200)}")
                        null
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "spend failed: ${e.message}")
                null // честный null, а не выдуманный расход
            }
        }
    }

    /** Разбор ответа админ-API. Публично для юнит-тестов. */
    fun parse(body: String, poolId: Long, now: Long = System.currentTimeMillis()): QuotaSnapshot? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val members = root.optJSONArray("teamMemberSpend") ?: return null

        var spentCents = 0.0
        var limitDollars = 0.0
        for (i in 0 until members.length()) {
            val m = members.optJSONObject(i) ?: continue
            spentCents += m.optDouble("spendCents", 0.0)
            limitDollars += m.optDouble("hardLimitOverrideDollars", 0.0)
        }

        val used = spentCents / 100.0
        // Лимит есть только если он реально задан: ноль означает «не задан».
        val limit = limitDollars.takeIf { it > 0.0 }
        val cycleStart = root.optLong("subscriptionCycleStart", 0L).takeIf { it > 0L }

        return QuotaSnapshot(
            poolId = poolId,
            used = used,
            remaining = limit?.let { (it - used).coerceAtLeast(0.0) },
            limit = limit,
            unit = QuotaUnit.USD.name,
            resetsAt = cycleStart?.let { nextCycleAfter(it, now) },
            updatedAt = now,
            source = QuotaSource.PROVIDER_API.name,
        )
    }

    /**
     * Следующий сброс: цикл Cursor месячный, поэтому от его начала шагаем на
     * месяц вперёд, пока дата не окажется в будущем. Фиксированные 30 дней дали
     * бы сдвиг на месяцах разной длины.
     */
    private fun nextCycleAfter(cycleStartMs: Long, now: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = cycleStartMs }
        var guard = 0
        while (cal.timeInMillis <= now && guard < MAX_CYCLE_STEPS) {
            cal.add(Calendar.MONTH, 1)
            guard++
        }
        return cal.timeInMillis
    }

    companion object {
        private const val TAG = "CursorQuota"
        const val DEFAULT_BASE_URL = "https://api.cursor.com"
        const val SPEND_PATH = "/teams/spend"
        /** Защита от бесконечного шага при заведомо неверной дате цикла. */
        private const val MAX_CYCLE_STEPS = 600
        private val JSON = "application/json".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
