package com.aigate.router.capability

import java.util.Locale

/*
 * Предохранитель локального инференса по питанию и нагреву.
 *
 * Счёт на устройстве греет корпус и садит батарею быстрее любой сетевой
 * работы, поэтому решение «пускать ли запрос в локальную модель» нельзя
 * оставлять на усмотрение пользователя в момент запроса: телефон должен
 * отказать сам. Пороги вынесены в настройки, потому что «горячо» у разных
 * устройств наступает при разной температуре батареи.
 *
 * Файл намеренно свободен от android.*: зонд, который читает BatteryManager и
 * PowerManager, живёт в Android-слое и отдаёт сюда готовый снимок [PowerState].
 * Так правила проверяются обычными JVM-тестами, без эмулятора.
 */

/**
 * Снимок состояния питания на момент проверки.
 *
 * [thermalStatus] повторяет значения PowerManager.THERMAL_STATUS_* и приходит
 * числом, а не enum: константы объявлены в [ThermalBatteryPolicy], чтобы файл
 * не тянул android.os. На устройствах ниже API 29 источника таких сведений нет,
 * там ожидается [ThermalBatteryPolicy.THERMAL_UNKNOWN].
 */
data class PowerState(
    val batteryPercent: Int,
    val batteryTemperatureC: Float,
    val thermalStatus: Int,
    val isCharging: Boolean,
)

/**
 * Пороги предохранителя. Значения по умолчанию рассчитаны на обычный телефон:
 * ниже 20 % заряда локальная модель съест остаток за один длинный ответ, а
 * 42 °C на батарее — уже ощутимо горячий корпус.
 *
 * [allowWhenCharging] выключен по умолчанию намеренно: зарядка не отменяет
 * нагрев, а наоборот добавляет его, и разрешать разряд «под розеткой» имеет
 * смысл только осознанно.
 */
data class PowerLimits(
    val minBatteryPercent: Int = 20,
    val maxTemperatureC: Float = 42f,
    val blockOnThermalThrottle: Boolean = true,
    val allowWhenCharging: Boolean = false,
)

/**
 * Решение предохранителя. Причина отказа приходит готовой строкой на русском:
 * показать её надо пользователю как есть, без разбора кодов в UI.
 */
sealed interface PowerVerdict {

    data object Allow : PowerVerdict

    data class Block(val reasonRu: String) : PowerVerdict
}

object ThermalBatteryPolicy {

    /** Сведений о тепловом режиме нет: устройство ниже API 29. */
    const val THERMAL_UNKNOWN = -1
    const val THERMAL_NONE = 0
    const val THERMAL_LIGHT = 1
    const val THERMAL_MODERATE = 2
    const val THERMAL_SEVERE = 3
    const val THERMAL_CRITICAL = 4
    const val THERMAL_EMERGENCY = 5
    const val THERMAL_SHUTDOWN = 6

    /** Ключи в SharedPreferences aigate_config; их читает Android-слой. */
    const val KEY_MIN_BATTERY = "local_min_battery"
    const val KEY_MAX_TEMP = "local_max_temp_c"
    const val KEY_BLOCK_ON_THROTTLE = "local_block_on_throttle"
    const val KEY_ALLOW_WHEN_CHARGING = "local_allow_when_charging"

    /**
     * Запас для возврата из отказа. Без него состояние моргает на границе:
     * модель выгружается на 19 %, экран рисует «доступно» на 20 %, и так по
     * кругу каждые несколько секунд.
     */
    private const val BATTERY_HYSTERESIS_PERCENT = 3
    private const val TEMPERATURE_HYSTERESIS_C = 2f

    /**
     * Разумные пределы настройки: заряд выше 90 % запретил бы локальные модели
     * почти всегда, а порог температуры ниже 30 °C срабатывал бы на тёплом
     * устройстве в кармане.
     */
    private const val MIN_BATTERY_FLOOR = 5
    private const val MIN_BATTERY_CEILING = 90
    private const val MAX_TEMP_FLOOR = 30f
    private const val MAX_TEMP_CEILING = 60f

    /**
     * Проверка перед запуском локального счёта.
     *
     * [wasBlocked] — было ли отказано в прошлый раз. При возврате из отказа
     * пороги ужесточаются на величину гистерезиса, поэтому один и тот же
     * [state] может дать разный вердикт: это не ошибка, а защита от мерцания.
     *
     * В тексте отказа по заряду называется настроенный порог, а не поднятый
     * гистерезисом: пользователю нужно число, которое он видит в настройках.
     */
    fun evaluate(state: PowerState, limits: PowerLimits, wasBlocked: Boolean = false): PowerVerdict {
        val batteryGate =
            if (wasBlocked) limits.minBatteryPercent + BATTERY_HYSTERESIS_PERCENT else limits.minBatteryPercent
        // Зарядка снимает только вопрос разряда: греться устройство от неё не перестаёт.
        val chargingWaiver = limits.allowWhenCharging && state.isCharging
        if (state.batteryPercent < batteryGate && !chargingWaiver) {
            return PowerVerdict.Block(
                "Низкий заряд батареи: ${state.batteryPercent}%, " +
                    "локальные модели включаются с ${limits.minBatteryPercent}%"
            )
        }

        val temperatureGate =
            if (wasBlocked) limits.maxTemperatureC - TEMPERATURE_HYSTERESIS_C else limits.maxTemperatureC
        // После отказа мало остыть до порога — нужно уйти под него с запасом.
        val overheated =
            if (wasBlocked) state.batteryTemperatureC > temperatureGate
            else state.batteryTemperatureC >= temperatureGate
        if (overheated) {
            return PowerVerdict.Block(
                "Перегрев: ${celsius(state.batteryTemperatureC)} °C, предел ${celsius(limits.maxTemperatureC)} °C"
            )
        }

        // THERMAL_UNKNOWN меньше любого порога, поэтому отсутствие сведений
        // о тепловом режиме ничего не запрещает — гадать по нему нельзя.
        if (limits.blockOnThermalThrottle && state.thermalStatus >= THERMAL_SEVERE) {
            return PowerVerdict.Block("Устройство перегрето, система снижает частоты")
        }

        return PowerVerdict.Allow
    }

    /**
     * Сборка порогов из строкового хранилища настроек. Сигнатура повторяет
     * GatewayForegroundService.getGatewayConfig(key, default), чтобы вызов из
     * Android-слоя был ссылкой на метод, а не адаптером.
     *
     * Непарсящееся значение — это испорченный или чужой конфиг, и подставлять
     * вместо него ноль было бы опаснее умолчания. Значение за разумными
     * пределами прижимается к границе: пользователь явно хотел «строже» или
     * «мягче», а не сброса настройки.
     */
    fun limitsFrom(readConfig: (String, String) -> String): PowerLimits {
        val defaults = PowerLimits()
        return PowerLimits(
            minBatteryPercent = readConfig(KEY_MIN_BATTERY, defaults.minBatteryPercent.toString())
                .trim().toIntOrNull()
                ?.coerceIn(MIN_BATTERY_FLOOR, MIN_BATTERY_CEILING)
                ?: defaults.minBatteryPercent,
            maxTemperatureC = readConfig(KEY_MAX_TEMP, defaults.maxTemperatureC.toString())
                .trim().toFloatOrNull()
                ?.takeIf { it.isFinite() }
                ?.coerceIn(MAX_TEMP_FLOOR, MAX_TEMP_CEILING)
                ?: defaults.maxTemperatureC,
            blockOnThermalThrottle = boolean(
                readConfig(KEY_BLOCK_ON_THROTTLE, defaults.blockOnThermalThrottle.toString()),
                defaults.blockOnThermalThrottle,
            ),
            allowWhenCharging = boolean(
                readConfig(KEY_ALLOW_WHEN_CHARGING, defaults.allowWhenCharging.toString()),
                defaults.allowWhenCharging,
            ),
        )
    }

    /**
     * Температура в тексте отказа: один знак после запятой и запятая как
     * разделитель. Locale.ROOT берётся ради предсказуемости — системная локаль
     * устройства не должна менять формат сообщения, а русский вид получается
     * заменой точки.
     */
    private fun celsius(value: Float): String =
        String.format(Locale.ROOT, "%.1f", value).replace('.', ',')

    /** Строгий разбор флага: всё, кроме true/false, считается порчей значения. */
    private fun boolean(raw: String, fallback: Boolean): Boolean = when (raw.trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> fallback
    }
}
