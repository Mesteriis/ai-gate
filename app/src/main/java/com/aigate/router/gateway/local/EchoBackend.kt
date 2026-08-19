package com.aigate.router.gateway.local

import com.aigate.router.service.GatewayForegroundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Отладочный локальный бэкенд: отвечает без всякой модели.
 *
 * Нужен, чтобы проверить сам путь обслуживания — поток, переключение при
 * сбое, обрыв соединения, учёт токенов — до того, как в проекте появятся
 * настоящие движки. Отвечает медленно и по кусочкам именно ради этого:
 * мгновенный ответ не позволил бы поймать ошибки потоковой записи.
 *
 * Включается только вручную ключом настроек и никогда не регистрируется сам.
 */
class EchoBackend(override val providerType: String = LocalBackendRegistry.TYPE_LLAMA) : LocalBackend {

    /** Ключ в aigate_config; без него бэкенд не подключается. */
    companion object {
        const val KEY_ENABLED = "local_echo_backend"

        fun isEnabled(): Boolean =
            GatewayForegroundService.getGatewayConfig(KEY_ENABLED, "false").trim().equals("true", ignoreCase = true)
    }

    override suspend fun readiness(): Readiness =
        if (isEnabled()) Readiness.Ready else Readiness.NotReady("Отладочный бэкенд выключен")

    override fun generate(req: LocalChatRequest): Flow<LocalDelta> = flow {
        val prompt = req.messages.lastOrNull { it.role == "user" }?.text.orEmpty()
        val answer = "Отладочный ответ на: ${prompt.take(200)}"
        // Задержка перед первым куском проверяет ожидание первого токена,
        // а разбиение по словам — потоковую запись и отмену на середине.
        delay(120)
        var emitted = 0
        for (word in answer.split(' ')) {
            emit(LocalDelta.Token(if (emitted == 0) word else " $word"))
            emitted++
            delay(40)
        }
        emit(
            LocalDelta.Done(
                finishReason = "stop",
                promptTokens = estimateTokens(prompt),
                completionTokens = estimateTokens(answer),
            )
        )
    }

    /** Грубая оценка, как в замерах скорости: четыре символа на токен. */
    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)
}
