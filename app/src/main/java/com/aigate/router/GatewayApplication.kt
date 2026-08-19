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
            // Обновить список моделей Codex с сервера (он меняется на стороне провайдера).
            try { com.aigate.router.auth.CliSessionManager.ensureCodexModels(database) } catch (_: Exception) { }
            // Засев встроенной таблицы цен + первичный расчёт квот (локальный usage).
            try { com.aigate.router.pricing.PricingTable.seedIfNeeded(database) } catch (_: Exception) { }
            try { com.aigate.router.quota.QuotaRepository.refreshAll(database) } catch (_: Exception) { }
            restoreLocalModels()
        }
        // Реальные адаптеры квот (документированные публичные endpoint'ы).
        com.aigate.router.quota.QuotaProviderRegistry.register(
            com.aigate.router.quota.adapters.OpenRouterQuotaProvider()
        )
        com.aigate.router.quota.QuotaProviderRegistry.register(
            com.aigate.router.quota.adapters.CodexQuotaProvider()
        )
        com.aigate.router.quota.QuotaProviderRegistry.register(
            com.aigate.router.quota.adapters.DeepSeekQuotaProvider()
        )
        com.aigate.router.quota.QuotaProviderRegistry.register(
            com.aigate.router.quota.adapters.CursorQuotaProvider()
        )
        com.aigate.router.quota.QuotaProviderRegistry.register(
            com.aigate.router.quota.adapters.ClaudeQuotaProvider()
        )
        // Периодическое обновление квот (каждые 6ч; не поллинг).
        com.aigate.router.quota.QuotaRefreshWorker.schedule(this)
        registerLocalBackends()
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

    /**
     * Приведение скачанных моделей в порядок после запуска процесса.
     *
     * Процесс могли убить посреди загрузки: WorkManager вернёт задание сам, но
     * записи в состояниях «в очереди» и «скачивается» надо поставить обратно —
     * иначе они останутся висеть навсегда. Файлы-сироты появляются по той же
     * причине, и это гигабайты: без уборки они лежали бы мёртвым грузом,
     * не числясь ни за одной моделью.
     */
    private suspend fun restoreLocalModels() {
        val dao = database.localModelDao()
        runCatching { com.aigate.router.download.LocalModelSync.sync(database) }
        runCatching {
            val all = dao.getAll()
            com.aigate.router.download.ModelStorage.cleanupOrphans(
                context = this,
                knownPaths = all.filter { it.filePath.isNotBlank() }.map { it.filePath }.toSet(),
                activeIds = all.map { it.id }.toSet(),
            )
        }
        runCatching { com.aigate.router.download.DownloadQueue.resumeInterruptedOnStartup(this, dao) }
    }

    /**
     * Подключение локальных бэкендов ровно в том объёме, который тянет
     * устройство.
     *
     * Неподдержанный движок не регистрируется совсем — тогда шлюз даже не
     * узнаёт о таком типе провайдера и идёт обычным сетевым путём. Это и есть
     * мягкое отключение: на телефоне без встроенной модели функции просто нет,
     * вместо ошибки на каждый запрос.
     */
    private fun registerLocalBackends() {
        runCatching {
            val support = com.aigate.router.capability.DeviceSupportProbe.report(this)
            if (com.aigate.router.gateway.local.EchoBackend.isEnabled()) {
                // Отладочный бэкенд занимает место движка llama.cpp: так путь
                // обслуживания проверяется целиком, вместе с маршрутизацией.
                com.aigate.router.gateway.local.LocalBackendRegistry.register(
                    com.aigate.router.gateway.local.EchoBackend()
                )
                GatewayForegroundService.addDebugLog("Локальный отладочный бэкенд подключён")
                return@runCatching
            }
            if (support.nano.supported) {
                com.aigate.router.gateway.local.LocalBackendRegistry.register(
                    com.aigate.router.gateway.local.nano.GeminiNanoBackend()
                )
            }
            if (!support.anyLocalSupported) {
                GatewayForegroundService.addDebugLog("Локальные модели недоступны: ${support.nano.reasonRu}")
            }
            // Движки скачанных моделей подключатся здесь по мере готовности:
            // LiteRT-LM и llama.cpp.
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
