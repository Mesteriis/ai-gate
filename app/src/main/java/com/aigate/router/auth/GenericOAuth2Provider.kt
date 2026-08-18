package com.aigate.router.auth

import com.aigate.router.data.model.Credential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Стандартный OAuth2-адаптер (RFC 6749, grant_type=refresh_token). Работает с любым
 * провайдером, у которого есть совместимый token endpoint. Для провайдеров с
 * недокументированным flow используйте отдельный экспериментальный подкласс/адаптер —
 * этот класс намеренно не содержит провайдер-специфичных хаков.
 */
open class GenericOAuth2Provider(
    private val config: OAuth2Config
) : AuthProvider {

    override val providerType: String get() = config.providerType

    override fun canRefresh(credential: Credential): Boolean =
        credential.type == Credential.TYPE_OAUTH && !credential.encOAuthRefresh.isNullOrEmpty()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun refresh(refreshToken: String): TokenBundle = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", config.clientId)
            .apply {
                config.clientSecret?.let { add("client_secret", it) }
                if (config.scopes.isNotEmpty()) add("scope", config.scopes.joinToString(" "))
            }
            .build()

        val request = Request.Builder()
            .url(config.tokenUrl)
            .header("Accept", "application/json")
            .post(form)
            .build()

        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("OAuth refresh failed: HTTP ${resp.code} ${body.take(200)}")
            }
            val json = JSONObject(body)
            val access = json.optString("access_token", "")
            if (access.isEmpty()) throw IOException("OAuth refresh: no access_token in response")
            val newRefresh = json.optString("refresh_token", "").ifEmpty { null }
            val expiresIn = json.optLong("expires_in", -1L)
            val expiresAt = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else null
            TokenBundle(
                accessToken = access,
                refreshToken = newRefresh ?: refreshToken,
                expiresAt = expiresAt
            )
        }
    }
}
