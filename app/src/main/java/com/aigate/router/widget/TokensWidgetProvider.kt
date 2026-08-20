package com.aigate.router.widget

import android.content.Context
import android.widget.RemoteViews
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.ui.design.Fmt

/**
 * «AiGate — токены по дням»: стопка входных и выходных токенов по дням.
 *
 * Пара серий 0 и 1 закреплена за направлением (входные азурные, выходные охра) —
 * та же связка, что на графиках приложения.
 */
class TokensWidgetProvider : BaseWidgetProvider<WidgetData.TokensData>() {

    override val requestCode: Int = 1002
    override val route: String = "activity"

    override suspend fun load(context: Context, db: AppDatabase): WidgetData.TokensData =
        WidgetData.tokens(db, days = PERIOD_DAYS)

    override fun build(
        context: Context,
        data: WidgetData.TokensData,
        tier: WidgetTier,
        contentWidthDp: Float,
        contentHeightDp: Float,
    ): RemoteViews {
        val shell = WidgetShell(context, contentWidthDp, contentHeightDp)
        shell.onClick(openApp(context))
        val now = System.currentTimeMillis()

        if (data.total == 0L) {
            shell.head(eyebrow = EYEBROW, time = Fmt.time(now))
            if (tier.isRow) shell.head(eyebrow = "", read = WidgetText.DASH, sub = "расхода не было")
            else shell.empty("За период расхода не было")
            return shell.build()
        }

        val readout = WidgetText.tokensReadout(
            totalTokens = data.total,
            days = data.periodDays,
            peakDay = data.peak?.dayStartMs,
            peakTokens = data.peak?.total ?: 0L,
            average = data.average,
        )
        val short = WidgetText.tokensReadoutShort(data.total, data.periodDays, data.average)

        when (tier) {
            WidgetTier.ROW_NARROW, WidgetTier.ROW_WIDE -> {
                shell.head(eyebrow = EYEBROW, read = short.first, sub = short.second, time = Fmt.time(now))
            }

            WidgetTier.SQUARE -> {
                shell.head(eyebrow = EYEBROW, read = short.first, sub = short.second)
                shell.chart(
                    WidgetDraw.sparkline(
                        context = context,
                        widthDp = contentWidthDp,
                        heightDp = 40f,
                        values = data.days.map { it.total.toDouble() },
                        color = shell.theme.series(0),
                        background = shell.theme.surface,
                    )
                )
            }

            WidgetTier.WIDE -> {
                shell.head(eyebrow = EYEBROW, read = short.first, sub = short.second, time = Fmt.time(now))
                shell.chart(stacked(context, shell, data, contentWidthDp, heightDp = 60f))
                axis(shell, data)
            }

            WidgetTier.LARGE -> {
                shell.head(
                    eyebrow = EYEBROW,
                    read = readout.first,
                    sub = readout.second,
                    time = Fmt.time(now),
                    readSp = 20f,
                )
                shell.chart(stacked(context, shell, data, contentWidthDp, heightDp = 150f))
                axis(shell, data)
                val total = data.total.coerceAtLeast(1)
                shell.legendRow(
                    name = "Входные",
                    value = Fmt.compact(data.prompt),
                    share = "${data.prompt * 100 / total}%",
                    dotColor = shell.theme.series(0),
                )
                shell.legendRow(
                    name = "Выходные",
                    value = Fmt.compact(data.completion),
                    share = "${data.completion * 100 / total}%",
                    dotColor = shell.theme.series(1),
                )
                shell.footer("по локальному подсчёту — только через шлюз")
            }
        }
        return shell.build()
    }

    private fun stacked(
        context: Context,
        shell: WidgetShell,
        data: WidgetData.TokensData,
        widthDp: Float,
        heightDp: Float,
    ) = WidgetDraw.stackedBars(
        context = context,
        widthDp = widthDp,
        heightDp = heightDp,
        columns = data.days.map { it.prompt.toDouble() to it.completion.toDouble() },
        axisMax = WidgetData.niceCeil(data.days.maxOfOrNull { it.total.toDouble() } ?: 1.0),
        colorLower = shell.theme.series(0),
        colorUpper = shell.theme.series(1),
        gridColor = shell.theme.grid,
        background = shell.theme.surface,
    )

    private fun axis(shell: WidgetShell, data: WidgetData.TokensData) {
        val first = data.days.firstOrNull()?.dayStartMs
        val middle = data.days.getOrNull(data.days.size / 2)?.dayStartMs
        shell.axis(
            start = first?.let { Fmt.day(it) } ?: "",
            mid = middle?.let { Fmt.day(it) } ?: "",
            end = "сегодня",
        )
    }

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
        const val EYEBROW = "Токены по дням"
        const val PERIOD_DAYS = 14
    }
}
