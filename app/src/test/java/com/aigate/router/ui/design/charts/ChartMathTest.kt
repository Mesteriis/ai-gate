package com.aigate.router.ui.design.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ось с «грязным» максимумом и столкновение подписей с «сегодня» — реальные баги макета. */
class ChartMathTest {

    @Test
    fun `nice ceiling snaps to clean ticks`() {
        assertEquals(500f, ChartMath.niceCeil(473f))
        assertEquals(60f, ChartMath.niceCeil(58f))
        assertEquals(10_000f, ChartMath.niceCeil(8_560f))
        assertEquals(1_000f, ChartMath.niceCeil(923f))
        assertEquals(100f, ChartMath.niceCeil(100f))
    }

    @Test
    fun `nice ceiling survives degenerate input`() {
        assertEquals(1f, ChartMath.niceCeil(0f))
        assertEquals(1f, ChartMath.niceCeil(-5f))
        assertEquals(1f, ChartMath.niceCeil(Float.NaN))
    }

    @Test
    fun `median for odd and even counts`() {
        assertEquals(3f, ChartMath.median(listOf(5f, 1f, 3f)))
        assertEquals(2.5f, ChartMath.median(listOf(4f, 1f, 2f, 3f)))
        assertEquals(0f, ChartMath.median(emptyList()))
    }

    @Test
    fun `percentile interpolates between neighbours`() {
        val values = (1..10).map { it.toFloat() }
        assertEquals(9.55f, ChartMath.percentile(values, 0.95f), 0.001f)
        assertEquals(1f, ChartMath.percentile(values, 0f))
        assertEquals(10f, ChartMath.percentile(values, 1f))
    }

    @Test
    fun `delta percent against previous window`() {
        assertEquals(13, ChartMath.deltaPercent(3767.0, 3323.0))
        assertEquals(-50, ChartMath.deltaPercent(50.0, 100.0))
        assertNull(ChartMath.deltaPercent(100.0, 0.0))
    }

    @Test
    fun `label step grows with column count`() {
        assertEquals(1, ChartMath.labelEvery(7))
        assertEquals(2, ChartMath.labelEvery(14))
        assertEquals(5, ChartMath.labelEvery(30))
    }

    @Test
    fun `label before today is hidden to avoid collision`() {
        val labels = ChartMath.axisLabelIndices(count = 14, every = 2)
        assertTrue(13 in labels)
        assertTrue(12 !in labels)
        assertTrue(0 in labels && 2 in labels && 10 in labels)
    }

    @Test
    fun `last column is always labelled`() {
        val labels = ChartMath.axisLabelIndices(count = 30, every = 5)
        assertTrue(29 in labels)
        assertTrue(25 !in labels)
        assertEquals(setOf(0, 5, 10, 15, 20, 29), labels)
    }
}
