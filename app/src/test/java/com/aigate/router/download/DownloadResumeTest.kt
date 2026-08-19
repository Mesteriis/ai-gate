package com.aigate.router.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Правила докачки гигабайтного файла.
 *
 * Ошибка здесь стоит дороже всего в загрузчике: неверное решение либо склеивает
 * старый кусок с новым в испорченный файл, который пройдёт проверку размера и
 * упадёт только при запуске модели, либо каждый раз качает гигабайты заново.
 */
class DownloadResumeTest {

    @Test
    fun `nothing downloaded yet means nothing to decide`() {
        assertEquals(
            DownloadResume.Decision.Continue,
            DownloadResume.decide(offset = 0L, code = DownloadResume.HTTP_OK),
        )
    }

    @Test
    fun `server honoured the range so we continue from where we stopped`() {
        assertEquals(
            DownloadResume.Decision.Continue,
            DownloadResume.decide(offset = 1_000_000L, code = DownloadResume.HTTP_PARTIAL),
        )
    }

    @Test
    fun `plain 200 on a range request means the whole file is coming`() {
        // Диапазон проигнорирован: тело годится, но приклеивать его к старому
        // куску нельзя — получится файл с задвоенным началом.
        assertEquals(
            DownloadResume.Decision.RestartWithSameResponse,
            DownloadResume.decide(offset = 1_000_000L, code = DownloadResume.HTTP_OK),
        )
    }

    @Test
    fun `416 means the file changed and the response is useless`() {
        // Сдвиг за пределы файла: в реестре лежит уже другой файл. Тело такого
        // ответа нужных байт не содержит, поэтому нужен новый запрос без Range.
        assertEquals(
            DownloadResume.Decision.RestartWithNewRequest,
            DownloadResume.decide(offset = 5_000_000L, code = DownloadResume.HTTP_RANGE_NOT_SATISFIABLE),
        )
    }

    @Test
    fun `any other successful code with a partial file restarts the write`() {
        // Перенаправления и прочие коды сюда не доходят: OkHttp следует за ними
        // сам. Всё, что не 206, для накопленного куска одинаково негодно.
        assertEquals(
            DownloadResume.Decision.RestartWithSameResponse,
            DownloadResume.decide(offset = 42L, code = 203),
        )
    }

    @Test
    fun `range header is set only when there is something to continue`() {
        assertNull("с нуля качаем без заголовка", DownloadResume.rangeHeader(0L))
        assertNull("отрицательный сдвиг — тоже с нуля", DownloadResume.rangeHeader(-1L))
        assertEquals("bytes=1024-", DownloadResume.rangeHeader(1024L))
    }
}
