package com.aigate.router.quota

import com.aigate.router.data.model.QuotaSnapshot

/** Что заставило обновление запуститься. От этого зависит допустимая частота. */
enum class RefreshTrigger { APP_START, PERIODIC, SCREEN_OPEN, USER_ACTION }

/** Чем закончилось обновление конкретного пула. */
enum class PoolRefreshOutcome {
    /** Ответ провайдера получен и записан. */
    OK_PROVIDER,

    /** Записан локальный расчёт: у пула нет адаптера либо данные провайдера давно мертвы. */
    OK_LOCAL,

    /** Слишком рано после предыдущей попытки. */
    SKIPPED_THROTTLED,

    /** Токен обновить не удалось, запрос к провайдеру заведомо не пройдёт. */
    AUTH_EXPIRED,

    /** Провайдер ответил ошибкой либо ответ не разобран. */
    FETCH_FAILED,

    /** Сети нет, к провайдеру не ходим. */
    OFFLINE,
}

/**
 * Когда можно идти к провайдеру за квотой и что записывать, если он не ответил.
 *
 * Правила вынесены из планировщиков намеренно: тик сервиса, воркер, старт
 * приложения и открытие экрана зовут одно и то же обновление, поэтому решение
 * «пора или рано» должно быть одно на всех и проверяться тестами без Android.
 */
object RefreshPolicy {

    private const val MINUTE_MS = 60_000L

    /** Целевой период обновления квот. */
    const val INTERVAL_MS = 5 * MINUTE_MS

    /**
     * Данные провайдера считаются живыми сутки: пока они есть, локальная оценка
     * их не подменяет.
     */
    private const val PROVIDER_DATA_TTL_MS = 24 * 60 * MINUTE_MS

    /** Пора ли обновляться. Двигатель тика в сервисе. */
    fun isDue(lastAttemptAt: Long?, now: Long, intervalMs: Long = INTERVAL_MS): Boolean {
        if (lastAttemptAt == null) return true
        // Метка из будущего означает сдвиг системных часов, а не свежее
        // обновление: иначе перевод часов вперёд запер бы квоты до тех пор,
        // пока реальное время его не догонит.
        if (lastAttemptAt > now) return true
        return now - lastAttemptAt >= intervalMs
    }

    /**
     * Минимальный промежуток между запросами к провайдеру для каждого триггера.
     * Запуск процесса идёт к провайдеру без задержки: кэш пуст, показывать нечего.
     */
    private fun minIntervalFor(trigger: RefreshTrigger): Long = when (trigger) {
        RefreshTrigger.APP_START -> 0L
        // Чуть меньше пяти минут, чтобы штатный тик всегда проходил, а воркер,
        // сработавший следом за ним, — нет.
        RefreshTrigger.PERIODIC -> 4 * MINUTE_MS
        RefreshTrigger.SCREEN_OPEN -> 2 * MINUTE_MS
        RefreshTrigger.USER_ACTION -> 30_000L
    }

    /**
     * Идти ли к провайдеру за этим пулом.
     *
     * Гейт считается по последней **попытке**, а не по последнему успеху: иначе
     * провайдер, который стабильно отвечает ошибкой, получал бы запрос при каждом
     * открытии экрана. Метка удачного ответа участвует потому, что память о
     * попытках живёт в процессе, а метка ответа — в базе: после пересоздания
     * процесса только она и остаётся.
     */
    fun shouldFetchRemote(
        trigger: RefreshTrigger,
        lastProviderApiAt: Long?,
        lastAttemptAt: Long?,
        now: Long,
    ): Boolean {
        val minInterval = minIntervalFor(trigger)
        if (minInterval == 0L) return true
        val last = maxOf(lastAttemptAt ?: Long.MIN_VALUE, lastProviderApiAt ?: Long.MIN_VALUE)
        if (last == Long.MIN_VALUE) return true
        return isDue(last, now, minInterval)
    }

    /**
     * Записывать ли локальный расчёт, когда провайдерский ответ не получен.
     *
     * Нет: пока в базе лежит свежий ответ провайдера, локальная оценка поверх
     * него врала бы дважды — числом и свежестью. Устаревший снимок провайдера
     * честнее выдуманного «обновлено только что», а его возраст видно в интерфейсе.
     */
    fun shouldWriteLocalFallback(
        hasAdapter: Boolean,
        lastProviderApiAt: Long?,
        now: Long,
    ): Boolean {
        if (!hasAdapter) return true
        if (lastProviderApiAt == null) return true
        return now - lastProviderApiAt >= PROVIDER_DATA_TTL_MS
    }

    /**
     * Совпадают ли показания, если не смотреть на идентификатор и время записи.
     * При обновлении раз в пять минут вставка каждого повтора раздула бы историю
     * примерно до 288 строк на пул в сутки, поэтому неизменившемуся показанию
     * достаточно обновить метку времени.
     */
    fun sameReading(previous: QuotaSnapshot?, next: QuotaSnapshot): Boolean {
        if (previous == null) return false
        return previous.used == next.used &&
            previous.remaining == next.remaining &&
            previous.limit == next.limit &&
            previous.unit.equals(next.unit, ignoreCase = true) &&
            previous.resetsAt == next.resetsAt &&
            previous.source == next.source
    }
}
