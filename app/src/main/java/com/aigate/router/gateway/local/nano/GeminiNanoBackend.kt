package com.aigate.router.gateway.local.nano

import android.util.Log
import com.aigate.router.gateway.local.LocalBackend
import com.aigate.router.gateway.local.LocalBackendRegistry
import com.aigate.router.gateway.local.LocalChatRequest
import com.aigate.router.gateway.local.LocalDelta
import com.aigate.router.gateway.local.Readiness
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Встроенная в систему модель Gemini Nano.
 *
 * Считает не приложение, а системный сервис AICore, поэтому здесь нет ни
 * загрузки весов, ни управления памятью: всё это делает система, и она же
 * обновляет модель. Наша часть — превратить список сообщений в один текст и
 * разложить ответ обратно в поток токенов.
 *
 * Мьютекс движков этот бэкенд не берёт намеренно: счёт идёт в чужом процессе,
 * и занимать им очередь llama.cpp и LiteRT было бы неправдой.
 */
class GeminiNanoBackend : LocalBackend {

    override val providerType: String = LocalBackendRegistry.TYPE_NANO

    override suspend fun readiness(): Readiness = when (AiCoreStatus.availability()) {
        AiCoreStatus.Availability.AVAILABLE -> Readiness.Ready
        AiCoreStatus.Availability.DOWNLOADABLE ->
            Readiness.NotReady("Модель ещё не скачана системой")

        AiCoreStatus.Availability.DOWNLOADING ->
            Readiness.NotReady("Система скачивает модель")

        AiCoreStatus.Availability.UNAVAILABLE ->
            Readiness.NotReady("Встроенная модель недоступна на устройстве")
    }

    override fun generate(req: LocalChatRequest): Flow<LocalDelta> = flow {
        val model = AiCoreStatus.client()
            ?: throw IllegalStateException("Встроенная модель недоступна на устройстве")

        // Скачивание отсюда не запускаем: многогигабайтная загрузка по обычному
        // запросу в чат — не то, чего ждёт клиент. Её начинает пользователь.
        if (AiCoreStatus.availability() != AiCoreStatus.Availability.AVAILABLE) {
            throw IllegalStateException("Встроенная модель не готова к ответу")
        }

        val prompt = NanoPromptBuilder.build(req.messages)
        // Счётчик самой модели точнее оценки по символам, но он может быть
        // недоступен — тогда остаётся приблизительная цифра.
        val promptTokens = runCatching {
            model.countTokens(generateContentRequest(TextPart(prompt)) {}).totalTokens
        }.getOrElse { NanoPromptBuilder.estimateTokens(prompt) }

        var emitted = 0
        var accumulated = ""
        var finishReason = "stop"

        try {
            model.generateContentStream(prompt).collect { response ->
                val candidate = response.candidates.firstOrNull() ?: return@collect
                val text = candidate.text
                if (text.isEmpty()) return@collect
                // Библиотека может отдавать как прирост, так и весь текст
                // заново. Отличаем по началу строки: гадать нельзя, иначе ответ
                // либо задвоится, либо потеряет куски.
                val delta = if (text.startsWith(accumulated) && text.length > accumulated.length) {
                    text.substring(accumulated.length)
                } else {
                    text
                }
                accumulated = if (text.startsWith(accumulated)) text else accumulated + text
                if (delta.isNotEmpty()) {
                    emit(LocalDelta.Token(delta))
                    emitted++
                }
                // Ненулевая причина завершения означает, что модель оборвала
                // ответ сама — чаще всего упёрлась в предел вывода.
                candidate.finishReason?.let { if (it != 0) finishReason = "length" }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Встроенная модель прервала ответ: ${t.message}")
            // До первого токена это станет отказом и переключением на другую
            // модель; после — оборванным потоком, как и у сетевого пути.
            if (emitted == 0) throw t
        }

        emit(
            LocalDelta.Done(
                finishReason = finishReason,
                promptTokens = promptTokens,
                completionTokens = NanoPromptBuilder.estimateTokens(accumulated),
            )
        )
    }

    private companion object {
        const val TAG = "GeminiNanoBackend"
    }
}
