package com.aigate.router.quota

/**
 * Единицы измерения квоты. Разные провайдеры считают ресурс по-разному —
 * НИКОГДА не нормализуем неизвестную единицу в «токены».
 */
enum class QuotaUnit {
    TOKENS,
    REQUESTS,
    CREDITS,
    USD,
    COMPUTE_MINUTES,
    PERCENT,
    UNKNOWN;

    companion object {
        fun fromName(name: String?): QuotaUnit =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * Источник данных о квоте. Ключевой инвариант: провайдерский баланс (PROVIDER_API) —
 * реальный, а расчётный остаток бюджета (ESTIMATED/LOCAL_USAGE) — это оценка AiGate.
 * В UI они подписываются по-разному и никогда не выдаются один за другой.
 */
enum class QuotaSource {
    /** Реальные данные из API провайдера. */
    PROVIDER_API,
    /** Посчитано из локального usage (сколько израсходовано). */
    LOCAL_USAGE,
    /** Задано пользователем (месячный бюджет/лимит). */
    USER_CONFIGURED,
    /** Оценка (экстраполяция), помечается как estimate. */
    ESTIMATED;

    companion object {
        fun fromName(name: String?): QuotaSource =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: LOCAL_USAGE
    }
}

/**
 * Тип пула ресурсов.
 *  - SUBSCRIPTION — подписочная квота (напр. Codex/Claude по подписке), обычно с reset.
 *  - API_BALANCE — денежный баланс API-ключа у провайдера.
 *  - LOCAL_BUDGET — локальный бюджет, заданный пользователем (не данные провайдера).
 */
enum class ResourcePoolKind {
    SUBSCRIPTION,
    API_BALANCE,
    LOCAL_BUDGET;

    companion object {
        fun fromName(name: String?): ResourcePoolKind =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: LOCAL_BUDGET
    }
}

/**
 * Уровень «давления» на ресурс — ЛОКАЛЬНАЯ рекомендация AiGate, вычисленная из
 * остатка + времени до сброса + недавнего темпа расхода. НЕ данные провайдера.
 */
enum class ResourcePressure(val label: String) {
    FREE("Свободно"),
    NORMAL("Нормально"),
    CONSERVE("Экономить"),
    CRITICAL("Критично"),
    UNKNOWN("Нет данных");
}
