package com.aigate.router.gateway

import java.io.IOException
import java.net.BindException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор причины неудачного запуска шлюза. Проверяется именно он, потому что
 * причина приходит завёрнутой: Ktor поднимает движок в своей корутине, а до
 * этого правила падение процесса на занятом порту было единственным «отчётом».
 */
class GatewayStartTest {

    private val port = 8889

    @Test
    fun `busy port is recognised`() {
        val failure = GatewayStart.failure(port, BindException("Address already in use"))
        assertEquals(GatewayStart.PortBusy(port), failure)
    }

    @Test
    fun `busy port is recognised through the cause chain`() {
        // Так это и приходит из движка: настоящая причина лежит под обёртками.
        val wrapped = IllegalStateException(
            "engine failed",
            RuntimeException("bind stage", BindException("Address already in use")),
        )
        assertEquals(GatewayStart.PortBusy(port), GatewayStart.failure(port, wrapped))
    }

    @Test
    fun `other failures keep the reported reason`() {
        val failure = GatewayStart.failure(port, IOException("Permission denied"))
        assertEquals(GatewayStart.Broken(port, "Permission denied"), failure)
    }

    @Test
    fun `failure without a message falls back to the class name`() {
        // Исключения отмены приходят без сообщения, а пустая строка на экране
        // ничего не объясняет.
        val failure = GatewayStart.failure(port, IOException())
        assertEquals(GatewayStart.Broken(port, "IOException"), failure)
    }

    @Test
    fun `failure without a cause is still reported`() {
        val failure = GatewayStart.failure(port, null)
        assertEquals(GatewayStart.Broken(port, GatewayStart.UNKNOWN_REASON), failure)
    }

    @Test
    fun `cyclic cause chain does not loop`() {
        // Обёртки умеют ссылаться друг на друга по кругу, и обход такой цепочки
        // без защиты не заканчивается никогда. Прямое самоссылание JDK
        // запрещает, а кольцо из двух звеньев — нет.
        val outer = IOException("сломано")
        val inner = IllegalStateException("обёртка")
        outer.initCause(inner)
        inner.initCause(outer)
        assertEquals(GatewayStart.Broken(port, "сломано"), GatewayStart.failure(port, outer))
    }

    @Test
    fun `failure names the port everywhere it is shown`() {
        for (failure in listOf(GatewayStart.PortBusy(port), GatewayStart.Broken(port, "сломано"))) {
            assertTrue(failure.logLine, failure.logLine.contains(port.toString()))
            assertTrue(failure.verdict, failure.verdict.isNotBlank())
        }
        assertTrue(GatewayStart.PortBusy(port).shortText.contains(port.toString()))
    }

    @Test
    fun `only the debug journal carries the marker`() {
        // Эмодзи запрещены дизайн-системой на экранах и в уведомлении, но в
        // журнале отладки записи ими размечены — тексты не взаимозаменяемы.
        val failures = listOf(GatewayStart.PortBusy(port), GatewayStart.Broken(port, "сломано"))
        for (failure in failures) {
            assertTrue(failure.logLine, failure.logLine.startsWith("❌"))
            assertFalse(failure.shortText, failure.shortText.contains("❌"))
            assertFalse(failure.verdict, failure.verdict.contains("❌"))
        }
    }
}
