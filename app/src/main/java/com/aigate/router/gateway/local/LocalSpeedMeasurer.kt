package com.aigate.router.gateway.local

import com.aigate.router.capability.LocalGuard
import com.aigate.router.data.model.SpeedMetrics
import com.aigate.router.utils.ModelSpeedTester
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Замер скорости локальной модели.
 *
 * Сетевой замер сюда не годится: у локального провайдера нет ни адреса, ни
 * ключа. Зато и подделывать метрики нельзя — рейтинг моделей и выбор для
 * `auto`-запроса строятся на сравнении локальных и облачных чисел, поэтому
 * счёт идёт настоящий, тем же путём, которым пойдёт живой запрос.
 *
 * Отказ кодируется так же, как в [ModelSpeedTester]: ttftMs = -1. Обход всех
 * моделей уже умеет считать такие записи неудачами, и отдельная ветка в UI
 * не нужна.
 */
object LocalSpeedMeasurer {

    /** Потолок замера. В него входит и загрузка весов в память. */
    private const val MEASURE_TIMEOUT_MS = 90_000L

    /** Столько токенов достаточно, чтобы устойчиво посчитать скорость. */
    private const val MAX_TOKENS = 200

    suspend fun measure(modelId: String, providerType: String): SpeedMetrics {
        val backend = LocalBackendRegistry.forType(providerType) ?: return failure()
        LocalGuard.blockReason(providerType)?.let { return failure() }
        if (backend.readiness() !is Readiness.Ready) return failure()

        val request = LocalChatRequest(
            modelId = modelId,
            messages = listOf(ChatMsg(role = "user", text = ModelSpeedTester.DEFAULT_PROMPT)),
            maxTokens = MAX_TOKENS,
            temperature = null,
        )

        val startedAt = System.currentTimeMillis()
        var firstTokenAt = 0L
        var tokenCount = 0

        val completed = withTimeoutOrNull(MEASURE_TIMEOUT_MS) {
            runCatching {
                backend.generate(request).collect { delta ->
                    when (delta) {
                        is LocalDelta.Token -> {
                            if (firstTokenAt == 0L) firstTokenAt = System.currentTimeMillis()
                            tokenCount++
                        }

                        is LocalDelta.Done -> {
                            // Движок знает точное число токенов — оно честнее
                            // подсчёта кадров, где один кадр не равен токену.
                            if (delta.completionTokens > 0) tokenCount = delta.completionTokens
                        }
                    }
                }
            }.isSuccess
        } ?: false

        if (!completed || firstTokenAt == 0L) return failure()

        val now = System.currentTimeMillis()
        val ttft = firstTokenAt - startedAt
        val generationMs = (now - firstTokenAt).coerceAtLeast(1)
        return SpeedMetrics(
            ttftMs = ttft,
            tps = tokenCount * 1000.0 / generationMs,
            totalMs = now - startedAt,
            tokenCount = tokenCount,
            measuredAt = now,
        )
    }

    private fun failure() = SpeedMetrics(
        ttftMs = -1,
        tps = 0.0,
        totalMs = 0,
        tokenCount = 0,
        measuredAt = System.currentTimeMillis(),
    )
}
