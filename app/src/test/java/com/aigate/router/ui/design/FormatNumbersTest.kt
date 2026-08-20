package com.aigate.router.ui.design

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Числа в русском интерфейсе всегда с запятой: без явной локали форматтеры
 * брали системную и на англоязычном устройстве рядом с русским текстом
 * появлялось «3.8M» и «10.6 МБ». Нулевая дробь при этом только зашумляла.
 */
class FormatNumbersTest {

    private val original = Locale.getDefault()

    /** Именно английская локаль по умолчанию и вскрывала расхождение. */
    @Before
    fun setEnglishDefault() {
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreDefault() {
        Locale.setDefault(original)
    }

    @Test
    fun `compact keeps russian decimal comma`() {
        assertEquals("3,8M", Fmt.compact(3_767_000))
        assertEquals("12,4K", Fmt.compact(12_400))
        assertEquals("842", Fmt.compact(842))
    }

    @Test
    fun `compact drops zero fraction`() {
        assertEquals("473K", Fmt.compact(473_000))
        assertEquals("1M", Fmt.compact(1_000_000))
        assertEquals("500K", Fmt.compact(500_000))
    }

    @Test
    fun `bytes use comma and drop zero fraction`() {
        assertEquals("512 Б", Fmt.bytes(512))
        assertEquals("10,6 МБ", Fmt.bytes(11_115_000))
        assertEquals("1 КБ", Fmt.bytes(1024))
        assertEquals("2,08 ГБ", Fmt.bytes(2_233_382_993))
    }

    /** У суммы копейки не сокращаются: «$40,00» — цена, а не округление. */
    @Test
    fun `money uses comma and keeps cents`() {
        assertEquals("\$52,84", Fmt.usd(52.84))
        assertEquals("\$0,0042", Fmt.usd(0.0042))
        assertEquals("\$40,00", Fmt.usd(40.0))
    }

    @Test
    fun `latency uses comma and drops zero fraction`() {
        assertEquals("812 мс", Fmt.latency(812))
        assertEquals("4,9 с", Fmt.latency(4_900))
        assertEquals("2 с", Fmt.latency(2_000))
    }

    @Test
    fun `quota renders each unit in russian`() {
        assertEquals("\$12,84", Fmt.quota(12.84, "USD"))
        assertEquals("34%", Fmt.quota(34.0, "PERCENT"))
        assertEquals("1,2M", Fmt.quota(1_200_000.0, "TOKENS"))
        assertEquals("42", Fmt.quota(42.0, "REQUESTS"))
    }
}
