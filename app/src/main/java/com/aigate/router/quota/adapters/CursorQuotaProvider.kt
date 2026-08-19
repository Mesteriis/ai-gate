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
            // Схему аутентификации перебираем, а не угадываем. Админ-API Cursor
            // документирован как basic с ключом вместо имени пользователя, но
            // на отказ он отвечает тем же «Invalid Team API Key», что и на
            // запрос вовсе без ключа, — по ответу не отличить неверный ключ от
            // неверной схемы. Порядок задан документацией: сначала basic.
            val schemes = listOf(
                "basic" to okhttp3.Credentials.basic(key, ""),
                "bearer" to "Bearer $key",
            )
            var lastFailure: String? = null
            for ((name, authorization) in schemes) {
                val snapshot = runCatching { request(base, authorization, pool, name) }
                    .onFailure { lastFailure = "$name: ${it.message}" }
                    .getOrNull()
                if (snapshot != null) {
                    Log.i(TAG, "spend получен схемой $name")
                    return@withContext snapshot
                }
            }
            // Молчать нельзя: без строки в журнале пользователь видит пустой
            // расход и не понимает, ключ ли отвергнут или сеть подвела.
            val reason = lastFailure ?: "ключ отклонён (проверьте, что это ключ Team API, а не персональный)"
            Log.w(TAG, "расход не получен: $reason")
            GatewayForegroundService.addDebugLog("Cursor: расход не получен — $reason")
            null
        }
    }

    /** Один запрос расхода. Возвращает null, если сервер отказал или ответ не тот. */
    private fun request(base: String, authorization: String, pool: ResourcePool, scheme: String): QuotaSnapshot? {
        val request = Request.Builder()
            .url("$base$SPEND_PATH")
            .header("Authorization", authorization)
            .header("Accept", "application/json")
            .post("{}".toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "spend ($scheme) → HTTP ${resp.code}: ${body.take(200)}")
                // Сообщение сервера уносим наверх: пользователю нужна причина
                // отказа дословно, а не наша догадка о ней.
                error("HTTP ${resp.code} — ${serverMessage(body)}")
            }
            return parse(body, pool.id) ?: run {
                Log.w(TAG, "spend ($scheme): неожидаемый формат ответа: ${body.take(200)}")
                null
            }
        }
    }

    /** Текст ошибки из тела ответа: у Cursor он лежит в поле message. */
    private fun serverMessage(body: String): String =
        runCatching { JSONObject(body).optString("message") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: body.take(120).ifBlank { "ответ без текста" }

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
