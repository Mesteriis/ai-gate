package com.aigate.router.gateway.local

/*
 * Каталог локальных файлов моделей: что скачано на устройство и чем его
 * запускать. Отделён от LocalBackend, потому что каталог живёт дольше движка —
 * список нужен экранам загрузки и выбора модели даже тогда, когда ни один
 * бэкенд ещё не поднят.
 */

/**
 * Одна модель на диске.
 *
 * [modelId] — то, что клиент шлёт в поле `model`, а [filePath] — реальный путь;
 * их разделение нужно, потому что файл может переехать (перенос кеша, смена
 * каталога загрузок), а идентификатор в маршрутах и статистике должен
 * оставаться прежним. [contextWindow] и [quant] берутся из метаданных файла и
 * нужны шлюзу, чтобы отказать по длине запроса до запуска тяжёлой генерации.
 */
data class LocalModelRecord(
    val engine: EngineKind,
    val filePath: String,
    val modelId: String,
    val displayName: String,
    val sizeBytes: Long,
    val contextWindow: Int,
    val quant: String,
)

/**
 * Источник каталога. Реализация поверх базы живёт в Android-слое, здесь —
 * только контракт, чтобы бэкенды и их тесты не зависели от Room.
 */
interface LocalModelStore {

    suspend fun list(engine: EngineKind): List<LocalModelRecord>

    suspend fun byModelId(engine: EngineKind, modelId: String): LocalModelRecord?
}

/**
 * Каталог в памяти — для тестов и отладочного бэкенда, где базы нет.
 *
 * Список подменяется целиком через [replaceAll]: частичные правки породили бы
 * состояние, которого на диске никогда не бывает. Поле volatile, потому что
 * подмена идёт из теста или отладочного UI, а чтение — из корутин движка.
 */
class InMemoryLocalModelStore(records: List<LocalModelRecord> = emptyList()) : LocalModelStore {

    @Volatile
    private var snapshot: List<LocalModelRecord> = records.toList()

    fun replaceAll(records: List<LocalModelRecord>) {
        snapshot = records.toList()
    }

    override suspend fun list(engine: EngineKind): List<LocalModelRecord> =
        snapshot.filter { it.engine == engine }

    override suspend fun byModelId(engine: EngineKind, modelId: String): LocalModelRecord? =
        snapshot.firstOrNull { it.engine == engine && it.modelId == modelId }
}
