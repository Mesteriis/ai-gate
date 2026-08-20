package com.aigate.router.gateway

import java.net.BindException

/**
 * Чем закончилась попытка поднять шлюз.
 *
 * Вынесено отдельным типом без Context: правило разбора причины проверяется
 * тестом без Ktor, сокетов и Android.
 *
 * Зачем вообще разбор. Ktor CIO сообщает о неудачном bind двумя путями сразу:
 * `start()` бросает служебное исключение отмены, в котором настоящей причины
 * нет, а сам `BindException` уходит в обработчик исключений корутины движка.
 * Обработчика не было, поэтому занятый порт приводил не к отказу запуска, а к
 * необработанному исключению в рабочем потоке — то есть к падению процесса.
 */
sealed interface GatewayStart {

    /** Порт, о котором идёт речь. */
    val port: Int

    /** Шлюз слушает порт. */
    data class Started(override val port: Int) : GatewayStart

    /**
     * Запуск не удался. Формулировок три, потому что у них разные адресаты:
     * журнал отладки в этом приложении пишется со значками, а уведомление и
     * экраны их не допускают — эмодзи там запрещены дизайн-системой.
     */
    sealed interface Failure : GatewayStart {
        /** Строка журнала отладки — в стиле остальных записей, со значком. */
        val logLine: String

        /** Подпись для уведомления и строки экрана, без значков. */
        val shortText: String

        /** Короткий вердикт для чипа в блоке «Требует внимания». */
        val verdict: String
    }

    /** Порт занять не удалось — тот самый исход, ради которого всё это. */
    data class PortBusy(override val port: Int) : Failure {
        override val logLine: String
            get() = "❌ Порт занят: $port используется другим процессом, шлюз не запущен"
        override val shortText: String get() = "Порт $port занят другим процессом"
        override val verdict: String get() = "порт занят"
    }

    /** Всё остальное: причину показываем как есть, диагноз не выдумываем. */
    data class Broken(override val port: Int, val reason: String) : Failure {
        override val logLine: String get() = "❌ Шлюз не запущен на порту $port: $reason"
        override val shortText: String get() = "Шлюз не запущен: $reason"
        override val verdict: String get() = "сбой"
    }

    companion object {
        /** Что писать, когда причина отказа до нас так и не дошла. */
        const val UNKNOWN_REASON = "причина неизвестна"

        /**
         * Разбор причины отказа.
         *
         * Причина приходит завёрнутой: движок Ktor поднимается в своей корутине
         * и оборачивает исходное исключение, поэтому смотрим всю цепочку, а не
         * только верхний класс. Цепочка бывает замкнутой (исключение ссылается
         * на само себя), поэтому пройденное запоминаем — иначе обход зациклится.
         */
        fun failure(port: Int, cause: Throwable?): Failure {
            val seen = mutableSetOf<Throwable>()
            var current = cause
            while (current != null && seen.add(current)) {
                if (current is BindException) return PortBusy(port)
                current = current.cause
            }
            return Broken(port, describe(cause))
        }

        /**
         * Человеческая причина. Сообщение бывает пустым (например, у исключений
         * отмены), и тогда честнее назвать класс, чем показать пустую строку.
         */
        private fun describe(cause: Throwable?): String {
            if (cause == null) return UNKNOWN_REASON
            cause.message?.takeIf { it.isNotBlank() }?.let { return it }
            return cause::class.java.simpleName.takeIf { it.isNotBlank() } ?: UNKNOWN_REASON
        }
    }
}
