package com.aigate.router.ui.design

import com.aigate.router.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Подбор знака провайдера: имя важнее типа, тип — это протокол, а не бренд. */
class ProviderBrandTest {

    @Test
    fun `name wins over protocol type`() {
        // Тип «OpenAI Compatible» не должен превращать DeepSeek в OpenAI.
        assertEquals(R.drawable.logo_deepseek, providerBrand("DeepSeek", "OpenAI Compatible").logo)
        assertEquals(R.drawable.logo_qwen, providerBrand("Qwen (Тунъи Цяньвэнь)", "OpenAI Compatible").logo)
    }

    @Test
    fun `codex and chatgpt share the openai mark`() {
        assertEquals(R.drawable.logo_openai, providerBrand("Codex", "codex").logo)
        assertEquals(R.drawable.logo_openai, providerBrand("Codex · user@example.com", "codex").logo)
    }

    @Test
    fun `local models get the ollama mark`() {
        assertEquals(R.drawable.logo_ollama, providerBrand("Ollama", "Ollama").logo)
        assertEquals(R.drawable.logo_ollama, providerBrand("Домашний сервер", "Ollama").logo)
    }

    @Test
    fun `type is used when the name says nothing`() {
        assertEquals(R.drawable.logo_claude, providerBrand("Мой ассистент", "Anthropic Claude").logo)
    }

    @Test
    fun `bare openai compatible provider keeps a neutral monogram`() {
        // Голый «OpenAI Compatible» с безымянным названием — это не бренд OpenAI.
        val brand = providerBrand("Свой сервис", "OpenAI Compatible")
        assertNull(brand.logo)
        assertEquals("С", brand.monogram)
    }

    @Test
    fun `unknown provider falls back to first letter`() {
        val brand = providerBrand("Zeta Labs", "Custom")
        assertNull(brand.logo)
        assertEquals("Z", brand.monogram)
    }

    @Test
    fun `distinct providers do not share a mark`() {
        assertNotEquals(
            providerBrand("DeepSeek", "OpenAI Compatible").logo,
            providerBrand("OpenAI", "OpenAI Compatible").logo,
        )
    }
}
