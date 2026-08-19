package com.aigate.router.quota.adapters

import android.util.Log
import com.aigate.router.auth.ClaudeCliAuth
import com.aigate.router.data.credential.CredentialStore
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.data.model.Provider
import com.aigate.router.data.model.QuotaSnapshot
import com.aigate.router.data.model.ResourcePool
import com.aigate.router.quota.QuotaSource
import com.aigate.router.quota.QuotaUnit
import com.aigate.router.quota.QuotaWindow
import com.aigate.router.quota.QuotaWindows
import com.aigate.router.quota.RemoteQuotaProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.GregorianCalendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Квота подписки Claude: `GET {base}/api/oauth/usage` с токеном сессии.
 *
 * Этот путь берёт и сам Claude Code (в его сборке он вызывается как
 * `fetchUtilization`), а в ответе — окна лимитов верхнего уровня: `five_hour`
 * (текущая сессия), `seven_day` (неделя по всем моделям) и отдельные недельные
 * окна по семействам моделей. У каждого окна — `utilization` и `resets_at`.
 *
 * Снимок квоты описывает САМОЕ напряжённое окно: именно оно упрётся в лимит
 * первым, и именно его сброса ждать. Сами окна сохраняются рядом
 * ([QuotaWindows]), чтобы интерфейс показал их все — у подписки Claude сессия и
 * неделя идут одновременно, и одного числа для неё мало.
 *
 * Разбор защитный: доля приходит то как 0..1, то как проценты, а момент сброса —
 * то epoch-секундами, то строкой ISO, поэтому принимаются оба варианта. Если
 * чисел нет — null и тело в лог, без выдумок.
 */
class ClaudeQuotaProvider(
    private val client: OkHttpClient = defaultClient()
) : RemoteQuotaProvider {

    override fun appliesTo(provider: Provider): Boolean =
        provider.type.equals(ClaudeCliAuth.PROVIDER_TYPE, ignoreCase = true)

    override suspend fun fetch(db: AppDatabase, provider: Provider, pool: ResourcePool): QuotaSnapshot? {
        val token = CredentialStore.apiKeyForProvider(provider)?.takeIf { it.isNotBlank() } ?: return null
        val base = provider.resolvedBaseUrl.trimEnd('/').ifBlank { ClaudeCliAuth.DEFAULT_BASE_URL }
        return withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("$base$USAGE_PATH")
                    .header("Authorization", "Bearer $token")
                    .header(ClaudeCliAuth.BETA_HEADER, ClaudeCliAuth.BETA_OAUTH)
                    .header(ClaudeCliAuth.VERSION_HEADER, ClaudeCliAuth.VERSION)
                    .header("Accept", "application/json")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful || body.isBlank()) {
                        Log.w(TAG, "usage → HTTP ${resp.code}: ${body.take(200)}")
                        return@withContext null
                    }
                    val reading = read(body, pool.id)
                    if (reading == null) {
                        Log.w(TAG, "usage: окна лимитов не найдены: ${body.take(300)}")
                        return@withContext null
                    }
                    // Окна кладём рядом со снимком: интерфейс показывает их все.
                    QuotaWindows.save(pool.id, reading.windows)
                    Log.i(TAG, "usage → окна: " + reading.windows.joinToString {
                        "${it.key}=${Math.round(it.percent)}%"
                    })
                    reading.snapshot
                }
            } catch (e: Exception) {
                Log.w(TAG, "usage failed: ${e.message}")
                null
            }
        }
    }

    /** Снимок самого напряжённого окна плюс все окна лимита. */
    data class Reading(val snapshot: QuotaSnapshot, val windows: List<QuotaWindow>)

    /** Разбор ответа `/api/oauth/usage`. Публично для юнит-тестов. */
    fun read(body: String, poolId: Long, now: Long = System.currentTimeMillis()): Reading? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val windows = WINDOWS.mapNotNull { (key, label) ->
            val win = root.optJSONObject(key) ?: return@mapNotNull null
            val percent = percentOf(win) ?: return@mapNotNull null
            QuotaWindow(key = key, label = label, percent = percent, resetsAt = resetAtOf(win, now))
        }
        // Самое напряжённое окно: оно упрётся в лимит первым.
        val worst = windows.maxByOrNull { it.percent } ?: return null
        val used = worst.percent
        val resetsAt = worst.resetsAt

        val snapshot = QuotaSnapshot(
            poolId = poolId,
            used = used,
            remaining = (100.0 - used).coerceIn(0.0, 100.0),
            limit = 100.0,
            unit = QuotaUnit.PERCENT.name,
            resetsAt = resetsAt,
            updatedAt = now,
            source = QuotaSource.PROVIDER_API.name,
        )
        return Reading(snapshot, windows)
    }

    /**
     * Доля израсходованного в процентах. Значение ≤ 1 трактуем как долю: в
     * заголовках ответов эта же величина приходит как 0..1, и умножать проценты
     * второй раз нельзя. Ровно 1 при этом читается как 1%, а не как 100% — это
     * осознанный компромисс: 100% приходит как 100.
     */
    private fun percentOf(win: JSONObject): Double? {
        val raw = listOf("utilization", "percent", "used_percent")
            .firstNotNullOfOrNull { k ->
                if (win.has(k) && !win.isNull(k)) win.optDouble(k) else null
            } ?: return null
        if (raw.isNaN()) return null
        return (if (raw > 1.0) raw else raw * 100.0).coerceIn(0.0, 100.0)
    }

    /** Момент сброса: epoch-секунды или строка ISO-8601. */
    private fun resetAtOf(win: JSONObject, now: Long): Long? {
        for (key in listOf("resets_at", "reset_at", "resets_at_ms")) {
            if (!win.has(key) || win.isNull(key)) continue
            val asNumber = win.optLong(key, 0L)
            if (asNumber > 0L) {
                // Секунды или миллисекунды — различаем по порядку величины.
                return if (asNumber > 1_000_000_000_000L) asNumber else asNumber * 1000
            }
            val asText = win.optString(key)
            if (asText.isNotBlank()) {
                val parsed = parseIsoMillis(asText)
                if (parsed != null) return parsed
                // Молчать нельзя: без срока сброса окно показывается неполным.
                Log.w(TAG, "usage: не разобран момент сброса $key=$asText")
            }
        }
        val after = listOf("resets_in_seconds", "reset_after_seconds")
            .firstNotNullOfOrNull { k -> win.optLong(k, 0L).takeIf { it > 0L } }
        return after?.let { now + it * 1000 }
    }

    companion object {
        private const val TAG = "ClaudeQuota"
        const val USAGE_PATH = "/api/oauth/usage"

        /**
         * Окна лимитов подписки: сессия на пять часов, неделя по всем моделям и
         * отдельные недельные окна по семействам. Имена — из клиента Claude Code,
         * порядок — от короткого окна к длинным (так они и показываются).
         */
        private val WINDOWS = listOf(
            "five_hour" to "5 ч",
            "seven_day" to "неделя",
            "seven_day_opus" to "неделя · Opus",
            "seven_day_sonnet" to "неделя · Sonnet",
            "seven_day_oauth_apps" to "неделя · приложения",
        )

        /**
         * Строка ISO-8601: дата и время, необязательные доли секунды любой
         * длины и зона — `Z`, `+03:00`, `+0300` или `+03`. Зона обязательна:
         * без неё момент не определён, а угадывать её мы не станем.
         */
        private val ISO_8601 = Regex(
            """(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?""" +
                """(?:([Zz])|([+-])(\d{2}):?(\d{2})?)"""
        )

        /**
         * Момент из строки ISO-8601 в epoch-миллисекундах.
         *
         * Разбор ручной, а не `java.time.Instant.parse`: тот класс появился
         * только в API 26, а minSdk у нас 24 — на Android 7 вызов бросал бы
         * `NoClassDefFoundError`, и окна лимитов молча оставались бы без срока
         * сброса. Остальной проект считает время на `java.util`, так что второй
         * набор дат (и десугаринг ради одной строки) здесь не нужен.
         *
         * Доли секунды отбрасываются: срок сброса окна измеряется часами.
         */
        private fun parseIsoMillis(text: String): Long? {
            val g = ISO_8601.matchEntire(text.trim())?.groupValues ?: return null
            val cal = GregorianCalendar(TimeZone.getTimeZone("UTC"))
            // Строгий режим: «2026-13-45» должен быть отвергнут, а не переползти в следующий год.
            cal.isLenient = false
            cal.clear()
            cal.set(
                g[1].toInt(), g[2].toInt() - 1, g[3].toInt(),
                g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt(),
            )
            val utc = runCatching { cal.timeInMillis }.getOrNull() ?: return null
            if (g[7].isNotEmpty()) return utc
            // Смещение вычитаем, чтобы получить UTC: «12:00+03:00» — это 09:00 по нулевому меридиану.
            val offset = (g[9].toInt() * 60 + g[10].ifEmpty { "0" }.toInt()) * 60_000L
            return if (g[8] == "-") utc + offset else utc - offset
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
