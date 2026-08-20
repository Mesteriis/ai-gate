package com.aigate.router.widget

import android.content.Context
import android.widget.RemoteViews
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.ui.design.Fmt

/**
 * «AiGate — топ моделей»: ранжирование по расходу токенов.
 *
 * Цвет бара — фирменный цвет провайдера, поэтому надстрочник так и говорит:
 * «цвет — провайдер». Сравнение моделей всегда ранжирование, а не временной ряд:
 * у разных моделей замеры в разные моменты, и линия между ними ничего не значит.
 */
class ModelsWidgetProvider : BaseWidgetProvider<WidgetData.ModelsData>() {

    override val requestCode: Int = 1010
    override val route: String = "activity"

    override suspend fun load(context: Context, db: AppDatabase): WidgetData.ModelsData =
        WidgetData.topModels(db, days = PERIOD_DAYS)

    override fun build(
        context: Context,
        data: WidgetData.ModelsData,
        tier: WidgetTier,
        contentWidthDp: Float,
        contentHeightDp: Float,
    ): RemoteViews {
        val shell = WidgetShell(context, contentWidthDp, contentHeightDp)
        shell.onClick(openApp(context))

        if (data.rows.isEmpty()) {
            shell.head(eyebrow = EYEBROW, read = WidgetText.DASH, sub = "расход по моделям не записан")
            if (!tier.isRow) shell.empty("Расход по моделям не записан")
            return shell.build()
        }

        val top = data.rows.first()
        if (tier.isRow) {
            shell.head(
                eyebrow = EYEBROW,
                read = top.modelId,
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
            sub = "за ${data.periodDays} ${WidgetText.dayWord(data.periodDays)} · " +
                "${data.rows.size} ${Fmt.plural(data.rows.size.toLong(), "модель", "модели", "моделей")}",
            readSp = if (large) 20f else 18f,
        )

        // Строка модели: подписи под баром нет — провайдера несут знак и цвет,
        // а доля видна из длины бара.
        fun views(row: WidgetData.ModelRow, forList: Boolean): android.widget.RemoteViews {
            val brand = shell.theme.brand(row.providerName, row.providerType)
            return shell.barRowViews(
                title = row.modelId,
                value = Fmt.compact(row.tokens),
                fraction = row.tokens.toDouble() / maxTokens,
                barColor = brand,
                large = large,
                avatar = WidgetDraw.avatar(
                    context = context,
                    sizeDp = if (large) 20f else 16f,
                    background = brand,
                    ink = shell.theme.ink(brand),
                    monogram = shell.theme.monogram(row.providerName, row.providerType),
                ),
                forList = forList,
            )
        }

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
        const val EYEBROW = "Топ моделей · цвет — провайдер"
        const val PERIOD_DAYS = 14

        /** Потолок списка: элементы уезжают в лаунчер через Binder. */
        const val MAX_LIST = 24
    }
}
