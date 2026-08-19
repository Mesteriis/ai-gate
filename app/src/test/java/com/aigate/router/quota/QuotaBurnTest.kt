package com.aigate.router.quota

import com.aigate.router.data.model.QuotaSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Темп расхода квоты считается из истории снимков: фиксированные доли остатка
 * непригодны, потому что 29 % у одного тарифа — сутки работы, у другого — месяц.
 */
class QuotaBurnTest {

    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    private fun snap(usedValue: Double, agoHours: Long) = QuotaSnapshot(
        poolId = 1,
        used = usedValue,
        remaining = 100.0 - usedValue,
        limit = 100.0,
        unit = "PERCENT",
        resetsAt = now + 24 * hour,
        updatedAt = now - agoHours * hour,
        source = "PROVIDER_API",
    )

    @Test
    fun `rate is computed from the last 24 hours of snapshots`() {
        // За 24 часа израсходовано 48 единиц → 2 единицы в час.
        val rate = QuotaBurn.rate(listOf(snap(10.0, 24), snap(58.0, 0)), now)
        assertNotNull(rate)
        assertEquals(2.0, rate!!.perHour, 0.01)
    }

    @Test
    fun `peak rate uses the busiest day of the month`() {
        val history = listOf(
            snap(0.0, 72), snap(24.0, 48),
            snap(24.0, 47), snap(96.0, 24),
            snap(96.0, 23), snap(120.0, 0),
        )
        val rate = QuotaBurn.rate(history, now)!!
        assertTrue("пик не может быть ниже среднего", rate.peakPerHour >= rate.perHour)
        assertEquals(3.0, rate.peakPerHour, 0.2)
    }

    @Test
    fun `no history means no rate and no guessing`() {
        assertNull(QuotaBurn.rate(emptyList(), now))
        assertNull(QuotaBurn.rate(listOf(snap(10.0, 0)), now))
    }

    @Test
    fun `zero consumption yields no rate`() {
        assertNull(QuotaBurn.rate(listOf(snap(10.0, 24), snap(10.0, 0)), now))
    }

    @Test
    fun `quota running out before reset reports the exhaustion moment`() {
        val outlook = QuotaBurn.outlook(
            remaining = 10.0,
            resetsAt = now + 24 * hour,
            rate = QuotaBurn.Rate(perHour = 2.0, peakPerHour = 4.0),
            now = now,
        )!!
        assertEquals((now + 5 * hour).toDouble(), outlook.exhaustAtMs!!.toDouble(), hour / 2.0)
        assertEquals(0.0, outlook.surplus, 0.001)
    }

    @Test
    fun `unused quota is reported as surplus instead of exhaustion`() {
        // Осталось 100, темп 1 ед/ч, до сброса 24 ч → израсходуется 24, сгорит 76.
        val outlook = QuotaBurn.outlook(
            remaining = 100.0,
            resetsAt = now + 24 * hour,
            rate = QuotaBurn.Rate(perHour = 1.0, peakPerHour = 10.0),
            now = now,
        )!!
        assertNull(outlook.exhaustAtMs)
        assertEquals(76.0, outlook.surplus, 0.001)
        assertEquals(7.6, outlook.hoursNeededAtPeak, 0.1)
    }

    @Test
    fun `reset in the past yields no outlook`() {
        assertNull(
            QuotaBurn.outlook(
                remaining = 50.0,
                resetsAt = now - hour,
                rate = QuotaBurn.Rate(1.0, 1.0),
                now = now,
            )
        )
    }

    @Test
    fun `quota reset inside the window does not produce a negative rate`() {
        // Между снимками произошёл сброс: used упал с 90 до 5.
        val history = listOf(snap(90.0, 26), snap(5.0, 2), snap(9.0, 0))
        val rate = QuotaBurn.rate(history, now)
        assertNotNull("после сброса темп считается по свежим снимкам", rate)
        assertTrue(rate!!.perHour > 0.0)
    }
}
