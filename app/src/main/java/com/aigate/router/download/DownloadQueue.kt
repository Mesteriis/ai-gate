package com.aigate.router.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.data.db.LocalModelDao
import com.aigate.router.data.model.LocalModel

/*
 * Очередь загрузок моделей.
 *
 * Очередь строго последовательная, и это главное решение файла. Файл модели
 * весит гигабайты: две параллельные загрузки делят один и тот же канал, обе
 * идут вдвое дольше, и обе одновременно занимают место на диске — проверка
 * свободного места, сделанная для каждой по отдельности, при этом врёт.
 * Уникальная работа с политикой APPEND_OR_REPLACE даёт ровно это: следующая
 * модель начинает качаться, когда предыдущая закончила.
 *
 * Очередь ничего не знает про файлы на диске. Удаление .part и итогового файла
 * делает репозиторий — тот, кто владеет хранилищем и записью в базе. Разделение
 * намеренное: иначе отмена работы и удаление данных стали бы одной операцией, и
 * поставить загрузку на паузу без потери скачанного было бы нечем.
 */
object DownloadQueue {

    /** Имя уникальной работы: одна цепочка на всё приложение. */
    const val UNIQUE_WORK = "model_download_queue"

    /** Общий тег всех загрузок — для наблюдения за очередью целиком. */
    const val TAG_ALL = "aigate_model_dl"

    /**
     * Постановка модели в конец очереди.
     *
     * Ограничение — только наличие сети. Требовать безлимитную сеть здесь
     * нельзя: решение «качать ли гигабайты по мобильному интернету» принимает
     * пользователь, нажимая кнопку, а не система за него.
     */
    fun enqueue(context: Context, localModelId: Long) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_ID to localModelId))
            .addTag(ModelDownloadWorker.tagFor(localModelId))
            .addTag(TAG_ALL)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /**
     * Пауза. Работа снимается, недокачанный .part остаётся на диске — именно
     * ради него пауза и отличается от отмены.
     *
     * Функция suspend, потому что состояние записи живёт в Room, а Room не
     * пускает запись в главный поток. Вызывать её из репозитория, который и так
     * работает в корутине, дешевле, чем заводить здесь собственную область.
     */
    suspend fun pause(context: Context, localModelId: Long) {
        WorkManager.getInstance(context).cancelAllWorkByTag(ModelDownloadWorker.tagFor(localModelId))
        AppDatabase.getInstance(context).localModelDao()
            .updateState(localModelId, LocalModel.STATE_PAUSED)
    }

    /**
     * Продолжение с сохранённого куска. Воркер сам увидит .part и попросит у
     * сервера остаток заголовком Range, поэтому здесь достаточно вернуть запись
     * в очередь.
     */
    suspend fun resume(context: Context, localModelId: Long) {
        AppDatabase.getInstance(context).localModelDao()
            .updateState(localModelId, LocalModel.STATE_QUEUED)
        enqueue(context, localModelId)
    }

    /**
     * Отмена задания. Ни файлы, ни запись в базе не трогаются: этим занимается
     * репозиторий (см. комментарий к объекту). Здесь только снятие работы, и
     * оно безопасно, даже если задание уже завершилось.
     */
    fun cancel(context: Context, localModelId: Long) {
        WorkManager.getInstance(context).cancelAllWorkByTag(ModelDownloadWorker.tagFor(localModelId))
    }

    /**
     * Возврат к прерванным загрузкам при старте приложения.
     *
     * Состояния queued и downloading означают «работа была», но не «работа
     * идёт»: процесс могли убить между двумя буферами, и тогда в базе навсегда
     * осталась бы строка, которая якобы качается. WorkManager обычно
     * восстанавливает задания сам, повторная постановка на этот случай не
     * навредит — уникальная работа не удвоится, а лишний запуск воркера над уже
     * готовой записью просто завершится успехом.
     *
     * Порядок по времени создания сохраняет очерёдность, в которой пользователь
     * добавлял модели.
     */
    suspend fun resumeInterruptedOnStartup(context: Context, dao: LocalModelDao) {
        val interrupted = (dao.getByState(LocalModel.STATE_DOWNLOADING) + dao.getByState(LocalModel.STATE_QUEUED))
            .sortedBy { it.createdAt }
        for (model in interrupted) {
            if (model.state != LocalModel.STATE_QUEUED) {
                dao.updateState(model.id, LocalModel.STATE_QUEUED)
            }
            enqueue(context, model.id)
        }
    }
}
