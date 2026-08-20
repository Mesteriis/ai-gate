package com.aigate.router.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import com.aigate.router.GatewayApplication
import com.aigate.router.MainActivity
import com.aigate.router.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Общий каркас провайдеров комплекта.
 *
 * Данные читаются ОДИН раз на обновление, а раскладываются по экземплярам:
 * у каждого экземпляра свой размер, поэтому ярус вычисляется отдельно для
 * каждого appWidgetId. Сети виджет не касается — только локальный снимок.
 */
abstract class BaseWidgetProvider<T> : AppWidgetProvider() {

    /** Разный код на провайдера: иначе PendingIntent'ы виджетов схлопнулись бы в один. */
    protected abstract val requestCode: Int

    /** Ярус, который берётся, когда лаунчер ещё не сообщил размеры. */
    protected open val fallbackTier: WidgetTier = WidgetTier.WIDE

    protected abstract suspend fun load(context: Context, db: AppDatabase): T

    protected abstract fun build(
        context: Context,
        data: T,
        tier: WidgetTier,
        contentWidthDp: Float,
        contentHeightDp: Float,
    ): RemoteViews

    /** Что показать, если базы нет или чтение упало. */
    protected abstract fun buildFallback(
        context: Context,
        tier: WidgetTier,
        contentWidthDp: Float,
        contentHeightDp: Float,
    ): RemoteViews

    final override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // goAsync удерживает ресивер живым, пока идёт асинхронное чтение БД.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val data = runCatching { load(context, database(context)) }.getOrNull()
                for (id in appWidgetIds) {
                    val options = runCatching { appWidgetManager.getAppWidgetOptions(id) }.getOrNull()
                    val tier = WidgetTiers.fromOptions(options, fallbackTier)
                    val width = contentWidth(options, tier)
                    val height = contentHeight(options, tier)
                    val views = runCatching {
                        if (data == null) buildFallback(context, tier, width, height)
                        else build(context, data, tier, width, height)
                    }.getOrElse { buildFallback(context, tier, width, height) }
                    runCatching { appWidgetManager.updateAppWidget(id, views) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    final override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        // Размер поменялся — виджет обязан пересобраться под новый ярус.
        onUpdate(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    internal fun database(context: Context): AppDatabase =
        runCatching { GatewayApplication.getInstance().database }
            .getOrElse { AppDatabase.getInstance(context.applicationContext) }

    /**
     * Сборка виджета вне лаунчера — для отладочной галереи, из которой снимаются
     * скриншоты для описания и магазина. Данные настоящие, поэтому вызывать
     * только с фонового потока: Room на главном не работает.
     */
    internal suspend fun renderPreview(
        context: Context,
        tier: WidgetTier,
        contentWidthDp: Float,
        contentHeightDp: Float = contentWidthDp,
    ): RemoteViews {
        WidgetShell.previewMode = true
        try {
            return runCatching {
                build(context, load(context, database(context)), tier, contentWidthDp, contentHeightDp)
            }.getOrElse { buildFallback(context, tier, contentWidthDp, contentHeightDp) }
        } finally {
            WidgetShell.previewMode = false
        }
    }

    /**
     * Ширина содержимого: реальная ширина экземпляра минус отступы оболочки.
     * По ней рисуются растры, чтобы бар и график не растягивались по ширине.
     */
    private fun contentWidth(options: Bundle?, tier: WidgetTier): Float {
        val reported = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
        val nominal = if (tier.isWide) 380 else 184
        val width = if (reported > 0) reported else nominal
        return (width - 32).coerceAtLeast(80).toFloat()
    }

    /**
     * Высота содержимого: реальная высота экземпляра минус отступы оболочки.
     * По ней решается, сколько строк показать — лаунчеры дают очень разные
     * размеры, и фиксированный потолок оставлял пустоту на больших виджетах.
     */
    private fun contentHeight(options: Bundle?, tier: WidgetTier): Float {
        val reported = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
        val nominal = when (tier) {
            WidgetTier.ROW_NARROW, WidgetTier.ROW_WIDE -> 86
            WidgetTier.SQUARE, WidgetTier.WIDE -> 184
            WidgetTier.LARGE -> 380
        }
        val height = if (reported > 0) reported else nominal
        return (height - 32).coerceAtLeast(40).toFloat()
    }

    /** Клик по виджету открывает приложение. */
    protected fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            // Раздел передаётся уже сейчас, чтобы приложение могло открыть его
            // без пересборки виджетов, когда появится обработка в MainActivity.
            putExtra(EXTRA_WIDGET_ROUTE, route)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Раздел приложения, к которому относится виджет. */
    protected open val route: String = "overview"

    companion object {
        const val EXTRA_WIDGET_ROUTE = "aigate.widget.route"
    }
}
