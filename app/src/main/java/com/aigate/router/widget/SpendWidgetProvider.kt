package com.aigate.router.widget

import android.content.Context
import android.widget.RemoteViews
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.ui.design.Fmt
import java.util.Calendar

/**
 * «AiGate — расход за месяц»: накопительная линия факта и пунктир прогноза.
 *
 * И число, и график считают ТОЛЬКО расход по токенам. Тарифы подписок в сумму
 * не подмешиваются — иначе линия противоречила бы заголовку; если подписки есть,
 * они честно вынесены в футер.
 */
class SpendWidgetProvider : BaseWidgetProvider<WidgetData.SpendData>() {

    override val requestCode: Int = 1003
    override val route: String = "activity"

    override suspend fun load(context: Context, db: AppDatabase): WidgetData.SpendData =
        WidgetData.spend(db)

    override fun build(
        context: Context,
        data: WidgetData.SpendData,
        tier: WidgetTier,
        contentWidthDp: Float,
        contentHeightDp: Float,
    ): RemoteViews {
        val shell = WidgetShell(context, contentWidthDp, contentHeightDp)
        shell.onClick(openApp(context))

        val readout = WidgetText.spendReadout(
            monthToDate = data.monthToDateUsd,
            projected = data.projectedUsd,
            daysElapsed = data.daysElapsed,
            daysInMonth = data.daysInMonth,
            isEstimate = data.isEstimate,
        )

        if (data.cumulativeUsd.isEmpty()) {
            shell.head(eyebrow = EYEBROW, read = Fmt.usd(0.0), sub = "расхода в этом месяце не было")
            if (!tier.isRow) shell.empty("Пока нет данных")
            return shell.build()
        }

        when (tier) {
            WidgetTier.ROW_NARROW, WidgetTier.ROW_WIDE -> {
                shell.head(eyebrow = EYEBROW, read = readout.first, sub = readout.second)
            }

            WidgetTier.SQUARE -> {
                shell.head(eyebrow = EYEBROW, read = readout.first, sub = readout.second, readSp = 24f)
                shell.chart(
                    WidgetDraw.sparkline(
                        context = context,
                        widthDp = contentWidthDp,
                        heightDp = 34f,
                        values = data.dailyUsd,
                        color = shell.theme.series(0),
                        background = shell.theme.surface,
                    )
                )
                shell.footer(subscriptionsNote(data) ?: "прогноз ${Fmt.usd(data.projectedUsd)}")
            }

            WidgetTier.WIDE, WidgetTier.LARGE -> {
                val large = tier == WidgetTier.LARGE
                shell.head(
                    eyebrow = EYEBROW,
                    read = readout.first,
                    sub = readout.second,
                    readSp = if (large) 20f else 18f,
                )
                shell.chart(
                    WidgetDraw.lineWithForecast(
                        context = context,
                        widthDp = contentWidthDp,
                        heightDp = if (large) 150f else 60f,
                        factValues = data.cumulativeUsd,
                        slots = data.daysInMonth,
                        axisMax = WidgetData.niceCeil(
                            maxOf(data.projectedUsd, data.cumulativeUsd.lastOrNull() ?: 0.0)
                        ),
                        projectedEnd = data.projectedUsd.takeIf { it > 0.0 },
                        lineColor = shell.theme.series(0),
                        gridColor = shell.theme.grid,
                        projectionColor = shell.theme.projection,
                        background = shell.theme.surface,
                    )
                )
                shell.axis(
                    start = Fmt.day(data.monthStartMs),
                    mid = "",
                    end = Fmt.day(monthEnd(data)),
                )
                subscriptionsNote(data)?.let { shell.footer(it) }
            }
        }
        return shell.build()
    }

    /** Тарифы подписок не входят в линию, поэтому называются отдельной строкой. */
    private fun subscriptionsNote(data: WidgetData.SpendData): String? =
        if (data.subscriptionsUsd > 0.0) "плюс тарифы ${Fmt.usd(data.subscriptionsUsd)}" else null

    private fun monthEnd(data: WidgetData.SpendData): Long = Calendar.getInstance().apply {
        timeInMillis = data.monthStartMs
        set(Calendar.DAY_OF_MONTH, data.daysInMonth.coerceAtLeast(1))
    }.timeInMillis

    override fun buildFallback(
        context: Context,
        tier: WidgetTier,
        contentWidthDp: Float,
        contentHeightDp: Float,
    ): RemoteViews {
        val shell = WidgetShell(context, contentWidthDp, contentHeightDp)
        shell.onClick(openApp(context))
        shell.head(eyebrow = EYEBROW)
        shell.empty("Пока нет данных")
        return shell.build()
    }

    private companion object {
        const val EYEBROW = "Расход за месяц"
    }
}
