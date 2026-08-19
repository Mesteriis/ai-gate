package com.aigate.router.ui.design

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Единые форматтеры для всего UI. До этого в проекте жили четыре несогласованные
 * копии formatBytes и три способа показать токены — числа в разных местах
 * выглядели по-разному.
 */
object Fmt {

    /** Байты: 512 Б · 12,4 КБ · 3,1 МБ · 1,8 ГБ */
    fun bytes(value: Long): String = when {
        value < 1024 -> "$value Б"
        value < 1024L * 1024 -> "%.1f КБ".format(value / 1024.0)
        value < 1024L * 1024 * 1024 -> "%.1f МБ".format(value / (1024.0 * 1024))
        else -> "%.2f ГБ".format(value / (1024.0 * 1024 * 1024))
    }

    /** Компактные числа: 842 · 12,4K · 3,1M */
    /**
     * Русское склонение по числу: plural(2, "вызов", "вызова", "вызовов").
     * Без него в интерфейсе появлялись строки вида «за 2 вызовов».
     */
    fun plural(count: Long, one: String, few: String, many: String): String {
        val mod100 = count % 100
        val mod10 = count % 10
        return when {
            mod100 in 11..14 -> many
            mod10 == 1L -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }

    fun compact(value: Long): String = when {
        value < 1_000 -> value.toString()
        value < 1_000_000 -> "%.1fK".format(value / 1_000.0)
        else -> "%.1fM".format(value / 1_000_000.0)
    }

    /** Деньги: $0,00 (две значащие цифры центов, для мелких сумм — четыре знака). */
    fun usd(value: Double): String =
        if (value > 0.0 && value < 0.01) "$%.4f".format(value) else "$%.2f".format(value)

    /** Значение квоты в её единицах: проценты, запросы, токены, доллары. */
    fun quota(value: Double, unit: String): String = when (unit.uppercase(Locale.ROOT)) {
        "USD" -> usd(value)
        "PERCENT" -> "%.0f%%".format(value)
        "REQUESTS" -> "${value.toLong()}"
        "TOKENS" -> compact(value.toLong())
        else -> "%.2f".format(value)
    }

    /** Человеческая длительность: 2 дн · 5 ч · 30 мин · 45 с */
    fun duration(ms: Long): String {
        if (ms <= 0) return "—"
        val minutes = ms / 60_000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            days > 0 -> "$days дн"
            hours > 0 -> "$hours ч"
            minutes > 0 -> "$minutes мин"
            else -> "${ms / 1000} с"
        }
    }

    /** Обратный отсчёт mm:ss. */
    fun countdown(seconds: Int): String = "%02d:%02d".format(seconds / 60, seconds % 60)

    /** Задержка: 812 мс · 4,9 с */
    fun latency(ms: Long): String =
        if (ms < 1000) "$ms мс" else "%.1f с".format(ms / 1000.0)

    /** Время: 14:05 */
    fun time(timestamp: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

    /** Дата и время: 18.08 14:05 */
    fun dateTime(timestamp: Long): String =
        SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(timestamp))

    /** День для оси графика: 18.08 */
    fun day(timestamp: Long): String =
        SimpleDateFormat("dd.MM", Locale.getDefault()).format(Date(timestamp))
}
