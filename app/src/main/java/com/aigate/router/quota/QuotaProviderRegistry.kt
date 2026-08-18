package com.aigate.router.quota

import com.aigate.router.data.model.Provider
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Реестр провайдер-специфичных адаптеров квот. Стартует с нулём или несколькими
 * адаптерами, у которых есть ПОДТВЕРЖДЁННЫЙ публичный endpoint остатка/лимита
 * (например, OpenRouter). Для остальных провайдеров реальный баланс не подделывается —
 * репозиторий откатывается к локальному расчёту расхода.
 */
object QuotaProviderRegistry {
    private val providers = CopyOnWriteArrayList<RemoteQuotaProvider>()

    fun register(provider: RemoteQuotaProvider) {
        providers.add(provider)
    }

    /** Первый адаптер, применимый к данному провайдеру, либо null. */
    fun resolve(provider: Provider): RemoteQuotaProvider? =
        providers.firstOrNull { it.appliesTo(provider) }

    fun isEmpty(): Boolean = providers.isEmpty()

    fun clear() = providers.clear()
}
