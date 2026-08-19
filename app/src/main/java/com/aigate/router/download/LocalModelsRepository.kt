package com.aigate.router.download

import android.content.Context
import com.aigate.router.catalog.ModelCatalogRepository
import com.aigate.router.catalog.ModelCatalogRepository.CatalogEntry
import com.aigate.router.catalog.ModelCatalogRepository.CatalogVariant
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.data.db.LocalModelDao
import com.aigate.router.data.model.LocalModel
import kotlinx.coroutines.flow.Flow

/*
 * Единственный вход для экранов, работающих со скачанными моделями.
 *
 * Слоёв под ним три — каталог, очередь загрузок и файлы на диске, — и порядок
 * обращения к ним не произвольный: сначала проверка устройства, потом запись в
 * базу, и только потом постановка в очередь. Собрать этот порядок во ViewModel
 * значило бы повторить его в каждом экране и рано или поздно разойтись.
 *
 * Compose здесь намеренно нет: тот же порядок нужен и фоновым задачам
 * (возобновление прерванных загрузок на старте), у которых экрана нет вовсе.
 */
object LocalModelsRepository {

    /**
     * Место на диске для панели хранилища.
     *
     * [modelsBytes] — реально занятое файлами моделей, [freeBytes] — свободное
     * на разделе, [totalBytes] — сумма этих двух чисел, то есть весь объём,
     * которым приложение может распорядиться. Полный размер раздела намеренно
     * не показывается: в него входят система и чужие данные, освободить
     * которые пользователь всё равно не может, и полоса заполнения по нему
     * выглядела бы всегда почти пустой.
     */
    data class StorageStats(
        val modelsBytes: Long,
        val freeBytes: Long,
        val totalBytes: Long,
    )

    fun observeAll(dao: LocalModelDao): Flow<List<LocalModel>> = dao.observeAll()

    /**
     * Поставить модель в очередь на скачивание.
     *
     * Первым делом идёт [ModelCatalogRepository.resolveExact] — это и есть
     * блокирующая проверка: она берёт точный размер и контрольную сумму и
     * заново спрашивает гейт устройства. Всё, что дальше, выполняется только
     * после её успеха, потому что запись в базе — это уже обещание
     * пользователю, что модель появится.
     *
     * Повторный вызов для той же модели не создаёт дубля: уникальный ключ
     * source+repo+ref в local_models не даст вставить вторую строку, и
     * возвращается идентификатор существующей — экран покажет её прогресс
     * вместо ошибки.
     *
     * @return идентификатор записи в local_models либо отказ с русской причиной
     */
    suspend fun startDownload(
        context: Context,
        dao: LocalModelDao,
        entry: CatalogEntry,
        variant: CatalogVariant,
    ): Result<Long> {
        val app = context.applicationContext
        val resolved = ModelCatalogRepository.resolveExact(app, entry, variant)
            .getOrElse { return Result.failure(it) }

        val source = ModelCatalogRepository.dbSource(entry.source)
        dao.getByKey(source, entry.repo, variant.ref)?.let { return Result.success(it.id) }

        val row = LocalModel(
            source = source,
            repo = entry.repo,
            ref = variant.ref,
            engine = variant.engine.dbValue,
            displayName = ModelCatalogRepository.variantDisplayName(entry, variant),
            sizeBytes = resolved.sizeBytes,
            // Контрольная сумма у Ollama — это digest слоя весов, по нему же
            // строится адрес blob-а; отдельного поля под имя файла в таблице
            // нет, и оно восстанавливается из repo и ref.
            sha256 = resolved.sha256,
            quant = variant.quant,
            paramsB = variant.paramsB,
            state = LocalModel.STATE_QUEUED,
        )
        val inserted = dao.insert(row)
        // insert идёт с OnConflictStrategy.IGNORE и возвращает -1, если строку
        // успел вставить кто-то другой между проверкой и вставкой.
        val id = if (inserted > 0L) {
            inserted
        } else {
            dao.getByKey(source, entry.repo, variant.ref)?.id
                ?: return Result.failure(
                    IllegalStateException("Не удалось сохранить запись о модели ${row.displayName}")
                )
        }

        DownloadQueue.enqueue(app, id)
        return Result.success(id)
    }

    /**
     * Приостановить загрузку.
     *
     * [dao] в сигнатуре не используется и стоит здесь ради единообразия вызовов
     * с экрана, где рядом лежат [delete] и [startDownload] с той же тройкой
     * аргументов. Само состояние записи меняет очередь: только она знает, где
     * остановилось тело ответа.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun pause(context: Context, dao: LocalModelDao, id: Long) {
        DownloadQueue.pause(context.applicationContext, id)
    }

    /** Продолжить приостановленную загрузку с того места, где она встала. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun resume(context: Context, dao: LocalModelDao, id: Long) {
        DownloadQueue.resume(context.applicationContext, id)
    }

    /** Отменить загрузку; запись и частично скачанный файл остаются до [delete]. */
    @Suppress("UNUSED_PARAMETER")
    fun cancel(context: Context, dao: LocalModelDao, id: Long) {
        DownloadQueue.cancel(context.applicationContext, id)
    }

    /**
     * Удалить модель целиком.
     *
     * Порядок обязателен: сначала снять загрузку с очереди, иначе она допишет
     * файл после удаления и оставит на диске сироту, потом удалить файлы, и
     * только потом запись — по записи находятся пути, и без неё файл уже не
     * найти.
     *
     * Пересинхронизация в конце убирает модель из списка шлюза. Она обёрнута в
     * runCatching намеренно: файл уже удалён, и упасть на приведении списка
     * моделей значило бы оставить пользователя с ошибкой на экране при
     * успешно выполненном удалении. Расхождение поправит следующая
     * синхронизация — она идёт после каждой загрузки и на старте.
     */
    suspend fun delete(context: Context, dao: LocalModelDao, model: LocalModel) {
        val app = context.applicationContext
        DownloadQueue.cancel(app, model.id)
        ModelStorage.deleteModelFiles(app, model)
        dao.deleteById(model.id)
        runCatching { LocalModelSync.sync(AppDatabase.getInstance(app)) }
    }

    /**
     * Сколько места занято моделями и сколько осталось.
     *
     * Занятое берётся с диска, а не суммой размеров из базы: в каталоге
     * моделей могут лежать хвосты прерванных удалений и недокачанные файлы, и
     * человеку важно видеть, сколько места действительно израсходовано, а не
     * сколько числится за учтёнными записями.
     *
     * [dao] в сигнатуре ради единообразия вызовов с экрана хранилища, который
     * тут же показывает список моделей.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun storageStats(context: Context, dao: LocalModelDao): StorageStats {
        val app = context.applicationContext
        val used = ModelStorage.usedBytes(app)
        val free = ModelStorage.freeBytes(app)
        return StorageStats(
            modelsBytes = used,
            freeBytes = free,
            totalBytes = used + free,
        )
    }
}
