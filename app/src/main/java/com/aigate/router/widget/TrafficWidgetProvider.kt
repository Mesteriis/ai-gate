package com.aigate.router.widget

import android.content.Context
import android.widget.RemoteViews
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.ui.design.Fmt

/**
 * «AiGate — трафик»: сколько получено и отправлено через шлюз.
 *
 * Направления закреплены за парой серий 0 и 1, как входные и выходные токены на
 * графиках приложения: цвет всегда означает одно и то же.
 */
class TrafficWidgetProvider : BaseWidgetProvider<WidgetData.TrafficData>() {

    override val requestCode: Int = 1009
    override val route: String = "overview"
    override val fallbackTier: WidgetTier = WidgetTier.SQUARE

    override suspend fun load(context: Context, db: AppDatabase): WidgetData.TrafficData =
        WidgetData.traffic(db, days = PERIOD_DAYS)

    override fun build(
        context: Context,
        data: WidgetData.TrafficData,
        tier: WidgetTier,
        contentWidthDp: Float,
        contentHeightDp: Float,
    ): RemoteViews {
        val shell = WidgetShell(context, contentWidthDp, contentHeightDp)
        shell.onClick(openApp(context))

        if (data.downloadTotal == 0L && data.uploadTotal == 0L) {
            shell.head(eyebrow = EYEBROW, read = WidgetText.DASH, sub = "трафика за период не было")
            return shell.build()
        }

        val down = Fmt.bytes(data.downloadTotal)
        val up = Fmt.bytes(data.uploadTotal)
        val period = "за ${data.periodDays} ${WidgetText.dayWord(data.periodDays)}"

        when (tier) {
            WidgetTier.ROW_NARROW, WidgetTier.ROW_WIDE -> {
                shell.head(eyebrow = EYEBROW, read = down, sub = "получено · отправлено $up")
            }

            WidgetTier.SQUARE -> {
                shell.head(eyebrow = EYEBROW, read = down, sub = "получено · $period", readSp = 24f)
                shell.chart(
                    WidgetDraw.sparkline(
                        context = context,
                        widthDp = contentWidthDp,
                        heightDp = 34f,
                        values = data.download,
                        color = shell.theme.series(0),
                        background = shell.theme.surface,
                    )
                )
                // На узком ярусе в футер влезает только само число.
                shell.footer("отправлено $up")
            }

            WidgetTier.WIDE, WidgetTier.LARGE -> {
                shell.head(eyebrow = EYEBROW, sub = period)
                shell.metrics(
                    aValue = down,
                    aLabel = "получено",
                    aSpark = WidgetDraw.sparkline(
                        context = context,
                        widthDp = 96f,
                        heightDp = 30f,
                        values = data.download,
                        color = shell.theme.series(0),
                        background = shell.theme.surface,
                    ),
                    bValue = up,
                    bLabel = "отправлено",
                    bSpark = WidgetDraw.sparkline(
                        context = context,
                        widthDp = 96f,
                        heightDp = 30f,
                        values = data.upload,
                        color = shell.theme.series(1),
                        background = shell.theme.surface,
                    ),
                )
                if (tier == WidgetTier.LARGE) shell.footer("по локальному подсчёту — только через шлюз")
            }
        }
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
        shell.head(eyebrow = EYEBROW, read = WidgetText.DASH, sub = "нет данных")
        return shell.build()
    }

    private companion object {
        const val EYEBROW = "Трафик"
        const val PERIOD_DAYS = 14
    }
}
