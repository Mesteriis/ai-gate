package com.aigate.router.quota

import java.util.concurrent.ConcurrentHashMap

/**
 * Реестр провайдер-специфичных адаптеров квот. Пустой по умолчанию: в v0.1.0 нет
 * подтверждённых публичных endpoint'ов остатка/лимита, поэтому реальный баланс не
 * подделывается. Адаптеры добавляются здесь по мере появления (без изменения ядра).
 */
object QuotaProviderRegistry {
    private val providers = ConcurrentHashMap<String, RemoteQuotaProvider>()

    fun register(provider: RemoteQuotaProvider) {
        providers[provider.providerType.lowercase()] = provider
    }

    fun providerFor(providerType: String): RemoteQuotaProvider? =
        providers[providerType.lowercase()]

    fun isEmpty(): Boolean = providers.isEmpty()
}
