package com.aigate.router.auth

import android.util.Base64
import com.aigate.router.service.GatewayForegroundService
import org.json.JSONObject

/**
 * Сведения об аккаунте Codex, вытащенные из самого токена сессии.
 *
 * Токены OpenAI — это JWT, и в его клеймах лежит всё нужное: идентификатор
 * аккаунта (`chatgpt_account_id`), тариф подписки (`chatgpt_plan_type`) и почта.
 * Это позволяет узнать тариф без отдельного запроса, а также получить верный
 * идентификатор аккаунта у провайдеров, подключённых старой версией приложения
 * (она сохраняла в это поле почту).
 *
 * Подпись НЕ проверяется: токен нам выдал сам провайдер, а клеймы используются
 * только для отображения и служебных заголовков.
 */
object CodexAccount {

    data class Info(
        val accountId: String?,
        val planType: String?,
        val email: String?,
    )

    /** Известные тарифы ChatGPT и их подписи в интерфейсе. */
    private val planLabels = mapOf(
        "free" to "Free",
        "plus" to "Plus",
        "pro" to "Pro",
        "team" to "Team",
        "business" to "Business",
        "enterprise" to "Enterprise",
        "edu" to "Edu",
    )

    /**
     * Ориентировочная цена тарифа в месяц (USD) — прейскурант на момент
     * написания. Значение только ПРЕДЛАГАЕТСЯ: фактическая цена зависит от
     * страны, валюты и числа мест, поэтому её можно изменить, и введённая
     * пользователем цена всегда важнее.
     */
    private val planListPriceUsd = mapOf(
        "free" to 0.0,
        "plus" to 20.0,
        "pro" to 200.0,
        "team" to 30.0,
        "business" to 30.0,
    )

    fun planLabel(planType: String?): String? {
        val key = planType?.trim()?.lowercase()?.removeSuffix("_plan") ?: return null
        if (key.isEmpty()) return null
        return planLabels[key] ?: key.replaceFirstChar { it.uppercase() }
    }

    fun listPriceUsd(planType: String?): Double? {
        val key = planType?.trim()?.lowercase()?.removeSuffix("_plan") ?: return null
        return planListPriceUsd[key]
    }

    /** Разбор клеймов JWT. Возвращает null, если это не JWT. */
    fun fromJwt(token: String?): Info? {
        if (token.isNullOrBlank() || !token.contains('.')) return null
        val claims = runCatching {
            val payload = token.split('.')[1]
            JSONObject(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING)))
        }.getOrNull() ?: return null
        return Info(
            accountId = findClaim(claims, "chatgpt_account_id"),
            planType = findClaim(claims, "chatgpt_plan_type"),
            email = findClaim(claims, "email"),
        )
    }

    /** Клейм на верхнем уровне или во вложенном объекте (namespaced-claims). */
    private fun findClaim(obj: JSONObject, key: String, depth: Int = 0): String? {
        if (depth > 3) return null
        obj.optString(key, "").takeIf { it.isNotBlank() }?.let { return it }
        val keys = obj.keys()
        while (keys.hasNext()) {
            val nested = obj.optJSONObject(keys.next()) ?: continue
            findClaim(nested, key, depth + 1)?.let { return it }
        }
        return null
    }

    /**
     * Идентификатор аккаунта для заголовка `ChatGPT-Account-ID`.
     *
     * Значение из токена приоритетнее сохранённого: старая версия писала в это
     * поле почту, а почта в заголовке аккаунта бессмысленна.
     */
    fun headerAccountId(storedAccountId: String?, token: String?): String? {
        fromJwt(token)?.accountId?.takeIf { it.isNotBlank() }?.let { return it }
        return storedAccountId?.takeIf { it.isNotBlank() && !it.contains('@') }
    }

    // ---- Тариф и его цена (в конфиге: схема БД не меняется) ----------------

    private fun planKey(providerId: Long) = "codex_plan_$providerId"
    private fun priceKey(providerId: Long) = "sub_price_$providerId"

    /** Запомнить тариф провайдера (берётся из токена при входе и синхронизации). */
    fun savePlan(providerId: Long, planType: String?) {
        val value = planType?.trim().orEmpty()
        if (value.isEmpty()) return
        GatewayForegroundService.saveGatewayConfig(planKey(providerId), value)
    }

    fun storedPlan(providerId: Long): String? =
        GatewayForegroundService.getGatewayConfig(planKey(providerId), "").takeIf { it.isNotBlank() }

    /** Цена подписки в месяц: заданная пользователем, иначе прейскурантная. */
    fun monthlyPriceUsd(providerId: Long): Double? {
        val own = GatewayForegroundService.getGatewayConfig(priceKey(providerId), "")
        own.toDoubleOrNull()?.let { return it }
        return listPriceUsd(storedPlan(providerId))
    }

    /** true, если цену задал пользователь, а не прейскурант. */
    fun isPriceUserDefined(providerId: Long): Boolean =
        GatewayForegroundService.getGatewayConfig(priceKey(providerId), "").toDoubleOrNull() != null

    fun setMonthlyPriceUsd(providerId: Long, usd: Double?) {
        GatewayForegroundService.saveGatewayConfig(
            priceKey(providerId),
            usd?.takeIf { it >= 0 }?.toString() ?: ""
        )
    }
}
