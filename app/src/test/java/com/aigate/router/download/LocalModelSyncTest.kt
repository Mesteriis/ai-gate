package com.aigate.router.download

import com.aigate.router.data.model.LocalModel
import com.aigate.router.gateway.local.EngineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контракт превращения скачанного файла в строку списка моделей шлюза.
 *
 * Идентификатор здесь важнее всего: его клиенты присылают в поле `model`, и
 * изменить его форму потом уже нельзя — сломаются чужие настройки. Поэтому он
 * проверяется буквально, а не через ту же функцию, что его строит.
 */
class LocalModelSyncTest {

    private fun localModel(
        source: String = LocalModel.SOURCE_OLLAMA,
        repo: String = "qwen3",
        ref: String = "4b",
        engine: EngineKind = EngineKind.GGUF,
        displayName: String = "qwen3:4b",
        contextWindow: Int = 8192,
    ) = LocalModel(
        id = 7,
        source = source,
        repo = repo,
        ref = ref,
        engine = engine.dbValue,
        displayName = displayName,
        sizeBytes = 2_500_000_000L,
        state = LocalModel.STATE_READY,
        contextWindow = contextWindow,
    )

    @Test
    fun `идентификатор собирается из движка, репозитория и ссылки`() {
        val model = toAiModel(localModel(), providerId = 3, isEnabled = true)

        assertEquals("local/gguf/qwen3:4b", model.modelId)
        assertEquals(3L, model.providerId)
    }

    @Test
    fun `движок входит в идентификатор, иначе один файл слился бы с другим`() {
        val litert = toAiModel(
            localModel(
                source = LocalModel.SOURCE_HF,
                repo = "litert-community/Gemma3-1B-IT",
                ref = "gemma3-1b-it-int4.litertlm",
                engine = EngineKind.LITERT,
            ),
            providerId = 5,
            isEnabled = true,
        )

        assertEquals(
            "local/litertlm/litert-community/Gemma3-1B-IT:gemma3-1b-it-int4.litertlm",
            litert.modelId
        )
    }

    @Test
    fun `имя и окно контекста переносятся из файла`() {
        val model = toAiModel(
            localModel(displayName = "Qwen3-4B-Q4_K_M", contextWindow = 16_384),
            providerId = 1,
            isEnabled = true,
        )

        assertEquals("Qwen3-4B-Q4_K_M", model.displayName)
        assertEquals(16_384, model.contextWindow)
        // Алиас — пользовательское поле, синхронизация его не выдумывает.
        assertEquals("", model.customAlias)
    }

    @Test
    fun `выключенная пользователем модель остаётся выключенной`() {
        val disabled = toAiModel(localModel(), providerId = 1, isEnabled = false)
        val enabled = toAiModel(localModel(), providerId = 1, isEnabled = true)

        assertFalse("повторная синхронизация не имеет права включить модель обратно", disabled.isEnabled)
        assertTrue(enabled.isEnabled)
    }

    @Test
    fun `локальная модель не ходит через прокси и считается синхронизированной`() {
        val model = toAiModel(localModel(), providerId = 1, isEnabled = true)

        // Счёт идёт в процессе приложения: гнать его через прокси нечего.
        assertFalse(model.useProxy)
        assertEquals("Synced", model.syncStatus)
    }
}
