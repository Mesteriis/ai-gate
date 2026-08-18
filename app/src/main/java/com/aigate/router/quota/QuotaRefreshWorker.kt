package com.aigate.router.quota

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.notify.QuotaNotifier
import java.util.concurrent.TimeUnit

/**
 * Периодическое обновление квот. НЕ поллинг каждую минуту: локальный usage-снимок
 * пересчитывается по расписанию (и дополнительно локально после каждого запроса, если
 * это подключено). Внешние адаптеры (когда появятся) тоже дёргаются здесь.
 */
class QuotaRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val db = AppDatabase.getInstance(applicationContext)
            QuotaRepository.refreshAll(db)
            // Проверить пороги и, если включено, уведомить.
            QuotaNotifier.checkAndNotify(applicationContext, db)
            // Обновить домашние виджеты из свежего локального снимка.
            com.aigate.router.widget.QuotaWidgetProvider.refresh(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Обновление квот не удалось: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "QuotaRefreshWorker"
        private const val WORK_NAME = "quota_refresh"

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            try {
                val existing = wm.getWorkInfosForUniqueWork(WORK_NAME).get()
                if (existing.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }) return
            } catch (_: Exception) {}

            val request = PeriodicWorkRequestBuilder<QuotaRefreshWorker>(
                6, TimeUnit.HOURS,
                30, TimeUnit.MINUTES
            )
                .setConstraints(Constraints.Builder().build())
                .addTag("aigate_quota_refresh")
                .build()

            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Немедленное одноразовое обновление (например, при открытии экрана ресурсов). */
        fun refreshNow(context: Context) {
            val req = androidx.work.OneTimeWorkRequestBuilder<QuotaRefreshWorker>()
                .addTag("aigate_quota_refresh_now")
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
