package com.aigate.router.widget

import android.content.Context
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.data.model.AiModel
import com.aigate.router.data.model.ModelPricing
import com.aigate.router.data.model.Provider
import com.aigate.router.data.model.QuotaSnapshot
import com.aigate.router.data.model.ResourcePool
import com.aigate.router.data.model.TokenUsage
import java.util.concurrent.TimeUnit

/**
 * Демонстрационные данные для скриншотов виджетов (ТОЛЬКО отладочная сборка).
 *
 * Нужны потому, что на чистом устройстве база пуста, а виджеты честно показывают
 * «Пока нет данных» — для описания в репозитории и магазина нужен наполненный
 * вид. Набор повторяет датасет макетов, чтобы снимки совпадали с дизайном.
 *
 * ВНИМАНИЕ: сеятель ПЕРЕЗАПИСЫВАЕТ таблицы провайдеров, пулов, снимков и
 * расхода. Запускать только на пустом устройстве или эмуляторе и только руками:
 *   adb shell am start -n com.aigate.router/.widget.WidgetGalleryActivity --ez seed true
 */
object WidgetDemoData {

    private const val DAY = 24 * 60 * 60 * 1000L

    /** Расход по дням: входные и выходные токены, тысячи. */
    private val DAILY = listOf(
        6.2 to 2.1, 9.4 to 3.4, 4.1 to 1.5, 2.8 to 0.9, 11.2 to 4.1, 13.6 to 5.2, 8.9 to 3.1,
        10.4 to 3.8, 7.3 to 2.6, 3.2 to 1.1, 5.6 to 2.0, 16.1 to 6.0, 9.8 to 3.6, 9.1 to 3.3,
    )

    suspend fun seed(context: Context, now: Long = System.currentTimeMillis()) {
        val db = AppDatabase.getInstance(context.applicationContext)

        db.tokenUsageDao().clearAll()
        db.quotaSnapshotDao().clearAll()
        db.resourcePoolDao().clearAll()
        db.aiModelDao().deleteAll()
        db.providerDao().deleteAll()

        val codex = db.providerDao().insert(
            Provider(name = "Codex", type = "OpenAI Compatible", baseUrl = "https://chatgpt.com/backend-api")
        )
        val claude = db.providerDao().insert(
            Provider(name = "Claude", type = "Anthropic", baseUrl = "https://api.anthropic.com")
        )
        val cursor = db.providerDao().insert(
            Provider(name = "Cursor", type = "OpenAI Compatible", baseUrl = "https://api.cursor.sh")
        )
        val deepseek = db.providerDao().insert(
            Provider(name = "DeepSeek", type = "OpenAI Compatible", baseUrl = "https://api.deepseek.com")
        )
        val openrouter = db.providerDao().insert(
            Provider(name = "OpenRouter", type = "OpenAI Compatible", baseUrl = "https://openrouter.ai/api")
        )
        val ollama = db.providerDao().insert(
            Provider(name = "Ollama", type = "Ollama", baseUrl = "http://10.34.10.2:11434")
        )

        db.aiModelDao().insertAll(
            listOf(
                AiModel(providerId = claude, modelId = "claude-sonnet-4-5", displayName = "Claude Sonnet 4.5", syncStatus = "Synced"),
                AiModel(providerId = deepseek, modelId = "deepseek-chat", displayName = "DeepSeek Chat", syncStatus = "Synced"),
                AiModel(providerId = codex, modelId = "gpt-5-codex", displayName = "GPT-5 Codex", syncStatus = "Synced"),
                AiModel(providerId = ollama, modelId = "qwen2.5-7b", displayName = "Qwen 2.5 7B", syncStatus = "Synced"),
            )
        )

        db.modelPricingDao().upsertAll(
            listOf(
                ModelPricing(providerType = "Anthropic", modelId = "claude-sonnet-4-5", inputPer1M = 3.0, outputPer1M = 15.0),
                ModelPricing(providerType = "OpenAI Compatible", modelId = "deepseek-chat", inputPer1M = 0.27, outputPer1M = 1.10),
                ModelPricing(providerType = "OpenAI Compatible", modelId = "gpt-5-codex", inputPer1M = 2.50, outputPer1M = 10.0),
                ModelPricing(providerType = "Ollama", modelId = "qwen2.5-7b", inputPer1M = 0.0, outputPer1M = 0.0),
            )
        )

        // Пулы ресурсов: по одному на каждый тип, чтобы виджет показал все состояния.
        val codexPool = db.resourcePoolDao().insert(
            ResourcePool(providerId = codex, name = "Codex", kind = "QUOTA", unit = "PERCENT")
        )
        val claudePool = db.resourcePoolDao().insert(
            ResourcePool(providerId = claude, name = "Claude", kind = "QUOTA", unit = "PERCENT")
        )
        val cursorPool = db.resourcePoolDao().insert(
            ResourcePool(providerId = cursor, name = "Cursor", kind = "BUDGET", unit = "USD", configuredLimit = 20.0, resetDayOfMonth = 1)
        )
        val deepseekPool = db.resourcePoolDao().insert(
            ResourcePool(providerId = deepseek, name = "DeepSeek", kind = "BALANCE", unit = "USD")
        )
        val routerPool = db.resourcePoolDao().insert(
            ResourcePool(providerId = openrouter, name = "OpenRouter", kind = "BALANCE", unit = "CREDITS")
        )
        val ollamaPool = db.resourcePoolDao().insert(
            ResourcePool(providerId = ollama, name = "Ollama", kind = "FREE", unit = "UNKNOWN")
        )

        // История нужна, чтобы посчитался темп расхода и появился вердикт.
        suspend fun history(poolId: Long, unit: String, limit: Double?, from: Double, to: Double, resetsAt: Long?, source: String) {
            val steps = 12
            for (i in 0..steps) {
                val fraction = i.toDouble() / steps
                val remaining = from + (to - from) * fraction
                db.quotaSnapshotDao().insert(
                    QuotaSnapshot(
                        poolId = poolId,
                        used = limit?.let { it - remaining },
                        remaining = remaining,
                        limit = limit,
                        unit = unit,
                        resetsAt = resetsAt,
                        updatedAt = now - (steps - i) * TimeUnit.MINUTES.toMillis(20),
                        source = source,
                    )
                )
            }
        }

        history(codexPool, "PERCENT", 100.0, 34.0, 3.0, now + TimeUnit.HOURS.toMillis(5), "PROVIDER_API")
        history(claudePool, "PERCENT", 100.0, 46.0, 12.0, now + TimeUnit.HOURS.toMillis(3), "PROVIDER_API")
        history(cursorPool, "USD", 20.0, 8.9, 3.8, null, "USER_CONFIGURED")
        history(deepseekPool, "USD", null, 6.4, 4.12, null, "PROVIDER_API")
        history(routerPool, "CREDITS", null, 1310.0, 1240.0, null, "PROVIDER_API")
        db.quotaSnapshotDao().insert(
            QuotaSnapshot(poolId = ollamaPool, unit = "UNKNOWN", updatedAt = now, source = "LOCAL_USAGE")
        )

        // Расход по дням: последний день — сегодня, чтобы ось заканчивалась «сегодня».
        val usage = mutableListOf<TokenUsage>()
        val models = listOf(
            Triple(claude, "claude-sonnet-4-5", 0.41),
            Triple(deepseek, "deepseek-chat", 0.22),
            Triple(codex, "gpt-5-codex", 0.14),
            Triple(ollama, "qwen2.5-7b", 0.09),
            Triple(claude, "claude-haiku-4-5", 0.08),
            Triple(codex, "gpt-4o-mini", 0.06),
        )
        DAILY.forEachIndexed { index, (promptK, completionK) ->
            val dayStart = now - (DAILY.size - 1 - index) * DAY
            models.forEach { (providerId, modelId, share) ->
                val prompt = (promptK * 1000 * share).toInt()
                val completion = (completionK * 1000 * share).toInt()
                if (prompt + completion <= 0) return@forEach
                usage += TokenUsage(
                    providerId = providerId,
                    modelId = modelId,
                    promptTokens = prompt,
                    completionTokens = completion,
                    totalTokens = prompt + completion,
                    uploadBytes = prompt * 4L,
                    downloadBytes = completion * 6L,
                    timestamp = dayStart - TimeUnit.HOURS.toMillis(2) + models.indexOfFirst { it.second == modelId } * 1000L,
                    apiKeyLabel = if (providerId == ollama) "" else "Рабочий ноутбук",
                )
            }
        }
        // Несколько сегодняшних вызовов с разным временем — для таблицы вызовов.
        listOf(
            Triple(claude, "claude-sonnet-4-5", 8_400),
            Triple(deepseek, "deepseek-chat", 3_100),
            Triple(codex, "gpt-5-codex", 12_700),
            Triple(ollama, "qwen2.5-7b", 5_200),
            Triple(claude, "claude-haiku-4-5", 1_900),
        ).forEachIndexed { index, (providerId, modelId, total) ->
            val prompt = (total * 0.73).toInt()
            usage += TokenUsage(
                providerId = providerId,
                modelId = modelId,
                promptTokens = prompt,
                completionTokens = total - prompt,
                totalTokens = total,
                uploadBytes = prompt * 4L,
                downloadBytes = (total - prompt) * 6L,
                timestamp = now - index * TimeUnit.MINUTES.toMillis(7),
                apiKeyLabel = if (providerId == ollama) "" else "Рабочий ноутбук",
            )
        }
        db.tokenUsageDao().insertAll(usage)
    }
}
