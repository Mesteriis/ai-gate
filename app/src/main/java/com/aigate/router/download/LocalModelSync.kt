package com.aigate.router.download

import com.aigate.router.data.db.AppDatabase
import com.aigate.router.data.db.LocalModelDao
import com.aigate.router.data.model.AiModel
import com.aigate.router.data.model.LocalModel
import com.aigate.router.data.model.Provider
import com.aigate.router.gateway.local.EngineKind
import com.aigate.router.gateway.local.LocalBackendRegistry
import com.aigate.router.gateway.local.LocalModelRecord
import com.aigate.router.gateway.local.LocalModelStore

/*
 * Мост между файлами на диске и списком моделей шлюза.
 *
 * Таблицы разные не случайно: local_models отвечает за судьбу файла (сколько
 * скачано, чем проверено, где лежит), а models — за маршрутизацию запросов.
 * Пока файл качается или сломан, в списке моделей его быть не должно: иначе
 * клиент выберет модель, которой нет, и получит ошибку вместо ответа. Поэтому
 * в models переносятся только записи в состоянии ready, и перенос повторяется
 * после каждого события загрузки и удаления.
 *
 * Синхронизация односторонняя: local_models — источник истины, models — его
 * проекция. Правки, сделанные пользователем в списке моделей, сохраняются
 * только там, где это осмысленно (включена ли модель, её алиас) — остальное
 * пересобирается из файла.
 */

/**
 * Строка списка моделей для скачанного файла.
 *
 * Вынесена из [LocalModelSync] отдельной функцией сознательно: это единственное
 * место, где решается, каким идентификатором модель видна клиентам шлюза, и
 * такое решение должно проверяться обычным тестом, без базы и без Android.
 *
 * [isEnabled] приходит снаружи, а не берётся из умолчания: пользователь мог
 * выключить модель в списке, и повторная синхронизация не имеет права включить
 * её обратно.
 *
 * useProxy выключен: локальный счёт идёт в процессе приложения и в сеть не
 * ходит вовсе, так что гнать его через прокси нечего и незачем.
 */
fun toAiModel(model: LocalModel, providerId: Long, isEnabled: Boolean): AiModel = AiModel(
    providerId = providerId,
    modelId = model.routableModelId,
    displayName = model.displayName,
    syncStatus = "Synced",
    isEnabled = isEnabled,
    customAlias = "",
    useProxy = false,
    contextWindow = model.contextWindow,
)

object LocalModelSync {

    const val LOCAL_BASE_URL = "local://models"

    private const val NAME_LLAMA = "Локальные модели (llama.cpp)"
    private const val NAME_LITERT = "Локальные модели (LiteRT)"

    /** Тип провайдера, которым шлюз находит нужный локальный движок. */
    fun providerTypeFor(engine: EngineKind): String = when (engine) {
        EngineKind.GGUF -> LocalBackendRegistry.TYPE_LLAMA
        EngineKind.LITERT -> LocalBackendRegistry.TYPE_LITERT
    }

    /**
     * Найти или создать провайдера локального движка.
     *
     * Провайдер один на движок и заводится сам: заставлять пользователя
     * вручную добавлять «провайдера» для файла, который лежит у него же на
     * телефоне, бессмысленно. Ключ поиска — тип, а не имя: имя пользователь
     * может переименовать, и второй провайдер того же типа сломал бы
     * маршрутизацию.
     *
     * credentialId = 0 — локальному движку нечего предъявлять, ключа у него нет.
     *
     * @return идентификатор провайдера в базе
     */
    suspend fun ensureLocalProvider(db: AppDatabase, engine: EngineKind): Long {
        val type = providerTypeFor(engine)
        val providers = db.providerDao().getAllProvidersList()
        providers.firstOrNull { it.type.equals(type, ignoreCase = true) }?.let { return it.id }

        return db.providerDao().insert(
            Provider(
                name = when (engine) {
                    EngineKind.GGUF -> NAME_LLAMA
                    EngineKind.LITERT -> NAME_LITERT
                },
                type = type,
                baseUrl = LOCAL_BASE_URL,
                credentialId = 0,
                isEnabled = true,
                // В конец списка: локальные модели дополняют сетевых
                // провайдеров, а не вытесняют их с первого места.
                orderIndex = (providers.maxOfOrNull { it.orderIndex } ?: -1) + 1,
            )
        )
    }

    /**
     * Привести список моделей шлюза в соответствие со скачанными файлами.
     *
     * Вызывается после каждой завершённой загрузки, удаления модели и на
     * старте приложения: файл мог исчезнуть вместе с очисткой данных, а запись
     * в models пережила бы это и обещала клиентам несуществующую модель.
     */
    suspend fun sync(db: AppDatabase) {
        for (engine in EngineKind.entries) {
            syncEngine(db, engine)
        }
    }

    private suspend fun syncEngine(db: AppDatabase, engine: EngineKind) {
        val ready = db.localModelDao().getReadyByEngine(engine.dbValue)
        val type = providerTypeFor(engine)
        val existingProvider = db.providerDao().getAllProvidersList()
            .firstOrNull { it.type.equals(type, ignoreCase = true) }

        if (ready.isEmpty()) {
            // Пустой провайдер в списке только мешает: он выглядит как
            // настроенный источник моделей, у которого ничего нет, и путает
            // при разборе «почему модель не отвечает». Удаляется только
            // автоматически созданный — провайдер того же типа, добавленный
            // пользователем руками, трогать нельзя.
            if (existingProvider != null && existingProvider.baseUrl == LOCAL_BASE_URL) {
                db.aiModelDao().deleteByProvider(existingProvider.id)
                db.providerDao().deleteById(existingProvider.id)
            }
            return
        }

        val providerId = existingProvider?.id ?: ensureLocalProvider(db, engine)
        val existingModels = db.aiModelDao().getModelsByProvider(providerId)
        val desiredIds = ready.mapTo(HashSet()) { it.routableModelId }

        // Файла больше нет — строка маршрутизации тоже не нужна.
        for (stale in existingModels) {
            if (stale.modelId !in desiredIds) db.aiModelDao().delete(stale)
        }

        for (model in ready) {
            val current = existingModels.firstOrNull { it.modelId == model.routableModelId }
            if (current == null) {
                db.aiModelDao().insert(toAiModel(model, providerId, isEnabled = true))
                continue
            }
            // Обновление на месте, а не «удалить и вставить заново», как это
            // делает синхронизация сетевых провайдеров: идентификатор строки
            // модели переживает синхронизацию, и вместе с ним переживают
            // выбор модели по умолчанию, алиас и настройка прокси.
            val updated = toAiModel(model, providerId, current.isEnabled).copy(
                id = current.id,
                isDefault = current.isDefault,
                customAlias = current.customAlias,
                useProxy = current.useProxy,
            )
            if (updated != current) db.aiModelDao().update(updated)
        }
    }
}

/**
 * Каталог локальных файлов поверх Room.
 *
 * Реализация контракта из gateway/local: сам контракт свободен от Android,
 * чтобы движки и их тесты не тянули базу, а вот единственная настоящая
 * реализация неизбежно живёт рядом с Room — здесь.
 *
 * Отдаются только записи в состоянии ready: движку нужен файл, который можно
 * открыть прямо сейчас, а недокачанный файл — это не модель.
 */
class RoomLocalModelStore(private val dao: LocalModelDao) : LocalModelStore {

    override suspend fun list(engine: EngineKind): List<LocalModelRecord> =
        dao.getReadyByEngine(engine.dbValue).map { it.toRecord(engine) }

    /**
     * Поиск идёт по [LocalModel.routableModelId], а не по имени файла: клиент
     * шлёт в поле `model` именно этот идентификатор, и он остаётся прежним,
     * даже если файл переехал в другой каталог.
     */
    override suspend fun byModelId(engine: EngineKind, modelId: String): LocalModelRecord? =
        dao.getReadyByEngine(engine.dbValue).firstOrNull { it.routableModelId == modelId }?.toRecord(engine)

    private fun LocalModel.toRecord(engine: EngineKind): LocalModelRecord = LocalModelRecord(
        engine = engine,
        filePath = filePath,
        modelId = routableModelId,
        displayName = displayName,
        sizeBytes = sizeBytes,
        contextWindow = contextWindow,
        quant = quant,
    )
}
