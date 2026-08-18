package com.aigate.router.auth

import android.util.Log
import com.aigate.router.data.credential.CredentialStore
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.data.model.Provider
import org.json.JSONObject

/**
 * Поддержка «CLI-провайдеров» по образцу omniroute: провайдер аутентифицируется не
 * API-ключом, а СЕССИЕЙ, полученной его CLI (Codex CLI, Gemini CLI, Claude Code) через
 * браузерный OAuth. AiGate импортирует эту сессию, ХРАНИТ её (Keystore), переиспользует
 * как Bearer и автоматически рефрешит (single-flight) — как omniroute переиспользует
 * локальную сессию CLI.
 *
 * ВАЖНО (ToS/эксперимент): вызов приватных эндпоинтов провайдера чужим клиентом может
 * нарушать их Terms. Поэтому AiGate НЕ хардкодит приватные chat-эндпоинты — Base URL и
 * параметры refresh (token URL, client_id) задаёт пользователь. Хранение/refresh
 * стандартной OAuth-сессии, которой пользователь владеет, — это инфраструктура, а
 * ответственность за использование конкретного эндпоинта лежит на пользователе.
 */

/** Разобранная из CLI-файла сессия. */
data class ImportedSession(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Long?,      // epoch ms
    val accountId: String?,
    val providerHint: String?  // угаданный тип провайдера (codex/gemini/claude), если понятно
)

/**
 * Толерантный парсер JSON-сессий популярных CLI:
 *  - Codex `~/.codex/auth.json`: { "tokens": { access_token, refresh_token, account_id, id_token }, "last_refresh" }
 *  - Gemini CLI `~/.gemini/oauth_creds.json`: { access_token, refresh_token, expiry_date(ms), token_type }
 *  - Claude / прочие: { access_token, refresh_token, expires_at | expires_in }
 * Плюс любые вариации с этими полями на верхнем уровне или в объекте `tokens`.
 */
object CliSessionImporter {
    private const val TAG = "CliSessionImporter"

    fun parse(raw: String): ImportedSession? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return try {
            val root = JSONObject(text)
            val tokens = root.optJSONObject("tokens") ?: root
            val access = firstNonEmpty(
                tokens.optString("access_token"),
                root.optString("access_token"),
                tokens.optString("accessToken"),
                root.optString("accessToken")
            ) ?: return null
            val refresh = firstNonEmpty(
                tokens.optString("refresh_token"),
                root.optString("refresh_token"),
                tokens.optString("refreshToken"),
                root.optString("refreshToken")
            )
            val expiresAt = parseExpiry(root, tokens)
            val account = firstNonEmpty(
                tokens.optString("account_id"),
                root.optString("account_id"),
                tokens.optString("accountId"),
                root.optString("accountId"),
                extractEmailFromIdToken(tokens.optString("id_token").ifEmpty { root.optString("id_token") })
            )
            ImportedSession(
                accessToken = access,
                refreshToken = refresh,
                expiresAt = expiresAt,
                accountId = account,
                providerHint = guessProvider(root, tokens)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось разобрать сессию: ${e.message}")
            null
        }
    }

    private fun parseExpiry(root: JSONObject, tokens: JSONObject): Long? {
        // expiry_date (Gemini, epoch ms)
        for (o in listOf(tokens, root)) {
            if (o.has("expiry_date") && !o.isNull("expiry_date")) return o.optLong("expiry_date")
            if (o.has("expires_at") && !o.isNull("expires_at")) {
                val v = o.optLong("expires_at")
                // эвристика: секунды → мс
                return if (v < 10_000_000_000L) v * 1000 else v
            }
            if (o.has("expires_in") && !o.isNull("expires_in")) {
                val secs = o.optLong("expires_in")
                if (secs > 0) return System.currentTimeMillis() + secs * 1000
            }
        }
        return null
    }

    private fun guessProvider(root: JSONObject, tokens: JSONObject): String? {
        val blob = root.toString().lowercase()
        return when {
            blob.contains("openai") || tokens.has("account_id") && blob.contains("chatgpt") -> "codex"
            blob.contains("googleapis") || blob.contains("expiry_date") -> "gemini"
            blob.contains("anthropic") || blob.contains("claude") -> "claude"
            else -> null
        }
    }

    /** Достать email из JWT id_token (payload.email) без проверки подписи — только для отображения. */
    private fun extractEmailFromIdToken(idToken: String?): String? {
        if (idToken.isNullOrEmpty() || !idToken.contains('.')) return null
        return try {
            val payload = idToken.split('.')[1]
            val json = String(android.util.Base64.decode(payload, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING))
            JSONObject(json).optString("email").ifEmpty { null }
        } catch (_: Exception) { null }
    }

    private fun firstNonEmpty(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrEmpty() }
}

/** Известный CLI-провайдер: подсказки для UI + опциональный стандартный OAuth-flow. */
data class CliProviderTemplate(
    val id: String,              // тип провайдера (Provider.type)
    val displayName: String,
    val defaultBaseUrl: String,  // пользователь может переопределить; пусто = задать вручную
    val tokenUrl: String?,       // OAuth token endpoint (refresh + обмен кода)
    val experimental: Boolean,
    val note: String,
    val authUrl: String? = null, // OAuth authorization endpoint (для браузерного входа)
    val scopes: List<String> = emptyList()
)

/**
 * Каталог CLI-провайдеров. Стандартные OAuth token-эндпоинты (например Google) —
 * известны и документированы; client_id для refresh вводится пользователем (у каждой
 * инсталляции CLI он свой/публичный из открытого кода CLI). Приватные chat-эндпоинты
 * НЕ хардкодятся.
 */
object CliProviderCatalog {
    val GEMINI = CliProviderTemplate(
        id = "gemini-cli",
        displayName = "Gemini CLI",
        defaultBaseUrl = "",
        tokenUrl = "https://oauth2.googleapis.com/token",
        experimental = true,
        note = "Google OAuth. Вход через браузер. Укажите client_id (и client_secret для Desktop-клиента) из вашего Gemini CLI и Base URL совместимого эндпоинта.",
        authUrl = "https://accounts.google.com/o/oauth2/v2/auth",
        scopes = listOf("https://www.googleapis.com/auth/cloud-platform", "openid", "email")
    )
    val CODEX = CliProviderTemplate(
        id = "codex",
        displayName = "Codex CLI (ChatGPT)",
        defaultBaseUrl = "",
        tokenUrl = null,
        experimental = true,
        note = "Экспериментально. Использование сессии ChatGPT сторонним клиентом может нарушать ToS OpenAI — на ваш риск."
    )
    val CLAUDE = CliProviderTemplate(
        id = "claude-cli",
        displayName = "Claude Code",
        defaultBaseUrl = "",
        tokenUrl = null,
        experimental = true,
        note = "Экспериментально. Проверьте Terms Anthropic перед использованием сессии сторонним клиентом."
    )
    val GENERIC = CliProviderTemplate(
        id = "custom",
        displayName = "Свой (OAuth-сессия)",
        defaultBaseUrl = "",
        tokenUrl = null,
        experimental = false,
        note = "Любой OpenAI-совместимый эндпоинт с Bearer-сессией."
    )

    fun all(): List<CliProviderTemplate> = listOf(GEMINI, CODEX, CLAUDE, GENERIC)
    fun byId(id: String): CliProviderTemplate? = all().firstOrNull { it.id == id }
}

/**
 * Codex как ключевая платформа: полностью преднастроенный OAuth (публичный client_id
 * из открытого codex CLI). Вход в одну кнопку. ВАЖЕН фиксированный порт 1455 —
 * redirect_uri `http://localhost:1455/auth/callback` зарегистрирован у провайдера, и
 * случайный порт не подойдёт.
 *
 * Эксперим./ToS: доступ к ChatGPT/Codex сторонним клиентом может нарушать Terms OpenAI —
 * на риск пользователя. Аутентификацию выполняет сам пользователь своей учётной записью.
 */
object CodexAuth {
    const val DISPLAY_NAME = "Codex"
    const val PROVIDER_TYPE = "codex"
    const val PORT = 1455
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val DEFAULT_BASE_URL = "https://chatgpt.com/backend-api/codex"

    val config = OAuthFlowConfig(
        providerType = PROVIDER_TYPE,
        authUrl = "https://auth.openai.com/oauth/authorize",
        tokenUrl = "https://auth.openai.com/oauth/token",
        clientId = CLIENT_ID,
        scopes = listOf("openid", "profile", "email", "offline_access"),
        fixedPort = PORT,
        redirectPath = "/auth/callback",
        extraAuthParams = mapOf(
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true"
        )
    )
}

/**
 * Оркестратор подключения CLI-сессии: создаёт/обновляет Provider + OAuth-credential
 * (в Keystore), при наличии refresh-конфига регистрирует адаптер автообновления.
 */
object CliSessionManager {

    /** Один тап: браузерный вход в Codex и сохранение сессии. Возвращает providerId или ошибку. */
    suspend fun connectCodex(context: android.content.Context, db: AppDatabase): Result<Long> {
        val res = OAuthBrowserFlow.authorize(context, CodexAuth.config)
        return res.mapCatching { session ->
            connect(
                db = db,
                providerType = CodexAuth.PROVIDER_TYPE,
                name = CodexAuth.DISPLAY_NAME,
                baseUrl = CodexAuth.DEFAULT_BASE_URL,
                session = session,
                refreshTokenUrl = CodexAuth.config.tokenUrl,
                clientId = CodexAuth.CLIENT_ID,
                clientSecret = null
            )
        }
    }

    /** Подключить сессию: вернуть providerId. Сессия сохраняется в Keystore и переживает рестарт. */
    suspend fun connect(
        db: AppDatabase,
        providerType: String,
        name: String,
        baseUrl: String,
        session: ImportedSession,
        refreshTokenUrl: String? = null,
        clientId: String? = null,
        clientSecret: String? = null
    ): Long {
        // 1) Provider (без секрета).
        val providerDao = db.providerDao()
        val existing = providerDao.getAllProvidersOnce().firstOrNull {
            it.name == name && it.type == providerType
        }
        val providerId: Long = if (existing != null) {
            providerDao.update(existing.copy(baseUrl = baseUrl.trimEnd('/')))
            existing.id
        } else {
            providerDao.insert(
                Provider(
                    name = name,
                    type = providerType,
                    baseUrl = baseUrl.trimEnd('/'),
                    port = "",
                    credentialId = 0,
                    isEnabled = true
                )
            )
        }

        // 2) OAuth-credential в Keystore.
        val bundle = TokenBundle(
            accessToken = session.accessToken,
            refreshToken = session.refreshToken,
            expiresAt = session.expiresAt
        )
        val credId = CredentialStore.setOAuth(db, providerId, bundle, session.accountId)
        val prov = providerDao.getProviderById(providerId)
        if (prov != null && prov.credentialId != credId) {
            providerDao.update(prov.copy(credentialId = credId))
        }

        // 3) Refresh-адаптер (single-flight), если задан стандартный OAuth token endpoint.
        if (!refreshTokenUrl.isNullOrBlank() && !clientId.isNullOrBlank()) {
            registerAdapter(providerType, refreshTokenUrl, clientId, clientSecret)
            // Персистим конфиг (зашифрованно), чтобы автообновление пережило рестарт.
            persistRefreshConfig(providerType, refreshTokenUrl, clientId, clientSecret)
        }
        return providerId
    }

    private fun registerAdapter(providerType: String, tokenUrl: String, clientId: String, clientSecret: String?) {
        AuthRegistry.register(
            GenericOAuth2Provider(
                OAuth2Config(
                    providerType = providerType,
                    tokenUrl = tokenUrl,
                    clientId = clientId,
                    clientSecret = clientSecret
                )
            )
        )
    }

    // Хранилище refresh-конфигов: единый JSON-объект, зашифрованный CryptoBox в конфиге.
    private const val REFRESH_CONFIG_KEY = "cli_refresh_configs"

    private fun readRefreshConfigs(): JSONObject {
        val enc = com.aigate.router.service.GatewayForegroundService.getGatewayConfig(REFRESH_CONFIG_KEY, "")
        if (enc.isEmpty()) return JSONObject()
        val plain = com.aigate.router.security.CryptoBox.decrypt(enc)
        return if (plain.isEmpty()) JSONObject() else try { JSONObject(plain) } catch (_: Exception) { JSONObject() }
    }

    private fun persistRefreshConfig(providerType: String, tokenUrl: String, clientId: String, clientSecret: String?) {
        val all = readRefreshConfigs()
        all.put(providerType, JSONObject().apply {
            put("tokenUrl", tokenUrl); put("clientId", clientId)
            if (!clientSecret.isNullOrBlank()) put("clientSecret", clientSecret)
        })
        val enc = com.aigate.router.security.CryptoBox.encrypt(all.toString())
        com.aigate.router.service.GatewayForegroundService.saveGatewayConfig(REFRESH_CONFIG_KEY, enc)
    }

    /** Восстановить refresh-адаптеры при старте приложения (сессии переживают рестарт). */
    fun restoreAdapters() {
        val all = readRefreshConfigs()
        for (type in all.keys()) {
            val o = all.optJSONObject(type) ?: continue
            val tokenUrl = o.optString("tokenUrl"); val clientId = o.optString("clientId")
            if (tokenUrl.isNotEmpty() && clientId.isNotEmpty()) {
                registerAdapter(type, tokenUrl, clientId, o.optString("clientSecret").ifEmpty { null })
            }
        }
    }

    /** Форс-обновление сессии (кнопка «Обновить»). true, если после есть валидный токен. */
    suspend fun refreshNow(db: AppDatabase, providerId: Long): Boolean {
        val provider = db.providerDao().getProviderById(providerId) ?: return false
        // сбросить срок, чтобы ensureFresh точно попробовал обновить
        return AuthRegistry.ensureFreshForProvider(db, provider)
    }

    /** Отключить: удалить провайдера и его credential. */
    suspend fun disconnect(db: AppDatabase, providerId: Long) {
        CredentialStore.deleteForProvider(db, providerId)
        db.providerDao().deleteById(providerId)
    }

    /** Статус сессии для отображения в UI. */
    data class SessionStatus(
        val provider: Provider,
        val accountId: String?,
        val expiresAt: Long?,
        val hasRefresh: Boolean,
        val connected: Boolean
    )

    /** Все провайдеры с OAuth-сессией + их статус. */
    suspend fun listSessions(db: AppDatabase): List<SessionStatus> {
        val providers = db.providerDao().getAllProvidersOnce()
        val result = ArrayList<SessionStatus>()
        for (p in providers) {
            if (p.credentialId == 0L) continue
            val c = db.credentialDao().getById(p.credentialId) ?: continue
            if (c.type != com.aigate.router.data.model.Credential.TYPE_OAUTH) continue
            result.add(
                SessionStatus(
                    provider = p,
                    accountId = c.accountId,
                    expiresAt = c.oauthExpiresAt,
                    hasRefresh = !c.encOAuthRefresh.isNullOrEmpty(),
                    connected = !c.encOAuthAccess.isNullOrEmpty()
                )
            )
        }
        return result
    }
}
