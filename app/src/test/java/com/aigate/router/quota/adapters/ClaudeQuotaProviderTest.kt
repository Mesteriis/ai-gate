package com.aigate.router.quota.adapters

import com.aigate.router.quota.QuotaSource
import com.aigate.router.quota.QuotaUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Разбор окон лимитов подписки Claude из `/api/oauth/usage`. */
class ClaudeQuotaProviderTest {

    private val adapter = ClaudeQuotaProvider()

    @Test
    fun `берётся самое напряжённое окно и его сброс`() {
        // Упрётся в лимит первым именно оно — его и показываем.
        val snap = adapter.parse5h(
            """{"five_hour":{"utilization":0.20,"resets_at":1787200000},
                "seven_day":{"utilization":0.85,"resets_at":1787500000}}"""
        )
        assertEquals(85.0, snap!!.used!!, 0.01)
        assertEquals(15.0, snap.remaining!!, 0.01)
        assertEquals(100.0, snap.limit!!, 0.01)
        assertEquals(QuotaUnit.PERCENT.name, snap.unit)
        assertEquals(1787500000L * 1000, snap.resetsAt)
        assertEquals(QuotaSource.PROVIDER_API.name, snap.source)
    }

    @Test
    fun `доля 0-1 и проценты 0-100 понимаются оба`() {
        assertEquals(42.0, adapter.parse5h("""{"five_hour":{"utilization":0.42}}""")!!.used!!, 0.01)
        assertEquals(42.0, adapter.parse5h("""{"five_hour":{"utilization":42}}""")!!.used!!, 0.01)
        assertEquals(100.0, adapter.parse5h("""{"five_hour":{"utilization":100}}""")!!.used!!, 0.01)
    }

    @Test
    fun `сброс строкой ISO тоже принимается`() {
        val snap = adapter.parse5h(
            """{"seven_day":{"utilization":0.5,"resets_at":"2026-08-26T10:00:00Z"}}"""
        )
        assertEquals(1787738400000L, snap!!.resetsAt)
    }

    @Test
    fun `у момента сброса читаются доли секунды и смещение зоны`() {
        // Один и тот же момент тремя записями: API шлёт то `Z`, то смещение,
        // а доли секунды бывают любой длины — раньше их разбирал java.time,
        // недоступный на Android 7.
        val forms = listOf(
            "2026-08-26T10:00:00.123456789Z",
            "2026-08-26T13:00:00+03:00",
            "2026-08-26T07:00:00-0300",
            "2026-08-26T10:00:00+00",
        )
        for (raw in forms) {
            assertEquals(raw, 1787738400000L, adapter.parse5h(iso(raw))!!.resetsAt)
        }
    }

    @Test
    fun `негодная строка сброса не превращается в выдуманный момент`() {
        // Несуществующая дата, отсутствующая зона и просто мусор: окно остаётся
        // с процентом, но без срока — угадывать момент нечем.
        val bad = listOf("2026-13-45T10:00:00Z", "2026-08-26T10:00:00", "скоро")
        for (raw in bad) {
            val snap = adapter.parse5h(iso(raw))!!
            assertEquals(raw, 50.0, snap.used!!, 0.01)
            assertNull(raw, snap.resetsAt)
        }
    }

    @Test
    fun `недельные окна по семействам моделей учитываются`() {
        val snap = adapter.parse5h(
            """{"seven_day":{"utilization":0.1},"seven_day_opus":{"utilization":0.97}}"""
        )
        assertEquals(97.0, snap!!.used!!, 0.01)
    }

    @Test
    fun `без окон лимитов остаток не выдумывается`() {
        assertNull(adapter.parse5h("""{"extra_usage":{"is_enabled":false}}"""))
        assertNull(adapter.parse5h("не json"))
    }

    @Test
    fun `окна возвращаются по порядку от короткого к длинным`() {
        // Плитка рисует кольцо в кольце: внешнее — сессия, внутреннее — неделя.
        val reading = adapter.read(
            """{"seven_day":{"utilization":0.30,"resets_at":1787500000},
                "five_hour":{"utilization":0.10,"resets_at":1787200000}}""",
            poolId = 1L,
        )!!
        assertEquals(listOf("five_hour", "seven_day"), reading.windows.map { it.key })
        assertEquals(listOf("5 ч", "неделя"), reading.windows.map { it.label })
        assertEquals(10.0, reading.windows[0].percent, 0.01)
        assertEquals(1787200000L * 1000, reading.windows[0].resetsAt)
        // Снимок описывает самое напряжённое окно — неделю.
        assertEquals(30.0, reading.snapshot.used!!, 0.01)
    }

    /** Недельное окно с заданной строкой момента сброса. */
    private fun iso(raw: String) = """{"seven_day":{"utilization":0.5,"resets_at":"$raw"}}"""

    /** Короткий вызов разбора с фиксированным пулом. */
    private fun ClaudeQuotaProvider.parse5h(body: String) = read(body, poolId = 1L)?.snapshot
}
