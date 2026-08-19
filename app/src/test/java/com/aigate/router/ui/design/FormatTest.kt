package com.aigate.router.ui.design

import org.junit.Assert.assertEquals
import org.junit.Test

/** Склонение по числу: в интерфейсе появлялись строки вида «за 2 вызовов». */
class FormatTest {

    private fun calls(n: Long) = "$n " + Fmt.plural(n, "вызов", "вызова", "вызовов")

    @Test
    fun `singular for one`() {
        assertEquals("1 вызов", calls(1))
        assertEquals("21 вызов", calls(21))
        assertEquals("101 вызов", calls(101))
    }

    @Test
    fun `few for two to four`() {
        assertEquals("2 вызова", calls(2))
        assertEquals("3 вызова", calls(3))
        assertEquals("24 вызова", calls(24))
    }

    @Test
    fun `many for five and above`() {
        assertEquals("5 вызовов", calls(5))
        assertEquals("100 вызовов", calls(100))
        assertEquals("0 вызовов", calls(0))
    }

    @Test
    fun `teens always take the many form`() {
        assertEquals("11 вызовов", calls(11))
        assertEquals("12 вызовов", calls(12))
        assertEquals("14 вызовов", calls(14))
        assertEquals("111 вызовов", calls(111))
    }
}
