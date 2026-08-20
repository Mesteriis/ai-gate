package com.aigate.router.network

import com.aigate.router.data.model.Provider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Разбор списков моделей: у каждого семейства провайдеров свой формат ответа. */
class ModelCatalogApiTest {

    private fun provider(type: String) = Provider(name = "p", type = type, baseUrl = "https://x")

    @Test
    fun `family is detected by provider type`() {
        assertEquals(ModelCatalogApi.Family.ANTHROPIC, ModelCatalogApi.familyOf(provider("Anthropic")))
        assertEquals(ModelCatalogApi.Family.ANTHROPIC, ModelCatalogApi.familyOf(provider("Claude Code")))
        assertEquals(ModelCatalogApi.Family.GEMINI, ModelCatalogApi.familyOf(provider("Google Gemini")))
        assertEquals(ModelCatalogApi.Family.OPENAI_COMPATIBLE, ModelCatalogApi.familyOf(provider("OpenAI Compatible")))
        assertEquals(ModelCatalogApi.Family.OPENAI_COMPATIBLE, ModelCatalogApi.familyOf(provider("Ollama")))
    }

    @Test
    fun `anthropic list uses id and display_name`() {
        val body = """
            {"data":[{"type":"model","id":"claude-x-1","display_name":"Claude X 1"},
                     {"type":"model","id":"claude-x-2"}]}
        """.trimIndent()

        val models = ModelCatalogApi.parse(body, ModelCatalogApi.Family.ANTHROPIC)

        assertEquals(2, models.size)
        assertEquals("claude-x-1", models[0].id)
        assertEquals("Claude X 1", models[0].displayName)
        // Без display_name подписью становится сам идентификатор.
        assertEquals("claude-x-2", models[1].displayName)
    }

    @Test
    fun `gemini strips models prefix and keeps only chat models`() {
        val body = """
            {"models":[
              {"name":"models/gemini-x-pro","displayName":"Gemini X Pro",
               "supportedGenerationMethods":["generateContent","countTokens"]},
              {"name":"models/text-embedding-x","displayName":"Embeddings",
               "supportedGenerationMethods":["embedContent"]}
            ]}
        """.trimIndent()

        val models = ModelCatalogApi.parse(body, ModelCatalogApi.Family.GEMINI)

        assertEquals(1, models.size)
        assertEquals("gemini-x-pro", models.single().id)
        assertEquals("Gemini X Pro", models.single().displayName)
    }

    @Test
    fun `openai compatible list reads data array`() {
        val body = """{"object":"list","data":[{"id":"model-a"},{"id":"model-b"}]}"""

        val models = ModelCatalogApi.parse(body, ModelCatalogApi.Family.OPENAI_COMPATIBLE)

        assertEquals(listOf("model-a", "model-b"), models.map { it.id })
    }

    @Test
    fun `garbage response yields empty list instead of throwing`() {
        listOf("", "не json", "{}", """{"data":[]}""").forEach { body ->
            assertTrue(ModelCatalogApi.parse(body, ModelCatalogApi.Family.OPENAI_COMPATIBLE).isEmpty())
            assertTrue(ModelCatalogApi.parse(body, ModelCatalogApi.Family.GEMINI).isEmpty())
        }
    }

    @Test
    fun `сетевым считается только http-адрес`() {
        assertTrue(ModelCatalogApi.isNetworkAddress("http://10.34.10.2:11434"))
        assertTrue(ModelCatalogApi.isNetworkAddress("https://api.deepseek.com"))
        assertTrue(ModelCatalogApi.isNetworkAddress("  HTTPS://API.DEEPSEEK.COM  "))
        // Модели на устройстве адресуются схемой local:// — спрашивать их по сети нечего.
        assertFalse(ModelCatalogApi.isNetworkAddress("local://models"))
        assertFalse(ModelCatalogApi.isNetworkAddress("local://device"))
        assertFalse(ModelCatalogApi.isNetworkAddress("api.deepseek.com"))
        assertFalse(ModelCatalogApi.isNetworkAddress(""))
    }

    @Test
    fun `модель на устройстве не запрашивается по сети и не роняет приложение`() = runBlocking {
        // Запрос каталога у local:// падал с IllegalArgumentException прямо в
        // Request.Builder.url, и проверка связи роняла приложение целиком.
        assertNull(
            ModelCatalogApi.fetch(
                Provider(name = "Локальные модели", type = "local-llamacpp", baseUrl = "local://models"),
                apiKey = null,
            )
        )
    }

    @Test
    fun `испорченный адрес возвращает null, а не исключение`() = runBlocking {
        // Адрес провайдера вводит пользователь, поэтому там может оказаться что угодно.
        listOf("ftp://файлы", "просто текст", "://", "http://").forEach { url ->
            assertNull(
                url,
                ModelCatalogApi.fetch(
                    Provider(name = "p", type = "OpenAI Compatible", baseUrl = url),
                    apiKey = null,
                )
            )
        }
    }
}
