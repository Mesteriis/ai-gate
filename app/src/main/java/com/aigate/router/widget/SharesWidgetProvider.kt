package com.aigate.router.widget

import android.content.Context
import android.widget.RemoteViews
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.ui.design.Fmt

/**
 * «AiGate — доли провайдеров»: донат расхода токенов по провайдерам.
 *
 * Цвет сектора — фирменный цвет провайдера, тот же, что у точки в таблице
 * вызовов: связка «цвет ↔ провайдер» держится по всему комплекту.
 */
class SharesWidgetProvider : BaseWidgetProvider<WidgetData.SharesData>() {

    override val requestCode: Int = 1004
    override val route: String = "activity"

    override suspend fun load(context: Context, db: AppDatabase): WidgetData.SharesData =
        WidgetData.shares(db, days = PERIOD_DAYS)

    override fun build(
        context: Context,
        data: WidgetData.SharesData,
        tier: WidgetTier,
        contentWidthDp: Float,
        contentHeightDp: Float,
    ): RemoteViews {
        val shell = WidgetShell(context, contentWidthDp, contentHeightDp)
        shell.onClick(openApp(context))

        if (data.rows.isEmpty() || data.total == 0L) {
            shell.head(eyebrow = EYEBROW)
            if (tier.isRow) shell.head(eyebrow = "", read = WidgetText.DASH, sub = "расход по провайдерам не записан")
            else shell.empty("Расход по провайдерам не записан")
            return shell.build()
        }

        // Сектора мельче пяти процентов сливаются в «Прочие», иначе донат
        // превращается в частокол зазоров.
        val shown = data.rows.take(5)
        val rest = data.rows.drop(5).sumOf { it.tokens }
        val segments = shown.map { it.tokens.toDouble() to shell.theme.brand(it.name, it.type) } +
            if (rest > 0) listOf(rest.toDouble() to shell.theme.pressureColor(com.aigate.router.quota.ResourcePressure.UNKNOWN)) else emptyList()

        if (tier.isRow) {
            val top = data.rows.first()
            shell.head(
                eyebrow = EYEBROW,
                read = top.name,
                sub = "${Fmt.compact(top.tokens)} · ${share(top.tokens, data.total)}",
            )
            return shell.build()
        }

        val diameter = if (tier == WidgetTier.SQUARE) 108f else 132f
        shell.head(eyebrow = EYEBROW, time = Fmt.time(System.currentTimeMillis()))
        val legendContainer = shell.donut(
            bitmap = WidgetDraw.donut(
                context = context,
                sizeDp = diameter,
                strokeDp = if (tier == WidgetTier.SQUARE) 14f else 18f,
                segments = segments,
                trackColor = shell.theme.surfaceHigh,
                background = shell.theme.surface,
            ),
            main = Fmt.compact(data.total),
            sub = "за ${data.periodDays} ${WidgetText.dayWord(data.periodDays)}",
            withLegend = tier != WidgetTier.SQUARE,
        )

        if (tier == WidgetTier.SQUARE) {
            shell.footer("токенов · ${data.rows.size} ${Fmt.plural(data.rows.size.toLong(), "провайдер", "провайдера", "провайдеров")}")
            return shell.build()
        }

        shown.forEach { row ->
            shell.legendRow(
                name = row.name,
                value = Fmt.compact(row.tokens),
                share = share(row.tokens, data.total),
                dotColor = shell.theme.brand(row.name, row.type),
                into = legendContainer,
            )
        }
        if (rest > 0) {
            shell.legendRow(
                name = "Прочие",
                value = Fmt.compact(rest),
                share = share(rest, data.total),
                dotColor = shell.theme.pressureColor(com.aigate.router.quota.ResourcePressure.UNKNOWN),
                into = legendContainer,
            )
        }
        if (tier == WidgetTier.LARGE) {
            shell.footer("по локальному подсчёту — только через шлюз")
        }
        return shell.build()
    }

    private fun share(value: Long, total: Long): String =
        if (total <= 0) WidgetText.DASH else "${Math.round(value * 100.0 / total)}%"

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
        const val EYEBROW = "Доли провайдеров"
        const val PERIOD_DAYS = 14
    }
}
