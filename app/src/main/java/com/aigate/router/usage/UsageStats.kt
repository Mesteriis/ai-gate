package com.aigate.router.usage

import com.aigate.router.data.model.TokenUsage
import kotlin.math.roundToInt

/**
 * Агрегация статистики расхода за период по уже загруженным строкам token_usage.
 *
 * Чистая логика без базы и Android: вход — готовый список, поэтому расчёт
 * детерминирован и проверяется обычным JUnit. Дельта сравнивает окна строго
 * одинаковой длины, иначе процент вводил бы в заблуждение.
 */
object UsageStats {

    data class ProviderShare(val providerId: Long, val name: String, val tokens: Long)

    data class ModelShare(val modelId: String, val providerId: Long, val tokens: Long)

    data class LabelShare(val label: String, val tokens: Long)

    data class Snapshot(
        val periodDays: Int,
        val fromMs: Long,
        val totalTokens: Long,
        val promptTokens: Long,
        val completionTokens: Long,
        val calls: Int,
        /** null — в предыдущем окне расхода не было, сравнивать не с чем. */
        val deltaPercent: Int?,
        val byProvider: List<ProviderShare>,
        val byModel: List<ModelShare>,
        val byApiKey: List<LabelShare>,
        val uploadBytes: Long,
        val downloadBytes: Long,
    )

    private const val DAY_MS = 86_400_000L

    /** Срез за последние [days] дней от [nowMs] по уже загруженным строкам. */
    fun snapshot(
        rows: List<TokenUsage>,
        providerNames: Map<Long, String>,
        nowMs: Long,
        days: Int,
    ): Snapshot {
        val windowMs = days * DAY_MS
        val fromMs = nowMs - windowMs
        val current = rows.filter { it.timestamp in fromMs..nowMs }
        // Граница fromMs отдана текущему окну, чтобы строка не попала в оба окна.
        val previousTotal = rows
            .filter { it.timestamp >= fromMs - windowMs && it.timestamp < fromMs }
            .sumOf { it.totalTokens.toLong() }

        val total = current.sumOf { it.totalTokens.toLong() }
        val delta =
            if (previousTotal == 0L) null
            else ((total - previousTotal) * 100.0 / previousTotal).roundToInt()

        val byProvider = current.groupBy { it.providerId }
            .map { (id, list) ->
                // Имени в карте нет — честно показываем id, а не выдуманное название.
                ProviderShare(id, providerNames[id] ?: id.toString(), list.sumOf { it.totalTokens.toLong() })
            }
            .sortedByDescending { it.tokens }
        // Одна и та же модель у разных провайдеров — разные строки затрат.
        val byModel = current.groupBy { it.modelId to it.providerId }
            .map { (key, list) -> ModelShare(key.first, key.second, list.sumOf { it.totalTokens.toLong() }) }
            .sortedByDescending { it.tokens }
        val byApiKey = current.groupBy { it.apiKeyLabel.ifBlank { "Без ключа" } }
            .map { (label, list) -> LabelShare(label, list.sumOf { it.totalTokens.toLong() }) }
            .sortedByDescending { it.tokens }

        return Snapshot(
            periodDays = days,
            fromMs = fromMs,
            totalTokens = total,
            promptTokens = current.sumOf { it.promptTokens.toLong() },
            completionTokens = current.sumOf { it.completionTokens.toLong() },
            calls = current.size,
            deltaPercent = delta,
            byProvider = byProvider,
            byModel = byModel,
            byApiKey = byApiKey,
            uploadBytes = current.sumOf { it.uploadBytes },
            downloadBytes = current.sumOf { it.downloadBytes },
        )
    }
}
