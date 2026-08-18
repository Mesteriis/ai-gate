package com.aigate.router.notify

import com.aigate.router.quota.QuotaBurn
import com.aigate.router.quota.ResourcePoolKind
import kotlin.math.roundToLong

/**
 * Какие уведомления заслужены текущим состоянием ресурса.
 *
 * Чистая функция: ни Android, ни базы — только данные, поэтому поведение
 * проверяется тестами. Темповые триггеры не знают фиксированных процентов:
 * значимость и своевременность считаются от собственного расхода владельца.
 */
object QuotaTriggers {

    enum class Kind { LOW_QUOTA, EXHAUST_BEFORE_RESET, SURPLUS, RESET, LOW_BALANCE }

    /**
     * Во сколько раз запас времени до сброса может превышать время, нужное на
     * пиковом темпе, чтобы сообщение оставалось своевременным. Больше — рано
     * тревожить, меньше — уже не успеть.
     */
    private const val SURPLUS_WINDOW_FACTOR = 4.0

    data class Alert(val kind: Kind, val title: String, val body: String)

    data class Input(
        val poolName: String,
        val kind: ResourcePoolKind,
        val remaining: Double?,
        val limit: Double?,
        val unit: String,
        val resetsAt: Long?,
        val rate: QuotaBurn.Rate?,
        val settings: NotifyPrefs.Settings,
        val now: Long,
        /** Момент сброса, о котором уже сообщали; null — ещё не сообщали. */
        val resetSeenAt: Long?,
    )

    fun evaluate(input: Input): List<Alert> {
        // Бесплатный ресурс: ни остатка, ни сброса — уведомлять не о чем.
        if (input.kind == ResourcePoolKind.FREE) return emptyList()
        if (input.kind == ResourcePoolKind.BALANCE) return balanceAlerts(input)
        return quotaAlerts(input)
    }

    private fun balanceAlerts(input: Input): List<Alert> {
        val remaining = input.remaining ?: return emptyList()
        if (!input.settings.lowBalanceEnabled) return emptyList()
        if (remaining >= input.settings.lowBalanceUsd) return emptyList()
        return listOf(
            Alert(
                kind = Kind.LOW_BALANCE,
                title = "Баланс на исходе",
                body = "${input.poolName}: на счету ${money(remaining)}",
            )
        )
    }

    private fun quotaAlerts(input: Input): List<Alert> {
        val remaining = input.remaining ?: return emptyList()
        val limit = input.limit ?: return emptyList()
        if (limit <= 0.0) return emptyList()

        val out = mutableListOf<Alert>()
        val fraction = remaining / limit

        if (input.settings.lowQuotaEnabled && fraction < input.settings.lowQuotaFraction) {
            out += Alert(
                kind = Kind.LOW_QUOTA,
                title = "Квота на исходе",
                body = "${input.poolName}: осталось ${percent(fraction)}",
            )
        }

        val resetsAt = input.resetsAt
        val rate = input.rate
        if (resetsAt != null && rate != null) {
            QuotaBurn.outlook(remaining, resetsAt, rate, input.now)?.let { outlook ->
                val exhaustAt = outlook.exhaustAtMs
                if (exhaustAt != null && input.settings.exhaustBeforeResetEnabled) {
                    out += Alert(
                        kind = Kind.EXHAUST_BEFORE_RESET,
                        title = "Квота кончится раньше сброса",
                        body = "${input.poolName}: при нынешнем темпе — на " +
                            "${hours((resetsAt - exhaustAt) / 3_600_000.0)} раньше сброса",
                    )
                }
                if (exhaustAt == null && input.settings.surplusEnabled) {
                    surplusAlert(input, rate, outlook)?.let { out += it }
                }
            }
        }

        // Сброс: квота снова полная, и об этом сбросе ещё не сообщали.
        if (input.settings.resetEnabled && input.resetSeenAt == null && fraction > 0.99) {
            out += Alert(
                kind = Kind.RESET,
                title = "Квота обновилась",
                body = "${input.poolName}: доступно ${percent(fraction)}",
            )
        }
        return out
    }

    /**
     * Часть квоты сгорит неиспользованной. Сообщаем при двух условиях, и оба
     * вычисляются, а не задаются процентом:
     *  - значимость: сгорит больше, чем обычно уходит за [NotifyPrefs.Settings.surplusDays] суток;
     *  - своевременность: времени до сброса хватает на эту работу, но запас не
     *    больше [SURPLUS_WINDOW_FACTOR] сроков. Если нужного времени уже нет,
     *    остаток не выбрать при всём желании, и сообщение было бы шумом.
     */
    private fun surplusAlert(
        input: Input,
        rate: QuotaBurn.Rate,
        outlook: QuotaBurn.Outlook,
    ): Alert? {
        val dailyUsage = rate.perHour * 24.0
        val worthTelling = outlook.surplus >= dailyUsage * input.settings.surplusDays
        val actionable = outlook.hoursNeededAtPeak <= outlook.hoursToReset &&
            outlook.hoursToReset <= SURPLUS_WINDOW_FACTOR * outlook.hoursNeededAtPeak
        if (!worthTelling || !actionable) return null
        return Alert(
            kind = Kind.SURPLUS,
            title = "Квота сгорит неиспользованной",
            body = "${input.poolName}: сгорит ${amount(outlook.surplus, input.unit)}; " +
                "нужно ${hours(outlook.hoursNeededAtPeak)} работы, " +
                "осталось ${hours(outlook.hoursToReset)}",
        )
    }

    private fun percent(fraction: Double) = "${(fraction * 100).roundToLong()}%"

    private fun money(usd: Double) = "$" + String.format("%.2f", usd)

    private fun hours(h: Double) =
        if (h >= 24) "${(h / 24).roundToLong()} дн" else "${h.roundToLong()} ч"

    private fun amount(value: Double, unit: String) =
        if (unit.equals("PERCENT", ignoreCase = true)) "${value.roundToLong()}%"
        else value.roundToLong().toString()
}
