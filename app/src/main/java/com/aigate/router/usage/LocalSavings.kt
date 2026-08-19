package com.aigate.router.usage

import com.aigate.router.data.db.AppDatabase
import com.aigate.router.quota.QuotaRepository
import com.aigate.router.quota.ResourcePoolKind
import java.util.Calendar

/**
 * Сколько сэкономили локальные модели за месяц.
 *
 * Локальные токены бесплатны, поэтому экономия — это то, во что тот же объём
 * обошёлся бы в облаке. Эталон не выдумывается: берётся самая дешёвая
 * доступная облачная цена, и её модель называется в подписи прямо, иначе цифра
 * ни о чём не говорит. Нет ни одной облачной цены — экономии не показываем.
 */
object LocalSavings {

    data class Result(
        val savedUsd: Double,
        /** Модель-эталон, по цене которой считали; null — считать не по чему. */
        val referenceModel: String?,
        val localPromptTokens: Long,
        val localCompletionTokens: Long,
    ) {
        val localTokens: Long get() = localPromptTokens + localCompletionTokens
    }

    /** Чистый расчёт: тестируется без базы. */
    fun compute(
        localPromptTokens: Long,
        localCompletionTokens: Long,
        cheapestInputPer1M: Double?,
        cheapestOutputPer1M: Double?,
        referenceModel: String?,
    ): Result {
        if (cheapestInputPer1M == null || cheapestOutputPer1M == null || referenceModel == null) {
            return Result(0.0, null, localPromptTokens, localCompletionTokens)
        }
        val saved = localPromptTokens / 1_000_000.0 * cheapestInputPer1M +
            localCompletionTokens / 1_000_000.0 * cheapestOutputPer1M
        return Result(saved, referenceModel, localPromptTokens, localCompletionTokens)
    }

    /** Экономия с начала месяца по данным приложения. */
    suspend fun monthToDate(db: AppDatabase, now: Long = System.currentTimeMillis()): Result {
        val monthStart = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        // Бесплатные провайдеры определяются типом ресурса, а не именем.
        val freeProviderIds = QuotaRepository.latest(db)
            .filter { ResourcePoolKind.fromName(it.pool.kind) == ResourcePoolKind.FREE }
            .map { it.pool.providerId }
            .toSet()
        if (freeProviderIds.isEmpty()) return Result(0.0, null, 0, 0)

        val rows = db.tokenUsageDao().getAllUsageOnce()
            .filter { it.timestamp >= monthStart && it.providerId in freeProviderIds }
        val prompt = rows.sumOf { it.promptTokens.toLong() }
        val completion = rows.sumOf { it.completionTokens.toLong() }
        if (prompt + completion == 0L) return Result(0.0, null, 0, 0)

        // Эталон: самая дешёвая облачная цена из известных приложению.
        val cheapest = db.modelPricingDao().getAll()
            .filter { it.inputPer1M > 0.0 || it.outputPer1M > 0.0 }
            .minByOrNull { it.inputPer1M + it.outputPer1M }

        return compute(
            localPromptTokens = prompt,
            localCompletionTokens = completion,
            cheapestInputPer1M = cheapest?.inputPer1M,
            cheapestOutputPer1M = cheapest?.outputPer1M,
            referenceModel = cheapest?.modelId,
        )
    }
}
