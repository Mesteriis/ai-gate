package com.aigate.router.pricing

import com.aigate.router.data.db.AppDatabase
import com.aigate.router.data.model.ModelPricing

/**
 * Встроенная таблица цен (USD за 1M токенов) для распространённых моделей.
 *
 * ВАЖНО про честность источника: это статический справочник, который ДРЕЙФУЕТ.
 * Каждая запись помечается source="bundled" и фиксированным `cachedAt` (дата, на
 * которую цены были актуальны). Пользовательские записи (source="user") всегда
 * приоритетнее и не перезаписываются при засеве.
 */
object PricingTable {

    /** Дата актуальности встроенных цен (2026-01-01 UTC) — показывается в UI как «на дату». */
    const val BUNDLED_AS_OF: Long = 1_767_225_600_000L

    private fun p(type: String, model: String, input: Double, output: Double) =
        ModelPricing(
            providerType = type,
            modelId = model,
            inputPer1M = input,
            outputPer1M = output,
            currency = "USD",
            source = "bundled",
            cachedAt = BUNDLED_AS_OF
        )

    /**
     * Ориентировочные публичные цены. Список намеренно короткий и консервативный —
     * лучше отсутствие цены (и честное «стоимость неизвестна»), чем неверная цифра.
     */
    val BUNDLED: List<ModelPricing> = listOf(
        // OpenAI
        p("openai", "gpt-4o", 2.50, 10.00),
        p("openai", "gpt-4o-mini", 0.15, 0.60),
        p("openai", "gpt-4.1", 2.00, 8.00),
        p("openai", "gpt-4.1-mini", 0.40, 1.60),
        p("openai", "gpt-4.1-nano", 0.10, 0.40),
        p("openai", "o3-mini", 1.10, 4.40),
        // Anthropic
        p("anthropic", "claude-3-5-sonnet", 3.00, 15.00),
        p("anthropic", "claude-3-5-haiku", 0.80, 4.00),
        p("anthropic", "claude-3-opus", 15.00, 75.00),
        // Google Gemini
        p("gemini", "gemini-1.5-pro", 1.25, 5.00),
        p("gemini", "gemini-1.5-flash", 0.075, 0.30),
        p("gemini", "gemini-2.0-flash", 0.10, 0.40),
        // Локальные модели (Ollama) — бесплатны локально
        p("ollama", "*", 0.0, 0.0),
    )

    /** Быстрый доступ к встроенной цене без БД (для фолбэка). */
    fun bundledFor(providerType: String, modelId: String): ModelPricing? {
        val t = providerType.lowercase()
        val m = modelId.lowercase()
        // точное совпадение
        BUNDLED.firstOrNull { it.providerType == t && it.modelId.lowercase() == m }?.let { return it }
        // wildcard провайдера (например ollama/*)
        BUNDLED.firstOrNull { it.providerType == t && it.modelId == "*" }?.let { return it }
        // по базовому имени модели (без суффиксов даты/версии), тот же тип
        BUNDLED.firstOrNull { it.providerType == t && m.startsWith(it.modelId.lowercase()) }?.let { return it }
        return null
    }

    /** Засев встроенных цен в БД при первом запуске (не трогает пользовательские записи). */
    suspend fun seedIfNeeded(db: AppDatabase) {
        val dao = db.modelPricingDao()
        val existing = dao.getAll()
        if (existing.any { it.source == "bundled" }) return
        val userKeys = existing.map { it.providerType.lowercase() to it.modelId.lowercase() }.toSet()
        val toInsert = BUNDLED.filter { (it.providerType.lowercase() to it.modelId.lowercase()) !in userKeys }
        if (toInsert.isNotEmpty()) dao.upsertAll(toInsert)
    }
}
