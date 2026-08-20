package com.aigate.router.widget

import android.app.PendingIntent
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.ColorInt
import com.aigate.router.R

/**
 * Сборка виджета из готовых блоков.
 *
 * Оболочка одна на весь комплект (widget_shell.xml), а содержимое добавляется
 * строками через RemoteViews.addView — поэтому на двенадцать виджетов и пять
 * размеров не нужно двенадцать на пять layout-файлов.
 */
class WidgetShell(
    private val context: Context,
    /** Ширина содержимого без отступов оболочки — по ней считаются растры. */
    val contentWidthDp: Float,
    /** Высота содержимого без отступов — по ней решается, сколько строк влезет. */
    val contentHeightDp: Float,
    hero: Boolean = false,
) {
    val theme: WidgetTheme = WidgetTheme.of(context)
    private val root = RemoteViews(context.packageName, R.layout.widget_shell)

    init {
        // ПЕРВОЕ действие в списке — очистка контейнера.
        //
        // Лаунчер не всегда надувает разметку заново: если id разметки совпадает
        // с уже показанной, AppWidgetHostView вызывает RemoteViews.reapply() и
        // проигрывает действия на СУЩЕСТВУЮЩЕМ дереве. Тогда каждый addView
        // дописывает строки к уже имеющимся, и содержимое виджета удваивается —
        // особенно заметно при растягивании, когда обновление приходит на каждый
        // новый размер. removeAllViews в начале списка делает пересборку
        // идемпотентной при любом числе повторных применений.
        root.removeAllViews(R.id.w_rows)
        if (hero) root.setInt(R.id.w_root, "setBackgroundResource", R.drawable.widget_bg_hero)
    }

    private fun child(layout: Int): RemoteViews = RemoteViews(context.packageName, layout)

    private fun add(views: RemoteViews) = root.addView(R.id.w_rows, views)

    /** Положить уже собранную строку в статичный набор. */
    fun addRow(views: RemoteViews) = add(views)

    /**
     * Сколько строк заданной высоты влезает в оставшуюся высоту экземпляра.
     * Нужно потому, что лаунчеры дают виджету очень разные размеры: на OneUI
     * ярус 4×4 приходит как 464 × 567 dp, и фиксированный потолок в шесть строк
     * оставлял треть карточки пустой.
     */
    fun rowCapacity(rowDp: Float, reservedDp: Float, min: Int = 1): Int =
        ((contentHeightDp - reservedDp) / rowDp).toInt().coerceAtLeast(min)

    /**
     * Умеет ли система показать прокручиваемый список внутри виджета.
     *
     * Коллекции RemoteViews обслуживает только хост виджетов: при обычном
     * apply() в активности система пишет «setRemoteAdapter can only be used for
     * AppWidgets» и список остаётся пустым. Поэтому отладочная галерея,
     * рисующая строки в активности, просит статичный набор.
     */
    val canScroll: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !previewMode

    /**
     * Бар давления. На Android 12+ это ProgressBar с тонированием: он ничего не
     * весит, поэтому десяток строк спокойно уезжает в лаунчер через Binder.
     * На более старых системах тонирования нет — рисуем растр, как раньше.
     */
    private fun paintBar(v: RemoteViews, fraction: Double, @ColorInt color: Int, heightDp: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            v.setViewVisibility(R.id.w_pool_bar, View.GONE)
            v.setViewVisibility(R.id.w_pool_bar_view, View.VISIBLE)
            v.setProgressBar(R.id.w_pool_bar_view, 100, Math.round(fraction * 100).toInt(), false)
            v.setColorStateList(R.id.w_pool_bar_view, "setProgressTintList", ColorStateList.valueOf(color))
            v.setViewLayoutHeight(R.id.w_pool_bar_view, heightDp, TypedValue.COMPLEX_UNIT_DIP)
        } else {
            v.setImageViewBitmap(
                R.id.w_pool_bar,
                WidgetDraw.pill(
                    context = context,
                    widthDp = contentWidthDp,
                    heightDp = heightDp,
                    usedFraction = fraction,
                    fillColor = color,
                    trackColor = theme.surfaceHigh,
                ),
            )
        }
    }

    /**
     * Прокручиваемый список строк вместо статичного набора. Элементы — обычные
     * RemoteViews, собранные теми же строителями, поэтому вид не расходится.
     */
    fun list(items: List<RemoteViews>, tap: PendingIntent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || items.isEmpty()) return
        val builder = RemoteViews.RemoteCollectionItems.Builder()
            .setHasStableIds(true)
            .setViewTypeCount(1)
        items.forEachIndexed { index, item -> builder.addItem(index.toLong(), item) }
        root.setRemoteAdapter(R.id.w_list, builder.build())
        // Тап по строке открывает приложение: у ListView свой обработчик, и клик
        // по оболочке до строк не доходит.
        root.setPendingIntentTemplate(R.id.w_list, tap)
        root.setViewVisibility(R.id.w_list, View.VISIBLE)
    }

    /** Строка-вывод: надстрочник, время, значение, подстрочник. */
    fun head(
        eyebrow: String,
        read: String? = null,
        sub: String? = null,
        time: String? = null,
        readSp: Float = 18f,
    ) {
        val v = child(R.layout.widget_head)
        v.setTextViewText(R.id.w_eyebrow, eyebrow)
        if (time != null) {
            v.setTextViewText(R.id.w_time, time)
        } else {
            v.setViewVisibility(R.id.w_time, View.GONE)
        }
        if (read != null) {
            v.setTextViewText(R.id.w_read, read)
            v.setTextViewTextSize(R.id.w_read, TypedValue.COMPLEX_UNIT_SP, readSp)
        } else {
            v.setViewVisibility(R.id.w_read, View.GONE)
        }
        if (sub != null) {
            v.setTextViewText(R.id.w_sub, sub)
        } else {
            v.setViewVisibility(R.id.w_sub, View.GONE)
        }
        add(v)
    }

    /** Строка пула ресурсов: знак, название, остаток, бар давления и вердикт. */
    fun poolRow(card: WidgetData.PoolCard, large: Boolean) = add(poolRowViews(card, large))

    /**
     * Та же строка, но возвращённая, а не добавленная: так она годится и в
     * прокручиваемый список, и в статичный набор — вид гарантированно один.
     */
    fun poolRowViews(card: WidgetData.PoolCard, large: Boolean, forList: Boolean = false): RemoteViews {
        val v = child(R.layout.widget_row_pool)
        val avatarDp = if (large) 20f else 16f
        val brand = theme.brand(card.name, card.providerType)
        v.setImageViewBitmap(
            R.id.w_pool_avatar,
            WidgetDraw.avatar(context, avatarDp, brand, theme.ink(brand), theme.monogram(card.name, card.providerType)),
        )
        v.setTextViewText(R.id.w_pool_title, WidgetText.poolTitle(card.name, card.kind))
        v.setTextViewTextSize(R.id.w_pool_title, TypedValue.COMPLEX_UNIT_SP, if (large) 14f else 12f)
        v.setTextViewText(R.id.w_pool_value, card.value)
        v.setTextColor(R.id.w_pool_value, theme.pressureColor(card.pressure))

        if (card.usedFraction != null) {
            paintBar(v, card.usedFraction, theme.pressureColor(card.pressure), if (large) 8f else 6f)
        } else {
            // У баланса и бесплатного пула доли нет — бара тоже быть не должно.
            v.setViewVisibility(R.id.w_pool_bar, View.GONE)
            v.setViewVisibility(R.id.w_pool_bar_view, View.GONE)
        }

        val note = card.note ?: card.reset.takeIf { large }
        if (large && note != null) {
            v.setViewVisibility(R.id.w_pool_note, View.VISIBLE)
            v.setTextViewText(R.id.w_pool_note, note)
        }
        if (forList) v.setOnClickFillInIntent(R.id.w_row_root, android.content.Intent())
        return v
    }

    /**
     * Строка со своим цветом бара — ранжирование моделей и расход по ключам.
     * Отличается от строки пула только тем, что цвет задаётся снаружи: у модели
     * это цвет провайдера, а не шкала давления.
     */
    fun barRow(
        title: String,
        value: String,
        fraction: Double?,
        @ColorInt barColor: Int,
        @ColorInt valueColor: Int = theme.on,
        large: Boolean = false,
        avatar: Bitmap? = null,
        note: String? = null,
    ) = add(barRowViews(title, value, fraction, barColor, valueColor, large, avatar, note))

    /** Та же строка, но возвращённая — для прокручиваемого списка. */
    fun barRowViews(
        title: String,
        value: String,
        fraction: Double?,
        @ColorInt barColor: Int,
        @ColorInt valueColor: Int = theme.on,
        large: Boolean = false,
        avatar: Bitmap? = null,
        note: String? = null,
        forList: Boolean = false,
    ): RemoteViews {
        val v = child(R.layout.widget_row_pool)
        if (avatar != null) {
            v.setImageViewBitmap(R.id.w_pool_avatar, avatar)
        } else {
            v.setViewVisibility(R.id.w_pool_avatar, View.GONE)
        }
        v.setTextViewText(R.id.w_pool_title, title)
        v.setTextViewTextSize(R.id.w_pool_title, TypedValue.COMPLEX_UNIT_SP, if (large) 14f else 12f)
        v.setTextViewText(R.id.w_pool_value, value)
        v.setTextColor(R.id.w_pool_value, valueColor)
        if (fraction != null) {
            paintBar(v, fraction, barColor, if (large) 10f else 8f)
        } else {
            v.setViewVisibility(R.id.w_pool_bar, View.GONE)
            v.setViewVisibility(R.id.w_pool_bar_view, View.GONE)
        }
        if (note != null) {
            v.setViewVisibility(R.id.w_pool_note, View.VISIBLE)
            v.setTextViewText(R.id.w_pool_note, note)
        }
        if (forList) v.setOnClickFillInIntent(R.id.w_row_root, android.content.Intent())
        return v
    }

    /** Пара метрик рядом: значение, подпись и спарклайн у каждой. */
    fun metrics(
        aValue: String,
        aLabel: String,
        aSpark: Bitmap?,
        bValue: String?,
        bLabel: String?,
        bSpark: Bitmap?,
    ) {
        val v = child(R.layout.widget_row_metrics)
        v.setTextViewText(R.id.w_metric_a_value, aValue)
        v.setTextViewText(R.id.w_metric_a_label, aLabel)
        if (aSpark != null) v.setImageViewBitmap(R.id.w_metric_a_spark, aSpark)
        else v.setViewVisibility(R.id.w_metric_a_spark, View.GONE)
        if (bValue == null) {
            v.setViewVisibility(R.id.w_metric_div, View.GONE)
            v.setViewVisibility(R.id.w_metric_b, View.GONE)
        } else {
            v.setTextViewText(R.id.w_metric_b_value, bValue)
            v.setTextViewText(R.id.w_metric_b_label, bLabel ?: "")
            if (bSpark != null) v.setImageViewBitmap(R.id.w_metric_b_spark, bSpark)
            else v.setViewVisibility(R.id.w_metric_b_spark, View.GONE)
        }
        add(v)
    }

    /** Полоса графика. */
    fun chart(bitmap: Bitmap) {
        val v = child(R.layout.widget_row_chart)
        v.setImageViewBitmap(R.id.w_chart, bitmap)
        add(v)
    }

    /** Подписи оси X: начало, середина, «сегодня». */
    fun axis(start: String, mid: String, end: String) {
        val v = child(R.layout.widget_row_axis)
        v.setTextViewText(R.id.w_axis_start, start)
        v.setTextViewText(R.id.w_axis_mid, mid)
        v.setTextViewText(R.id.w_axis_end, end)
        add(v)
    }

    /** Строка таблицы вызовов. */
    fun tableRow(time: String, model: String, tokens: String, usd: String, @ColorInt dotColor: Int) =
        add(tableRowViews(time, model, tokens, usd, dotColor))

    /** Та же строка, но возвращённая — для прокручиваемого списка. */
    fun tableRowViews(
        time: String,
        model: String,
        tokens: String,
        usd: String,
        @ColorInt dotColor: Int,
        forList: Boolean = false,
    ): RemoteViews {
        val v = child(R.layout.widget_row_table)
        v.setTextViewText(R.id.w_cell_time, time)
        v.setImageViewBitmap(R.id.w_cell_dot, WidgetDraw.dot(context, 9f, dotColor))
        v.setTextViewText(R.id.w_cell_model, model)
        v.setTextViewText(R.id.w_cell_tokens, tokens)
        v.setTextViewText(R.id.w_cell_usd, usd)
        if (forList) v.setOnClickFillInIntent(R.id.w_row_root, android.content.Intent())
        return v
    }

    /** Строка легенды. */
    fun legendRow(name: String, value: String, share: String, @ColorInt dotColor: Int, into: Int = R.id.w_body) {
        val v = child(R.layout.widget_row_legend)
        v.setImageViewBitmap(R.id.w_leg_dot, WidgetDraw.dot(context, 9f, dotColor))
        v.setTextViewText(R.id.w_leg_name, name)
        v.setTextViewText(R.id.w_leg_value, value)
        v.setTextViewText(R.id.w_leg_share, share)
        root.addView(into, v)
    }

    /**
     * Донат с центром и легендой. Возвращает контейнер легенды, чтобы строки
     * добавлялись именно в него, а не в тело виджета.
     */
    fun donut(bitmap: Bitmap, main: String, sub: String, withLegend: Boolean): Int {
        val v = child(R.layout.widget_row_donut)
        v.setImageViewBitmap(R.id.w_donut, bitmap)
        v.setTextViewText(R.id.w_donut_main, main)
        v.setTextViewText(R.id.w_donut_sub, sub)
        if (!withLegend) v.setViewVisibility(R.id.w_donut_legend, View.GONE)
        add(v)
        return R.id.w_donut_legend
    }

    /** Кольцо квоты с числом в центре. */
    fun ring(bitmap: Bitmap, center: String) {
        val v = child(R.layout.widget_row_ring)
        v.setImageViewBitmap(R.id.w_ring, bitmap)
        v.setTextViewText(R.id.w_ring_text, center)
        add(v)
    }

    /** Чип-вердикт с пояснением справа. */
    fun chip(text: String, tone: WidgetTone, note: String? = null) {
        val v = child(R.layout.widget_row_chip)
        v.setTextViewText(R.id.w_chip, text)
        v.setInt(R.id.w_chip, "setBackgroundResource", theme.chipBackground(tone))
        v.setTextColor(R.id.w_chip, theme.chipForeground(tone))
        if (note != null) v.setTextViewText(R.id.w_chip_note, note) else v.setViewVisibility(R.id.w_chip_note, View.GONE)
        add(v)
    }

    /** Статус шлюза. Правая половина показывается только на широком ярусе. */
    fun status(data: WidgetData.StatusData, wide: Boolean) {
        val v = child(R.layout.widget_row_status)
        val accent = if (data.running) theme.primary else theme.onVariant
        v.setImageViewBitmap(R.id.w_gate_glyph, WidgetDraw.gateGlyph(context, 22f, accent))
        v.setImageViewBitmap(
            R.id.w_gate_dot,
            WidgetDraw.dot(context, 9f, if (data.running) theme.pressureColor(com.aigate.router.quota.ResourcePressure.FREE) else theme.pressureColor(com.aigate.router.quota.ResourcePressure.CRITICAL)),
        )
        v.setTextViewText(R.id.w_gate_state, WidgetText.gateState(data.running))
        v.setTextViewText(R.id.w_gate_port, WidgetText.gatePort(data.running, data.port))
        if (wide) {
            v.setTextViewText(R.id.w_next_eyebrow, "Следующий запрос обслужит")
            v.setTextViewText(R.id.w_next_model, if (data.running) (data.modelId ?: WidgetText.DASH) else WidgetText.DASH)
            v.setTextViewText(
                R.id.w_next_reason,
                if (!data.running) "запросы не принимаются"
                else listOfNotNull(data.providerName, data.reason).joinToString(" · "),
            )
        } else {
            v.setViewVisibility(R.id.w_gate_div, View.GONE)
            v.setViewVisibility(R.id.w_gate_next, View.GONE)
        }
        add(v)
    }

    /** Пустое состояние: значок и одна короткая строка. */
    fun empty(text: String) {
        // Пустое состояние центрируется по вертикали: контейнер строк теперь
        // обёрнут по содержимому, поэтому гравитацию задаём телу оболочки.
        root.setInt(R.id.w_body, "setGravity", Gravity.CENTER)
        val v = child(R.layout.widget_row_empty)
        v.setImageViewBitmap(R.id.w_empty_icon, WidgetDraw.emptyGlyph(context, 40f, theme.onVariant))
        v.setTextViewText(R.id.w_empty_text, text)
        add(v)
    }

    /** Футер прижат к нижней кромке оболочки. */
    fun footer(text: String) {
        root.setViewVisibility(R.id.w_footer, View.VISIBLE)
        root.setTextViewText(R.id.w_footer, text)
    }

    fun onClick(pendingIntent: PendingIntent) {
        root.setOnClickPendingIntent(R.id.w_root, pendingIntent)
    }

    fun build(): RemoteViews = root

    internal companion object {
        /**
         * Рисуем ли мы сейчас вне хоста виджетов (отладочная галерея). Флаг
         * выставляется только на время сборки превью и читается в canScroll.
         */
        @Volatile
        internal var previewMode: Boolean = false
    }
}
