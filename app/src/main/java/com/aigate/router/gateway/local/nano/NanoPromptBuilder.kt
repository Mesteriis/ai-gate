package com.aigate.router.gateway.local.nano

import com.aigate.router.gateway.local.ChatMsg

/**
 * Сборка одной строки запроса для Gemini Nano.
 *
 * Модель принимает не список сообщений, а единственный текст, поэтому диалог
 * приходится разворачивать в стенограмму. И у неё жёсткий предел входа около
 * четырёх тысяч токенов — превышение отвергается целиком, а не обрезается само,
 * так что решать, чем пожертвовать, должны мы.
 *
 * Правило простое и предсказуемое: системные указания и последний вопрос
 * пользователя не выбрасываются никогда — без них ответ будет не тем, о чём
 * спрашивали. Жертвуют старыми ходами беседы, парами, с начала.
 *
 * Файл свободен от android.* и от классов ML Kit: так правило проверяется
 * обычными JVM-тестами.
 */
object NanoPromptBuilder {

    /**
     * Запас от заявленного предела в 4000 токенов. Оценка числа токенов по
     * символам неточна, и упереться в предел на живом запросе хуже, чем
     * отдать модели чуть меньше контекста.
     */
    const val DEFAULT_BUDGET_TOKENS = 3800

    private const val USER_PREFIX = "Пользователь: "
    private const val ASSISTANT_PREFIX = "Ассистент: "

    /** Метка обрыва внутри одного длинного сообщения. */
    private const val ELLIPSIS = " […] "

    /**
     * Стенограмма диалога одной строкой.
     *
     * @param estimate оценка числа токенов; по умолчанию грубая, но на месте
     * вызова стоит подставить настоящий счётчик модели — он знает её словарь.
     */
    fun build(
        messages: List<ChatMsg>,
        budgetTokens: Int = DEFAULT_BUDGET_TOKENS,
        estimate: (String) -> Int = ::estimateTokens,
    ): String {
        val system = messages
            .filter { it.role == "system" || it.role == "developer" }
            .map { it.text }
            .filter { it.isNotBlank() }
        val dialogue = messages
            .filter { it.role != "system" && it.role != "developer" }
            .filter { it.text.isNotBlank() }

        val systemBlock = system.joinToString("\n\n")
        // Последний ход пользователя — это и есть вопрос; всё прочее лишь фон.
        val lastIndex = dialogue.indexOfLast { it.role == "user" }
        val mandatory = if (lastIndex >= 0) listOf(dialogue[lastIndex]) else dialogue.takeLast(1)
        val history = if (lastIndex >= 0) dialogue.subList(0, lastIndex) else emptyList()

        var kept = history
        while (true) {
            val text = render(systemBlock, kept + mandatory)
            if (estimate(text) <= budgetTokens || kept.isEmpty()) break
            // Парами: вопрос без ответа и ответ без вопроса одинаково сбивают
            // модель, поэтому старый ход выбрасывается целиком.
            kept = kept.drop(minOf(2, kept.size))
        }

        val assembled = render(systemBlock, kept + mandatory)
        if (estimate(assembled) <= budgetTokens) return assembled

        // Ужимать больше нечего: не влезает уже само обязательное. Режем
        // середину, сохраняя начало и конец — в них обычно и суть.
        return trimMiddle(assembled, budgetTokens, estimate)
    }

    private fun render(systemBlock: String, dialogue: List<ChatMsg>): String {
        val body = dialogue.joinToString("\n") { msg ->
            when (msg.role) {
                "assistant" -> ASSISTANT_PREFIX + msg.text
                else -> USER_PREFIX + msg.text
            }
        }
        val parts = listOf(systemBlock, body).filter { it.isNotBlank() }
        // Пустая строка «Ассистент:» в конце — приглашение продолжить именно
        // ответом, а не новым вопросом от лица пользователя.
        return (parts.joinToString("\n\n") + "\n" + ASSISTANT_PREFIX.trimEnd()).trimStart()
    }

    /**
     * Обрезка середины по символам. Двоичный поиск не нужен: доля лишнего
     * известна из оценки, а запас берём с двойным коэффициентом, потому что
     * оценка занижает на текстах с длинными словами.
     */
    private fun trimMiddle(text: String, budgetTokens: Int, estimate: (String) -> Int): String {
        var allowed = text.length
        repeat(8) {
            val tokens = estimate(text.take(allowed))
            if (tokens <= budgetTokens) return@repeat
            allowed = (allowed.toDouble() * budgetTokens / tokens * 0.9).toInt().coerceAtLeast(ELLIPSIS.length + 2)
        }
        if (allowed >= text.length) return text
        val half = (allowed - ELLIPSIS.length).coerceAtLeast(2) / 2
        return text.take(half) + ELLIPSIS + text.takeLast(half)
    }

    /**
     * Грубая оценка: четыре символа на токен для латиницы и кириллицы, для
     * иероглифов — почти символ в токен. Тот же приём, что в замерах скорости,
     * чтобы числа в разных местах приложения не расходились.
     */
    fun estimateTokens(text: String): Int {
        val cjk = text.count { it in '一'..'龥' }
        val other = text.length - cjk
        return (cjk * 0.65 + other / 4.0).toInt().coerceAtLeast(1)
    }
}
