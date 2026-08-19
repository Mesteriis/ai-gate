package com.aigate.router

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.aigate.router.ui.navigation.AppNavHost
import com.aigate.router.ui.theme.GatewayTheme
import com.aigate.router.utils.localizedText

class MainActivity : AppCompatActivity() {

    private var showPermDialog by mutableStateOf(false)
    private var permMessage by mutableStateOf("")

    // ★ 通知权限请求器（Android 13+）
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 用户已响应 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // ★★ 应用隐藏多任务设置（运行时从最近任务中移除）★★
        val hideFromRecents = com.aigate.router.service.GatewayForegroundService.getGatewayConfig("hide_from_recents", "false").toBoolean()
        if (hideFromRecents) {
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.appTasks.firstOrNull()?.setExcludeFromRecents(true)
            } catch (_: Exception) {}
        }

        // ★★ 恢复测速开关状态 ★★
        val pipelineEnabled = com.aigate.router.service.GatewayForegroundService.getGatewayConfig("pipeline_test_enabled", "true").toBoolean()

        // ★★ 打开软件时自动检查关键权限 ★★
        checkPermissionsOnStart()

        setContent {
            GatewayTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost()
                }

                // Диалог о недостающих разрешениях: показываем ОДИН раз, а не при
                // каждом запуске — иначе он перекрывает дашборд на старте.
                if (showPermDialog) {
                    AlertDialog(
                        onDismissRequest = { showPermDialog = false },
                        title = { Text("Работа в фоне") },
                        text = { Text(permMessage) },
                        confirmButton = {
                            Button(onClick = {
                                showPermDialog = false
                                // 引导用户去电池优化设置
                                try {
                                    val intent = Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                                    ).apply {
                                        data = android.net.Uri.fromParts("package", packageName, null)
                                    }
                                    startActivity(intent)
                                } catch (_: Exception) {
                                    // 降级到应用详情页
                                    try {
                                        val intent = Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                        ).apply {
                                            data = android.net.Uri.fromParts("package", packageName, null)
                                        }
                                        startActivity(intent)
                                    } catch (_: Exception) {}
                                }
                            }) {
                                Text("В настройки")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPermDialog = false }) {
                                Text("Позже")
                            }
                        }
                    )
                }
            }
        }
    }

    /** 启动时检查关键权限，缺失则弹窗引导 */
    private fun checkPermissionsOnStart() {
        val missingPerms = mutableListOf<String>()

        // 1. 通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                missingPerms.add("Разрешение на уведомления")
                // 尝试直接请求
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 2. 忽略电池优化（后台保活关键）
        if (Build.VERSION.SDK_INT >= 23) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                missingPerms.add("Игнорировать оптимизацию батареи")
            }
        }

        // 3. 检查唤醒保活是否已开启（没有则建议用户开启）
        val wakeEnabled = com.aigate.router.service.GatewayForegroundService.run {
            com.aigate.router.GatewayApplication.getInstance()
                .getSharedPreferences("aigate_config", Context.MODE_PRIVATE)
                .getBoolean("wake_enabled", false)
        }
        if (!wakeEnabled) {
            missingPerms.add("Поддержание активности (включить в уведомлении)")
        }

        // Подсказку о фоновых разрешениях показываем один раз за установку:
        // повторный показ при каждом запуске только мешает.
        val alreadyShown = com.aigate.router.service.GatewayForegroundService
            .getGatewayConfig("perm_hint_shown", "false").toBoolean()
        if (missingPerms.isNotEmpty() && !alreadyShown) {
            permMessage = "Чтобы шлюз стабильно работал в фоне, включите:\n\n• " +
                missingPerms.joinToString("\n• ")
            showPermDialog = true
            com.aigate.router.service.GatewayForegroundService
                .saveGatewayConfig("perm_hint_shown", "true")
        }
    }
}