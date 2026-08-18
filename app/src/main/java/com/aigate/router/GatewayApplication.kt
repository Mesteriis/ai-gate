package com.aigate.router

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.data.credential.CredentialStore
import com.aigate.router.service.GatewayForegroundService
import com.aigate.router.utils.TranslationManager
import com.aigate.router.utils.CrashHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GatewayApplication : Application() {

    /** 全局数据库实例 */
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    /** 应用级协程作用域 */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashHandler.init(this)  // ★ 初始化全局崩溃捕获
        TranslationManager.init(this)  // ★ 初始化多语言
        createNotificationChannel()
        // ★★ 预加载凭据缓存（Keystore 解密），确保网关启动后能解析上游密钥 ★★
        applicationScope.launch(Dispatchers.IO) {
            try { CredentialStore.load(database) } catch (_: Exception) { }
            // Восстановить refresh-адаптеры CLI-сессий (автообновление переживает рестарт).
            try { com.aigate.router.auth.CliSessionManager.restoreAdapters() } catch (_: Exception) { }
            // Засев встроенной таблицы цен + первичный расчёт квот (локальный usage).
            try { com.aigate.router.pricing.PricingTable.seedIfNeeded(database) } catch (_: Exception) { }
            try { com.aigate.router.quota.QuotaRepository.refreshAll(database) } catch (_: Exception) { }
        }
        // Реальные адаптеры квот (документированные публичные endpoint'ы).
        com.aigate.router.quota.QuotaProviderRegistry.register(
            com.aigate.router.quota.adapters.OpenRouterQuotaProvider()
        )
        // Периодическое обновление квот (каждые 6ч; не поллинг).
        com.aigate.router.quota.QuotaRefreshWorker.schedule(this)
        // ★★ 从 SharedPreferences 恢复上次网关运行状态（进程重建时最可靠的初始化）★★
        if (GatewayForegroundService.getGatewayWasRunning()) {
            GatewayForegroundService.isServiceRunning = true
        }
        // ★★ 恢复定时备份 WorkManager 调度 ★★
        if (GatewayForegroundService.getGatewayConfig("auto_backup_enabled", "false").toBoolean()) {
            val hour = GatewayForegroundService.getGatewayConfig("auto_backup_hour", "3").toIntOrNull() ?: 3
            val minute = GatewayForegroundService.getGatewayConfig("auto_backup_minute", "0").toIntOrNull() ?: 0
            com.aigate.router.data.db.AutoBackupWorker.schedule(this, hour, minute)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_description)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "aigate_service_channel"

        @Volatile
        private var instance: GatewayApplication? = null

        fun getInstance(): GatewayApplication =
            instance ?: throw IllegalStateException("GatewayApplication not initialized")
    }
}
