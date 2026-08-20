package com.aigate.router.usage

import com.aigate.router.data.model.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Агрегация за период: окно, дельта, сортировки и суммы без обращений к базе. */
class UsageStatsTest {

    // Фиксированный момент времени: тест не должен зависеть от часов машины.
    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    private fun row(
        ts: Long,
        provider: Long = 1L,
        model: String = "m1",
        prompt: Int = 10,
        completion: Int = 5,
        total: Int = prompt + completion,
        upload: Long = 0,
        download: Long = 0,
        label: String = "",
    ) = TokenUsage(
        providerId = provider,
        modelId = model,
        promptTokens = prompt,
        completionTokens = completion,
        totalTokens = total,
        uploadBytes = upload,
        downloadBytes = download,
        timestamp = ts,
        apiKeyLabel = label,
    )

    @Test
    fun `window keeps only rows between fromMs and nowMs`() {
        val days = 7
        val fromMs = now - days * day
        val rows = listOf(
            row(ts = now),                // правая граница включена
            row(ts = now - 2 * day),
            row(ts = fromMs),             // левая граница включена
            row(ts = fromMs - 1),         // уже прошлое окно
            row(ts = now + 1),            // будущее не считается
        )
        val snap = UsageStats.snapshot(rows, emptyMap(), now, days)
        assertEquals(3, snap.calls)
        assertEquals(45L, snap.totalTokens)
        assertEquals(30L, snap.promptTokens)
        assertEquals(15L, snap.completionTokens)
        assertEquals(fromMs, snap.fromMs)
        assertEquals(days, snap.periodDays)
    }

    @Test
    fun `delta compares windows of equal length`() {
        val days = 7
        val rows = listOf(
            row(ts = now - day, total = 90, prompt = 60, completion = 30),
            row(ts = now - day, total = 60, prompt = 40, completion = 20),
            row(ts = now - 8 * day, total = 100, prompt = 70, completion = 30),
        )
        // 150 против 100 в прошлом окне: рост на 50 процентов.
        assertEquals(50, UsageStats.snapshot(rows, emptyMap(), now, days).deltaPercent)
    }

    @Test
    fun `delta is negative when usage dropped`() {
        val rows = listOf(
            row(ts = now - day, total = 100, prompt = 70, completion = 30),
            row(ts = now - 8 * day, total = 200, prompt = 150, completion = 50),
        )
        assertEquals(-50, UsageStats.snapshot(rows, emptyMap(), now, 7).deltaPercent)
    }

    @Test
    fun `delta is rounded to the nearest whole percent`() {
        val rows = listOf(
            row(ts = now - day, total = 4, prompt = 3, completion = 1),
            row(ts = now - 8 * day, total = 3, prompt = 2, completion = 1),
        )
        // (4 - 3) / 3 = 33.33 -> 33
        assertEquals(33, UsageStats.snapshot(rows, emptyMap(), now, 7).deltaPercent)
    }

    @Test
    fun `delta is null when previous window is empty`() {
        val rows = listOf(
            row(ts = now - day),
            // Строка есть, но расхода нет: сравнивать всё равно не с чем.
            row(ts = now - 8 * day, total = 0, prompt = 0, completion = 0),
        )
        assertNull(UsageStats.snapshot(rows, emptyMap(), now, 7).deltaPercent)
    }

    @Test
    fun `boundary row counts once in the current window`() {
        val days = 7
        val fromMs = now - days * day
        val snap = UsageStats.snapshot(listOf(row(ts = fromMs)), emptyMap(), now, days)
        assertEquals(15L, snap.totalTokens)
        assertNull(snap.deltaPercent)
    }

    @Test
    fun `shares are sorted by descending tokens`() {
        val rows = listOf(
            row(ts = now - day, provider = 1L, model = "small", total = 100, label = "home"),
            row(ts = now - day, provider = 2L, model = "big", total = 300, label = "work"),
        )
        val snap = UsageStats.snapshot(rows, mapOf(1L to "Первый", 2L to "Второй"), now, 7)
        assertEquals(listOf(2L, 1L), snap.byProvider.map { it.providerId })
        assertEquals(listOf("big", "small"), snap.byModel.map { it.modelId })
        assertEquals(listOf("work", "home"), snap.byApiKey.map { it.label })
    }

    @Test
    fun `same model at different providers stays separate`() {
        val rows = listOf(
            row(ts = now - day, provider = 1L, model = "m", total = 100),
            row(ts = now - day, provider = 2L, model = "m", total = 40),
        )
        val snap = UsageStats.snapshot(rows, emptyMap(), now, 7)
        assertEquals(2, snap.byModel.size)
        assertEquals(1L, snap.byModel.first().providerId)
        assertEquals(100L, snap.byModel.first().tokens)
    }

    @Test
    fun `provider missing from the map is named by its id`() {
        val rows = listOf(
            row(ts = now - day, provider = 1L, total = 10),
            row(ts = now - day, provider = 7L, total = 20),
        )
        val snap = UsageStats.snapshot(rows, mapOf(1L to "OpenAI"), now, 7)
        assertEquals("7", snap.byProvider.first { it.providerId == 7L }.name)
        assertEquals("OpenAI", snap.byProvider.first { it.providerId == 1L }.name)
    }

    @Test
    fun `blank api key labels merge into the no-key group`() {
        val rows = listOf(
            row(ts = now - day, label = "", total = 10),
            row(ts = now - day, label = " ", total = 20),
            row(ts = now - day, label = "work", total = 5),
        )
        val snap = UsageStats.snapshot(rows, emptyMap(), now, 7)
        assertEquals(2, snap.byApiKey.size)
        assertEquals("Без ключа", snap.byApiKey.first().label)
        assertEquals(30L, snap.byApiKey.first().tokens)
    }

    @Test
    fun `byte sums tolerate zero rows`() {
        val rows = listOf(
            row(ts = now - day, upload = 100, download = 10),
            row(ts = now - 2 * day, upload = 0, download = 0),
            row(ts = now - 3 * day, upload = 50, download = 20),
            row(ts = now - 10 * day, upload = 999, download = 999), // вне окна
        )
        val snap = UsageStats.snapshot(rows, emptyMap(), now, 7)
        assertEquals(150L, snap.uploadBytes)
        assertEquals(30L, snap.downloadBytes)
    }
}
