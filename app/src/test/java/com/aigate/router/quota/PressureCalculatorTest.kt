package com.aigate.router.quota

import org.junit.Assert.assertEquals
import org.junit.Test

class PressureCalculatorTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `unknown when remaining or limit missing`() {
        assertEquals(ResourcePressure.UNKNOWN, PressureCalculator.compute(null, 100.0, null, null, now))
        assertEquals(ResourcePressure.UNKNOWN, PressureCalculator.compute(50.0, null, null, null, now))
        assertEquals(ResourcePressure.UNKNOWN, PressureCalculator.compute(50.0, 0.0, null, null, now))
    }

    @Test
    fun `fraction thresholds map to levels`() {
        assertEquals(ResourcePressure.FREE, PressureCalculator.compute(80.0, 100.0, null, null, now))
        assertEquals(ResourcePressure.NORMAL, PressureCalculator.compute(30.0, 100.0, null, null, now))
        assertEquals(ResourcePressure.CONSERVE, PressureCalculator.compute(10.0, 100.0, null, null, now))
        assertEquals(ResourcePressure.CRITICAL, PressureCalculator.compute(3.0, 100.0, null, null, now))
    }

    @Test
    fun `projected exhaustion before reset escalates one level`() {
        // 30% остаток (NORMAL), но темп сожжёт остаток задолго до сброса → CONSERVE
        val resetsAt = now + 10 * 3_600_000L // через 10 часов
        val level = PressureCalculator.compute(
            remaining = 30.0, limit = 100.0, resetsAt = resetsAt,
            spendPerHour = 10.0, // 100 за 10ч >> 30 остатка
            now = now
        )
        assertEquals(ResourcePressure.CONSERVE, level)
    }

    @Test
    fun `no escalation when burn rate fits within reset window`() {
        val resetsAt = now + 10 * 3_600_000L
        val level = PressureCalculator.compute(
            remaining = 30.0, limit = 100.0, resetsAt = resetsAt,
            spendPerHour = 1.0, // 10 за 10ч < 30 остатка
            now = now
        )
        assertEquals(ResourcePressure.NORMAL, level)
    }
}
