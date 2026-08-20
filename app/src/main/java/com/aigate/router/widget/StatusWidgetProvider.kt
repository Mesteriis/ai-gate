package com.aigate.router.widget

import android.content.Context
import android.widget.RemoteViews
import com.aigate.router.data.db.AppDatabase

/**
 * «AiGate — статус шлюза»: самый компактный ярус комплекта.
 *
 * Своей строки состояния виджет не рисует: на телефоне поверх него уже есть
 * настоящая, и нарисованная копия выглядела бы удвоением.
 */
class StatusWidgetProvider : BaseWidgetProvider<WidgetData.StatusData>() {

    override val requestCode: Int = 1006
    override val route: String = "overview"
    override val fallbackTier: WidgetTier = WidgetTier.ROW_WIDE

    override suspend fun load(context: Context, db: AppDatabase): WidgetData.StatusData =
        WidgetData.status(db)

    override fun build(
        context: Context,
        data: WidgetData.StatusData,
        tier: WidgetTier,
        contentWidthDp: Float,
        contentHeightDp: Float,
    ): RemoteViews {
        val shell = WidgetShell(context, contentWidthDp, contentHeightDp)
        shell.onClick(openApp(context))
        shell.status(data, wide = tier.isWide)
        return shell.build()
    }

    override fun buildFallback(
        context: Context,
        tier: WidgetTier,
        contentWidthDp: Float,
        contentHeightDp: Float,
    ): RemoteViews {
        val shell = WidgetShell(context, contentWidthDp, contentHeightDp)
        shell.onClick(openApp(context))
        shell.status(
            WidgetData.StatusData(
                running = false,
                port = 8889,
                modelId = null,
                providerName = null,
                reason = "нет данных",
            ),
            wide = tier.isWide,
        )
        return shell.build()
    }
}
