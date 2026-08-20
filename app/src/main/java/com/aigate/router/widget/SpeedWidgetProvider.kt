package com.aigate.router.widget

import android.content.Context
import android.widget.RemoteViews
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.ui.design.Fmt

/**
 * «AiGate — скорость»: медианы времени первого токена и потока токенов.
 *
 * Спарклайн TTFT перевёрнут: у времени ответа «меньше — лучше», и линия,
 * которая растёт при ухудшении, читалась бы наоборот. Медиана, а не среднее:
 * один зависший запрос не должен портить показание.
 */
class SpeedWidgetProvider : BaseWidgetProvider<WidgetData.SpeedData>() {

    override val requestCode: Int = 1012
    override val route: String = "activity"
    override val fallbackTier: WidgetTier = WidgetTier.ROW_WIDE

    override suspend fun load(context: Context, db: AppDatabase): WidgetData.SpeedData =
        WidgetData.speed(db)

    override fun build(
        context: Context,
        data: WidgetData.SpeedData,
        tier: WidgetTier,
        contentWidthDp: Float,
        contentHeightDp: Float,
    ): RemoteViews {
        val shell = WidgetShell(context, contentWidthDp, contentHeightDp)
        shell.onClick(openApp(context))

        if (data.measurements == 0) {
            shell.head(eyebrow = EYEBROW, read = WidgetText.DASH, sub = "замеров скорости пока нет")
            return shell.build()
        }

        val ttft = Fmt.latency(data.ttftMedian)
        val tps = "${trimZero(data.tpsMedian)} ток/с"
        val spark = { values: List<Double>, colorIndex: Int, invert: Boolean, width: Float ->
            WidgetDraw.sparkline(
                context = context,
                widthDp = width,
                heightDp = 30f,
                values = values,
                color = shell.theme.series(colorIndex),
                background = shell.theme.surface,
                invert = invert,
            )
        }

        when (tier) {
            WidgetTier.ROW_NARROW -> {
                shell.metrics(
                    aValue = ttft,
                    aLabel = "TTFT · медиана",
                    aSpark = spark(data.ttftSeries, 0, true, 60f),
                    bValue = null,
                    bLabel = null,
                    bSpark = null,
                )
            }

            WidgetTier.ROW_WIDE -> {
                // Спарклайн узкий намеренно: с широким «41,5 ток/с» обрезалось.
                shell.metrics(
                    aValue = ttft,
                    aLabel = "TTFT · медиана",
                    aSpark = spark(data.ttftSeries, 0, true, 56f),
                    bValue = tps,
                    bLabel = "TPS · медиана",
                    bSpark = spark(data.tpsSeries, 1, false, 56f),
                )
            }

            else -> {
                shell.head(
                    eyebrow = EYEBROW,
                    read = ttft,
                    sub = "TTFT · медиана по ${data.measurements} " +
                        Fmt.plural(data.measurements.toLong(), "замеру", "замерам", "замерам"),
                    readSp = if (tier == WidgetTier.LARGE) 20f else 18f,
                )
                shell.chart(spark(data.ttftSeries, 0, true, contentWidthDp))
                shell.metrics(
                    aValue = tps,
                    aLabel = "TPS · медиана",
                    aSpark = spark(data.tpsSeries, 1, false, 72f),
                    bValue = null,
                    bLabel = null,
                    bSpark = null,
                )
                if (data.failures > 0) {
                    shell.chip(
                        text = "${data.failures} ${Fmt.plural(data.failures.toLong(), "неудачный", "неудачных", "неудачных")}",
                        tone = WidgetTone.WARNING,
                    )
                }
                if (tier == WidgetTier.LARGE) shell.footer("замеры хранятся семь дней")
            }
        }
        return shell.build()
    }

    /** Дробная часть, равная нулю, только зашумляет: 42,0 → 42. */
    private fun trimZero(value: Double): String {
        val text = String.format(java.util.Locale.forLanguageTag("ru"), "%.1f", value)
        return if (text.endsWith(",0")) text.dropLast(2) else text
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
        const val EYEBROW = "Скорость"
    }
}
