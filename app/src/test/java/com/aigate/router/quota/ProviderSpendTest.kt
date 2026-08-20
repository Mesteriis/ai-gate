package com.aigate.router.quota

import com.aigate.router.data.model.QuotaSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Расход за период по данным поставщика.
 *
 * Локальный подсчёт видит только запросы через шлюз, поэтому потребление в обход
 * шлюза для него невидимо. Счётчики поставщика накопительные, значит расход за
 * период — это сумма их приростов между снимками, и пропущенный опрос ничего не
 * теряет: следующая разность поглощает разрыв.
 */
class ProviderSpendTest {

    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L
    private val from = now - 24 * hour

    private fun snap(
        used: Double? = null,
        remaining: Double? = null,
        agoHours: Long,
        unit: String = "USD",
        source: String = "PROVIDER_API",
    ) = QuotaSnapshot(
        poolId = 1,
        used = used,
        remaining = remaining,
        limit = null,
        unit = unit,
        resetsAt = null,
        updatedAt = now - agoHours * hour,
        source = source,
    )

    @Test
    fun `расход это сумма приростов счётчика поставщика`() {
        val spend = ProviderSpend.periodSpend(
            listOf(snap(used = 10.0, agoHours = 20), snap(used = 14.0, agoHours = 10), snap(used = 21.0, agoHours = 0)),
            from, now,
        )
        assertNotNull(spend)
        assertEquals(11.0, spend!!.amount, 0.0001)
        assertEquals("USD", spend.unit)
        assertEquals(3, spend.points)
    }

    @Test
    fun `снимок до начала периода задаёт базу отсчёта`() {
        // Точка перед окном нужна, иначе расход между ней и первой точкой внутри
        // окна потерялся бы.
        val spend = ProviderSpend.periodSpend(
            listOf(snap(used = 10.0, agoHours = 30), snap(used = 25.0, agoHours = 2)),
            from, now,
        )!!
        assertEquals(15.0, spend.amount, 0.0001)
        assertEquals("покрытие считается от начала периода", from, spend.coveredFromMs)
    }

    @Test
    fun `без точки до периода покрытие начинается с первого снимка`() {
        val firstAt = now - 20 * hour
        val spend = ProviderSpend.periodSpend(
            listOf(snap(used = 10.0, agoHours = 20), snap(used = 18.0, agoHours = 1)),
            from, now,
        )!!
        assertEquals(8.0, spend.amount, 0.0001)
        assertEquals(firstAt, spend.coveredFromMs)
    }

    @Test
    fun `сброс у поставщика не даёт отрицательного расхода`() {
        // Счётчик упал с 90 до 5 — это сброс периода, а не возврат средств.
        val spend = ProviderSpend.periodSpend(
            listOf(snap(used = 80.0, agoHours = 20), snap(used = 90.0, agoHours = 12), snap(used = 5.0, agoHours = 6), snap(used = 9.0, agoHours = 0)),
            from, now,
        )!!
        assertEquals("10 до сброса плюс 4 после", 14.0, spend.amount, 0.0001)
    }

    @Test
    fun `локальные снимки в ряд поставщика не попадают`() {
        // Локальная оценка не знает о расходе мимо шлюза: смешивать её с данными
        // поставщика значит считать один и тот же период двумя разными мерками.
        val spend = ProviderSpend.periodSpend(
            listOf(
                snap(used = 10.0, agoHours = 20),
                snap(used = 0.0, agoHours = 15, source = "LOCAL_USAGE"),
                snap(used = 18.0, agoHours = 0),
            ),
            from, now,
        )!!
        assertEquals(8.0, spend.amount, 0.0001)
        assertEquals(2, spend.points)
    }

    @Test
    fun `единица берётся от последнего снимка, чужие отбрасываются`() {
        val spend = ProviderSpend.periodSpend(
            listOf(
                snap(used = 5.0, agoHours = 20, unit = "USD"),
                snap(used = 10.0, agoHours = 10, unit = "PERCENT"),
                snap(used = 30.0, agoHours = 0, unit = "PERCENT"),
            ),
            from, now,
        )!!
        assertEquals("PERCENT", spend.unit)
        assertEquals(20.0, spend.amount, 0.0001)
    }

    @Test
    fun `когда известен только остаток, расход считается по его убыли`() {
        // DeepSeek отдаёт баланс без израсходованного.
        val spend = ProviderSpend.periodSpend(
            listOf(snap(remaining = 20.0, agoHours = 20), snap(remaining = 15.5, agoHours = 0)),
            from, now,
        )!!
        assertEquals(4.5, spend.amount, 0.0001)
    }

    @Test
    fun `пополнение счёта не выглядит отрицательным расходом`() {
        val spend = ProviderSpend.periodSpend(
            listOf(
                snap(remaining = 20.0, agoHours = 20),
                snap(remaining = 15.0, agoHours = 12),
                snap(remaining = 50.0, agoHours = 6),
                snap(remaining = 48.0, agoHours = 0),
            ),
            from, now,
        )!!
        assertEquals("5 до пополнения плюс 2 после", 7.0, spend.amount, 0.0001)
    }

    @Test
    fun `одной точки для разности недостаточно`() {
        assertNull(ProviderSpend.periodSpend(emptyList(), from, now))
        assertNull(ProviderSpend.periodSpend(listOf(snap(used = 10.0, agoHours = 5)), from, now))
    }

    @Test
    fun `без данных поставщика расчёт молчит`() {
        assertNull(
            ProviderSpend.periodSpend(
                listOf(
                    snap(used = 1.0, agoHours = 10, source = "LOCAL_USAGE"),
                    snap(used = 2.0, agoHours = 0, source = "LOCAL_USAGE"),
                ),
                from, now,
            )
        )
    }

    @Test
    fun `снимки после конца окна не учитываются`() {
        val spend = ProviderSpend.periodSpend(
            listOf(
                snap(used = 10.0, agoHours = 20),
                snap(used = 15.0, agoHours = 2),
                snap(used = 99.0, agoHours = -5),
            ),
            from, now,
        )!!
        assertEquals(5.0, spend.amount, 0.0001)
    }

    @Test
    fun `нулевой расход это ответ, а не отсутствие данных`() {
        // Поставщик подтвердил, что за период не потрачено ничего — это факт,
        // и он отличается от «данных нет».
        val spend = ProviderSpend.periodSpend(
            listOf(snap(used = 7.0, agoHours = 20), snap(used = 7.0, agoHours = 0)),
            from, now,
        )
        assertNotNull(spend)
        assertEquals(0.0, spend!!.amount, 0.0001)
    }
}
