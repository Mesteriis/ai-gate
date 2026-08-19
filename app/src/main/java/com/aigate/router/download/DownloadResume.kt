package com.aigate.router.download

/**
 * Правила докачки: что делать с уже скачанным куском, когда сервер ответил.
 *
 * Вынесено из воркера отдельным объектом ради проверяемости. Само по себе
 * правило короткое, но ошибка в нём стоит дорого: неверное решение либо
 * склеивает старый кусок с новым в испорченный файл, либо каждый раз качает
 * гигабайты заново.
 */
object DownloadResume {

    const val HTTP_OK = 200
    const val HTTP_PARTIAL = 206
    const val HTTP_RANGE_NOT_SATISFIABLE = 416

    /** Что делать с накопленным куском после ответа сервера. */
    sealed interface Decision {
        /** Продолжать с накопленного места. */
        data object Continue : Decision

        /** Кусок недействителен: писать с нуля тем же ответом. */
        data object RestartWithSameResponse : Decision

        /** Кусок недействителен и ответ бесполезен: запрашивать заново без Range. */
        data object RestartWithNewRequest : Decision
    }

    /**
     * @param offset сколько байт уже лежит в частичном файле
     * @param code код ответа сервера
     */
    fun decide(offset: Long, code: Int): Decision = when {
        // Ничего не накоплено — и решать нечего.
        offset <= 0L -> Decision.Continue

        // Диапазон принят: сервер продолжает с нужного места.
        code == HTTP_PARTIAL -> Decision.Continue

        // Наш сдвиг серверу не подошёл: файл в реестре сменился. Тело такого
        // ответа не содержит нужных байт, поэтому нужен новый запрос.
        code == HTTP_RANGE_NOT_SATISFIABLE -> Decision.RestartWithNewRequest

        // 200 на запрос с Range означает, что сервер диапазон проигнорировал и
        // шлёт файл целиком. Тело годится, но старый кусок к нему не клеится.
        else -> Decision.RestartWithSameResponse
    }

    /** Заголовок диапазона или null, когда качаем с начала. */
    fun rangeHeader(offset: Long): String? = if (offset > 0L) "bytes=$offset-" else null
}
