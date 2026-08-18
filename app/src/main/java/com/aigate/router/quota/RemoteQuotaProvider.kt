package com.aigate.router.quota

import com.aigate.router.data.db.AppDatabase
import com.aigate.router.data.model.Provider
import com.aigate.router.data.model.QuotaSnapshot
import com.aigate.router.data.model.ResourcePool

/**
 * Адаптер получения РЕАЛЬНОЙ квоты из API провайдера (source = PROVIDER_API).
 *
 * Реализации провайдер-специфичны и заменяемы. Если у провайдера нет публичного
 * endpoint'а для остатка/лимита — адаптер не регистрируется, и репозиторий честно
 * оставляет remaining=null («Данные о квоте недоступны»), не выдумывая цифру.
 */
interface RemoteQuotaProvider {
    /**
     * Применим ли этот адаптер к данному провайдеру. Матчинг по `Provider` (обычно по
     * host в baseUrl), а не только по `type`, потому что OpenAI-совместимые сервисы
     * (OpenRouter и т.п.) настраиваются с type="openai"/"custom".
     */
    fun appliesTo(provider: Provider): Boolean

    /**
     * Запросить квоту. Возвращает снимок с source=PROVIDER_API или null, если данные
     * недоступны/endpoint не отвечает. Бросать исключение не нужно — null достаточно.
     */
    suspend fun fetch(db: AppDatabase, provider: Provider, pool: ResourcePool): QuotaSnapshot?
}
