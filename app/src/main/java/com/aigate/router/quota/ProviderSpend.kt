package com.aigate.router.quota

import com.aigate.router.data.model.QuotaSnapshot

/**
 * Расход за период по данным поставщика.
 *
 * Собственный учёт (`token_usage`) записывает только то, что прошло через шлюз,
 * поэтому потребление в обход шлюза для него невидимо. Счётчики поставщика,
 * наоборот, накопительные и учитывают всё, откуда бы запрос ни пришёл, — значит
 * расход за период это сумма их приростов между снимками. Пропущенный опрос при
 * этом ничего не теряет: следующая разность поглощает разрыв.
 *
 * Расчёт молчит, когда данных не хватает: ноль здесь означал бы «поставщик
 * подтвердил, что не потрачено ничего», а это другое утверждение.
 */
object ProviderSpend {

    /**
     * @param amount израсходовано за период в единицах [unit]
     * @param coveredFromMs с какого момента расход действительно покрыт данными;
     *   позже начала периода, если снимков за его начало нет
     * @param points сколько снимков поставщика участвовало в расчёте
     */
    data class PeriodSpend(
        val amount: Double,
        val unit: String,
        val coveredFromMs: Long,
        val points: Int,
    )

    fun periodSpend(history: List<QuotaSnapshot>, fromMs: Long, toMs: Long): PeriodSpend? {
        val provider = history
            .filter { it.source == QuotaSource.PROVIDER_API.name && it.updatedAt <= toMs }
            .sortedBy { it.updatedAt }
        // Единицу закрепляем по последнему снимку: пул мог считаться в долларах,
        // а потом поставщик начал отдавать проценты — смена шкалы не расход.
        val unit = provider.lastOrNull()?.unit ?: return null
        val sameUnit = provider.filter { it.unit.equals(unit, ignoreCase = true) }

        // Что известно поставщику: израсходованное или только остаток. Часть
        // поставщиков (DeepSeek) отдаёт лишь баланс, и тогда расход виден
        // как его убыль.
        val byUsed = sameUnit.filter { it.used != null }
        val byRemaining = sameUnit.filter { it.remaining != null }
        val series: List<QuotaSnapshot>
        val value: (QuotaSnapshot) -> Double
        when {
            byUsed.size >= 2 -> {
                series = byUsed
                value = { it.used!! }
            }
            byRemaining.size >= 2 -> {
                series = byRemaining
                // Убыль остатка это расход, поэтому знак переворачиваем и дальше
                // считаем ровно так же, как по израсходованному.
                value = { -it.remaining!! }
            }
            else -> return null
        }

        // Точка перед началом периода нужна как база: без неё расход между ней и
        // первым снимком внутри окна потерялся бы.
        val baseline = series.lastOrNull { it.updatedAt <= fromMs }
        val inWindow = series.filter { it.updatedAt > fromMs }
        val points = listOfNotNull(baseline) + inWindow
        if (points.size < 2) return null

        var spent = 0.0
        for (i in 1 until points.size) {
            val delta = value(points[i]) - value(points[i - 1])
            // Отрицательный прирост — сброс периода у поставщика или пополнение
            // счёта, а не возврат средств: вклад такого участка ноль.
            if (delta > 0.0) spent += delta
        }

        return PeriodSpend(
            amount = spent,
            unit = unit,
            coveredFromMs = if (baseline != null) fromMs else points.first().updatedAt,
            points = points.size,
        )
    }
}
