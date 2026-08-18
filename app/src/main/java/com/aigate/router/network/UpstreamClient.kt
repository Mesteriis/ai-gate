package com.aigate.router.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ConnectionPool
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 上游 API 客户端 —— 支持 HTTP / SOCKS5 代理及账号密码认证
 *
 * 支持的代理配置：
 * - HTTP 无认证：java.net.Proxy(Type.HTTP, InetSocketAddress)
 * - HTTP 有认证：java.net.Proxy + okhttp3.Authenticator (Proxy-Authorization Basic)
 * - SOCKS5 无认证：Socks5SocketFactory（空用户名密码）
 * - SOCKS5 有认证：Socks5SocketFactory（RFC 1929 用户名/密码认证）
 */
object UpstreamClient {

    @Volatile
    private var currentConfig: ProxyConfig? = null

    @Volatile
    private var client = createClient()

    /**
     * 当前生效的代理配置（精简版，供 UpstreamClient 内部使用）
     */
    data class ProxyConfig(
        val type: String = "HTTP",
        val host: String = "",
        val port: Int = 0,
        val username: String = "",
        val password: String = "",
        val enabled: Boolean = false
    ) {
        val isValid: Boolean get() = enabled && host.isNotBlank() && port > 0
    }

    private fun createClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)      // 0 = 无上限，流式响应不中断
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .callTimeout(0, TimeUnit.SECONDS)       // 0 = 无总超时，长流式不断连
            .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS)) // 连接池：最多5个，空闲30秒回收

        val config = currentConfig
        if (config != null && config.isValid) {
            when (config.type.uppercase()) {
                "HTTP", "HTTPS" -> {
                    // 使用完整限定名避免与 okhttp3.Proxy 冲突
                    val proxy = java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress(config.host, config.port))
                    builder.proxy(proxy)

                    // 如果设置了用户名密码，配置 Proxy-Authorization (407 认证)
                    if (config.username.isNotBlank()) {
                        builder.proxyAuthenticator { _, response ->
                            if (response.code == 407) {
                                val credential = okhttp3.Credentials.basic(config.username, config.password)
                                response.request.newBuilder()
                                    .header("Proxy-Authorization", credential)
                                    .build()
                            } else {
                                null
                            }
                        }
                    }

                    // HTTPS proxies use the platform default TLS validation (system trust
                    // store, real hostname verification). The upstream "trust-all + verify{true}"
                    // MITM hole from QiTong is intentionally NOT reproduced here.
                }
                "SOCKS5", "SOCKS" -> {
                    // 使用自定义 Socks5SocketFactory（支持 RFC 1929 认证）
                    val socketFactory = Socks5SocketFactory(
                        proxyHost = config.host,
                        proxyPort = config.port,
                        username = config.username,
                        password = config.password
                    )
                    builder.socketFactory(socketFactory)
                }
            }
        }

        return builder.build()
    }

    /**
     * 设置代理配置（null 表示不使用代理）
     */
    fun setProxy(config: ProxyConfig?) {
        currentConfig = config
        client = createClient()
    }

    /**
     * 获取当前 OkHttpClient 实例（供 GatewayService 通用代理使用）
     */
    fun getOkHttpClient(): OkHttpClient = client

    /**
     * 获取直连 OkHttpClient（不走代理，供按模型粒度控制代理使用）
     */
    private var directClient: OkHttpClient = createDirectClient()

    private fun createDirectClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .callTimeout(0, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
            .build()
    }

    /**
     * 获取直连客户端（无代理）
     */
    fun getDirectClient(): OkHttpClient = directClient

    /**
     * 根据模型是否走代理，返回对应的客户端
     * @param useProxy true=走代理（使用配置了代理的client），false=直连
     */
    fun getClientForModel(useProxy: Boolean): OkHttpClient {
        return if (useProxy) client else directClient
    }

    /**
     * 获取当前代理配置
     */
    fun getCurrentProxy(): ProxyConfig? = currentConfig

    private val jsonMediaType = "application/json".toMediaType()

    // ── Cleartext policy ──────────────────────────────────────────────
    // network_security_config permits cleartext at the platform level so LAN LLM
    // servers work; the real restriction is enforced HERE: plain http:// is allowed
    // only to loopback / RFC1918 private hosts (or a user allowlist). Public hosts
    // must use HTTPS. (Static network_security_config can't express private CIDRs.)
    private fun isLocalOrPrivate(host: String): Boolean {
        if (host.equals("localhost", true) || host == "::1") return true
        if (host.endsWith(".local", true)) return true
        val o = host.split(".")
        if (o.size == 4 && o.all { (it.toIntOrNull() ?: -1) in 0..255 }) {
            val a = o[0].toInt(); val b = o[1].toInt()
            return a == 127 || a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254)
        }
        return false
    }

    private fun userCleartextAllowlist(): Set<String> =
        com.aigate.router.service.GatewayForegroundService
            .getGatewayConfig("cleartext_allowlist", "")
            .split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    /** Throws if [url] is cleartext http:// to a non-local, non-allowlisted host. */
    fun enforceCleartextPolicy(url: String) {
        if (!url.startsWith("http://", ignoreCase = true)) return
        val host = try { java.net.URI(url).host?.lowercase() ?: "" } catch (_: Exception) { "" }
        if (host.isEmpty() || isLocalOrPrivate(host) || host in userCleartextAllowlist()) return
        throw java.io.IOException("Cleartext HTTP to '$host' is blocked. Use HTTPS, or add the host to the LAN cleartext allowlist.")
    }

    /**
     * 同步请求上游 API（非流式）
     */
    fun request(
        baseUrl: String,
        apiKey: String?,
        requestBody: String,
        path: String = "/v1/chat/completions"
    ): Response {
        val url = baseUrl.trimEnd('/') + path
        enforceCleartextPolicy(url)
        val builder = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody(jsonMediaType))

        if (!apiKey.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        builder.header("Content-Type", "application/json")

        return client.newCall(builder.build()).execute()
    }

    /**
     * 流式 SSE 请求上游 API
     */
    fun requestStream(
        baseUrl: String,
        apiKey: String?,
        requestBody: String,
        path: String = "/v1/chat/completions",
        listener: EventSourceListener
    ): EventSource {
        val url = baseUrl.trimEnd('/') + path
        enforceCleartextPolicy(url)
        val builder = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody(jsonMediaType))

        if (!apiKey.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        builder.header("Content-Type", "application/json")
        builder.header("Accept", "text/event-stream")

        val factory = EventSources.createFactory(client)
        return factory.newEventSource(builder.build(), listener)
    }

    /**
     * 获取模型列表
     */
    fun fetchModels(
        baseUrl: String,
        apiKey: String?,
        apiPath: String = "/v1/models"
    ): Response {
        val url = baseUrl.trimEnd('/') + apiPath
        enforceCleartextPolicy(url)
        val builder = Request.Builder().url(url).get()
        if (!apiKey.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        return client.newCall(builder.build()).execute()
    }
}