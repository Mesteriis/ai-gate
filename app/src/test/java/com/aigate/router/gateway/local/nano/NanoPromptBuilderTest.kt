package com.aigate.router.gateway.local.nano

import com.aigate.router.gateway.local.ChatMsg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Стенограмма для Gemini Nano. Модель принимает один текст и жёстко отвергает
 * превышение предела, поэтому проверяется и порядок частей, и то, чем именно
 * жертвует сборка, когда всё не помещается.
 */
class NanoPromptBuilderTest {

    private fun user(text: String) = ChatMsg("user", text)
    private fun assistant(text: String) = ChatMsg("assistant", text)
    private fun system(text: String) = ChatMsg("system", text)

    @Test
    fun `system instructions come first and dialogue keeps its order`() {
        val prompt = NanoPromptBuilder.build(
            listOf(
                system("Отвечай кратко."),
                user("Первый вопрос"),
                assistant("Первый ответ"),
                user("Второй вопрос"),
            )
        )

        val systemAt = prompt.indexOf("Отвечай кратко.")
        val firstAt = prompt.indexOf("Первый вопрос")
        val secondAt = prompt.indexOf("Второй вопрос")
        assertTrue("системная часть должна быть выше диалога", systemAt in 0 until firstAt)
        assertTrue("порядок реплик должен сохраняться", firstAt < secondAt)
        assertTrue("реплики размечены ролями", prompt.contains("Пользователь: Первый вопрос"))
        assertTrue(prompt.contains("Ассистент: Первый ответ"))
    }

    @Test
    fun `prompt ends with an invitation to answer`() {
        val prompt = NanoPromptBuilder.build(listOf(user("Вопрос")))

        assertTrue("в конце должно быть приглашение ответить: $prompt", prompt.trimEnd().endsWith("Ассистент:"))
    }

    @Test
    fun `several system messages are merged`() {
        val prompt = NanoPromptBuilder.build(
            listOf(system("Правило один."), system("Правило два."), user("Вопрос"))
        )

        assertTrue(prompt.contains("Правило один."))
        assertTrue(prompt.contains("Правило два."))
    }

    @Test
    fun `role developer is treated as system`() {
        val prompt = NanoPromptBuilder.build(listOf(ChatMsg("developer", "Служебное"), user("Вопрос")))

        assertTrue(prompt.indexOf("Служебное") < prompt.indexOf("Вопрос"))
        assertFalse("служебная роль не должна попасть в стенограмму", prompt.contains("Пользователь: Служебное"))
    }

    @Test
    fun `oldest turns are dropped first while the question survives`() {
        // Бюджет намеренно мал: поместится только часть беседы.
        val messages = buildList {
            add(system("Система"))
            repeat(10) { i ->
                add(user("Старый вопрос номер $i с длинным продолжением текста"))
                add(assistant("Старый ответ номер $i с длинным продолжением текста"))
            }
            add(user("Самый свежий вопрос"))
        }

        val prompt = NanoPromptBuilder.build(messages, budgetTokens = 60)

        assertTrue("вопрос обязан уцелеть", prompt.contains("Самый свежий вопрос"))
        assertTrue("системная часть обязана уцелеть", prompt.contains("Система"))
        assertFalse("самый старый ход должен быть выброшен", prompt.contains("номер 0"))
    }

    @Test
    fun `result fits the budget`() {
        val messages = buildList {
            add(system("Система"))
            repeat(30) { add(user("Реплика номер $it, довольно длинная строка для набора объёма")) }
        }

        val prompt = NanoPromptBuilder.build(messages, budgetTokens = 100)

        assertTrue(
            "оценка ${NanoPromptBuilder.estimateTokens(prompt)} не должна превышать бюджет",
            NanoPromptBuilder.estimateTokens(prompt) <= 100,
        )
    }

    @Test
    fun `a single oversized message is cut in the middle keeping both ends`() {
        val long = "начало " + "х".repeat(5000) + " конец"

        val prompt = NanoPromptBuilder.build(listOf(user(long)), budgetTokens = 50)

        assertTrue("начало должно уцелеть", prompt.contains("начало"))
        assertTrue("должна стоять метка обрыва: $prompt", prompt.contains("[…]"))
        assertTrue(
            "результат обязан уложиться в бюджет",
            NanoPromptBuilder.estimateTokens(prompt) <= 50,
        )
    }

    @Test
    fun `blank messages are skipped`() {
        val prompt = NanoPromptBuilder.build(listOf(user("   "), user("Вопрос")))

        assertFalse("пустая реплика не должна давать пустую строку роли", prompt.contains("Пользователь:    "))
        assertTrue(prompt.contains("Пользователь: Вопрос"))
    }

    @Test
    fun `custom estimator is used instead of the default one`() {
        var calls = 0
        NanoPromptBuilder.build(listOf(user("Вопрос")), estimate = { calls++; it.length })

        assertTrue("сборка обязана спрашивать переданный счётчик", calls > 0)
    }

    @Test
    fun `hieroglyphs are counted denser than latin`() {
        val cjk = NanoPromptBuilder.estimateTokens("这是一个测试")
        val latin = NanoPromptBuilder.estimateTokens("abcdef")

        assertTrue("иероглифы должны стоить дороже: $cjk против $latin", cjk > latin)
    }

    @Test
    fun `dialogue without a user message still produces a prompt`() {
        val prompt = NanoPromptBuilder.build(listOf(assistant("Только ответ")))

        assertEquals(false, prompt.isBlank())
        assertTrue(prompt.contains("Только ответ"))
    }
}
