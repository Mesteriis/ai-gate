package com.aigate.router.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Галерея виджетов для скриншотов (только отладочная сборка).
 *
 * Показывает НАСТОЯЩИЕ RemoteViews провайдеров с настоящими данными, поэтому
 * снимок этого экрана честно показывает то, что пользователь увидит на домашнем
 * экране. Тему берём системную: тёмный вариант снимается тем же экраном после
 * `adb shell cmd uimode night yes`.
 *
 * Запуск:
 *   adb shell am start -n com.aigate.router/.widget.WidgetGalleryActivity --es mode cover
 *   adb shell am start -n com.aigate.router/.widget.WidgetGalleryActivity --es mode gallery
 */
class WidgetGalleryActivity : ComponentActivity() {

    private data class Item(
        val provider: BaseWidgetProvider<*>,
        val tier: WidgetTier,
        val caption: String,
    )

    private var column: LinearLayout? = null

    /** Настоящий хост виджетов — только для режима «host». */
    private var host: AppWidgetHost? = null
    private val hostedIds = mutableListOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUp()
        render(intent)
    }

    /**
     * Повторный `am start` не создаёт новый экземпляр, а доставляет интент сюда:
     * без этого снимок «галереи» получался копией предыдущего режима.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent)
    }

    private fun setUp() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, dp(24), pad, dp(32))
            background = wallpaper()
        }
        val scroll = ScrollView(this).apply {
            addView(column, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isVerticalScrollBarEnabled = false
        }
        setContentView(scroll)
        this.column = column
    }

    private fun render(source: android.content.Intent?) {
        val column = this.column ?: return
        column.removeAllViews()
        val mode = source?.getStringExtra("mode")
        if (mode == "host") {
            renderHosted(column, source?.getBooleanExtra("seed", false) == true)
            return
        }
        val cover = mode != "gallery"
        // Галерея разбита на страницы: на один экран влезает пять виджетов, а
        // скриншот снимает только видимую часть.
        val page = (source?.getIntExtra("page", 1) ?: 1).coerceAtLeast(1)
        val items = if (cover) {
            coverItems()
        } else {
            galleryItems().drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
        }
        val seed = source?.getBooleanExtra("seed", false) == true
        lifecycleScope.launch {
            if (seed) {
                // Только по явному флагу: сеятель перезаписывает таблицы.
                withContext(Dispatchers.IO) { WidgetDemoData.seed(this@WidgetGalleryActivity) }
                // Шлюз поднимаем по-настоящему, чтобы на снимке было настоящее
                // «Работает», а не выдуманное состояние: сервис запускает Ktor
                // только если сохранённый флаг говорит, что шлюз был активен.
                // Второй запуск сервиса роняет процесс: порт уже занят прошлым
                // экземпляром, и BindException летит из фоновой корутины шлюза.
                if (!com.aigate.router.service.GatewayForegroundService.isServiceRunning) {
                    runCatching {
                        com.aigate.router.service.GatewayForegroundService.saveGatewayPort(8889)
                        com.aigate.router.service.GatewayForegroundService.saveGatewayRunningState(true)
                        val intent = android.content.Intent(
                            this@WidgetGalleryActivity,
                            com.aigate.router.service.GatewayForegroundService::class.java,
                        )
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                    }
                    kotlinx.coroutines.delay(2500)
                }
            }
            for (item in items) {
                val width = nominalWidth(item.tier)
                val views = withContext(Dispatchers.IO) {
                    item.provider.renderPreview(
                        this@WidgetGalleryActivity,
                        item.tier,
                        (width - 32).toFloat(),
                    )
                }
                if (!cover) column.addView(caption(item.caption))
                val host = FrameLayout(this@WidgetGalleryActivity)
                val rendered = views.apply(this@WidgetGalleryActivity, host)
                host.addView(rendered)
                column.addView(
                    host,
                    LinearLayout.LayoutParams(dp(width), dp(nominalHeight(item.tier))).apply {
                        topMargin = dp(12)
                        gravity = Gravity.CENTER_HORIZONTAL
                    },
                )
            }
        }
    }

    /**
     * Режим настоящего хоста: виджеты биндятся через AppWidgetHost и рисуются
     * ровно так, как их рисует лаунчер — вместе с коллекциями, которые при
     * обычном apply() в активности не работают.
     *
     * Требует однократной выдачи права:
     *   adb shell appwidget grantbind --package com.aigate.router --user 0
     */
    private fun renderHosted(column: LinearLayout, seed: Boolean) {
        val manager = AppWidgetManager.getInstance(this)
        val host = this.host ?: AppWidgetHost(this, HOST_ID).also { this.host = it }
        lifecycleScope.launch {
            if (seed) withContext(Dispatchers.IO) { WidgetDemoData.seed(this@WidgetGalleryActivity) }
            for ((provider, tier, caption) in hostedItems()) {
                val id = host.allocateAppWidgetId()
                hostedIds += id
                val width = nominalWidth(tier)
                val height = nominalHeight(tier)
                val options = Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
                }
                val bound = manager.bindAppWidgetIdIfAllowed(id, ComponentName(this@WidgetGalleryActivity, provider), options)
                column.addView(caption(if (bound) caption else "$caption — нет права bind"))
                if (!bound) continue
                manager.updateAppWidgetOptions(id, options)
                val info = manager.getAppWidgetInfo(id) ?: continue
                val view = host.createView(this@WidgetGalleryActivity, id, info)
                column.addView(
                    view,
                    LinearLayout.LayoutParams(dp(width), dp(height)).apply {
                        topMargin = dp(12)
                        gravity = Gravity.CENTER_HORIZONTAL
                    },
                )
            }
            host.startListening()
        }
    }

    /** Что показать в режиме хоста: виджеты со списками. */
    private fun hostedItems(): List<Triple<Class<out BaseWidgetProvider<*>>, WidgetTier, String>> = listOf(
        Triple(QuotaWidgetProvider::class.java, WidgetTier.LARGE, "Ресурсы · 4×4 · прокрутка"),
        Triple(CallsWidgetProvider::class.java, WidgetTier.WIDE, "Последние вызовы · 4×2 · прокрутка"),
        Triple(ModelsWidgetProvider::class.java, WidgetTier.WIDE, "Топ моделей · 4×2 · прокрутка"),
    )

    override fun onDestroy() {
        super.onDestroy()
        // Не оставляем за собой висящие id: иначе система копит пустые экземпляры.
        host?.let { h ->
            runCatching { h.stopListening() }
            hostedIds.forEach { runCatching { h.deleteAppWidgetId(it) } }
        }
        hostedIds.clear()
    }

    /** Курируемая раскладка — та же, что на обложке макетов. */
    private fun coverItems(): List<Item> = listOf(
        Item(StatusWidgetProvider(), WidgetTier.ROW_WIDE, "Статус шлюза · 4×1"),
        Item(QuotaWidgetProvider(), WidgetTier.WIDE, "Ресурсы · 4×2"),
        Item(TokensWidgetProvider(), WidgetTier.WIDE, "Токены по дням · 4×2"),
        Item(SharesWidgetProvider(), WidgetTier.WIDE, "Доли провайдеров · 4×2"),
        Item(CallsWidgetProvider(), WidgetTier.WIDE, "Последние вызовы · 4×2"),
    )

    /** Полная галерея: каждый виджет во всех ярусах, которые он поддерживает. */
    private fun galleryItems(): List<Item> = listOf(
        Item(QuotaWidgetProvider(), WidgetTier.SQUARE, "Ресурсы · 2×2"),
        Item(QuotaWidgetProvider(), WidgetTier.WIDE, "Ресурсы · 4×2"),
        Item(QuotaWidgetProvider(), WidgetTier.LARGE, "Ресурсы · 4×4"),
        Item(TokensWidgetProvider(), WidgetTier.WIDE, "Токены по дням · 4×2"),
        Item(TokensWidgetProvider(), WidgetTier.LARGE, "Токены по дням · 4×4"),
        Item(SpendWidgetProvider(), WidgetTier.SQUARE, "Расход за месяц · 2×2"),
        Item(SpendWidgetProvider(), WidgetTier.WIDE, "Расход за месяц · 4×2"),
        Item(SharesWidgetProvider(), WidgetTier.SQUARE, "Доли провайдеров · 2×2"),
        Item(SharesWidgetProvider(), WidgetTier.WIDE, "Доли провайдеров · 4×2"),
        Item(CallsWidgetProvider(), WidgetTier.WIDE, "Последние вызовы · 4×2"),
        Item(CallsWidgetProvider(), WidgetTier.LARGE, "Последние вызовы · 4×4"),
        Item(StatusWidgetProvider(), WidgetTier.ROW_NARROW, "Статус шлюза · 2×1"),
        Item(StatusWidgetProvider(), WidgetTier.ROW_WIDE, "Статус шлюза · 4×1"),
        Item(WindowsWidgetProvider(), WidgetTier.SQUARE, "Окна квоты · 2×2"),
        Item(WindowsWidgetProvider(), WidgetTier.WIDE, "Окна квоты · 4×2"),
        Item(BurnWidgetProvider(), WidgetTier.WIDE, "Темп расхода · 4×2"),
        Item(BurnWidgetProvider(), WidgetTier.LARGE, "Темп расхода · 4×4"),
        Item(TrafficWidgetProvider(), WidgetTier.SQUARE, "Трафик · 2×2"),
        Item(TrafficWidgetProvider(), WidgetTier.WIDE, "Трафик · 4×2"),
        Item(ModelsWidgetProvider(), WidgetTier.WIDE, "Топ моделей · 4×2"),
        Item(ModelsWidgetProvider(), WidgetTier.LARGE, "Топ моделей · 4×4"),
        Item(KeysWidgetProvider(), WidgetTier.WIDE, "Расход по ключам · 4×2"),
        Item(SpeedWidgetProvider(), WidgetTier.ROW_WIDE, "Скорость · 4×1"),
        Item(SpeedWidgetProvider(), WidgetTier.WIDE, "Скорость · 4×2"),
    )

    private fun nominalWidth(tier: WidgetTier): Int = if (tier.isWide) 380 else 184

    private fun nominalHeight(tier: WidgetTier): Int = when (tier) {
        WidgetTier.ROW_NARROW, WidgetTier.ROW_WIDE -> 86
        WidgetTier.SQUARE, WidgetTier.WIDE -> 184
        WidgetTier.LARGE -> 380
    }

    private fun caption(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(captionColor())
        letterSpacing = 0.05f
        setPadding(dp(4), dp(16), 0, 0)
    }

    private fun isDark(): Boolean =
        WidgetTheme.luminance(WidgetTheme.of(this).surface) < 0.5

    private fun captionColor(): Int = if (isDark()) Color.parseColor("#A9BAD4") else Color.parseColor("#4A5A70")

    /** Обои «морозных врат»: на них и проверяется читаемость оболочки. */
    private fun wallpaper(): GradientDrawable {
        val colors = if (isDark()) {
            intArrayOf(Color.parseColor("#0A1220"), Color.parseColor("#101B30"), Color.parseColor("#16233A"))
        } else {
            intArrayOf(Color.parseColor("#DCE9FA"), Color.parseColor("#EDF3FC"), Color.parseColor("#E4EDFA"))
        }
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PAGE_SIZE = 5
        const val HOST_ID = 4210
    }

}
