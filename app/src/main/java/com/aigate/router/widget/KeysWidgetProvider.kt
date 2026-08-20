package com.aigate.router.widget

import android.content.Context
import android.widget.RemoteViews
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.ui.design.Fmt

/**
 * «AiGate — расход по ключам»: какой API-ключ сколько израсходовал.
 *
 * Счёт в токенах, а не в деньгах: цена известна не для всех моделей, и
 * складывать оценённое с неоценённым значило бы выдать оценку за факт —
 * ровно так же считает экран статистики.
 */
class KeysWidgetProvider : BaseWidgetProvider<WidgetData.KeysData>() {

    override val requestCode: Int = 1011
    override val route: String = "activity"

    override suspend fun load(context: Context, db: AppDatabase): WidgetData.KeysData =
        WidgetData.apiKeys(db)

    override fun build(
        context: Context,
        data: WidgetData.KeysData,
        tier: WidgetTier,
        contentWidthDp: Float,
        contentHeightDp: Float,
    ): RemoteViews {
        val shell = WidgetShell(context, contentWidthDp, contentHeightDp)
        shell.onClick(openApp(context))

        if (data.rows.isEmpty()) {
            shell.head(eyebrow = EYEBROW, read = WidgetText.DASH, sub = "запросов с ключом не было")
            if (!tier.isRow) shell.empty("Запросов с API-ключом не было")
            return shell.build()
        }

        val top = data.rows.first()
        if (tier.isRow) {
            shell.head(
                eyebrow = EYEBROW,
                read = top.label,
                sub = "${Fmt.compact(top.tokens)} · ${share(top.tokens, data.total)}",
            )
            return shell.build()
        }

        val large = tier == WidgetTier.LARGE
        val maxTokens = data.rows.first().tokens.coerceAtLeast(1)
        val rowDp = if (large) 38f else 32f
        val reservedDp = (if (large) 62f else 58f) + (if (large) 16f else 0f)

        shell.head(
            eyebrow = EYEBROW,
            read = Fmt.compact(data.total),
            sub = "${data.rows.size} ${Fmt.plural(data.rows.size.toLong(), "ключ", "ключа", "ключей")} · за всё время",
            readSp = if (large) 20f else 18f,
        )

        // Число вызовов третьей строкой не пишем: доля читается из длины бара.
        fun views(row: WidgetData.KeyRow, forList: Boolean) = shell.barRowViews(
            title = row.label,
            value = Fmt.compact(row.tokens),
            fraction = row.tokens.toDouble() / maxTokens,
            barColor = shell.theme.series(0),
            large = large,
            forList = forList,
        )

        val capacity = shell.rowCapacity(rowDp, reservedDp)
        if (shell.canScroll && data.rows.size > capacity) {
            shell.list(data.rows.take(MAX_LIST).map { views(it, forList = true) }, openApp(context))
        } else {
            data.rows.take(capacity).forEach { shell.addRow(views(it, forList = false)) }
        }
        if (large) shell.footer("по локальному подсчёту — только через шлюз")
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
        const val EYEBROW = "Расход по API-ключам"

        /** Потолок списка: элементы уезжают в лаунчер через Binder. */
        const val MAX_LIST = 24
    }
}
