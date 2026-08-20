package com.aigate.router.widget

import android.content.Context
import android.widget.RemoteViews
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.ui.design.Fmt

/**
 * Витрина комплекта: «AiGate — ресурсы».
 *
 * Показывает пулы провайдеров, отсортированные по давлению: сначала то, что
 * горит. Заливка бара и кольца — израсходованное, подпись всегда про остаток
 * (тот же инвариант, что на экранах). Сети не касается: читает локальный снимок,
 * который обновляют QuotaRefresher и WorkManager.
 *
 * Имя класса сохранено с первой версии виджета, чтобы уже размещённые на
 * домашнем экране экземпляры продолжили работать после редизайна.
 */
class QuotaWidgetProvider : BaseWidgetProvider<WidgetData.ResourcesData>() {

    override val requestCode: Int = 1001
    override val route: String = "resources"

    override suspend fun load(context: Context, db: AppDatabase): WidgetData.ResourcesData =
        WidgetData.resources(db)

    override fun build(
        context: Context,
        data: WidgetData.ResourcesData,
        tier: WidgetTier,
        contentWidthDp: Float,
        contentHeightDp: Float,
    ): RemoteViews {
        val shell = WidgetShell(context, contentWidthDp, contentHeightDp, hero = true)
        shell.onClick(openApp(context))

        if (data.pools.isEmpty()) {
            shell.head(eyebrow = "Ресурсы провайдеров", time = Fmt.time(data.now))
            if (tier.isRow) shell.head(eyebrow = "", read = WidgetText.DASH, sub = "снимков ещё не было")
            else shell.empty("Пока нет данных")
            return shell.build()
        }

        val top = data.pools.first()
        val readout = WidgetText.resourcesReadout(
            total = data.pools.size,
            attention = data.attention,
            topName = top.name,
            topPressure = top.pressure,
        )

        when (tier) {
            WidgetTier.ROW_NARROW -> {
                shell.head(
                    eyebrow = WidgetText.poolTitle(top.name, top.kind),
                    read = top.value,
                    sub = top.pressure.label.lowercase(),
                )
            }

            WidgetTier.ROW_WIDE -> {
                shell.head(
                    eyebrow = "Ресурсы провайдеров",
                    read = readout.first,
                    sub = readout.second,
                    time = Fmt.time(data.now),
                )
            }

            WidgetTier.SQUARE -> {
                shell.head(eyebrow = WidgetText.poolTitle(top.name, top.kind))
                shell.ring(
                    bitmap = WidgetDraw.ring(
                        context = context,
                        sizeDp = 86f,
                        strokeDp = 8f,
                        usedFraction = top.usedFraction,
                        color = shell.theme.pressureColor(top.pressure),
                        trackColor = shell.theme.surfaceHigh,
                    ),
                    center = if (top.usedFraction != null) {
                        WidgetText.ringCenter(top.usedFraction)
                    } else {
                        top.value
                    },
                )
                shell.chip(
                    text = top.pressure.label,
                    tone = top.pressure.widgetTone(),
                    note = top.note ?: top.reset,
                )
            }

            WidgetTier.WIDE -> {
                shell.head(
                    eyebrow = "Ресурсы провайдеров",
                    read = readout.first,
                    sub = readout.second,
                    time = Fmt.time(data.now),
                )
                pools(context, shell, data.pools, large = false, reservedDp = 58f)
            }

            WidgetTier.LARGE -> {
                shell.head(
                    eyebrow = "Ресурсы провайдеров",
                    read = readout.first,
                    sub = readout.second,
                    time = Fmt.time(data.now),
                    readSp = 20f,
                )
                pools(context, shell, data.pools, large = true, reservedDp = 78f)
                val freshest = data.pools.mapNotNull { it.updatedAt }.maxOrNull()
                val source = data.pools.firstOrNull { it.updatedAt == freshest }?.source
                shell.footer(WidgetText.updatedFooter(freshest, source, data.now))
            }
        }
        return shell.build()
    }

    /**
     * Пулы строками. Если их больше, чем влезает по высоте, на Android 12+
     * список становится прокручиваемым — иначе просто обрезается по месту.
     */
    private fun pools(
        context: Context,
        shell: WidgetShell,
        pools: List<WidgetData.PoolCard>,
        large: Boolean,
        reservedDp: Float,
    ) {
        // Строки пулов разной высоты: у баланса и бесплатного пула нет бара, у
        // квоты бывает вердикт. Поэтому набираем не «сколько-то строк», а
        // столько, сколько реально влезает по высоте.
        var left = shell.contentHeightDp - reservedDp
        val fits = pools.takeWhile { card ->
            val height = rowHeight(card, large)
            if (height <= left) {
                left -= height
                true
            } else {
                false
            }
        }
        if (shell.canScroll && pools.size > fits.size) {
            shell.list(
                pools.take(MAX_LIST).map { shell.poolRowViews(it, large, forList = true) },
                openApp(context),
            )
        } else {
            fits.forEach { shell.poolRow(it, large) }
        }
    }

    /** Высота строки пула в dp — по тем же отступам, что в widget_row_pool.xml. */
    private fun rowHeight(card: WidgetData.PoolCard, large: Boolean): Float {
        var height = 4f + if (large) 20f else 16f
        if (card.usedFraction != null) height += 4f + if (large) 8f else 6f
        if (large && (card.note ?: card.reset).isNotEmpty()) height += 3f + 16f
        return height
    }

    override fun buildFallback(
        context: Context,
        tier: WidgetTier,
        contentWidthDp: Float,
        contentHeightDp: Float,
    ): RemoteViews {
        val shell = WidgetShell(context, contentWidthDp, contentHeightDp, hero = true)
        shell.onClick(openApp(context))
        shell.head(eyebrow = "Ресурсы провайдеров")
        shell.empty("Пока нет данных")
        return shell.build()
    }

    private companion object {
        /** Потолок списка: элементы уезжают в лаунчер через Binder. */
        const val MAX_LIST = 24
    }
}
