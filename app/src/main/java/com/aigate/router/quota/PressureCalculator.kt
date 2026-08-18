package com.aigate.router.quota

/**
 * Вычисление «давления» на ресурс — ЛОКАЛЬНАЯ рекомендация AiGate из остатка,
 * времени до сброса и недавнего темпа расхода. НЕ данные провайдера.
 */
object PressureCalculator {

    /**
     * @param remaining остаток (в тех же единицах, что и limit); null = неизвестно.
     * @param limit ёмкость; null = неизвестно.
     * @param resetsAt момент сброса (epoch ms); null = без сброса.
     * @param spendPerHour недавний темп расхода в единицах ресурса за час; null = неизвестно.
     * @param now текущее время (epoch ms), передаётся для тестируемости.
     */
    fun compute(
        remaining: Double?,
        limit: Double?,
        resetsAt: Long?,
        spendPerHour: Double?,
        now: Long
    ): ResourcePressure {
        if (remaining == null || limit == null || limit <= 0.0) return ResourcePressure.UNKNOWN

        val fraction = (remaining / limit).coerceIn(0.0, 1.0)
        var level = when {
            fraction <= 0.05 -> ResourcePressure.CRITICAL
            fraction <= 0.15 -> ResourcePressure.CONSERVE
            fraction <= 0.40 -> ResourcePressure.NORMAL
            else -> ResourcePressure.FREE
        }

        // Прогноз исчерпания до сброса → повысить уровень на одну ступень.
        if (resetsAt != null && spendPerHour != null && spendPerHour > 0.0 && remaining > 0.0) {
            val hoursToReset = (resetsAt - now).toDouble() / 3_600_000.0
            if (hoursToReset > 0) {
                val projectedSpend = spendPerHour * hoursToReset
                if (projectedSpend > remaining) {
                    level = escalate(level)
                }
            }
        }
        return level
    }

    private fun escalate(level: ResourcePressure): ResourcePressure = when (level) {
        ResourcePressure.FREE -> ResourcePressure.NORMAL
        ResourcePressure.NORMAL -> ResourcePressure.CONSERVE
        ResourcePressure.CONSERVE -> ResourcePressure.CRITICAL
        else -> level
    }
}
