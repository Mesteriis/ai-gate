package com.aigate.router.gateway.local.llama

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Граница символов UTF-8 в ответе llama.cpp.
 *
 * Сам разбор живёт в нативном мосте и JVM-тестом не достаётся, но правило, по
 * которому он работает, проверить надо: токенизатор режет текст по токенам, а
 * не по символам, и один символ кириллицы приходит двумя кусками. Ровно на
 * этом падал шлюз — JNI отвергает обрубок многобайтовой последовательности и
 * убивает процесс.
 *
 * Тест фиксирует правило: наружу уходят только целые символы, а склейка кусков
 * даёт исходный текст без потерь.
 */
class Utf8BoundaryNote {

    /** Тот же расчёт, что и в llama_bridge.cpp: длина корректного префикса. */
    private fun validPrefix(bytes: ByteArray): Int {
        var i = 0
        while (i < bytes.size) {
            val c = bytes[i].toInt() and 0xFF
            val len = when {
                c < 0x80 -> 1
                c shr 5 == 0x6 -> 2
                c shr 4 == 0xE -> 3
                c shr 3 == 0x1E -> 4
                else -> return i
            }
            if (i + len > bytes.size) return i
            for (k in 1 until len) {
                if ((bytes[i + k].toInt() and 0xFF) shr 6 != 0x2) return i
            }
            i += len
        }
        return i
    }

    @Test
    fun `whole characters pass through untouched`() {
        val bytes = "Привет".toByteArray(Charsets.UTF_8)

        assertEquals(bytes.size, validPrefix(bytes))
    }

    @Test
    fun `a character split in half is held back until it is complete`() {
        val full = "Да".toByteArray(Charsets.UTF_8)
        // Обрываем на середине второго символа — так и приходит токен.
        val cut = full.copyOf(full.size - 1)

        assertEquals("хвост-обрубок наружу уходить не должен", full.size - 2, validPrefix(cut))
        assertEquals("а целиком — должен", full.size, validPrefix(full))
    }

    @Test
    fun `pieces glued back together give the original text`() {
        val full = "Привет, мир".toByteArray(Charsets.UTF_8)
        val out = StringBuilder()
        var pending = ByteArray(0)
        // Кормим по одному байту — худший случай для границы символов.
        for (b in full) {
            pending += b
            val good = validPrefix(pending)
            if (good > 0) {
                out.append(String(pending, 0, good, Charsets.UTF_8))
                pending = pending.copyOfRange(good, pending.size)
            }
        }

        assertEquals("Привет, мир", out.toString())
        assertEquals("не должно остаться незакрытого хвоста", 0, pending.size)
    }

    @Test
    fun `a stray continuation byte does not pass`() {
        assertEquals(0, validPrefix(byteArrayOf(0x80.toByte())))
    }
}
