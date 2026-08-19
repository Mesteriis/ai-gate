package com.aigate.router.capability

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/**
 * Снимок питания и нагрева для [ThermalBatteryPolicy].
 *
 * Подписки на широковещательные события нет намеренно: состояние нужно
 * в момент запроса, а не постоянно, и лишний приёмник в фоновом сервисе —
 * это батарея, которую мы как раз бережём. Вместо этого читается «липкий»
 * ACTION_BATTERY_CHANGED, который система хранит и отдаёт мгновенно.
 *
 * Короткий кэш нужен, чтобы серия запросов подряд не дёргала систему на
 * каждый токен: за несколько секунд ни заряд, ни температура заметно
 * не меняются.
 */
object PowerStateProvider {

    /** Столько живёт снимок. Больше брать нельзя: перегрев наступает быстро. */
    private const val CACHE_TTL_MS = 10_000L

    /** Когда сведений нет, считаем устройство исправным: запрещать вслепую хуже. */
    private val UNKNOWN = PowerState(
        batteryPercent = 100,
        batteryTemperatureC = 0f,
        thermalStatus = ThermalBatteryPolicy.THERMAL_UNKNOWN,
        isCharging = false,
    )

    @Volatile
    private var cached: PowerState? = null

    @Volatile
    private var cachedAt = 0L

    fun current(context: Context): PowerState {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAt < CACHE_TTL_MS) return it }
        val fresh = read(context)
        cached = fresh
        cachedAt = now
        return fresh
    }

    /** Сброс кэша: после смены настроек порогов ждать десять секунд незачем. */
    fun invalidate() {
        cached = null
    }

    private fun read(context: Context): PowerState = try {
        // registerReceiver с null-приёмником возвращает последний липкий
        // Intent и ничего не подписывает.
        val battery: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else UNKNOWN.batteryPercent
        // EXTRA_TEMPERATURE приходит в десятых долях градуса.
        val tenthsC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val temperature = if (tenthsC != Int.MIN_VALUE) tenthsC / 10f else UNKNOWN.batteryTemperatureC
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        PowerState(
            batteryPercent = percent,
            batteryTemperatureC = temperature,
            thermalStatus = thermalStatus(context),
            isCharging = charging,
        )
    } catch (_: Throwable) {
        UNKNOWN
    }

    /** Тепловой режим системы доступен с API 29; ниже сведений просто нет. */
    private fun thermalStatus(context: Context): Int =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ThermalBatteryPolicy.THERMAL_UNKNOWN
        } else {
            try {
                val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                power?.currentThermalStatus ?: ThermalBatteryPolicy.THERMAL_UNKNOWN
            } catch (_: Throwable) {
                ThermalBatteryPolicy.THERMAL_UNKNOWN
            }
        }
}
