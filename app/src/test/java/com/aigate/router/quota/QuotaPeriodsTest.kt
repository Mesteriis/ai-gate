package com.aigate.router.quota

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Границы расчётного периода. Раньше они были спрятаны внутри пересчёта квот,
 * из-за чего расход по данным поставщика пришлось бы считать по своим границам —
 * и два числа на одном экране разошлись бы.
 */
class QuotaPeriodsTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        Calendar.getInstance(TimeZone.getDefault()).apply {
            clear()
            set(year, month - 1, day, hour, 0, 0)
        }.timeInMillis

    private fun describe(ms: Long): String =
        Calendar.getInstance().apply { timeInMillis = ms }.let {
            "%04d-%02d-%02d %02d:%02d".format(
                it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH),
                it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE),
            )
        }

    @Test
    fun `период начинается в день сброса текущего месяца`() {
        assertEquals(
            "2026-08-05 00:00",
            describe(QuotaPeriods.periodStart(at(2026, 8, 20), resetDay = 5)),
        )
    }

    @Test
    fun `до дня сброса период начался в прошлом месяце`() {
        assertEquals(
            "2026-07-25 00:00",
            describe(QuotaPeriods.periodStart(at(2026, 8, 3), resetDay = 25)),
        )
    }

    @Test
    fun `переход через границу года считается назад корректно`() {
        assertEquals(
            "2025-12-15 00:00",
            describe(QuotaPeriods.periodStart(at(2026, 1, 3), resetDay = 15)),
        )
    }

    @Test
    fun `следующий сброс всегда в будущем`() {
        assertEquals(
            "2026-08-25 00:00",
            describe(QuotaPeriods.nextReset(at(2026, 8, 20), resetDay = 25)),
        )
        // День сброса уже прошёл — ждём следующий месяц.
        assertEquals(
            "2026-09-05 00:00",
            describe(QuotaPeriods.nextReset(at(2026, 8, 20), resetDay = 5)),
        )
    }

    @Test
    fun `день сброса зажат до 28-го`() {
        // 29, 30 и 31 есть не в каждом месяце: без зажима период уезжал бы
        // в феврале на другую дату.
        assertEquals(
            "2026-08-28 00:00",
            describe(QuotaPeriods.periodStart(at(2026, 8, 30), resetDay = 31)),
        )
        assertEquals(
            "2026-08-01 00:00",
            describe(QuotaPeriods.periodStart(at(2026, 8, 30), resetDay = 0)),
        )
    }

    @Test
    fun `не заданный день сброса считается первым числом`() {
        assertEquals(
            "2026-08-01 00:00",
            describe(QuotaPeriods.periodStart(at(2026, 8, 20), resetDay = null)),
        )
    }
}
