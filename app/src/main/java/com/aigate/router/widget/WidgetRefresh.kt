package com.aigate.router.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Принудительное обновление всех виджетов комплекта.
 *
 * Виджеты не просят систему о периодических обновлениях (updatePeriodMillis=0):
 * их будит тот, кто действительно принёс новые данные — QuotaRefresher после
 * успешного опроса и WorkManager по расписанию. Провайдеры без размещённых
 * экземпляров пропускаются, чтобы не тратить широковещания зря.
 */
object WidgetRefresh {

    private val providers: List<Class<out AppWidgetProvider>> = listOf(
        QuotaWidgetProvider::class.java,
        TokensWidgetProvider::class.java,
        SpendWidgetProvider::class.java,
        SharesWidgetProvider::class.java,
        CallsWidgetProvider::class.java,
        StatusWidgetProvider::class.java,
        WindowsWidgetProvider::class.java,
        BurnWidgetProvider::class.java,
        TrafficWidgetProvider::class.java,
        ModelsWidgetProvider::class.java,
        KeysWidgetProvider::class.java,
        SpeedWidgetProvider::class.java,
    )

    fun refreshAll(context: Context) {
        val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return
        for (provider in providers) {
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(context, provider))
            }.getOrNull() ?: continue
            if (ids.isEmpty()) continue
            val intent = Intent(context, provider).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            runCatching { context.sendBroadcast(intent) }
        }
    }
}
