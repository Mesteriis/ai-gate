package com.aigate.router.quota

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.notify.QuotaNotifier
import com.aigate.router.widget.QuotaWidgetProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * Единственная точка входа для обновления квот.
 *
 * Триггеров несколько — тик шлюза, воркер, запуск приложения, открытие экрана,
 * действие пользователя — и раньше каждый решал сам за себя: воркер ходил раз в
 * шесть часов, экраны не обновляли ничего, а уведомления и виджет оживали только
 * вместе с воркером. Здесь всё это сведено вместе: одна очередь, общий троттлинг
 * из [RefreshPolicy], проверка сети и видимые причины отказов.
 */
object QuotaRefresher {

    private const val TAG = "QuotaRefresher"

    /** Что известно про последнее обновление пула. Живёт в памяти процесса. */
    data class PoolRefreshStatus(
        val lastAttemptAt: Long,
        val lastSuccessAt: Long?,
        val outcome: PoolRefreshOutcome,
        val lastError: String?,
    )

    /** Одновременно идёт не больше одного обновления: триггеры схлопываются. */
    private val running = Mutex()

    private val attempts = ConcurrentHashMap<Long, Long>()
    private val successes = ConcurrentHashMap<Long, Long>()

    private val _status = MutableStateFlow<Map<Long, PoolRefreshStatus>>(emptyMap())

    /** Состояние обновления по пулам — для показа возраста данных и причины отказа. */
    val status: StateFlow<Map<Long, PoolRefreshStatus>> = _status.asStateFlow()

    @Volatile
    private var lastTickAt: Long? = null

    /**
     * Тик пятиминутного цикла. Зовётся чаще, чем раз в пять минут, и сам решает,
     * пора ли: короткий heartbeat переживает Doze, а один долгий сон — нет.
     */
    suspend fun tick(context: Context, db: AppDatabase) {
        if (!RefreshPolicy.isDue(lastTickAt, System.currentTimeMillis())) return
        refresh(context, db, RefreshTrigger.PERIODIC)
    }

    /**
     * Обновить квоты. Возвращает false, если обновление уже идёт: ждать второго
     * прохода незачем, данные всё равно обновит первый.
     */
    suspend fun refresh(
        context: Context,
        db: AppDatabase,
        trigger: RefreshTrigger,
    ): Boolean {
        if (!running.tryLock()) return false
        try {
            val now = System.currentTimeMillis()
            lastTickAt = now
            val online = isOnline(context)

            QuotaRepository.refreshAll(
                db = db,
                trigger = trigger,
                remoteAllowed = online,
                lastAttemptAt = { poolId -> attempts[poolId] },
                onPoolResult = { poolId, outcome, error -> record(poolId, outcome, error) },
            )

            QuotaNotifier.checkAndNotify(context, db)
            QuotaWidgetProvider.refresh(context)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Обновление квот не удалось: ${e.message}")
            return false
        } finally {
            running.unlock()
        }
    }

    private fun record(poolId: Long, outcome: PoolRefreshOutcome, error: String?) {
        val now = System.currentTimeMillis()
        // Попытка засчитывается только когда к провайдеру действительно ходили:
        // иначе пропуск по троттлингу сдвигал бы окно и растягивал паузу.
        if (outcome != PoolRefreshOutcome.SKIPPED_THROTTLED) attempts[poolId] = now
        if (outcome == PoolRefreshOutcome.OK_PROVIDER) successes[poolId] = now
        if (outcome == PoolRefreshOutcome.SKIPPED_THROTTLED) return

        _status.value = _status.value + (
            poolId to PoolRefreshStatus(
                lastAttemptAt = now,
                lastSuccessAt = successes[poolId],
                outcome = outcome,
                lastError = error,
            )
            )
    }

    private fun isOnline(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (e: Exception) {
        // Не смогли выяснить — считаем, что сеть есть: запрос сам сообщит правду.
        Log.w(TAG, "Состояние сети неизвестно: ${e.message}")
        true
    }
}
