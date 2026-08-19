package com.aigate.router.gateway.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

/**
 * Ожидание первого токена локального движка — точка, до которой ещё можно
 * переключиться на другую модель.
 *
 * Весь смысл в границе: пока первый элемент не получен, клиенту ничего не
 * отправлено, и любая беда (движок не поднялся, файл модели исчез, счёт занят
 * другим запросом, перегрев) выглядит как обычная недоступность апстрима.
 * После первого токена ответ уже открыт, и остаётся только оборвать поток —
 * ровно так же ведёт себя сетевой путь.
 *
 * Вынесено из конвейера отдельно, потому что правило одно для потокового и
 * обычного ответов, а проверять его удобнее на поддельных потоках.
 */
internal object LocalStreamGate {

    /** Запас кадров, чтобы движок не ждал медленного клиента на каждом токене. */
    private const val CHANNEL_CAPACITY = 64

    /**
     * Запускает счёт и дожидается первого элемента.
     *
     * [producerContext] по умолчанию уводит сбор с потока, обслуживающего
     * HTTP: счёт на устройстве занимает процессор надолго и заблокировал бы
     * приём остальных запросов. Движку разрешено переопределить контекст
     * своим `flowOn`, а тесты подставляют сюда собственный диспетчер, иначе
     * виртуальное время разошлось бы с реальным.
     *
     * @throws LocalStreamException если движок не ответил вовремя, упал или
     * завершился, не выдав ни одного элемента
     */
    suspend fun open(
        scope: CoroutineScope,
        deltas: Flow<LocalDelta>,
        firstTokenTimeoutMs: Long,
        producerContext: CoroutineContext = Dispatchers.Default,
    ): LocalStreamSource {
        val channel = Channel<LocalDelta>(capacity = CHANNEL_CAPACITY)
        val producer = scope.launch(producerContext) {
            try {
                deltas.collect { channel.send(it) }
                channel.close()
            } catch (t: Throwable) {
                channel.close(t)
            }
        }

        val received = withTimeoutOrNull(firstTokenTimeoutMs) { channel.receiveCatching() }
        if (received == null) {
            producer.cancel()
            channel.cancel()
            throw LocalStreamException("Локальная модель не ответила за ${firstTokenTimeoutMs / 1000} с")
        }

        val first = received.getOrNull()
        if (first == null) {
            producer.cancel()
            channel.cancel()
            val cause = received.exceptionOrNull()
            // Поток закрылся без единого элемента: либо движок бросил ошибку,
            // либо честно ничего не сгенерировал — и то и другое бесполезно
            // клиенту, поэтому это повод перейти к следующей модели.
            throw LocalStreamException(
                cause?.message?.takeIf { it.isNotBlank() }
                    ?: "Локальная модель не выдала ответа",
                cause,
            )
        }
        return LocalStreamSource(first, channel, producer)
    }
}

/**
 * Отказ локального движка до того, как ответ ушёл клиенту. Отдельный тип нужен
 * не для обработки, а для читаемости журнала: сообщение уже на русском и
 * годится для показа как есть.
 */
internal class LocalStreamException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Открытый поток движка: первый элемент уже получен и лежит в [first].
 *
 * [close] обязателен — по нему счёт прекращается. Без него отвалившийся клиент
 * оставил бы движок молотить в пустоту и греть телефон.
 */
internal class LocalStreamSource(
    val first: LocalDelta,
    private val channel: ReceiveChannel<LocalDelta>,
    private val producer: Job,
) {
    /** Следующий элемент или null, когда поток кончился либо оборвался. */
    suspend fun next(): LocalDelta? = channel.receiveCatching().getOrNull()

    fun close() {
        producer.cancel()
        channel.cancel()
    }
}
