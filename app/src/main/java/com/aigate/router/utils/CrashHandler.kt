package com.aigate.router.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局未捕获异常处理器
 * 捕获闪退日志，保存到本地，支持查看和提交到GitHub
 */
object CrashHandler : Thread.UncaughtExceptionHandler {

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var context: Context? = null
    private const val CRASH_DIR = "crash_logs"
    private const val CRASH_FILE = "crash_log.txt"

    fun init(ctx: Context) {
        context = ctx
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            saveCrashLog(throwable)
        } catch (_: Exception) { }
        // 转给默认处理器（系统会弹崩溃对话框）
        defaultHandler?.uncaughtException(thread, throwable)
    }

    /** 保存崩溃日志到文件 */
    private fun saveCrashLog(throwable: Throwable) {
        val ctx = context ?: return
        val dir = File(ctx.filesDir, CRASH_DIR)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, CRASH_FILE)

        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        pw.flush()
        val stackTrace = sw.toString()

        val deviceInfo = buildString {
            appendLine("===== 设备信息 =====")
            appendLine("品牌: ${Build.BRAND}")
            appendLine("型号: ${Build.MODEL}")
            appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("架构: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("应用版本: ${try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { "unknown" } }")
            appendLine("VersionCode: ${try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode } catch (_: Exception) { "?" } }")
        }

        FileWriter(file, true).use { writer ->
            writer.write("=".repeat(60) + "\n")
            writer.write("崩溃时间: $timeStr\n")
            writer.write(deviceInfo)
            writer.write("异常类型: ${throwable.javaClass.name}\n")
            writer.write("异常信息: ${throwable.message ?: "无"}\n")
            writer.write("堆栈跟踪:\n")
            writer.write(stackTrace)
            writer.write("=".repeat(60) + "\n")
            writer.write("\n")
        }
    }

    /** 读取崩溃日志 */
    fun getCrashLog(): String {
        val ctx = context ?: return "CrashHandler 未初始化"
        val file = File(File(ctx.filesDir, CRASH_DIR), CRASH_FILE)
        if (!file.exists()) return ""
        return try { file.readText() } catch (_: Exception) { "读取失败" }
    }

    /** 清除崩溃日志 */
    fun clearCrashLog() {
        val ctx = context ?: return
        val file = File(File(ctx.filesDir, CRASH_DIR), CRASH_FILE)
        if (file.exists()) file.delete()
    }

    /** 是否有崩溃日志 */
    fun hasCrashLog(): Boolean {
        val ctx = context ?: return false
        val file = File(File(ctx.filesDir, CRASH_DIR), CRASH_FILE)
        return file.exists() && file.length() > 0
    }

}