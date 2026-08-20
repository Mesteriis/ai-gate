package com.aigate.router.widget

import android.content.Context
import android.widget.RemoteViews
import com.aigate.router.data.db.AppDatabase

/**
 * «AiGate — окна квоты»: у подписки два лимита одновременно — сессия и неделя.
 *
 * Значение всегда говорит про остаток, а подпись окна — про израсходованное:
 * одно и то же число в двух ролях путало бы, поэтому роли названы словами.
 */
class WindowsWidgetProvider : BaseWidgetProvider<WidgetData.WindowsData?>() {

    override val requestCode: Int = 1007
    override val route: String = "resources"
    override val fallbackTier: WidgetTier = WidgetTier.ROW_WIDE

    override suspend fun load(context: Context, db: AppDatabase): WidgetData.WindowsData? =
        WidgetData.quotaWindows(db)

    override fun build(
        context: Context,
        data: WidgetData.WindowsData?,
        tier: WidgetTier,
        contentWidthDp: Float,
        contentHeightDp: Float,
    ): RemoteViews {
        val shell = WidgetShell(context, contentWidthDp, contentHeightDp)
        shell.onClick(openApp(context))

        if (data == null || data.windows.isEmpty()) {
            shell.head(eyebrow = EYEBROW, read = WidgetText.DASH, sub = "окон лимита не найдено")
            return shell.build()
        }

        val tightest = data.windows.maxByOrNull { it.usedFraction }!!
        val remaining = WidgetText.percent(1.0 - tightest.usedFraction)
        val eyebrow = WidgetText.poolTitle(data.poolName, com.aigate.router.quota.ResourcePoolKind.QUOTA)

        when (tier) {
            WidgetTier.ROW_NARROW, WidgetTier.ROW_WIDE -> {
                shell.head(
                    eyebrow = eyebrow,
                    read = "осталось $remaining",
                    sub = "${tightest.label} · ${tightest.reset}",
                )
            }

            WidgetTier.SQUARE -> {
                shell.head(eyebrow = eyebrow)
                val outer = data.windows.first()
                val inner = data.windows.getOrNull(1) ?: outer
                shell.ring(
                    bitmap = WidgetDraw.dualRing(
                        context = context,
                        sizeDp = 96f,
                        strokeDp = 8f,
                        outerFraction = outer.usedFraction,
                        innerFraction = inner.usedFraction,
                        outerColor = shell.theme.pressureColor(pressureOf(outer.usedFraction)),
                        innerColor = shell.theme.pressureColor(pressureOf(inner.usedFraction)),
                        trackColor = shell.theme.surfaceHigh,
                    ),
                    center = remaining,
                )
                shell.chip(
                    text = data.pressure.label,
                    tone = data.pressure.widgetTone(),
                    note = "${tightest.label} · ${tightest.reset}",
                )
            }

            WidgetTier.WIDE, WidgetTier.LARGE -> {
                val large = tier == WidgetTier.LARGE
                shell.head(
                    eyebrow = eyebrow,
                    read = "осталось $remaining",
                    sub = "${tightest.label} · ${tightest.reset}",
                    readSp = if (large) 20f else 18f,
                )
                data.windows.forEach { window ->
                    val pressure = pressureOf(window.usedFraction)
                    shell.barRow(
                        title = window.label,
                        value = "израсходовано ${WidgetText.percent(window.usedFraction)}",
                        fraction = window.usedFraction,
                        barColor = shell.theme.pressureColor(pressure),
                        valueColor = shell.theme.pressureColor(pressure),
                        large = large,
                        note = if (large) window.reset else null,
                    )
                }
                if (large) shell.footer("по данным поставщика")
            }
        }
        return shell.build()
    }

    /** Порог для отдельного окна — по израсходованной доле, как в ProviderSheet. */
    private fun pressureOf(usedFraction: Double): com.aigate.router.quota.ResourcePressure = when {
        usedFraction >= 0.90 -> com.aigate.router.quota.ResourcePressure.CRITICAL
        usedFraction >= 0.70 -> com.aigate.router.quota.ResourcePressure.CONSERVE
        usedFraction >= 0.30 -> com.aigate.router.quota.ResourcePressure.NORMAL
        else -> com.aigate.router.quota.ResourcePressure.FREE
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
        const val EYEBROW = "Окна квоты"
    }
}
