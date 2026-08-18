package com.aigate.router.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aigate.router.R
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.quota.QuotaRepository
import com.aigate.router.quota.ResourcePressure
import com.aigate.router.service.GatewayForegroundService

/**
 * Уведомления о квотах — ОПЦИОНАЛЬНЫ (по умолчанию выключены), пороги настраиваемы.
 * Никогда не запускает ИИ-задачи автоматически: только показывает уведомление о том,
 * что ресурс на исходе. Требует явного включения пользователем + разрешения на
 * уведомления.
 */
object QuotaNotifier {
    private const val CHANNEL_ID = "aigate_quota_channel"
    private const val NOTIFICATION_ID = 4711

    const val KEY_ENABLED = "quota_notify_enabled"
    const val KEY_THRESHOLD = "quota_notify_threshold"

    fun isEnabled(): Boolean =
        GatewayForegroundService.getGatewayConfig(KEY_ENABLED, "false") == "true"

    fun setEnabled(enabled: Boolean) {
        GatewayForegroundService.saveGatewayConfig(KEY_ENABLED, if (enabled) "true" else "false")
    }

    /** Порог доли остатка (0..1), ниже которого уведомляем. По умолчанию 0.15. */
    fun threshold(): Double =
        GatewayForegroundService.getGatewayConfig(KEY_THRESHOLD, "0.15").toDoubleOrNull() ?: 0.15

    fun setThreshold(value: Double) {
        GatewayForegroundService.saveGatewayConfig(KEY_THRESHOLD, value.coerceIn(0.01, 0.99).toString())
    }

    suspend fun checkAndNotify(context: Context, db: AppDatabase) {
        if (!isEnabled()) return
        if (!hasNotificationPermission(context)) return

        val quotas = QuotaRepository.latest(db)
        val alerting = quotas.filter {
            it.pressure == ResourcePressure.CRITICAL || it.pressure == ResourcePressure.CONSERVE
        }
        if (alerting.isEmpty()) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            return
        }

        ensureChannel(context)
        val worst = if (alerting.any { it.pressure == ResourcePressure.CRITICAL })
            ResourcePressure.CRITICAL else ResourcePressure.CONSERVE
        val title = if (worst == ResourcePressure.CRITICAL)
            "Ресурс на исходе" else "Пора экономить ресурс"
        val lines = alerting.take(5).joinToString("\n") { pq ->
            val name = pq.pool.name
            val snap = pq.snapshot
            val detail = if (snap?.remaining != null && snap.limit != null)
                "осталось ${fmt(snap.remaining)} из ${fmt(snap.limit)} ${snap.unit}"
            else "давление: ${pq.pressure.label}"
            "• $name — $detail"
        }

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_gate_fg)
            .setContentTitle(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notif)
        } catch (_: SecurityException) {
            // разрешение отозвано между проверкой и показом — молча пропускаем
        }
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Квоты и ресурсы",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Предупреждения об исчерпании квоты/бюджета" }
            )
        }
    }

    private fun fmt(v: Double): String =
        if (v >= 100) "%.0f".format(v) else "%.2f".format(v)
}
