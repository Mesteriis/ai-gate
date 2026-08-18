package com.aigate.router.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * 支持 RFC 1929 用户名/密码认证的 SOCKS5 Socket 工厂
 *
 * OkHttp 调用流程：createSocket() → socket.connect(targetAddr)
 * 通过自定义 Socks5Socket 在 connect() 中拦截，
 * 先连代理服务器，再执行 SOCKS5 握手（认证 + CONNECT）
 */
class Socks5SocketFactory(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val username: String = "",
    private val password: String = ""
) : SocketFactory() {

    override fun createSocket(): Socket = Socks5Socket()

    override fun createSocket(host: String, port: Int): Socket {
        val socket = Socks5Socket()
        socket.connect(InetSocketAddress(host, port))
        return socket
    }

    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket {
        val socket = Socks5Socket()
        socket.bind(InetSocketAddress(localHost, localPort))
        socket.connect(InetSocketAddress(host, port))
        return socket
    }

    override fun createSocket(host: java.net.InetAddress, port: Int): Socket {
        val socket = Socks5Socket()
        socket.connect(InetSocketAddress(host, port))
        return socket
    }

    override fun createSocket(host: java.net.InetAddress, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket {
        val socket = Socks5Socket()
        socket.bind(InetSocketAddress(localHost, localPort))
        socket.connect(InetSocketAddress(host, port))
        return socket
    }

    /**
     * 自定义 Socket — 在 connect() 时拦截，先连代理再执行 SOCKS5 握手
     */
    private inner class Socks5Socket : Socket() {

        @Volatile
        private var handshakeDone = false

        @Volatile
        private var connectingToProxy = false

        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            if (endpoint !is InetSocketAddress) {
                super.connect(endpoint, timeout)
                return
            }
            // 已握手完成 → 直接透传
            if (handshakeDone) {
                super.connect(endpoint, timeout)
                return
            }
            // 正在连代理服务器 → 透传（避免递归）
            if (connectingToProxy) {
                super.connect(endpoint, timeout)
                return
            }

            val targetHost = endpoint.hostString
            val targetPort = endpoint.port

            // 先连到 SOCKS5 代理服务器（设标志位防止递归）
            connectingToProxy = true
            try {
                super.connect(InetSocketAddress(proxyHost, proxyPort), timeout)
            } finally {
                connectingToProxy = false
            }

            try {
                val inputStream = getInputStream()
                val outputStream = getOutputStream()

                // SOCKS5 握手
                socks5Handshake(inputStream, outputStream, targetHost, targetPort)
                handshakeDone = true
            } catch (e: Exception) {
                try { close() } catch (_: Exception) {}
                throw ConnectException(
                    "Сбой SOCKS5 прокси [${proxyHost}:${proxyPort}→${targetHost}:${targetPort}]: ${e.message}"
                )
            }
        }

        private fun socks5Handshake(input: InputStream, output: OutputStream, targetHost: String, targetPort: Int) {
            val hasAuth = username.isNotBlank()

            // 1) 认证方法协商
            val methods = if (hasAuth) {
                byteArrayOf(0x05, 0x02, 0x00, 0x02.toByte())
            } else {
                byteArrayOf(0x05, 0x01, 0x00)
            }
            output.write(methods)
            output.flush()

            val resp = ByteArray(2)
            readFully(input, resp)

            if (resp[0].toInt() != 0x05) {
                throw IOException("Неверная версия прокси: ${resp[0].toInt()}")
            }

            val chosenMethod = resp[1].toInt() and 0xFF
            when (chosenMethod) {
                0xFF -> throw IOException("Прокси отклонил все методы аутентификации")
                0x02 -> {
                    // RFC 1929 用户名/密码认证
                    if (!hasAuth) throw IOException("Прокси требует аутентификацию, но не предоставлены имя пользователя и пароль")
                    doAuth(input, output)
                }
                0x00 -> { /* 无认证，继续 */ }
                else -> throw IOException("Прокси выбрал неизвестный метод аутентификации: $chosenMethod")
            }

            // 2) CONNECT
            val bos = ByteArrayOutputStream()
            bos.write(0x05) // VER
            bos.write(0x01) // CMD CONNECT
            bos.write(0x00) // RSV

            val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
            if (isIPv4(targetHost)) {
                bos.write(0x01) // ATYP IPv4
                targetHost.split(".").forEach { bos.write(it.toInt() and 0xFF) }
            } else {
                bos.write(0x03) // ATYP DOMAINNAME
                bos.write(hostBytes.size)
                bos.write(hostBytes)
            }
            bos.write((targetPort shr 8) and 0xFF)
            bos.write(targetPort and 0xFF)

            output.write(bos.toByteArray())
            output.flush()

            val header = ByteArray(4)
            readFully(input, header)

            if (header[0].toInt() != 0x05) {
                throw IOException("Неверная версия ответа CONNECT: ${header[0].toInt()}")
            }

            val rep = header[1].toInt() and 0xFF
            if (rep != 0x00) {
                val msg = when (rep) {
                    0x01 -> "Общий сбой сервера"
                    0x02 -> "Подключение не разрешено"
                    0x03 -> "Сеть недоступна"
                    0x04 -> "Хост недоступен"
                    0x05 -> "В подключении отказано"
                    0x06 -> "Таймаут TTL"
                    0x07 -> "Команда не поддерживается"
                    0x08 -> "Тип адреса не поддерживается"
                    else -> "Неизвестная ошибка($rep)"
                }
                throw IOException("Прокси отклонил подключение $targetHost:$targetPort → $msg")
            }

            // 读取绑定地址（忽略）
            val atyp = header[3].toInt() and 0xFF
            when (atyp) {
                0x01 -> readFully(input, ByteArray(4))
                0x03 -> { val len = input.read().toInt() and 0xFF; readFully(input, ByteArray(len)) }
                0x04 -> readFully(input, ByteArray(16))
            }
            readFully(input, ByteArray(2)) // 端口
        }

        private fun doAuth(input: InputStream, output: OutputStream) {
            val uBytes = username.toByteArray(Charsets.UTF_8)
            val pBytes = password.toByteArray(Charsets.UTF_8)
            val bos = ByteArrayOutputStream()
            bos.write(0x01) // 认证版本
            bos.write(uBytes.size)
            bos.write(uBytes)
            bos.write(pBytes.size)
            bos.write(pBytes)
            output.write(bos.toByteArray())
            output.flush()

            val resp = ByteArray(2)
            readFully(input, resp)
            if (resp[0].toInt() != 0x01) throw IOException("Неверная версия аутентификации")
            if (resp[1].toInt() != 0x00) throw IOException("Аутентификация не удалась, проверьте имя пользователя и пароль")
        }

        private fun readFully(input: InputStream, buf: ByteArray) {
            var offset = 0
            while (offset < buf.size) {
                val n = input.read(buf, offset, buf.size - offset)
                if (n < 0) throw IOException("Подключение неожиданно закрыто")
                offset += n
            }
        }

        private fun isIPv4(host: String): Boolean {
            val parts = host.split(".")
            return parts.size == 4 && parts.all {
                it.toIntOrNull()?.let { n -> n in 0..255 } == true
            }
        }
    }
}