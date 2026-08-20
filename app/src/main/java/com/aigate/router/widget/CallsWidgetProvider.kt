package com.aigate.router.widget

import android.content.Context
import android.widget.RemoteViews
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.ui.design.Fmt

/**
 * «AiGate — последние вызовы»: настоящее табличное представление.
 *
 * Колонки: время, модель, токены, расход. Точка перед моделью — фирменный цвет
 * провайдера. Расход считается по локальному прайсу; если цены на модель нет,
 * в клетке стоит прочерк, а не ноль.
 */
class CallsWidgetProvider : BaseWidgetProvider<WidgetData.CallsData>() {

    override val requestCode: Int = 1005
    override val route: String = "activity"

    override suspend fun load(context: Context, db: AppDatabase): WidgetData.CallsData =
        WidgetData.calls(db, limit = MAX_ROWS)

    override fun build(
        context: Context,
        data: WidgetData.CallsData,
        tier: WidgetTier,
        contentWidthDp: Float,
        contentHeightDp: Float,
    ): RemoteViews {
        val shell = WidgetShell(context, contentWidthDp, contentHeightDp)
        shell.onClick(openApp(context))
        val now = System.currentTimeMillis()

        if (data.rows.isEmpty()) {
            shell.head(eyebrow = EYEBROW, time = Fmt.time(now))
            if (tier.isRow) shell.head(eyebrow = "", read = WidgetText.DASH, sub = "вызовов ещё не было")
            else shell.empty("Вызовов ещё не было")
            return shell.build()
        }

        val large = tier == WidgetTier.LARGE
        val reservedDp = 62f + (if (large) 16f else 0f)
        val capacity = if (tier.isRow) 0 else shell.rowCapacity(ROW_DP, reservedDp)
        val scrolls = shell.canScroll && data.rows.size > capacity && capacity > 0

        val readout = WidgetText.callsReadout(
            totalCalls = data.todayCalls.coerceAtLeast(data.rows.size),
            shown = if (scrolls) data.rows.size else minOf(capacity, data.rows.size),
            lastAt = data.lastAt,
        )

        shell.head(
            eyebrow = EYEBROW,
            read = readout.first,
            sub = readout.second,
            time = Fmt.time(now),
            readSp = if (large) 20f else 18f,
        )

        fun views(row: WidgetData.CallRow, forList: Boolean) = shell.tableRowViews(
            time = Fmt.time(row.at),
            model = row.modelId,
            tokens = Fmt.compact(row.tokens),
            usd = row.usd?.let { Fmt.usd(it) } ?: WidgetText.DASH,
            dotColor = shell.theme.brand(row.providerName, row.providerType),
            forList = forList,
        )

        if (scrolls) {
            shell.list(data.rows.take(MAX_LIST).map { views(it, forList = true) }, openApp(context))
        } else {
            data.rows.take(capacity).forEach { shell.addRow(views(it, forList = false)) }
        }

        if (large) {
            shell.footer("обновлено ${Fmt.time(now)} · по локальному подсчёту")
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
        const val EYEBROW = "Последние вызовы"

        /** Читаем с запасом: в прокручиваемом списке помещается больше строк. */
        const val MAX_ROWS = 24
        const val MAX_LIST = 24

        /** Высота строки таблицы: отступ сверху плюс строка текста. */
        const val ROW_DP = 19f
    }
}
