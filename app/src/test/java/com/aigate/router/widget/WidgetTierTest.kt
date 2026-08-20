package com.aigate.router.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ярус решает, сколько строк и какой график поместится, поэтому границы
 * проверяются числами: ошибка здесь выглядит как обрезанный виджет.
 */
class WidgetTierTest {

    @Test
    fun `узкая строка это два на один`() {
        assertEquals(WidgetTier.ROW_NARROW, WidgetTiers.of(184, 86))
    }

    @Test
    fun `широкая строка это четыре на один`() {
        assertEquals(WidgetTier.ROW_WIDE, WidgetTiers.of(380, 86))
    }

    @Test
    fun `квадрат это два на два`() {
        assertEquals(WidgetTier.SQUARE, WidgetTiers.of(184, 184))
    }

    @Test
    fun `широкий это четыре на два`() {
        assertEquals(WidgetTier.WIDE, WidgetTiers.of(380, 184))
    }

    @Test
    fun `большой это четыре на четыре`() {
        assertEquals(WidgetTier.LARGE, WidgetTiers.of(380, 380))
    }

    @Test
    fun `узкий и высокий остаётся квадратом`() {
        // Растянутый по вертикали виджет на две клетки в ширину не превращается
        // в большой ярус: строки в него всё равно не влезут по ширине.
        assertEquals(WidgetTier.SQUARE, WidgetTiers.of(184, 380))
    }

    @Test
    fun `без опций берётся ярус по умолчанию`() {
        assertEquals(WidgetTier.WIDE, WidgetTiers.fromOptions(null))
        assertEquals(WidgetTier.ROW_WIDE, WidgetTiers.fromOptions(null, WidgetTier.ROW_WIDE))
    }

    @Test
    fun `широкие ярусы помечены как широкие`() {
        assertEquals(true, WidgetTier.WIDE.isWide)
        assertEquals(true, WidgetTier.ROW_WIDE.isWide)
        assertEquals(false, WidgetTier.SQUARE.isWide)
        assertEquals(true, WidgetTier.ROW_NARROW.isRow)
        assertEquals(false, WidgetTier.LARGE.isRow)
    }
}
