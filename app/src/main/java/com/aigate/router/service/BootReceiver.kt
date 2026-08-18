package com.aigate.router.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Поднимает шлюз после перезагрузки устройства, ЕСЛИ он был запущен до неё
 * (`gateway_was_running`). Запуск foreground-сервиса из BOOT_COMPLETED — разрешённое
 * исключение из ограничений фонового старта FGS (Android 12+).
 *
 * Использует ранее «мёртвое» разрешение RECEIVE_BOOT_COMPLETED.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                if (GatewayForegroundService.getGatewayWasRunning()) {
                    try {
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, GatewayForegroundService::class.java)
                        )
                        Log.i(TAG, "Автозапуск шлюза после перезагрузки")
                    } catch (e: Exception) {
                        Log.w(TAG, "Не удалось автозапустить шлюз: ${e.message}")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
