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
 * Тип ресурса провайдера. Это ТРИ РАЗНЫЕ СУЩНОСТИ, и смешивать их под одним
 * словом «квота» нельзя — ни в коде, ни в интерфейсе:
 *
 *  - [QUOTA] — периодическая квота подписки: расходуется и СБРАСЫВАЕТСЯ
 *    (Codex, Claude по подписке). Осмысленны «осталось %», «сброс через …».
 *  - [BALANCE] — денежный баланс, оплаченный заранее: уменьшается и сам НЕ
 *    восстанавливается (DeepSeek и другие pay-as-you-go). Сброса нет,
 *    проценты бессмысленны — показываем сумму остатка.
 *  - [FREE] — бесплатный ресурс без лимита: локальные модели (Ollama,
 *    встроенный в устройство ИИ). Ни остатка, ни сброса, ни стоимости;
 *    показывать «нет данных о квоте» здесь неверно.
 *  - [BUDGET] — не ресурс провайдера, а СОБСТВЕННЫЙ лимит пользователя
 *    поверх любого из типов выше (самоконтроль расхода).
 */
enum class ResourcePoolKind(
    /** Название типа в интерфейсе. */
    val label: String,
    /** Как называется остаток для этого типа. */
    val remainingLabel: String,
) {
    QUOTA("Квота", "Осталось"),
    BALANCE("Баланс", "На счету"),
    FREE("Бесплатно", "Без лимита"),
    BUDGET("Бюджет", "Осталось из лимита");

    /** Сброс по расписанию бывает только у периодической квоты. */
    val hasReset: Boolean get() = this == QUOTA

    /** Доля «израсходовано/лимит» осмысленна не для всех типов. */
    val hasFraction: Boolean get() = this == QUOTA || this == BUDGET

    companion object {
        /**
         * Разбор значения из БД. Понимает прежние имена (SUBSCRIPTION,
         * API_BALANCE, LOCAL_BUDGET), чтобы уже сохранённые пулы не потерялись.
         */
        fun fromName(name: String?): ResourcePoolKind = when (name?.uppercase()) {
            "QUOTA", "SUBSCRIPTION" -> QUOTA
            "BALANCE", "API_BALANCE" -> BALANCE
            "FREE", "FREE_LOCAL", "LOCAL" -> FREE
            "BUDGET", "LOCAL_BUDGET" -> BUDGET
            else -> BUDGET
        }
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
