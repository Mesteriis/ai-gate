package com.aigate.router.gateway.local

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow

/*
 * Общие контракты бэкендов, которые считают прямо в процессе приложения:
 * Gemini Nano (AICore), llama.cpp и LiteRT-LM.
 *
 * У таких бэкендов нет ни базового URL, ни ключа, ни ответа провайдера,
 * поэтому переводить чужой JSON, как это делает CodexUpstream, нечего:
 * движок отдаёт поток токенов, а сборку OpenAI-совместимого ответа берёт
 * на себя шлюз. Здесь описан минимум, одинаковый для всех трёх движков.
 *
 * Файл намеренно свободен от Android-зависимостей: контракт и реестр должны
 * проверяться обычными JVM-тестами, без эмулятора и Robolectric.
 */

/** Одно сообщение диалога в форме, не зависящей от формата провайдера. */
data class ChatMsg(val role: String, val text: String)

/**
 * Запрос к локальному движку.
 *
 * [modelId] — идентификатор именно движка (имя файла модели или её ключ в
 * AICore), а не строка из запроса клиента: разрешение алиасов делается выше.
 * [maxTokens] и [temperature] опциональны, потому что часть движков (Nano)
 * не даёт ими управлять, и подставлять свои значения вместо системных нельзя.
 */
data class LocalChatRequest(
    val modelId: String,
    val messages: List<ChatMsg>,
    val maxTokens: Int?,
    val temperature: Double?,
)

/**
 * Единица потока генерации. Разделение на [Token] и [Done] нужно, чтобы шлюз
 * закрывал SSE и списывал расход по одному и тому же событию — без догадок
 * по факту завершения Flow.
 */
sealed interface LocalDelta {

    /** Очередной кусок текста ответа. */
    data class Token(val text: String) : LocalDelta

    /**
     * Завершение генерации. [finishReason] совместим с OpenAI ("stop",
     * "length"), счётчики токенов — оценка движка; если движок их не отдаёт,
     * передаются нули, а не выдуманные значения.
     */
    data class Done(
        val finishReason: String,
        val promptTokens: Int,
        val completionTokens: Int,
    ) : LocalDelta
}

/**
 * Готовность движка к работе. Причина отказа приходит уже готовой строкой на
 * русском: решить, почему движок недоступен (нет файла модели, устройство без
 * AICore, мало памяти), может только сам бэкенд, а показать это надо
 * пользователю без перевода кодов ошибок в UI.
 */
sealed interface Readiness {

    data object Ready : Readiness

    data class NotReady(val reasonRu: String) : Readiness
}

/**
 * Тип файла локальной модели. [dbValue] хранится в базе как строка, поэтому
 * менять эти значения нельзя — они переживают обновление приложения.
 */
enum class EngineKind(val dbValue: String) {
    GGUF("gguf"),
    LITERT("litertlm");

    companion object {

        /** Разбор значения из базы; неизвестное значение — null, а не падение. */
        fun fromDbValue(value: String): EngineKind? =
            entries.firstOrNull { it.dbValue.equals(value, ignoreCase = true) }
    }
}

/**
 * Локальный бэкенд. [providerType] совпадает с полем `type` провайдера в базе —
 * так шлюз находит нужный движок, не зная о классах конкретных реализаций.
 */
interface LocalBackend {

    val providerType: String

    /** Проверка перед запросом: движок может стать недоступен между вызовами. */
    suspend fun readiness(): Readiness

    fun generate(req: LocalChatRequest): Flow<LocalDelta>
}

/**
 * Реестр локальных бэкендов.
 *
 * Реестр знает только строку типа провайдера и никогда — класс Provider:
 * Provider это Room-сущность с Android-зависимостями, и связывать с ней
 * контракт значило бы утащить весь реестр в инструментальные тесты.
 *
 * Регистрация идёт из Android-слоя на старте, а чтение — из потоков обработки
 * запросов шлюза, поэтому хранилище потокобезопасное.
 */
object LocalBackendRegistry {

    const val TYPE_NANO = "device-gemini-nano"
    const val TYPE_LLAMA = "local-llamacpp"
    const val TYPE_LITERT = "local-litertlm"

    val LOCAL_TYPES: Set<String> = setOf(TYPE_NANO, TYPE_LLAMA, TYPE_LITERT)

    private val backends = ConcurrentHashMap<String, LocalBackend>()

    /**
     * Обслуживается ли тип локально. Регистр не важен: тип приходит из базы и
     * из форм ручного добавления провайдера, где пользователь мог написать
     * что угодно.
     */
    fun ownsType(type: String): Boolean = normalize(type) in LOCAL_TYPES

    fun register(backend: LocalBackend) {
        backends[normalize(backend.providerType)] = backend
    }

    fun forType(type: String): LocalBackend? = backends[normalize(type)]

    /** Сброс состояния между тестами: реестр — синглтон и переживает тест. */
    fun clear() {
        backends.clear()
    }

    private fun normalize(type: String): String = type.trim().lowercase()
}
