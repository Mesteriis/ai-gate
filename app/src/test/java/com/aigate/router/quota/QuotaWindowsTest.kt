package com.aigate.router.quota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Окна лимита хранятся строкой в конфиге (схема базы не меняется), поэтому
 * кодирование и разбор должны быть устойчивы к мусору и потере поля.
 */
class QuotaWindowsTest {

    @Test
    fun `окна переживают кодирование и разбор`() {
        val windows = listOf(
            QuotaWindow("five_hour", "5 ч", 12.5, 1787200000000L),
            QuotaWindow("seven_day", "неделя", 33.0, null),
        )
        val back = QuotaWindows.decode(QuotaWindows.encode(windows))
        assertEquals(windows, back)
    }

    @Test
    fun `окно без ключа отбрасывается, а не ломает разбор`() {
        val raw = """[{"label":"без ключа","percent":5},{"key":"seven_day","percent":7}]"""
        val back = QuotaWindows.decode(raw)
        assertEquals(1, back.size)
        assertEquals("seven_day", back[0].key)
        // Подпись по умолчанию — сам ключ: пустого места в интерфейсе не будет.
        assertEquals("seven_day", back[0].label)
        assertNull(back[0].resetsAt)
    }

    @Test
    fun `мусор и пустая строка дают пустой список`() {
        assertTrue(QuotaWindows.decode("").isEmpty())
        assertTrue(QuotaWindows.decode("не json").isEmpty())
    }
}
