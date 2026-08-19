package com.aigate.router.capability

/*
 * Что из локального ИИ вообще возможно на этом устройстве.
 *
 * Приложение ставится на любой Android, а встроенная модель есть далеко не
 * везде: AICore живёт только на части флагманов, движки требуют arm64, а
 * beta-SDK может не подтянуться совсем. Правильное поведение в таком случае —
 * функция молча выключена, а не ряд ошибок на каждом экране и в каждом
 * запросе. Поэтому доступность считается один раз и превращается в набор
 * флагов с готовой причиной на русском.
 *
 * Файл намеренно свободен от android.*: сырые признаки собирает зонд
 * [DeviceSupportProbe], а правила их толкования проверяются JVM-тестами.
 */

/**
 * Сырые признаки устройства, снятые зондом. Каждый — простой факт без
 * толкования, чтобы вся политика жила в одном месте.
 */
data class SupportSignals(
    val sdkInt: Int,
    /** Установлен ли системный сервис com.google.android.aicore. */
    val aiCoreInstalled: Boolean,
    /** Загрузились ли классы ML Kit GenAI: без них вызов упал бы NoClassDefFoundError. */
    val mlKitClassesPresent: Boolean,
    val arm64: Boolean,
    /** Подгрузилась ли нативная библиотека llama.cpp (проверяется лениво, один раз). */
    val llamaLibraryLoadable: Boolean,
    /** Загрузились ли классы LiteRT-LM. */
    val liteRtClassesPresent: Boolean,
)

/**
 * Доступность одной функции. Причина заполняется только у недоступной:
 * её показывают пользователю как есть, разбирать коды в UI не нужно.
 */
data class FeatureSupport(val supported: Boolean, val reasonRu: String?) {

    companion object {
        val Available = FeatureSupport(supported = true, reasonRu = null)

        fun unavailable(reasonRu: String) = FeatureSupport(supported = false, reasonRu = reasonRu)
    }
}

/**
 * Итог по всем локальным функциям.
 *
 * [anyEngineSupported] отделён от [anyLocalSupported] намеренно: каталог
 * скачиваемых моделей имеет смысл только при живом движке, а системная модель
 * ничего не скачивает и живёт сама по себе.
 */
data class SupportReport(
    val nano: FeatureSupport,
    val llama: FeatureSupport,
    val litert: FeatureSupport,
) {
    /** Есть ли движок, способный крутить скачанный файл модели. */
    val anyEngineSupported: Boolean get() = llama.supported || litert.supported

    /** Есть ли вообще что-то локальное — включая системную модель. */
    val anyLocalSupported: Boolean get() = nano.supported || anyEngineSupported
}

object DeviceSupport {

    /** ML Kit GenAI требует API 26; на более старых устройствах его классов нет. */
    const val MIN_SDK_NANO = 26

    /**
     * Толкование признаков. Порядок проверок задаёт и текст причины: сначала
     * называется то, что пользователь может понять («старая версия Android»),
     * и только потом внутренние подробности вроде отсутствия библиотек.
     */
    fun evaluate(signals: SupportSignals): SupportReport = SupportReport(
        nano = evaluateNano(signals),
        llama = evaluateLlama(signals),
        litert = evaluateLiteRt(signals),
    )

    private fun evaluateNano(signals: SupportSignals): FeatureSupport = when {
        signals.sdkInt < MIN_SDK_NANO ->
            FeatureSupport.unavailable("Нужен Android 8.0 или новее")

        !signals.aiCoreInstalled ->
            FeatureSupport.unavailable("Устройство не поддерживает AICore")

        // Пакет AICore есть, а классов ML Kit нет — сборка без нужной
        // зависимости или урезанная прошивка. Пускать вызов туда нельзя:
        // он упал бы не исключением, а ошибкой загрузки классов.
        !signals.mlKitClassesPresent ->
            FeatureSupport.unavailable("Компоненты ML Kit недоступны на устройстве")

        else -> FeatureSupport.Available
    }

    private fun evaluateLlama(signals: SupportSignals): FeatureSupport = when {
        !signals.arm64 ->
            FeatureSupport.unavailable("Нужен 64-разрядный процессор arm64")

        !signals.llamaLibraryLoadable ->
            FeatureSupport.unavailable("Библиотека llama.cpp не поддерживается на устройстве")

        else -> FeatureSupport.Available
    }

    private fun evaluateLiteRt(signals: SupportSignals): FeatureSupport = when {
        !signals.arm64 ->
            FeatureSupport.unavailable("Нужен 64-разрядный процессор arm64")

        !signals.liteRtClassesPresent ->
            FeatureSupport.unavailable("Компоненты LiteRT недоступны на устройстве")

        else -> FeatureSupport.Available
    }
}
