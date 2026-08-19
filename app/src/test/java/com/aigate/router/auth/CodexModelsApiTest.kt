package com.aigate.router.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Формат серверного списка моделей Codex. Структура снята с реального ответа
 * (объект `models[]` с полями slug / display_name / visibility /
 * supported_in_api / priority / context_window), слаги в тесте условные.
 */
class CodexModelsApiTest {

    @Test
    fun `models are parsed and sorted by priority`() {
        val body = """
            {"fetched_at":"2026-08-18T19:45:29Z","etag":"W/\"abc\"","client_version":"0.148.0",
             "models":[
               {"slug":"model-slow","display_name":"Model Slow","visibility":"list",
                "supported_in_api":true,"priority":7,"context_window":272000},
               {"slug":"model-fast","display_name":"Model Fast","visibility":"list",
                "supported_in_api":true,"priority":1,"context_window":400000}
             ]}
        """.trimIndent()

        val models = CodexModelsApi.parse(body)

        assertEquals(listOf("model-fast", "model-slow"), models.map { it.slug })
        assertEquals("Model Fast", models[0].displayName)
        assertEquals(400000, models[0].contextWindow)
    }

    @Test
    fun `hidden and api-unsupported models are skipped`() {
        val body = """
            {"models":[
              {"slug":"visible","display_name":"Visible","visibility":"list","supported_in_api":true,"priority":1},
              {"slug":"internal","display_name":"Internal","visibility":"hide","supported_in_api":true,"priority":2},
              {"slug":"no-api","display_name":"No API","visibility":"list","supported_in_api":false,"priority":3}
            ]}
        """.trimIndent()

        assertEquals(listOf("visible"), CodexModelsApi.parse(body).map { it.slug })
    }

    @Test
    fun `display name falls back to slug and context window may be absent`() {
        val body = """{"models":[{"slug":"bare","visibility":"list","supported_in_api":true}]}"""

        val model = CodexModelsApi.parse(body).single()

        assertEquals("bare", model.slug)
        assertEquals("bare", model.displayName)
        assertNull(model.contextWindow)
    }

    @Test
    fun `max_context_window is used when context_window is missing`() {
        val body = """{"models":[{"slug":"m","max_context_window":128000}]}"""
        assertEquals(128000, CodexModelsApi.parse(body).single().contextWindow)
    }

    @Test
    fun `openai style data array is also accepted`() {
        val body = """{"data":[{"id":"m-1"},{"id":"m-2"}]}"""
        assertEquals(listOf("m-1", "m-2"), CodexModelsApi.parse(body).map { it.slug })
    }

    @Test
    fun `garbage does not throw and yields nothing`() {
        listOf("", "не json", "{}", """{"models":[]}""", """{"models":[{"display_name":"нет слага"}]}""")
            .forEach { assertTrue(CodexModelsApi.parse(it).isEmpty()) }
    }
}
