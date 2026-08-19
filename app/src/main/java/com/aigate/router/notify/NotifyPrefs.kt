package com.aigate.router.notify

import com.aigate.router.quota.ResourcePoolKind
import com.aigate.router.service.GatewayForegroundService

/**
 * Настройки уведомлений на каждый ресурс отдельно.
 *
 * Хранятся в конфиге, а не в таблице: база собрана с destructive fallback, и
 * добавление колонок стёрло бы пользовательские данные.
 */
object NotifyPrefs {

    data class Settings(
        val lowQuotaEnabled: Boolean,
        /** Доля остатка (0..1), ниже которой уведомляем. */
        val lowQuotaFraction: Double,
        val exhaustBeforeResetEnabled: Boolean,
        val surplusEnabled: Boolean,
        /** Сколько суток обычного расхода должно сгореть, чтобы сообщить. */
        val surplusDays: Double,
        val resetEnabled: Boolean,
        val lowBalanceEnabled: Boolean,
        val lowBalanceUsd: Double,
    )

    /** Значения по умолчанию зависят от типа ресурса: у баланса нет сброса, у бесплатного — ничего. */
    fun defaultsFor(kind: ResourcePoolKind): Settings = when (kind) {
        ResourcePoolKind.QUOTA, ResourcePoolKind.BUDGET -> Settings(
            lowQuotaEnabled = true,
            lowQuotaFraction = 0.15,
            exhaustBeforeResetEnabled = true,
            surplusEnabled = true,
            surplusDays = 1.0,
            resetEnabled = true,
            lowBalanceEnabled = false,
            lowBalanceUsd = 5.0,
        )
        ResourcePoolKind.BALANCE -> Settings(
            lowQuotaEnabled = false,
            lowQuotaFraction = 0.15,
            exhaustBeforeResetEnabled = false,
            surplusEnabled = false,
            surplusDays = 1.0,
            resetEnabled = false,
            lowBalanceEnabled = true,
            lowBalanceUsd = 5.0,
        )
        ResourcePoolKind.FREE -> Settings(
            lowQuotaEnabled = false,
            lowQuotaFraction = 0.15,
            exhaustBeforeResetEnabled = false,
            surplusEnabled = false,
            surplusDays = 1.0,
            resetEnabled = false,
            lowBalanceEnabled = false,
            lowBalanceUsd = 5.0,
        )
    }

    private fun key(poolId: Long, name: String) = "notify_${poolId}_$name"

    private fun flag(poolId: Long, name: String, fallback: Boolean): Boolean =
        when (GatewayForegroundService.getGatewayConfig(key(poolId, name), "")) {
            "true" -> true
            "false" -> false
            else -> fallback
        }

    private fun number(poolId: Long, name: String, fallback: Double): Double =
        GatewayForegroundService.getGatewayConfig(key(poolId, name), "").toDoubleOrNull() ?: fallback

    fun load(poolId: Long, kind: ResourcePoolKind): Settings {
        val d = defaultsFor(kind)
        return d.copy(
            lowQuotaEnabled = flag(poolId, "low", d.lowQuotaEnabled),
            lowQuotaFraction = number(poolId, "low_fraction", d.lowQuotaFraction),
            exhaustBeforeResetEnabled = flag(poolId, "exhaust", d.exhaustBeforeResetEnabled),
            surplusEnabled = flag(poolId, "surplus", d.surplusEnabled),
            surplusDays = number(poolId, "surplus_days", d.surplusDays),
            resetEnabled = flag(poolId, "reset", d.resetEnabled),
            lowBalanceEnabled = flag(poolId, "balance", d.lowBalanceEnabled),
            lowBalanceUsd = number(poolId, "balance_usd", d.lowBalanceUsd),
        )
    }

    fun save(poolId: Long, settings: Settings) {
        fun put(name: String, value: String) =
            GatewayForegroundService.saveGatewayConfig(key(poolId, name), value)
        put("low", settings.lowQuotaEnabled.toString())
        put("low_fraction", settings.lowQuotaFraction.toString())
        put("exhaust", settings.exhaustBeforeResetEnabled.toString())
        put("surplus", settings.surplusEnabled.toString())
        put("surplus_days", settings.surplusDays.toString())
        put("reset", settings.resetEnabled.toString())
        put("balance", settings.lowBalanceEnabled.toString())
        put("balance_usd", settings.lowBalanceUsd.toString())
    }

    // ---- Однократность: одно уведомление на цикл -------------------------

    private val TRIGGERS = listOf("low", "exhaust", "surplus", "reset", "balance")

    fun sentAt(poolId: Long, trigger: String): Long? =
        GatewayForegroundService.getGatewayConfig(key(poolId, "sent_$trigger"), "").toLongOrNull()

    fun markSent(poolId: Long, trigger: String, at: Long) {
        GatewayForegroundService.saveGatewayConfig(key(poolId, "sent_$trigger"), at.toString())
    }

    /** Квота сброшена — прошлые уведомления этого цикла больше не в счёт. */
    fun clearSent(poolId: Long) {
        TRIGGERS.forEach {
            GatewayForegroundService.saveGatewayConfig(key(poolId, "sent_$it"), "")
        }
    }

    /** Момент сброса, о котором уже сообщали. */
    fun resetSeenAt(poolId: Long): Long? =
        GatewayForegroundService.getGatewayConfig(key(poolId, "reset_seen"), "").toLongOrNull()

    fun markResetSeen(poolId: Long, resetsAt: Long) {
        GatewayForegroundService.saveGatewayConfig(key(poolId, "reset_seen"), resetsAt.toString())
    }
}
