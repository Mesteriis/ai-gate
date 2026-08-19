package com.aigate.router.quota

import com.aigate.router.service.GatewayForegroundService
import org.json.JSONArray
import org.json.JSONObject

/**
 * Окна лимита одного ресурса.
 *
 * У подписки Claude их два одновременно: сессия на 5 часов и неделя. Один
 * снимок квоты хранит только одно число, поэтому окна лежат рядом — в конфиге
 * (схема базы не меняется: в ней включён destructive fallback). Снимок при этом
 * продолжает описывать САМОЕ напряжённое окно: от него считаются давление,
 * прогноз и уведомления.
 */
data class QuotaWindow(
    /** Ключ провайдера: five_hour, seven_day и т.п. */
    val key: String,
    /** Подпись для интерфейса: «5 ч», «неделя». */
    val label: String,
    /** Израсходовано, проценты 0..100. */
    val percent: Double,
    /** Момент сброса этого окна; null, если провайдер его не сообщил. */
    val resetsAt: Long?,
)

object QuotaWindows {

    private fun key(poolId: Long) = "quota_windows_$poolId"

    fun save(poolId: Long, windows: List<QuotaWindow>) {
        GatewayForegroundService.saveGatewayConfig(key(poolId), encode(windows))
    }

    fun of(poolId: Long): List<QuotaWindow> =
        decode(GatewayForegroundService.getGatewayConfig(key(poolId), ""))

    fun encode(windows: List<QuotaWindow>): String =
        JSONArray().apply {
            windows.forEach { w ->
                put(JSONObject().apply {
                    put("key", w.key)
                    put("label", w.label)
                    put("percent", w.percent)
                    w.resetsAt?.let { put("resetsAt", it) }
                })
            }
        }.toString()

    fun decode(raw: String): List<QuotaWindow> {
        if (raw.isBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val k = o.optString("key").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            QuotaWindow(
                key = k,
                label = o.optString("label").ifBlank { k },
                percent = o.optDouble("percent", 0.0),
                resetsAt = o.optLong("resetsAt", 0L).takeIf { it > 0L },
            )
        }
    }
}
