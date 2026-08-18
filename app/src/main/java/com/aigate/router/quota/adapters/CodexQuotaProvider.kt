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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Квота Codex (ChatGPT-подписка) через бэкенд ChatGPT с OAuth-токеном сессии.
 *
 * Публичного документированного endpoint остатка нет, поэтому адаптер пробует
 * несколько кандидатов и разбирает ответ защитно. Если реальных чисел нет — возвращает
 * null (в UI «недоступно»), НЕ выдумывая остаток. Ответы логируются (без токена), чтобы
 * уточнить формат по факту.
 */
class CodexQuotaProvider(
    private val client: OkHttpClient = defaultClient()
) : RemoteQuotaProvider {

    override fun appliesTo(provider: Provider): Boolean =
        provider.type.equals("codex", ignoreCase = true)

    override suspend fun fetch(db: AppDatabase, provider: Provider, pool: ResourcePool): QuotaSnapshot? {
        val token = CredentialStore.apiKeyForProvider(provider) ?: return null
        if (token.isBlank()) return null
        return withContext(Dispatchers.IO) {
            for (url in CANDIDATES) {
                val snap = runCatching { probe(url, token, pool.id) }.getOrNull()
                if (snap != null) return@withContext snap
            }
            null
        }
    }

    private fun probe(url: String, token: String, poolId: Long): QuotaSnapshot? {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("User-Agent", "codex_cli_rs/0.0 (AiGate)")
            .header("originator", "codex_cli_rs")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful || body.isBlank()) {
                Log.i(TAG, "usage probe → HTTP ${resp.code}")
                return null
            }
            val snap = parseQuota(body, poolId)
            Log.i(TAG, "usage probe → HTTP ${resp.code}, parsed=${snap != null} used=${snap?.used}")
            return snap
        }
    }

    /**
     * Разбор ответа `/backend-api/codex/usage`. Реальный формат:
     * `{ plan_type, rate_limit: { primary_window: { used_percent, reset_after_seconds, reset_at(sec) }, secondary_window } }`.
     * Возвращает снимок в PERCENT (used/remaining) с моментом сброса; null, если чисел нет.
     */
    fun parseQuota(body: String, poolId: Long, now: Long = System.currentTimeMillis()): QuotaSnapshot? {
        val root = try { JSONObject(body) } catch (_: Exception) { return null }
        val rl = root.optJSONObject("rate_limit") ?: return null
        // основное окно приоритетнее; если его нет — вторичное
        val win = rl.optJSONObject("primary_window")?.takeIf { it.has("used_percent") }
            ?: rl.optJSONObject("secondary_window")?.takeIf { it.has("used_percent") }
            ?: return null
        if (win.isNull("used_percent")) return null
        val usedPercent = win.optDouble("used_percent")
        val remaining = (100.0 - usedPercent).coerceIn(0.0, 100.0)
        val resetAtSec = if (win.has("reset_at") && !win.isNull("reset_at")) win.optLong("reset_at") else null
        val resetAfter = if (win.has("reset_after_seconds") && !win.isNull("reset_after_seconds")) win.optLong("reset_after_seconds") else null
        val resetsAt = when {
            resetAtSec != null && resetAtSec > 0 -> resetAtSec * 1000
            resetAfter != null && resetAfter > 0 -> now + resetAfter * 1000
            else -> null
        }
        return QuotaSnapshot(
            poolId = poolId,
            used = usedPercent,
            remaining = remaining,
            limit = 100.0,
            unit = QuotaUnit.PERCENT.name,
            resetsAt = resetsAt,
            updatedAt = now,
            source = QuotaSource.PROVIDER_API.name
        )
    }

    companion object {
        private const val TAG = "CodexQuota"
        private val CANDIDATES = listOf(
            "https://chatgpt.com/backend-api/codex/usage"
        )
        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
