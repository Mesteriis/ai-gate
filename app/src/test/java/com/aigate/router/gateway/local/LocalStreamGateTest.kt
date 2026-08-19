package com.aigate.router.gateway.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Гейт первого токена — граница, за которой переключение на другую модель уже
 * невозможно. Проверяется, что до первого элемента любая беда становится
 * исключением (значит, сработает переключение), а после — поток просто
 * заканчивается, и клиент не получает битого ответа.
 *
 * Сбор запускается в контексте теста (EmptyCoroutineContext вместо
 * Dispatchers.Default): иначе продьюсер жил бы в реальном времени, а таймауты
 * гейта — в виртуальном времени runTest, и они разошлись бы.
 */
class LocalStreamGateTest {

    private val timeout = 5_000L

    private suspend fun open(scope: CoroutineScope, deltas: Flow<LocalDelta>, timeoutMs: Long = timeout) =
        LocalStreamGate.open(scope, deltas, timeoutMs, producerContext = EmptyCoroutineContext)

    private suspend fun drain(source: LocalStreamSource): List<LocalDelta> {
        val collected = mutableListOf(source.first)
        while (true) {
            collected += source.next() ?: break
        }
        return collected
    }

    @Test
    fun `first token opens the stream and the rest follows in order`() = runTest {
        val deltas = flowOf(
            LocalDelta.Token("Привет"),
            LocalDelta.Token(", мир"),
            LocalDelta.Done("stop", promptTokens = 3, completionTokens = 2),
        )

        val source = open(this, deltas)
        assertEquals(LocalDelta.Token("Привет"), source.first)

        val all = drain(source)
        assertEquals(3, all.size)
        assertEquals(LocalDelta.Token(", мир"), all[1])
        assertTrue(all[2] is LocalDelta.Done)
        source.close()
    }

    @Test
    fun `engine failure before the first token becomes a failover-triggering error`() = runTest {
        val deltas = flow<LocalDelta> { throw IllegalStateException("движок не поднялся") }

        val error = runCatching { open(this, deltas) }.exceptionOrNull()

        assertNotNull("отказ до первого токена обязан быть исключением", error)
        assertTrue(error is LocalStreamException)
        assertTrue(
            "сообщение движка должно дойти до вызывающего: ${error?.message}",
            error?.message?.contains("движок не поднялся") == true,
        )
    }

    @Test
    fun `empty stream is treated as a failure rather than an empty answer`() = runTest {
        // Движок завершился, не выдав ничего: клиенту такой ответ бесполезен,
        // и лучше уйти на следующую модель, чем отдать пустоту.
        val error = runCatching { open(this, flowOf()) }.exceptionOrNull()

        assertTrue(error is LocalStreamException)
        assertTrue(error?.message?.contains("не выдала ответа") == true)
    }

    @Test
    fun `silent engine trips the timeout instead of hanging the client`() = runTest {
        val deltas = flow<LocalDelta> { delay(60_000) }

        val error = runCatching { open(this, deltas, timeoutMs = 1_000) }.exceptionOrNull()

        assertTrue(error is LocalStreamException)
        assertTrue(
            "в сообщении должно быть время ожидания: ${error?.message}",
            error?.message?.contains("не ответила") == true,
        )
    }

    @Test
    fun `failure after the first token ends the stream without an exception`() = runTest {
        // Ответ уже ушёл клиенту, переключаться поздно: поток обязан просто
        // закончиться, а не выбросить исключение в уже открытый ответ.
        val deltas = flow {
            emit(LocalDelta.Token("начало"))
            throw IllegalStateException("движок упал на середине")
        }

        val source = open(this, deltas)
        assertEquals(LocalDelta.Token("начало"), source.first)
        assertNull("после обрыва next() отдаёт null, а не бросает", source.next())
        source.close()
    }

    @Test
    fun `close stops the engine so an abandoned request stops burning power`() = runTest {
        val cancelled = CompletableDeferred<Boolean>()
        val deltas: Flow<LocalDelta> = flow {
            try {
                emit(LocalDelta.Token("первый"))
                // Бесконечный счёт: без отмены он молотил бы вечно.
                while (true) {
                    delay(10)
                    emit(LocalDelta.Token("ещё"))
                }
            } finally {
                cancelled.complete(true)
            }
        }

        val source = open(this, deltas)
        assertEquals(LocalDelta.Token("первый"), source.first)
        source.close()

        assertTrue("движок должен получить отмену", cancelled.await())
    }
}
