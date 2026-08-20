package com.aigate.router.widget

import android.content.Context
import android.widget.RemoteViews
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.ui.design.Fmt

/**
 * «AiGate — темп расхода»: линия остатка квоты и вердикт по темпу.
 *
 * Вердикт — те же слова, что на экране обзора: «хватит до …», если квота
 * кончится раньше сброса, и «сгорит …», если наоборот останется
 * неиспользованной. Без истории снимков темпа не существует — тогда виджет
 * честно говорит, что темпа нет, а не рисует линию из одной точки.
 */
class BurnWidgetProvider : BaseWidgetProvider<WidgetData.BurnData?>() {

    override val requestCode: Int = 1008
    override val route: String = "overview"

    override suspend fun load(context: Context, db: AppDatabase): WidgetData.BurnData? =
        WidgetData.burn(db)

    override fun build(
        context: Context,
        data: WidgetData.BurnData?,
        tier: WidgetTier,
        contentWidthDp: Float,
        contentHeightDp: Float,
    ): RemoteViews {
        val shell = WidgetShell(context, contentWidthDp, contentHeightDp)
        shell.onClick(openApp(context))

        if (data == null) {
            shell.head(eyebrow = EYEBROW, read = WidgetText.DASH, sub = "темпа ещё нет")
            if (!tier.isRow) shell.empty("Темпа расхода ещё нет")
            return shell.build()
        }

        val exhaust = data.exhaustAtMs?.takeIf { it < data.resetsAt }
        val runsOutFirst = exhaust != null
        val verdict = when {
            exhaust != null -> "хватит до ${Fmt.time(exhaust)}"
            data.surplus > 0.0 -> "сгорит ${Fmt.quota(data.surplus, data.unit)}"
            else -> "ровно к сбросу"
        }
        val tone = if (runsOutFirst || data.surplus > 0.0) WidgetTone.WARNING else WidgetTone.INFO
        val remaining = WidgetText.percent(data.remainingPercent / 100.0)

        when (tier) {
            WidgetTier.ROW_NARROW, WidgetTier.ROW_WIDE -> {
                shell.head(
                    eyebrow = "$EYEBROW · ${data.poolName}",
                    read = remaining,
                    sub = "$verdict · сброс через ${Fmt.duration(data.resetsAt - data.now)}",
                )
            }

            WidgetTier.SQUARE -> {
                shell.head(eyebrow = "$EYEBROW · ${data.poolName}")
                shell.ring(
                    bitmap = WidgetDraw.ring(
                        context = context,
                        sizeDp = 86f,
                        strokeDp = 8f,
                        usedFraction = 1.0 - data.remainingPercent / 100.0,
                        color = shell.theme.pressureColor(data.pressure),
                        trackColor = shell.theme.surfaceHigh,
                    ),
                    center = remaining,
                )
                shell.chip(text = verdict, tone = tone)
            }

            WidgetTier.WIDE, WidgetTier.LARGE -> {
                val large = tier == WidgetTier.LARGE
                shell.head(
                    eyebrow = "$EYEBROW · ${data.poolName}",
                    read = remaining,
                    sub = "$verdict · сброс через ${Fmt.duration(data.resetsAt - data.now)}",
                    readSp = if (large) 20f else 18f,
                )
                shell.chart(
                    WidgetDraw.lineWithForecast(
                        context = context,
                        widthDp = contentWidthDp,
                        // На большом ярусе график занимает всю освободившуюся
                        // высоту: иначе под ним оставалась пустая половина карточки.
                        heightDp = if (large) 210f else 60f,
                        factValues = data.history,
                        // Три слота под пунктир: он показывает направление темпа,
                        // а не точный момент — точный назван словами в подстрочнике.
                        slots = data.history.size + 3,
                        axisMax = 100.0,
                        projectedEnd = if (runsOutFirst) 0.0 else data.surplus.takeIf { it > 0.0 },
                        lineColor = shell.theme.series(0),
                        gridColor = shell.theme.grid,
                        projectionColor = shell.theme.projection,
                        background = shell.theme.surface,
                    )
                )
                shell.axis(start = "начало окна", mid = "", end = "сейчас")
                if (large) {
                    shell.chip(text = verdict, tone = tone, note = "сброс в ${Fmt.time(data.resetsAt)}")
                    shell.footer("по данным поставщика · остаток в процентах от лимита")
                }
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
        shell.head(eyebrow = EYEBROW)
        shell.empty("Пока нет данных")
        return shell.build()
    }

    private companion object {
        const val EYEBROW = "Темп расхода квоты"
    }
}
