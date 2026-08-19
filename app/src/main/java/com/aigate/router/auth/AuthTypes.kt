package com.aigate.router.auth

/** Свежие токены после обновления. */
data class TokenBundle(
    val accessToken: String,
    val refreshToken: String? = null,
    /** epoch ms истечения access-токена; null = неизвестно. */
    val expiresAt: Long? = null
)

/** Метаданные аккаунта (опционально, для отображения). */
data class AccountInfo(
    val accountId: String?,
    val displayName: String? = null,
    val plan: String? = null
)

/** Стандартная конфигурация OAuth2-провайдера (authorization code + refresh). */
data class OAuth2Config(
    val providerType: String,
    val tokenUrl: String,
    val clientId: String,
    val clientSecret: String? = null,
    val scopes: List<String> = emptyList(),
    /**
     * Отправлять тело запроса токена как JSON, а не как form-urlencoded.
     * RFC 6749 требует form, но token endpoint Anthropic принимает JSON —
     * без этого обновление сессии Claude падает.
     */
    val jsonBody: Boolean = false,
    /** За сколько до истечения считать токен «пора обновить» (ms). */
    val refreshSkewMs: Long = 60_000L
)
