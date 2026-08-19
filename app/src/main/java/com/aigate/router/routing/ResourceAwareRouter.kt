package com.aigate.router.routing

import com.aigate.router.data.db.AppDatabase
import com.aigate.router.data.model.AiModel
import com.aigate.router.data.model.Provider
import com.aigate.router.gateway.local.LocalBackendRegistry
import com.aigate.router.pricing.CostCalculator
import com.aigate.router.quota.QuotaRepository
import com.aigate.router.quota.ResourcePressure
import com.aigate.router.service.GatewayForegroundService

/**
 * Переупорядочивает кандидатов `auto`-запроса согласно выбранной стратегии
 * (Phase 13). Уже отсортированный по скорости список (`smartSort`) — базовый порядок
 * и tie-breaker; стратегия добавляет измерение цены/квоты/локальности.
 */
object ResourceAwareRouter {

    /**
     * @param workloadHint значение заголовка `X-AIGate-Workload` (например "heavy"/"light").
     *        Подсказка без контента запроса; при AUTO+heavy смещает выбор в сторону экономии.
     */
    suspend fun reorder(
        db: AppDatabase,
        base: List<AiModel>,
        workloadHint: String?
    ): List<AiModel> {
        if (base.size <= 1) return base

        var strategy = RouteStrategy.fromName(
            GatewayForegroundService.getGatewayConfig(RouteStrategy.CONFIG_KEY, RouteStrategy.AUTO.name)
        )
        if (strategy == RouteStrategy.AUTO && workloadHint.equals("heavy", ignoreCase = true)) {
            strategy = RouteStrategy.CHEAP
        }

        return when (strategy) {
            RouteStrategy.AUTO, RouteStrategy.FAST -> base
            RouteStrategy.OFFLINE -> offlineFirst(db, base)
            RouteStrategy.CHEAP -> byPrice(db, base)
            RouteStrategy.QUOTA -> byQuota(db, base)
        }
    }

    /** Индекс исходного порядка — стабильный tie-breaker. */
    private fun originalIndex(base: List<AiModel>): Map<Long, Int> =
        base.mapIndexed { i, m -> m.id to i }.toMap()

    private suspend fun providersById(db: AppDatabase): Map<Long, Provider> =
        db.providerDao().getAllProvidersOnce().associateBy { it.id }


    private suspend fun offlineFirst(db: AppDatabase, base: List<AiModel>): List<AiModel> {
        val providers = providersById(db)
        val local = base.filter { isLocal(providers[it.providerId]) }
        // OFFLINE = только локальные. Если локальных нет — честно возвращаем исходный
        // список (иначе запрос гарантированно упадёт), маршрутизация не «врёт» о наличии.
        return if (local.isNotEmpty()) local else base
    }

    private suspend fun byPrice(db: AppDatabase, base: List<AiModel>): List<AiModel> {
        val providers = providersById(db)
        val idx = originalIndex(base)
        // цена = средняя (input+output)/2 за 1M; неизвестная цена → в конец.
        val priced = base.map { m ->
            val type = providers[m.providerId]?.type ?: "custom"
            val price = CostCalculator.priceFor(db, type, m.modelId)
            val avg = price?.let { (it.inputPer1M + it.outputPer1M) / 2.0 }
            m to avg
        }
        return priced.sortedWith(
            compareBy({ it.second ?: Double.MAX_VALUE }, { idx[it.first.id] })
        ).map { it.first }
    }

    private suspend fun byQuota(db: AppDatabase, base: List<AiModel>): List<AiModel> {
        val idx = originalIndex(base)
        val quotas = QuotaRepository.latest(db)
        // providerId → (есть остаток?, ранг давления)
        val byProvider = quotas.associate { pq ->
            val hasRemaining = pq.snapshot?.remaining?.let { it > 0.0 } ?: true // неизвестно → не штрафуем
            pq.pool.providerId to (hasRemaining to pressureRank(pq.pressure))
        }
        return base.sortedWith(
            compareBy(
                // сначала провайдеры с остатком
                { byProvider[it.providerId]?.first == false },
                // затем по давлению (меньше — лучше)
                { byProvider[it.providerId]?.second ?: 1 },
                { idx[it.id] }
            )
        )
    }

    private fun pressureRank(p: ResourcePressure): Int = when (p) {
        ResourcePressure.FREE -> 0
        ResourcePressure.NORMAL -> 1
        ResourcePressure.UNKNOWN -> 2
        ResourcePressure.CONSERVE -> 3
        ResourcePressure.CRITICAL -> 4
    }

    private fun isLocal(provider: Provider?): Boolean {
        if (provider == null) return false
        // Модель, считающаяся в самом приложении, локальнее любого адреса:
        // сети ей не нужно вовсе, поэтому в офлайне она и есть ответ.
        if (LocalBackendRegistry.ownsType(provider.type)) return true
        if (provider.type.equals("ollama", ignoreCase = true)) return true
        val url = provider.baseUrl.lowercase()
        return url.contains("localhost") || url.contains("127.0.0.1") || url.contains("::1") ||
            url.contains("//10.") || url.contains("//192.168.") ||
            Regex("//172\\.(1[6-9]|2[0-9]|3[0-1])\\.").containsMatchIn(url)
    }
}
