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
import com.aigate.router.quota.QuotaBurn
import com.aigate.router.quota.QuotaRepository
import com.aigate.router.quota.ResourcePoolKind
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

    /**
     * Проверить все ресурсы и разослать заслуженные уведомления.
     *
     * Каждый ресурс оценивается своими настройками ([NotifyPrefs]), а темповые
     * триггеры считаются от собственной истории расхода ([QuotaBurn]). Одно
     * уведомление на цикл: повтор до сброса квоты не шлётся.
     */
    suspend fun checkAndNotify(context: Context, db: AppDatabase) {
        if (!isEnabled()) return
        if (!hasNotificationPermission(context)) return

        val now = System.currentTimeMillis()
        val pools = QuotaRepository.latest(db)
        val fresh = mutableListOf<QuotaTriggers.Alert>()

        for (pq in pools) {
            val kind = ResourcePoolKind.fromName(pq.pool.kind)
            val snapshot = pq.snapshot
            val resetsAt = snapshot?.resetsAt

            // Сброс произошёл — прошлые уведомления цикла больше не в счёт.
            val seenReset = NotifyPrefs.resetSeenAt(pq.pool.id)
            if (resetsAt != null && seenReset != null && resetsAt != seenReset) {
                NotifyPrefs.clearSent(pq.pool.id)
            }

            val history = runCatching { db.quotaSnapshotDao().getHistoryForPool(pq.pool.id) }
                .getOrDefault(emptyList())
            val alerts = QuotaTriggers.evaluate(
                QuotaTriggers.Input(
                    poolName = pq.pool.name,
                    kind = kind,
                    remaining = snapshot?.remaining,
                    limit = snapshot?.limit,
                    unit = snapshot?.unit ?: pq.pool.unit,
                    resetsAt = resetsAt,
                    rate = QuotaBurn.rate(history, now),
                    settings = NotifyPrefs.load(pq.pool.id, kind),
                    now = now,
                    resetSeenAt = if (resetsAt != null && resetsAt == seenReset) seenReset else null,
                )
            )

            for (alert in alerts) {
                val trigger = alert.kind.name.lowercase()
                if (NotifyPrefs.sentAt(pq.pool.id, trigger) != null) continue
                fresh += alert
                NotifyPrefs.markSent(pq.pool.id, trigger, now)
                if (alert.kind == QuotaTriggers.Kind.RESET && resetsAt != null) {
                    NotifyPrefs.markResetSeen(pq.pool.id, resetsAt)
                }
            }
            if (resetsAt != null && seenReset == null) NotifyPrefs.markResetSeen(pq.pool.id, resetsAt)
        }

        if (fresh.isEmpty()) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            return
        }

        ensureChannel(context)
        val title = fresh.first().title
        val lines = fresh.joinToString("\n") { it.body }
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

}
