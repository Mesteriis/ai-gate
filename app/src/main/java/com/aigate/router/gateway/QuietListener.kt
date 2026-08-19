package com.aigate.router.gateway

import android.util.Log
import com.aigate.router.service.GatewayForegroundService
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Тихий приёмник: держит порт занятым, пока шлюз остановлен, отвечает `503` и
 * считает попытки подключения.
 *
 * Зачем отдельная сущность: пока порт никто не слушает, узнать о попытке
 * подключения технически невозможно — соединение отвергает ядро, и приложение
 * об этом не узнаёт. Поэтому счётчик попыток работает только с этим приёмником,
 * и по умолчанию он выключен: поведение остановленного шлюза не должно меняться
 * молча.
 */
object QuietListener {

    private const val TAG = "QuietListener"
    const val CONFIG_KEY = "quiet_listener_enabled"

    private var socket: ServerSocket? = null
    @Volatile private var running = false

    fun isEnabled(): Boolean =
        GatewayForegroundService.getGatewayConfig(CONFIG_KEY, "false") == "true"

    fun setEnabled(enabled: Boolean, port: Int) {
        GatewayForegroundService.saveGatewayConfig(CONFIG_KEY, if (enabled) "true" else "false")
        if (enabled) start(port) else stop()
    }

    val isRunning: Boolean get() = running

    /** Занять порт, если шлюз не работает и приёмник включён. */
    fun start(port: Int) {
        if (running) return
        if (!isEnabled()) return
        if (GatewayForegroundService.isServiceRunning) return
        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(port))
            socket = server
            running = true
            thread(name = "aigate-quiet-listener", isDaemon = true) { acceptLoop(server) }
        } catch (e: IOException) {
            // Порт занят кем-то ещё — молча не притворяемся, что слушаем.
            Log.w(TAG, "не удалось занять порт $port: ${e.message}")
            running = false
            socket = null
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running && !server.isClosed) {
            try {
                server.accept().use { client ->
                    GatewayForegroundService.blockedAttempts.incrementAndGet()
                    val body = """{"error":{"message":"Шлюз остановлен","type":"gateway_stopped"}}"""
                    val response = buildString {
                        append("HTTP/1.1 503 Service Unavailable\r\n")
                        append("Content-Type: application/json\r\n")
                        append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
                        append("Connection: close\r\n\r\n")
                        append(body)
                    }
                    client.getOutputStream().apply {
                        write(response.toByteArray(Charsets.UTF_8))
                        flush()
                    }
                }
            } catch (_: IOException) {
                // Закрытие сокета при остановке — обычный выход из цикла.
                if (!running) return
            }
        }
    }
}
