package com.aigate.router.gateway.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контракт реестра локальных бэкендов: по какому типу шлюз уходит в процесс
 * вместо HTTP и как значение движка переживает базу.
 */
class LocalBackendRegistryTest {

    private class FakeBackend(override val providerType: String) : LocalBackend {
        override suspend fun readiness(): Readiness = Readiness.Ready
        override fun generate(req: LocalChatRequest): Flow<LocalDelta> =
            flowOf(LocalDelta.Done("stop", 0, 0))
    }

    @After
    fun tearDown() {
        // Реестр — синглтон и переживает тест, иначе порядок тестов начинает
        // влиять на результат.
        LocalBackendRegistry.clear()
    }

    @Test
    fun `все три локальных типа принадлежат реестру`() {
        assertTrue(LocalBackendRegistry.ownsType(LocalBackendRegistry.TYPE_NANO))
        assertTrue(LocalBackendRegistry.ownsType(LocalBackendRegistry.TYPE_LLAMA))
        assertTrue(LocalBackendRegistry.ownsType(LocalBackendRegistry.TYPE_LITERT))
        assertEquals(3, LocalBackendRegistry.LOCAL_TYPES.size)
    }

    @Test
    fun `регистр и пробелы в типе не мешают опознать локальный бэкенд`() {
        // Тип приходит из базы и из формы ручного добавления провайдера, где
        // пользователь мог написать что угодно.
        assertTrue(LocalBackendRegistry.ownsType("Device-Gemini-Nano"))
        assertTrue(LocalBackendRegistry.ownsType("LOCAL-LLAMACPP"))
        assertTrue(LocalBackendRegistry.ownsType("  local-litertlm  "))
    }

    @Test
    fun `сетевой провайдер не считается локальным`() {
        assertFalse(LocalBackendRegistry.ownsType("codex"))
        assertFalse(LocalBackendRegistry.ownsType("openai"))
        assertFalse(LocalBackendRegistry.ownsType(""))
    }

    @Test
    fun `зарегистрированный бэкенд находится по своему типу`() {
        val backend = FakeBackend(LocalBackendRegistry.TYPE_LLAMA)

        LocalBackendRegistry.register(backend)

        assertSame(backend, LocalBackendRegistry.forType(LocalBackendRegistry.TYPE_LLAMA))
        assertSame(backend, LocalBackendRegistry.forType("Local-LlamaCpp"))
    }

    @Test
    fun `незарегистрированный тип не подменяется чужим бэкендом`() {
        LocalBackendRegistry.register(FakeBackend(LocalBackendRegistry.TYPE_LLAMA))

        assertNull(LocalBackendRegistry.forType(LocalBackendRegistry.TYPE_NANO))
        assertNull(LocalBackendRegistry.forType("codex"))
    }

    @Test
    fun `повторная регистрация типа заменяет прежний бэкенд`() {
        LocalBackendRegistry.register(FakeBackend(LocalBackendRegistry.TYPE_LITERT))
        val replacement = FakeBackend(LocalBackendRegistry.TYPE_LITERT)

        LocalBackendRegistry.register(replacement)

        assertSame(replacement, LocalBackendRegistry.forType(LocalBackendRegistry.TYPE_LITERT))
    }

    @Test
    fun `clear убирает все регистрации`() {
        LocalBackendRegistry.register(FakeBackend(LocalBackendRegistry.TYPE_NANO))
        LocalBackendRegistry.register(FakeBackend(LocalBackendRegistry.TYPE_LLAMA))

        LocalBackendRegistry.clear()

        assertNull(LocalBackendRegistry.forType(LocalBackendRegistry.TYPE_NANO))
        assertNull(LocalBackendRegistry.forType(LocalBackendRegistry.TYPE_LLAMA))
        // Список поддерживаемых типов от очистки не зависит.
        assertTrue(LocalBackendRegistry.ownsType(LocalBackendRegistry.TYPE_NANO))
    }

    @Test
    fun `значение движка в базе разбирается независимо от регистра`() {
        assertEquals(EngineKind.GGUF, EngineKind.fromDbValue("gguf"))
        assertEquals(EngineKind.GGUF, EngineKind.fromDbValue("GGUF"))
        assertEquals(EngineKind.LITERT, EngineKind.fromDbValue("litertlm"))
        assertEquals(EngineKind.LITERT, EngineKind.fromDbValue("LiteRTLM"))
    }

    @Test
    fun `неизвестное значение движка даёт null а не падение`() {
        // Строка приходит из базы: старая запись или ручная правка не должны
        // ронять загрузку каталога моделей.
        assertNull(EngineKind.fromDbValue("onnx"))
        assertNull(EngineKind.fromDbValue(""))
    }

    @Test
    fun `строковые значения движков зафиксированы`() {
        // Значения переживают обновление приложения — менять их нельзя.
        assertEquals("gguf", EngineKind.GGUF.dbValue)
        assertEquals("litertlm", EngineKind.LITERT.dbValue)
    }
}
