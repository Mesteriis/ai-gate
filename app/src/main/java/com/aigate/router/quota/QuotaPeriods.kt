package com.aigate.router.quota

import java.util.Calendar

/**
 * Границы расчётного периода пула: с какого момента считается расход и когда
 * произойдёт следующий сброс.
 *
 * Вынесены из пересчёта квот, чтобы расход по данным поставщика и локальный
 * подсчёт делили одни и те же границы: иначе два числа на одном экране
 * относились бы к разным отрезкам времени.
 */
object QuotaPeriods {

    /**
     * День сброса зажимается до 28-го: 29, 30 и 31 есть не в каждом месяце,
     * и без зажима период в феврале уезжал бы на другую дату.
     */
    private fun day(resetDay: Int?): Int = (resetDay ?: 1).coerceIn(1, 28)

    /** Начало текущего периода: последний прошедший день сброса в 00:00. */
    fun periodStart(now: Long, resetDay: Int?): Long {
        val d = day(resetDay)
        val cal = midnight(now)
        return if (cal.get(Calendar.DAY_OF_MONTH) >= d) {
            cal.set(Calendar.DAY_OF_MONTH, d); cal.timeInMillis
        } else {
            cal.add(Calendar.MONTH, -1); cal.set(Calendar.DAY_OF_MONTH, d); cal.timeInMillis
        }
    }

    /** Следующий сброс: ближайший будущий день сброса в 00:00. */
    fun nextReset(now: Long, resetDay: Int?): Long {
        val d = day(resetDay)
        val cal = midnight(now)
        return if (cal.get(Calendar.DAY_OF_MONTH) < d) {
            cal.set(Calendar.DAY_OF_MONTH, d); cal.timeInMillis
        } else {
            cal.add(Calendar.MONTH, 1); cal.set(Calendar.DAY_OF_MONTH, d); cal.timeInMillis
        }
    }

    private fun midnight(now: Long): Calendar = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
}
