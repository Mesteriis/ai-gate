package com.aigate.router.ui.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Подпись под числом квоты. Раньше источник данных нигде не показывался, и
 * локальная оценка выглядела так же убедительно, как ответ поставщика, хотя
 * видит только трафик через шлюз.
 */
class SourceCaptionTest {

    private val minute = 60_000L
    private val now = 1_800_000_000_000L

    @Test
    fun `данные поставщика подписаны возрастом`() {
        assertEquals(
            "по данным поставщика · обновлено 7 мин назад",
            Fmt.sourceCaption("PROVIDER_API", now - 7 * minute, now),
        )
    }

    @Test
    fun `совсем свежее показание не считает минуты`() {
        assertEquals(
            "по данным поставщика · обновлено только что",
            Fmt.sourceCaption("PROVIDER_API", now - 20_000, now),
        )
    }

    @Test
    fun `локальный подсчёт честно говорит о своей слепоте`() {
        // Главное, что должен понять владелец: мимо шлюза расход сюда не попадает.
        val caption = Fmt.sourceCaption("LOCAL_USAGE", now, now)
        assertTrue(caption, caption.contains("только через шлюз"))
    }

    @Test
    fun `заданный пользователем бюджет не выдаётся за данные поставщика`() {
        assertEquals("бюджет задан вами", Fmt.sourceCaption("USER_CONFIGURED", now, now))
    }

    @Test
    fun `неизвестный источник не выдумывает происхождение`() {
        assertEquals("источник данных неизвестен", Fmt.sourceCaption("НЕЧТО", now, now))
    }
}
