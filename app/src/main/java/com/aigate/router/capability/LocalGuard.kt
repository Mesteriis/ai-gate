package com.aigate.router.capability

import com.aigate.router.GatewayApplication
import com.aigate.router.gateway.local.LocalBackendRegistry
import com.aigate.router.service.GatewayForegroundService

/**
 * Единственная точка, где решается «можно ли сейчас считать на устройстве».
 *
 * Ответ разбит на два вопроса разной природы, и важно не путать их местами:
 *
 *  - «умеет ли устройство» — не меняется никогда, поэтому проверяется один раз
 *    при подключении бэкендов ([isTypeSupported]). Неподдержанный движок просто
 *    не регистрируется, и шлюз о нём не знает: это и есть мягкое отключение.
 *  - «можно ли прямо сейчас» — меняется постоянно вместе с зарядом и нагревом,
 *    поэтому спрашивается перед каждым счётом ([blockReason]).
 *
 * Сведение их в один объект нужно, чтобы обслуживание запроса, загрузка движка,
 * замер скорости и список моделей отвечали пользователю одинаково, а не
 * выдумывали каждый свою формулировку.
 */
object LocalGuard {

    /**
     * Помним прошлый отказ, чтобы [ThermalBatteryPolicy] применил гистерезис.
     * Иначе на границе порога модель то доступна, то нет, и список моделей
     * мерцает при каждом обновлении экрана.
     */
    @Volatile
    private var wasBlocked = false

    /**
     * Проверка питания и нагрева перед локальным счётом.
     *
     * Поддержку устройства здесь НЕ проверяем: неподдержанного бэкенда просто
     * нет в реестре, и до этого места вызов не доходит.
     *
     * @return причина отказа на русском или null, если запускать можно
     */
    fun blockReason(providerType: String): String? {
        if (!LocalBackendRegistry.ownsType(providerType)) {
            return "Неизвестный тип локального провайдера: $providerType"
        }
        val app = runCatching { GatewayApplication.getInstance() }.getOrNull()
            ?: return "Приложение не готово"

        val limits = ThermalBatteryPolicy.limitsFrom(GatewayForegroundService::getGatewayConfig)
        val state = PowerStateProvider.current(app)
        return when (val verdict = ThermalBatteryPolicy.evaluate(state, limits, wasBlocked)) {
            is PowerVerdict.Allow -> {
                wasBlocked = false
                null
            }

            is PowerVerdict.Block -> {
                wasBlocked = true
                verdict.reasonRu
            }
        }
    }

    /**
     * Поддержан ли тип устройством — без учёта заряда и нагрева.
     *
     * Спрашивается при подключении бэкендов и там, где решается «показывать ли
     * функцию вообще»: наличие раздела каталога, строка подключения системной
     * модели.
     */
    fun isTypeSupported(providerType: String): Boolean = featureFor(providerType)?.supported == true

    /**
     * Почему тип недоступен на этом устройстве. Нужен для сообщения, когда
     * провайдер в базе есть (например, остался от другого телефона), а бэкенд
     * не подключён.
     */
    fun unsupportedReason(providerType: String): String =
        featureFor(providerType)?.reasonRu ?: "Локальный движок «$providerType» не подключён"

    private fun featureFor(providerType: String): FeatureSupport? {
        val app = runCatching { GatewayApplication.getInstance() }.getOrNull() ?: return null
        val support = DeviceSupportProbe.report(app)
        return when (providerType.trim().lowercase()) {
            LocalBackendRegistry.TYPE_NANO -> support.nano
            LocalBackendRegistry.TYPE_LLAMA -> support.llama
            LocalBackendRegistry.TYPE_LITERT -> support.litert
            else -> null
        }
    }

    /** Сброс настроек порогов должен действовать сразу, без ожидания кэша. */
    fun onLimitsChanged() {
        PowerStateProvider.invalidate()
        wasBlocked = false
    }
}
