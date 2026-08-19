package com.aigate.router.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Предохранитель локального инференса: телефон должен сам отказать в счёте
 * на устройстве, когда садится батарея или растёт нагрев. Проверяются правила
 * и тексты отказов — их видит пользователь без перевода кодов в UI.
 */
class ThermalBatteryPolicyTest {

    private val cyrillic = Regex("[а-яА-ЯёЁ]")

    private fun state(
        battery: Int = 80,
        temperature: Float = 30f,
        thermal: Int = ThermalBatteryPolicy.THERMAL_NONE,
        charging: Boolean = false,
    ) = PowerState(
        batteryPercent = battery,
        batteryTemperatureC = temperature,
        thermalStatus = thermal,
        isCharging = charging,
    )

    private fun reasonOf(verdict: PowerVerdict): String {
        assertTrue("ожидался отказ, получено $verdict", verdict is PowerVerdict.Block)
        return (verdict as PowerVerdict.Block).reasonRu
    }

    private fun storage(vararg entries: Pair<String, String>): (String, String) -> String {
        val values = entries.toMap()
        return { key, default -> values[key] ?: default }
    }

    @Test
    fun `low battery blocks local inference and names both numbers`() {
        val reason = reasonOf(ThermalBatteryPolicy.evaluate(state(battery = 15), PowerLimits()))

        assertTrue("причина должна быть на русском: $reason", cyrillic.containsMatchIn(reason))
        assertTrue("нет текущего заряда: $reason", reason.contains("15%"))
        assertTrue("нет порога включения: $reason", reason.contains("20%"))
    }

    @Test
    fun `charging waiver keeps local models alive on low battery`() {
        val discharged = state(battery = 5, charging = true)

        assertEquals(
            PowerVerdict.Allow,
            ThermalBatteryPolicy.evaluate(discharged, PowerLimits(allowWhenCharging = true)),
        )
        // Разрешение действует только вместе с фактом зарядки и только по заряду.
        reasonOf(
            ThermalBatteryPolicy.evaluate(
                discharged.copy(isCharging = false),
                PowerLimits(allowWhenCharging = true),
            )
        )
        reasonOf(ThermalBatteryPolicy.evaluate(discharged, PowerLimits(allowWhenCharging = false)))
    }

    @Test
    fun `battery temperature over the limit blocks with a russian reason`() {
        val reason = reasonOf(ThermalBatteryPolicy.evaluate(state(temperature = 43.5f), PowerLimits()))

        assertTrue("причина должна быть на русском: $reason", cyrillic.containsMatchIn(reason))
        // Разделитель — запятая, один знак после неё: сообщение читает человек.
        assertTrue("нет измеренной температуры: $reason", reason.contains("43,5"))
        assertTrue("нет предела: $reason", reason.contains("42,0"))
    }

    @Test
    fun `severe thermal status blocks while moderate and unknown do not`() {
        reasonOf(
            ThermalBatteryPolicy.evaluate(state(thermal = ThermalBatteryPolicy.THERMAL_SEVERE), PowerLimits())
        )
        assertEquals(
            PowerVerdict.Allow,
            ThermalBatteryPolicy.evaluate(state(thermal = ThermalBatteryPolicy.THERMAL_MODERATE), PowerLimits()),
        )
        // Ниже API 29 сведений о тепловом режиме нет — гадать по ним нельзя.
        assertEquals(
            PowerVerdict.Allow,
            ThermalBatteryPolicy.evaluate(state(thermal = ThermalBatteryPolicy.THERMAL_UNKNOWN), PowerLimits()),
        )
    }

    @Test
    fun `throttle check can be switched off entirely`() {
        assertEquals(
            PowerVerdict.Allow,
            ThermalBatteryPolicy.evaluate(
                state(thermal = ThermalBatteryPolicy.THERMAL_SHUTDOWN),
                PowerLimits(blockOnThermalThrottle = false),
            ),
        )
    }

    @Test
    fun `hysteresis holds the block until the state has margin`() {
        val limits = PowerLimits()

        // Заряд ровно на пороге снял бы отказ и тут же вернул его на 19 % —
        // возврат разрешён только с запасом.
        reasonOf(ThermalBatteryPolicy.evaluate(state(battery = 22), limits, wasBlocked = true))
        assertEquals(
            PowerVerdict.Allow,
            ThermalBatteryPolicy.evaluate(state(battery = 23), limits, wasBlocked = true),
        )

        reasonOf(ThermalBatteryPolicy.evaluate(state(temperature = 41f), limits, wasBlocked = true))
        assertEquals(
            PowerVerdict.Allow,
            ThermalBatteryPolicy.evaluate(state(temperature = 40f), limits, wasBlocked = true),
        )

        // Без предыдущего отказа те же значения проходят: ужесточение работает
        // только на возврате.
        assertEquals(PowerVerdict.Allow, ThermalBatteryPolicy.evaluate(state(battery = 22), limits))
        assertEquals(PowerVerdict.Allow, ThermalBatteryPolicy.evaluate(state(temperature = 41f), limits))
    }

    @Test
    fun `empty storage yields the default limits`() {
        val limits = ThermalBatteryPolicy.limitsFrom { _, default -> default }

        assertEquals(PowerLimits(), limits)
    }

    @Test
    fun `broken values fall back to the defaults instead of zeroes`() {
        val limits = ThermalBatteryPolicy.limitsFrom { _, _ -> "не число" }

        assertEquals(20, limits.minBatteryPercent)
        assertEquals(42f, limits.maxTemperatureC, 0.001f)
        assertTrue("испорченный флаг не должен снимать защиту", limits.blockOnThermalThrottle)
        assertFalse(limits.allowWhenCharging)
    }

    @Test
    fun `valid storage values are read as configured`() {
        val limits = ThermalBatteryPolicy.limitsFrom(
            storage(
                ThermalBatteryPolicy.KEY_MIN_BATTERY to "35",
                ThermalBatteryPolicy.KEY_MAX_TEMP to "38.5",
                ThermalBatteryPolicy.KEY_BLOCK_ON_THROTTLE to "false",
                ThermalBatteryPolicy.KEY_ALLOW_WHEN_CHARGING to "true",
            )
        )

        assertEquals(35, limits.minBatteryPercent)
        assertEquals(38.5f, limits.maxTemperatureC, 0.001f)
        assertFalse(limits.blockOnThermalThrottle)
        assertTrue(limits.allowWhenCharging)
    }

    @Test
    fun `values outside the sane range are clamped to the borders`() {
        val tooStrict = ThermalBatteryPolicy.limitsFrom(
            storage(
                ThermalBatteryPolicy.KEY_MIN_BATTERY to "95",
                ThermalBatteryPolicy.KEY_MAX_TEMP to "12",
            )
        )
        assertEquals(90, tooStrict.minBatteryPercent)
        assertEquals(30f, tooStrict.maxTemperatureC, 0.001f)

        val tooLoose = ThermalBatteryPolicy.limitsFrom(
            storage(
                ThermalBatteryPolicy.KEY_MIN_BATTERY to "0",
                ThermalBatteryPolicy.KEY_MAX_TEMP to "150",
            )
        )
        assertEquals(5, tooLoose.minBatteryPercent)
        assertEquals(60f, tooLoose.maxTemperatureC, 0.001f)
    }

    @Test
    fun `read limits drive the verdict end to end`() {
        val limits = ThermalBatteryPolicy.limitsFrom(
            storage(ThermalBatteryPolicy.KEY_MIN_BATTERY to "50")
        )

        val reason = reasonOf(ThermalBatteryPolicy.evaluate(state(battery = 45), limits))
        assertTrue("порог должен прийти из настроек: $reason", reason.contains("50%"))
        assertEquals(PowerVerdict.Allow, ThermalBatteryPolicy.evaluate(state(battery = 55), limits))
    }
}
