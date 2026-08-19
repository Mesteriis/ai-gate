package com.aigate.router.notify

import com.aigate.router.quota.QuotaBurn
import com.aigate.router.quota.ResourcePoolKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Какие уведомления заслужены состоянием ресурса. Пороги в процентах здесь
 * только у простого «мало осталось»; темповые триггеры считаются от истории.
 */
class QuotaTriggersTest {

    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    private fun input(
        kind: ResourcePoolKind = ResourcePoolKind.QUOTA,
        remaining: Double? = 100.0,
        limit: Double? = 100.0,
        resetsAt: Long? = now + 24 * hour,
        rate: QuotaBurn.Rate? = QuotaBurn.Rate(1.0, 10.0),
        settings: NotifyPrefs.Settings = NotifyPrefs.defaultsFor(kind),
        resetSeenAt: Long? = now,
    ) = QuotaTriggers.Input(
        poolName = "Codex",
        kind = kind,
        remaining = remaining,
        limit = limit,
        unit = "PERCENT",
        resetsAt = resetsAt,
        rate = rate,
        settings = settings,
        now = now,
        resetSeenAt = resetSeenAt,
    )

    @Test
    fun `low quota fires below the configured fraction`() {
        val alerts = QuotaTriggers.evaluate(input(remaining = 10.0, rate = null))
        assertEquals(listOf(QuotaTriggers.Kind.LOW_QUOTA), alerts.map { it.kind })
    }

    @Test
    fun `exhaustion before reset is reported`() {
        val alerts = QuotaTriggers.evaluate(
            input(remaining = 40.0, rate = QuotaBurn.Rate(2.0, 4.0))
        )
        assertTrue(alerts.any { it.kind == QuotaTriggers.Kind.EXHAUST_BEFORE_RESET })
    }

    @Test
    fun `surplus fires only when the loss is worth a day of usage`() {
        // Темп 1 ед/ч: за сутки уходит 24. Сгорит 76 — больше суток, окно впритык.
        val big = QuotaTriggers.evaluate(
            input(remaining = 100.0, rate = QuotaBurn.Rate(1.0, 10.0), resetsAt = now + 24 * hour)
        )
        assertTrue(big.any { it.kind == QuotaTriggers.Kind.SURPLUS })

        // Сгорит 2 единицы — меньше суточного расхода, молчим.
        val small = QuotaTriggers.evaluate(
            input(remaining = 26.0, rate = QuotaBurn.Rate(1.0, 10.0), resetsAt = now + 24 * hour)
        )
        assertTrue(small.none { it.kind == QuotaTriggers.Kind.SURPLUS })
    }

    @Test
    fun `surplus stays silent while the reset is still far away`() {
        // Сгорит много, но до сброса месяц: действовать пока рано.
        val alerts = QuotaTriggers.evaluate(
            input(remaining = 100.0, rate = QuotaBurn.Rate(0.01, 0.02), resetsAt = now + 720 * hour)
        )
        assertTrue(alerts.none { it.kind == QuotaTriggers.Kind.SURPLUS })
    }

    @Test
    fun `hopeless surplus is not reported`() {
        // Сгорит столько, что не выбрать и за месяц: сообщать не о чем.
        val alerts = QuotaTriggers.evaluate(
            input(remaining = 100.0, rate = QuotaBurn.Rate(0.01, 0.02), resetsAt = now + 100 * hour)
        )
        assertTrue(alerts.none { it.kind == QuotaTriggers.Kind.SURPLUS })
    }

    @Test
    fun `fresh reset is announced when it has not been seen yet`() {
        val alerts = QuotaTriggers.evaluate(
            input(remaining = 100.0, resetsAt = now + 720 * hour, rate = null, resetSeenAt = null)
        )
        assertTrue(alerts.any { it.kind == QuotaTriggers.Kind.RESET })
    }

    @Test
    fun `known reset is not announced twice`() {
        val alerts = QuotaTriggers.evaluate(
            input(remaining = 100.0, resetsAt = now + 720 * hour, rate = null, resetSeenAt = now)
        )
        assertTrue(alerts.none { it.kind == QuotaTriggers.Kind.RESET })
    }

    @Test
    fun `balance pool only reports low balance`() {
        val alerts = QuotaTriggers.evaluate(
            input(kind = ResourcePoolKind.BALANCE, remaining = 3.0, limit = null, resetsAt = null, rate = null)
        )
        assertEquals(listOf(QuotaTriggers.Kind.LOW_BALANCE), alerts.map { it.kind })
    }

    @Test
    fun `sufficient balance is silent`() {
        val alerts = QuotaTriggers.evaluate(
            input(kind = ResourcePoolKind.BALANCE, remaining = 50.0, limit = null, resetsAt = null, rate = null)
        )
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `free pool never notifies`() {
        val alerts = QuotaTriggers.evaluate(
            input(kind = ResourcePoolKind.FREE, remaining = null, limit = null, resetsAt = null, rate = null)
        )
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `disabled triggers stay silent`() {
        val off = NotifyPrefs.defaultsFor(ResourcePoolKind.QUOTA).copy(
            lowQuotaEnabled = false,
            exhaustBeforeResetEnabled = false,
            surplusEnabled = false,
            resetEnabled = false,
        )
        assertTrue(QuotaTriggers.evaluate(input(remaining = 1.0, settings = off)).isEmpty())
    }

    @Test
    fun `missing data produces no alerts`() {
        assertTrue(QuotaTriggers.evaluate(input(remaining = null, limit = null)).isEmpty())
    }

    @Test
    fun `alert text names the resource and the amount`() {
        val alert = QuotaTriggers.evaluate(input(remaining = 5.0, rate = null)).single()
        assertTrue("в тексте должно быть имя ресурса", alert.body.contains("Codex"))
        assertTrue("в тексте должен быть остаток", alert.body.contains("5%"))
    }
}
