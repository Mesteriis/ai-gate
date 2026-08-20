package com.aigate.router.quota

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aigate.router.data.db.AppDatabase
import java.util.concurrent.TimeUnit

/**
 * Запасное обновление квот на то время, когда шлюз выключен и его сервиса нет.
 *
 * Основной пятиминутный цикл живёт в `GatewayForegroundService`: WorkManager
 * реже пятнадцати минут не умеет в принципе, поэтому здесь стоит именно этот
 * минимум. Когда сервис работает, воркер почти всегда попадает в троттлинг
 * [RefreshPolicy] и ничего не делает — это нормально и дешевле, чем гадать.
 */
class QuotaRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val db = AppDatabase.getInstance(applicationContext)
            QuotaRefresher.refresh(applicationContext, db, RefreshTrigger.PERIODIC)
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
            val request = PeriodicWorkRequestBuilder<QuotaRefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag("aigate_quota_refresh")
                .build()

            // Именно UPDATE: с KEEP уже поставленная задача жила бы вечно со
            // своим прежним расписанием, и смена интервала не дошла бы ни до
            // одной установки, где приложение уже запускалось.
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
