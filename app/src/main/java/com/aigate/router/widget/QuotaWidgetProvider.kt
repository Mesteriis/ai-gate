package com.aigate.router.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.aigate.router.MainActivity
import com.aigate.router.R
import com.aigate.router.quota.QuotaRepository
import com.aigate.router.quota.ResourcePressure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Домашний виджет квот «AiGate».
 *
 * Рендерит ЛОКАЛЬНЫЙ снимок квот (без сетевого опроса): читает последний снимок из БД
 * через [QuotaRepository.latest] и показывает до трёх самых «горящих» пулов. Обновления
 * гонит WorkManager (QuotaRefreshWorker) через [refresh]; систему об обновлениях не просим
 * (updatePeriodMillis=0 в quota_widget_info.xml).
 */
class QuotaWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // goAsync: удерживаем ресивер живым, пока идёт асинхронное чтение БД.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                renderAll(context, appWidgetManager, appWidgetIds)
            } finally {
                pending.finish()
            }
        }
    }

    /** Читает данные ОДИН раз и раскладывает их по всем экземплярам виджета. */
    private suspend fun renderAll(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val quotas: List<QuotaRepository.PoolQuota> = try {
            val db = com.aigate.router.GatewayApplication.getInstance().database
            QuotaRepository.latest(db)
        } catch (_: Exception) {
            // БД недоступна/приложение не инициализировано — покажем «нет данных», не падаем.
            emptyList()
        }

        for (appWidgetId in appWidgetIds) {
            val views = buildRemoteViews(context, quotas)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    /** Собирает RemoteViews из локального снимка квот. Устойчив к null-снимкам. */
    private fun buildRemoteViews(
        context: Context,
        quotas: List<QuotaRepository.PoolQuota>
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_quota)
        views.setTextViewText(R.id.widget_title, "AiGate — ресурсы")

        // Клик по виджету открывает MainActivity.
        val intent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, pi)

        val rowIds = intArrayOf(R.id.widget_row1, R.id.widget_row2, R.id.widget_row3)

        if (quotas.isEmpty()) {
            views.setViewVisibility(R.id.widget_row1, View.VISIBLE)
            views.setTextViewText(R.id.widget_row1, "Пока нет данных")
            views.setViewVisibility(R.id.widget_row2, View.GONE)
            views.setViewVisibility(R.id.widget_row3, View.GONE)
            return views
        }

        // Самые «горящие» пулы первыми: CRITICAL, затем CONSERVE, затем остальные.
        val top = quotas.sortedBy { pressureRank(it.pressure) }.take(rowIds.size)

        for (i in rowIds.indices) {
            val id = rowIds[i]
            if (i < top.size) {
                views.setViewVisibility(id, View.VISIBLE)
                views.setTextViewText(id, rowText(top[i]))
            } else {
                views.setViewVisibility(id, View.GONE)
            }
        }
        return views
    }

    /** Порядок сортировки: чем меньше, тем «горячее». */
    private fun pressureRank(pressure: ResourcePressure): Int = when (pressure) {
        ResourcePressure.CRITICAL -> 0
        ResourcePressure.CONSERVE -> 1
        ResourcePressure.NORMAL -> 2
        ResourcePressure.FREE -> 3
        ResourcePressure.UNKNOWN -> 4
    }

    /**
     * Строка вида «{имя}: осталось 3/100% · {давление}», устойчивая к null-снимкам.
     * Тип ресурса называется своим словом: у бесплатного нет остатка, у баланса
     * нет процентов, сброс бывает только у квоты.
     */
    private fun rowText(pq: QuotaRepository.PoolQuota): String {
        val name = pq.pool.name
        val snap = pq.snapshot
        val remaining = snap?.remaining
        val limit = snap?.limit
        val used = snap?.used
        val unit = snap?.unit ?: ""
        val kind = com.aigate.router.quota.ResourcePoolKind.fromName(pq.pool.kind)

        if (kind == com.aigate.router.quota.ResourcePoolKind.FREE) {
            return "$name: без лимита"
        }

        val value = when {
            remaining != null && limit != null && kind.hasFraction ->
                "$name: ${kind.remainingLabel.lowercase()} ${fmt(remaining)}/${fmt(limit)}${unitSuffix(unit)}"
            remaining != null ->
                "$name: ${kind.remainingLabel.lowercase()} ${fmt(remaining)}${unitSuffix(unit)}"
            used != null ->
                "$name: израсходовано ${fmt(used)}${unitSuffix(unit)}"
            else ->
                "$name: нет данных"
        }.trim()

        return "$value · ${pq.pressure.label}"
    }

    /** Человеческая единица: «PERCENT» на домашнем экране выглядит как ошибка. */
    private fun unitSuffix(unit: String): String = when (unit.uppercase()) {
        "PERCENT" -> "%"
        "USD" -> " $"
        "TOKENS" -> " ток."
        "REQUESTS" -> " запр."
        "CREDITS" -> " кред."
        "COMPUTE_MINUTES" -> " мин"
        "UNKNOWN", "" -> ""
        else -> " " + unit.lowercase()
    }

    /** Компактный формат числа: без дробной части от 100 и больше, иначе два знака. */
    private fun fmt(v: Double): String =
        if (v >= 100) String.format("%.0f", v) else String.format("%.2f", v)

    companion object {
        /**
         * Принудительно обновить все экземпляры виджета (вызывается из WorkManager).
         * Локально: широковещательный ACTION_APPWIDGET_UPDATE самому провайдеру.
         */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, QuotaWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            val intent = Intent(context, QuotaWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }
}
