package com.aigate.router.quota.adapters

import com.aigate.router.quota.QuotaSource
import com.aigate.router.quota.QuotaUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Разбор ответа админ-API Cursor: центы → доллары, лимит и дата сброса. */
class CursorQuotaProviderTest {

    private val adapter = CursorQuotaProvider()

    private fun ms(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day)
        }.timeInMillis

    @Test
    fun `расход участников суммируется и переводится в доллары`() {
        val snap = adapter.parse(
            """{"teamMemberSpend":[
                 {"spendCents":1250,"hardLimitOverrideDollars":0},
                 {"spendCents":750,"hardLimitOverrideDollars":0}]}""",
            poolId = 7L
        )
        assertNotNull(snap)
        assertEquals(20.0, snap!!.used!!, 0.001)
        assertEquals(QuotaUnit.USD.name, snap.unit)
        assertEquals(QuotaSource.PROVIDER_API.name, snap.source)
        assertEquals(7L, snap.poolId)
    }

    @Test
    fun `нулевой override не превращается в лимит`() {
        // hardLimitOverrideDollars=0 означает «лимит не задан», а не «лимит ноль»:
        // иначе интерфейс показал бы 100% исчерпания на пустом месте.
        val snap = adapter.parse(
            """{"teamMemberSpend":[{"spendCents":500,"hardLimitOverrideDollars":0}]}""",
            poolId = 1L
        )
        assertNull(snap!!.limit)
        assertNull(snap.remaining)
    }

    @Test
    fun `заданный лимит даёт остаток`() {
        val snap = adapter.parse(
            """{"teamMemberSpend":[{"spendCents":2500,"hardLimitOverrideDollars":100}]}""",
            poolId = 1L
        )
        assertEquals(100.0, snap!!.limit!!, 0.001)
        assertEquals(75.0, snap.remaining!!, 0.001)
    }

    @Test
    fun `перерасход не даёт отрицательного остатка`() {
        val snap = adapter.parse(
            """{"teamMemberSpend":[{"spendCents":15000,"hardLimitOverrideDollars":100}]}""",
            poolId = 1L
        )
        assertEquals(0.0, snap!!.remaining!!, 0.001)
    }

    @Test
    fun `сброс считается месячными шагами от начала цикла`() {
        val cycleStart = ms(2026, Calendar.JANUARY, 15)
        val now = ms(2026, Calendar.MARCH, 20)
        val snap = adapter.parse(
            """{"teamMemberSpend":[{"spendCents":100}],"subscriptionCycleStart":$cycleStart}""",
            poolId = 1L,
            now = now
        )
        val reset = Calendar.getInstance().apply { timeInMillis = snap!!.resetsAt!! }
        assertEquals(Calendar.APRIL, reset.get(Calendar.MONTH))
        assertEquals(15, reset.get(Calendar.DAY_OF_MONTH))
        assertTrue(snap!!.resetsAt!! > now)
    }

    @Test
    fun `без даты цикла сброс не выдумывается`() {
        val snap = adapter.parse("""{"teamMemberSpend":[{"spendCents":100}]}""", poolId = 1L)
        assertNull(snap!!.resetsAt)
    }

    @Test
    fun `чужой формат ответа отбрасывается`() {
        assertNull(adapter.parse("""{"error":"forbidden"}""", poolId = 1L))
        assertNull(adapter.parse("не json", poolId = 1L))
    }
}
