package com.aigate.router.pricing

import com.aigate.router.data.db.AppDatabase
import com.aigate.router.data.model.ModelPricing

/**
 * Расчёт ОЦЕНОЧНОЙ стоимости (estimated cost) из usage × pricing.
 *
 * Инвариант: это оценка AiGate, НЕ баланс провайдера. Если цены для модели нет —
 * возвращаем null (в UI «стоимость неизвестна»), а не 0.
 */
object CostCalculator {

    data class CostEstimate(
        val usd: Double,
        val currency: String,
        val pricingSource: String,   // "user" | "bundled"
        val pricedAt: Long
    )

    /** Разрешение цены: пользовательская запись → bundled в БД → встроенная константа. */
    suspend fun priceFor(db: AppDatabase, providerType: String, modelId: String): ModelPricing? {
        val dao = db.modelPricingDao()
        val t = providerType.lowercase()
        // точное совпадение (unique index по provider_type+model_id)
        dao.get(t, modelId)?.let { return it }
        // пользовательский/bundled wildcard провайдера
        dao.get(t, "*")?.let { return it }
        // из БД по всем записям того же типа — по базовому имени
        val all = dao.getAll().filter { it.providerType.lowercase() == t }
        val m = modelId.lowercase()
        all.firstOrNull { m.startsWith(it.modelId.lowercase()) && it.modelId != "*" }?.let { return it }
        // финальный фолбэк — встроенная константа
        return PricingTable.bundledFor(providerType, modelId)
    }

    /** Стоимость одного вызова. null, если цена неизвестна. */
    suspend fun estimateCall(
        db: AppDatabase,
        providerType: String,
        modelId: String,
        promptTokens: Int,
        completionTokens: Int
    ): CostEstimate? {
        val price = priceFor(db, providerType, modelId) ?: return null
        val usd = promptTokens / 1_000_000.0 * price.inputPer1M +
            completionTokens / 1_000_000.0 * price.outputPer1M
        return CostEstimate(usd, price.currency, price.source, price.cachedAt)
    }

    /**
     * Суммарная оценочная стоимость за период `since..now`.
     *
     * Возвращает [Aggregate] с суммой в USD и признаком `hasUnpriced` — были ли вызовы,
     * для которых цена неизвестна (тогда сумма — нижняя граница, о чём UI сообщает честно).
     */
    data class Aggregate(
        val usd: Double,
        val pricedCalls: Int,
        val unpricedCalls: Int
    ) {
        val hasUnpriced: Boolean get() = unpricedCalls > 0
    }

    suspend fun totalCostSince(db: AppDatabase, since: Long, providerId: Long? = null): Aggregate {
        val usageRows = db.tokenUsageDao().getAllUsageOnce().filter {
            it.timestamp >= since && (providerId == null || it.providerId == providerId)
        }
        if (usageRows.isEmpty()) return Aggregate(0.0, 0, 0)
        // типы провайдеров по id (одним проходом)
        val providerTypes = db.providerDao().getAllProvidersOnce().associate { it.id to it.type }
        var usd = 0.0
        var priced = 0
        var unpriced = 0
        // кэш цен на время расчёта
        val priceCache = HashMap<String, ModelPricing?>()
        for (row in usageRows) {
            val type = providerTypes[row.providerId] ?: "custom"
            val key = "$type::${row.modelId}"
            val price = priceCache.getOrPut(key) { priceFor(db, type, row.modelId) }
            if (price == null) {
                unpriced++
            } else {
                usd += row.promptTokens / 1_000_000.0 * price.inputPer1M +
                    row.completionTokens / 1_000_000.0 * price.outputPer1M
                priced++
            }
        }
        return Aggregate(usd, priced, unpriced)
    }
}
