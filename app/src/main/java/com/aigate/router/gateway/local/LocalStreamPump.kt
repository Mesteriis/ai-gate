package com.aigate.router.gateway.local

import com.aigate.router.gateway.OpenAiStreamCompat

/**
 * Превращение потока локального движка в кадры SSE формата OpenAI.
 *
 * Локальный движок отдаёт [LocalDelta] — у него нет ни своего JSON, ни своего
 * протокола, поэтому переводить, как это делает CodexUpstream, нечего: кадры
 * собираются здесь целиком. Отдельный класс нужен, чтобы `id` и `model` были
 * одинаковыми во всех чанках одного ответа: клиенты (в том числе Codex CLI)
 * сопоставляют чанки именно по `id`, и смена его на полпути ломает сборку
 * ответа.
 *
 * Класс отдаёт уже готовые к записи [ByteArray], а не строки: писать в тело
 * ответа приходится байтами, и повторная перекодировка на каждый токен —
 * лишняя работа на горячем пути генерации.
 *
 * Файл намеренно свободен от Android-зависимостей: формат кадра проверяется
 * обычными JVM-тестами.
 */
class LocalStreamPump(private val modelId: String, private val streamId: String) {

    /**
     * Кадры для одной единицы потока. Список, а не один кадр: пустой токен
     * кадра не порождает вовсе, и вызывающему коду не нужно знать, когда
     * писать, а когда молчать.
     */
    fun frameFor(delta: LocalDelta): List<ByteArray> = when (delta) {
        is LocalDelta.Token -> {
            // Пустая строка — это лишний кадр без содержимого, клиент от него
            // ничего не получает. Проверка именно на пустоту, а не на пробелы:
            // пробел и перевод строки — часть ответа модели, терять их нельзя.
            if (delta.text.isEmpty()) {
                emptyList()
            } else {
                listOf(frame(LocalOpenAi.chunkJson(streamId, modelId, delta.text, null)))
            }
        }

        is LocalDelta.Done -> listOf(
            frame(
                LocalOpenAi.chunkJson(
                    id = streamId,
                    model = modelId,
                    deltaText = null,
                    finishReason = delta.finishReason,
                    // Счётчики передаются как есть: если движок их не считает,
                    // в Done уже лежат нули, и выдумывать оценку здесь нельзя.
                    promptTokens = delta.promptTokens,
                    completionTokens = delta.completionTokens,
                )
            )
        )
    }

    /** Завершающий кадр потока — общий для всех апстримов шлюза. */
    fun doneFrame(): ByteArray = OpenAiStreamCompat.doneFrame()

    /**
     * Кадр на случай, когда движок завершился, не отдав ни одного токена:
     * клиент должен увидеть ошибку, а не пустой успешный ответ.
     */
    fun emptyErrorFrame(): ByteArray = OpenAiStreamCompat.emptyStreamErrorFrame()

    private fun frame(chunkJson: String): ByteArray =
        "data: $chunkJson\n\n".toByteArray(Charsets.UTF_8)
}
