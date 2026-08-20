package com.aigate.router.widget

import com.aigate.router.quota.ResourcePoolKind
import com.aigate.router.quota.ResourcePressure
import com.aigate.router.ui.design.Fmt

/**
 * Тексты виджетов: чистые функции без Context, чтобы решения о формулировках
 * проверялись обычным unit-тестом (RemoteViews в JVM-тестах недоступны).
 *
 * Правила те же, что на экранах: тип ресурса называется своим словом, значение
 * всегда говорит про ОСТАТОК, отсутствие данных показывается прочерком, а не
 * нулём, дельта печатается со знаком.
 */
object WidgetText {

    const val DASH = "—"

    /** Подпись строки пула: «Codex · квота». */
    fun poolTitle(name: String, kind: ResourcePoolKind): String =
        "$name · ${kind.label.lowercase()}"

    /**
     * Значение пула для правой колонки. Для квоты и бюджета это остаток,
     * для баланса — сумма на счету, для бесплатного пула лимита нет вовсе.
     */
    fun poolValue(
        kind: ResourcePoolKind,
        remaining: Double?,
        limit: Double?,
        used: Double?,
        unit: String,
    ): String {
        if (kind == ResourcePoolKind.FREE) return "без лимита"
        val fraction = usedFraction(remaining, limit)
        val suffix = unitSuffix(unit)
        return when {
            kind == ResourcePoolKind.QUOTA && fraction != null ->
                "осталось ${percent(1.0 - fraction)}"
            kind == ResourcePoolKind.BUDGET && remaining != null && limit != null ->
                "осталось ${Fmt.quota(remaining, unit)} из ${Fmt.quota(limit, unit)}$suffix"
            remaining != null -> "${Fmt.quota(remaining, unit)}$suffix"
            used != null -> "израсходовано ${Fmt.quota(used, unit)}$suffix"
            else -> DASH
        }
    }

    /**
     * Единица словом там, где формат числа её не несёт. Без этого баланс в
     * кредитах выглядел как «1240,00» — число без смысла; проценты и доллары
     * свой знак несут сами.
     */
    fun unitSuffix(unit: String): String = when (unit.uppercase(java.util.Locale.ROOT)) {
        "TOKENS" -> " ток."
        "REQUESTS" -> " запр."
        "CREDITS" -> " кред."
        "COMPUTE_MINUTES" -> " мин"
        else -> ""
    }

    /** Доля израсходованного: заливка бара и кольца показывает именно её. */
    fun usedFraction(remaining: Double?, limit: Double?): Double? {
        if (remaining == null || limit == null || limit <= 0.0) return null
        return ((limit - remaining) / limit).coerceIn(0.0, 1.0)
    }

    /** Центр кольца: остаток в процентах либо прочерк, если данных нет. */
    fun ringCenter(usedFraction: Double?): String =
        if (usedFraction == null) DASH else percent(1.0 - usedFraction)

    fun percent(fraction: Double): String = "${Math.round(fraction * 100)}%"

    /** «сброс через 3 ч» — только у квоты, у остальных сброса нет. */
    fun resetText(kind: ResourcePoolKind, resetsAt: Long?, now: Long): String = when {
        !kind.hasReset -> "без сброса"
        resetsAt == null -> "сброс неизвестен"
        resetsAt <= now -> "сброс наступил"
        else -> "сброс через ${Fmt.duration(resetsAt - now)}"
    }

    /**
     * Строка-вывод виджета ресурсов: сколько пулов и сколько из них требуют
     * внимания. Без выдуманных чисел: пустой список честно говорит об этом.
     */
    fun resourcesReadout(
        total: Int,
        attention: Int,
        topName: String?,
        topPressure: ResourcePressure?,
    ): Pair<String, String> {
        if (total == 0) return DASH to "снимков ещё не было"
        val main = "$total ${Fmt.plural(total.toLong(), "пул", "пула", "пулов")}"
        val sub = when {
            attention > 0 && topName != null && topPressure != null ->
                "$attention ${Fmt.plural(attention.toLong(), "требует", "требуют", "требуют")} внимания · " +
                    "${topPressure.label.lowercase()} у $topName"
            topName != null -> "все в норме · ближе всех к пределу $topName"
            else -> "все в норме"
        }
        return main to sub
    }

    /** Строка-вывод расхода токенов за период. */
    fun tokensReadout(
        totalTokens: Long,
        days: Int,
        peakDay: Long?,
        peakTokens: Long,
        average: Long,
    ): Pair<String, String> {
        if (totalTokens == 0L) return DASH to "за $days ${dayWord(days)} расхода не было"
        val main = Fmt.compact(totalTokens)
        val sub = if (peakDay != null) {
            "за $days ${dayWord(days)} · пик ${Fmt.day(peakDay)} (${Fmt.compact(peakTokens)}) · " +
                "в среднем ${Fmt.compact(average)} в день"
        } else {
            "за $days ${dayWord(days)} · в среднем ${Fmt.compact(average)} в день"
        }
        return main to sub
    }

    /** Короткий вариант того же вывода — для ярусов, где длинная строка обрезается. */
    fun tokensReadoutShort(totalTokens: Long, days: Int, average: Long): Pair<String, String> {
        if (totalTokens == 0L) return DASH to "расхода не было"
        return Fmt.compact(totalTokens) to "за $days ${dayWord(days)} · в среднем ${Fmt.compact(average)} в день"
    }

    /** Строка-вывод расхода за месяц с прогнозом. */
    fun spendReadout(
        monthToDate: Double,
        projected: Double,
        daysElapsed: Int,
        daysInMonth: Int,
        isEstimate: Boolean,
    ): Pair<String, String> {
        val main = Fmt.usd(monthToDate)
        val forecast = if (isEstimate) "прогноз ${Fmt.usd(projected)}" else Fmt.usd(projected)
        return main to "$forecast · день $daysElapsed из $daysInMonth"
    }

    /** Строка-вывод таблицы вызовов. */
    fun callsReadout(totalCalls: Int, shown: Int, lastAt: Long?): Pair<String, String> {
        if (totalCalls == 0) return DASH to "вызовов ещё не было"
        val main = "$totalCalls ${Fmt.plural(totalCalls.toLong(), "вызов", "вызова", "вызовов")}"
        val sub = buildString {
            if (lastAt != null) append("последний в ${Fmt.time(lastAt)}")
            if (shown < totalCalls) {
                if (isNotEmpty()) append(" · ")
                append("показаны $shown")
            }
        }
        return main to sub.ifEmpty { "за всё время" }
    }

    /** Подпись состояния шлюза. */
    fun gateState(running: Boolean): String = if (running) "Работает" else "Остановлен"

    fun gatePort(running: Boolean, port: Int): String =
        if (running) "порт $port" else "порт $port · не слушает"

    /**
     * Причина выбора модели — те же три формулировки, что на экране обзора
     * (ui/screens/NextRequestCard.kt).
     */
    fun nextReason(forced: Boolean, isBest: Boolean, hasMeasurements: Boolean): String = when {
        forced -> "выбрана вручную"
        isBest && hasMeasurements -> "быстрейшая по замерам"
        else -> "первая доступная: замеров ещё нет"
    }

    /** Подпись свежести снимка в футере. */
    fun updatedFooter(updatedAt: Long?, source: String?, now: Long): String {
        if (updatedAt == null) return "снимков ещё не было"
        val age = now - updatedAt
        val stamp = if (age > 20 * 60 * 60 * 1000L) {
            "обновлено ${Fmt.dateTime(updatedAt)}"
        } else {
            "обновлено ${Fmt.time(updatedAt)}"
        }
        if (source.isNullOrBlank()) return stamp
        return "$stamp · ${Fmt.sourceCaption(source, updatedAt, now).substringBefore(" · ")}"
    }

    /** Склонение слова «день» для оси и подписей периода. */
    fun dayWord(days: Int): String = Fmt.plural(days.toLong(), "день", "дня", "дней")
}
