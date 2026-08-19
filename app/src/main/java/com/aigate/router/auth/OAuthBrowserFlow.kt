package com.aigate.router.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Настоящий браузерный OAuth 2.0 (Authorization Code + PKCE) с loopback-редиректом —
 * ровно как это делают CLI (Codex/Gemini/Claude): приложение поднимает локальный
 * сервер на 127.0.0.1:<порт>, открывает браузер на authorization endpoint, пользователь
 * логинится, провайдер редиректит на `http://localhost:<порт>/callback?code=...`, мы
 * ловим код и меняем его на токены. Никакого копирования вручную.
 */
data class OAuthFlowConfig(
    val providerType: String,
    val authUrl: String,
    val tokenUrl: String,
    val clientId: String,
    val clientSecret: String? = null,
    val scopes: List<String> = emptyList(),
    /** Фиксированный порт loopback-редиректа (null = эфемерный). Для Codex ВАЖЕН: 1455. */
    val fixedPort: Int? = null,
    /** Путь редиректа (Codex: /auth/callback). */
    val redirectPath: String = "/callback",
    /** Доп. параметры authorization-запроса (провайдер-специфичные). */
    val extraAuthParams: Map<String, String> = emptyMap(),
    /**
     * Просить refresh-токен параметрами Google (`access_type`, `prompt`).
     * Провайдеры, которые их не знают, могут отвечать ошибкой — тогда false.
     */
    val requestOfflineAccess: Boolean = true,
    /**
     * Отправлять обмен кода как JSON, а не form-urlencoded: так требует token
     * endpoint Anthropic. RFC 6749 предписывает form, поэтому по умолчанию form.
     */
    val tokenRequestJson: Boolean = false,
    /** Передавать `state` в запросе токена (требование Anthropic). */
    val sendStateInTokenRequest: Boolean = false
)

object OAuthBrowserFlow {
    private const val TAG = "OAuthBrowserFlow"
    private const val CALLBACK_TIMEOUT_MS = 300_000  // 5 минут на вход

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Полный флоу: открыть браузер и дождаться редиректа с кодом, обменять на токены.
     * Возвращает готовую [ImportedSession] или ошибку (таймаут/отказ/сетевые проблемы).
     * Вызывать из корутины (IO); функция блокируется на приёме одного соединения.
     */
    suspend fun authorize(context: Context, config: OAuthFlowConfig): Result<ImportedSession> =
        withContext(Dispatchers.IO) {
            val verifier = randomUrlSafe(64)
            val challenge = s256(verifier)
            val state = randomUrlSafe(24)
            var server: ServerSocket? = null
            try {
                server = try {
                    ServerSocket(config.fixedPort ?: 0, 1, InetAddress.getByName("127.0.0.1"))
                } catch (e: Exception) {
                    return@withContext Result.failure(
                        IllegalStateException("Порт ${config.fixedPort} занят — закройте CLI, использующий его, и повторите")
                    )
                }
                val port = server.localPort
                val redirectUri = "http://localhost:$port${config.redirectPath}"
                val authUrl = buildAuthUrl(config, redirectUri, challenge, state)

                openBrowser(context, authUrl)

                val cb = awaitCallback(server)
                    ?: return@withContext Result.failure(IllegalStateException("Тайм-аут ожидания входа"))
                if (cb["error"] != null) {
                    return@withContext Result.failure(IllegalStateException("Отказ провайдера: ${cb["error"]}"))
                }
                if (cb["state"] != state) {
                    return@withContext Result.failure(IllegalStateException("Несовпадение state (защита от CSRF)"))
                }
                val code = cb["code"]
                    ?: return@withContext Result.failure(IllegalStateException("Провайдер не вернул code"))

                exchangeCode(config, code, redirectUri, verifier, state)
            } catch (e: Exception) {
                Log.w(TAG, "OAuth flow failed: ${e.message}")
                Result.failure(e)
            } finally {
                try { server?.close() } catch (_: Exception) {}
            }
        }

    private fun buildAuthUrl(config: OAuthFlowConfig, redirectUri: String, challenge: String, state: String): String {
        val params = buildMap {
            put("response_type", "code")
            put("client_id", config.clientId)
            put("redirect_uri", redirectUri)
            put("code_challenge", challenge)
            put("code_challenge_method", "S256")
            put("state", state)
            if (config.requestOfflineAccess) {
                put("access_type", "offline")   // Google: выдать refresh_token
                put("prompt", "consent")
            }
            if (config.scopes.isNotEmpty()) put("scope", config.scopes.joinToString(" "))
            putAll(config.extraAuthParams) // провайдер-специфичные (Codex и т.п.)
        }
        val query = params.entries.joinToString("&") {
            "${enc(it.key)}=${enc(it.value)}"
        }
        val sep = if (config.authUrl.contains('?')) "&" else "?"
        return "${config.authUrl}$sep$query"
    }

    private fun openBrowser(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Принять одно соединение от браузера, распарсить query, ответить страницей. */
    private fun awaitCallback(server: ServerSocket): Map<String, String>? {
        server.soTimeout = CALLBACK_TIMEOUT_MS
        repeat(20) { // терпим favicon/повторы, ждём запрос с code|error
            val socket = try { server.accept() } catch (_: Exception) { return null }
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val line = reader.readLine() ?: return@use
                // line: GET /callback?code=...&state=... HTTP/1.1
                val path = line.split(" ").getOrNull(1) ?: return@use
                val params = parseQuery(path)
                val done = params.containsKey("code") || params.containsKey("error")
                val body = if (done)
                    "<html><body style='font-family:sans-serif;text-align:center;padding-top:40px'>" +
                        "<h2>Готово</h2><p>Можно вернуться в приложение AiGate.</p></body></html>"
                else "<html><body>ok</body></html>"
                val resp = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                s.getOutputStream().write(resp.toByteArray())
                s.getOutputStream().flush()
                if (done) return params
            }
        }
        return null
    }

    private fun exchangeCode(
        config: OAuthFlowConfig,
        code: String,
        redirectUri: String,
        verifier: String,
        state: String,
    ): Result<ImportedSession> {
        val fields = buildMap {
            put("grant_type", "authorization_code")
            put("code", code)
            put("redirect_uri", redirectUri)
            put("client_id", config.clientId)
            put("code_verifier", verifier)
            config.clientSecret?.let { put("client_secret", it) }
            if (config.sendStateInTokenRequest) put("state", state)
        }
        val requestBody = if (config.tokenRequestJson) {
            JSONObject(fields).toString().toRequestBody("application/json".toMediaType())
        } else {
            FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        }
        val request = Request.Builder()
            .url(config.tokenUrl)
            .header("Accept", "application/json")
            .post(requestBody)
            .build()
        client.newCall(request).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                return Result.failure(IllegalStateException("Обмен кода не удался: HTTP ${resp.code} ${bodyStr.take(200)}"))
            }
            val json = JSONObject(bodyStr)
            val access = json.optString("access_token", "")
            if (access.isEmpty()) return Result.failure(IllegalStateException("Нет access_token в ответе"))
            val refresh = json.optString("refresh_token", "").ifEmpty { null }
            val expiresIn = json.optLong("expires_in", -1L)
            val expiresAt = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else null
            // Из id_token берём идентификатор аккаунта и e-mail. Идентификатор
            // аккаунта обязателен: бэкенд Codex требует его в заголовке
            // ChatGPT-Account-ID, и без него запросы отклоняются. У OpenAI он
            // лежит в namespaced-claim, поэтому ищем и на верхнем уровне, и во
            // вложенных объектах.
            val claims = decodeJwtClaims(json.optString("id_token", ""))
            // Anthropic отдаёт аккаунт прямо в теле ответа (`account.uuid`,
            // `account.email`), id_token не присылает — поэтому смотрим оба места.
            val account = json.optJSONObject("account")
            val accountId = account?.optString("uuid")?.takeIf { it.isNotBlank() }
                ?: claims?.let { findClaim(it, "chatgpt_account_id") }
            val email = account?.optString("email")?.takeIf { it.isNotBlank() }
                ?: claims?.let { findClaim(it, "email") }
            return Result.success(
                ImportedSession(
                    accessToken = access,
                    refreshToken = refresh,
                    expiresAt = expiresAt,
                    accountId = accountId,
                    providerHint = config.providerType,
                    email = email
                )
            )
        }
    }

    /** Payload JWT без проверки подписи: нужен только для клеймов аккаунта. */
    private fun decodeJwtClaims(idToken: String): JSONObject? {
        if (!idToken.contains('.')) return null
        return runCatching {
            val payload = idToken.split('.')[1]
            JSONObject(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING)))
        }.getOrNull()
    }

    /** Поиск клейма на верхнем уровне и в вложенных объектах (namespaced-claims). */
    private fun findClaim(obj: JSONObject, key: String, depth: Int = 0): String? {
        if (depth > 3) return null
        obj.optString(key, "").takeIf { it.isNotBlank() }?.let { return it }
        val keys = obj.keys()
        while (keys.hasNext()) {
            val nested = obj.optJSONObject(keys.next()) ?: continue
            findClaim(nested, key, depth + 1)?.let { return it }
        }
        return null
    }

    // ---- helpers ----
    private fun parseQuery(path: String): Map<String, String> {
        val q = path.substringAfter('?', "")
        if (q.isEmpty()) return emptyMap()
        return q.split("&").mapNotNull {
            val i = it.indexOf('='); if (i < 0) return@mapNotNull null
            Uri.decode(it.substring(0, i)) to Uri.decode(it.substring(i + 1))
        }.toMap()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun randomUrlSafe(bytes: Int): String {
        val b = ByteArray(bytes); SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun s256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
