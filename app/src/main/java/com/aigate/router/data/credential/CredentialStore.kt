package com.aigate.router.data.credential

import com.aigate.router.auth.TokenBundle
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.data.model.Credential
import com.aigate.router.data.model.Provider
import com.aigate.router.security.CryptoBox
import java.util.concurrent.ConcurrentHashMap

/**
 * The only place upstream secrets are resolved. Secrets are stored encrypted in the
 * `credentials` table (Keystore-wrapped via [CryptoBox]); this store keeps a small
 * in-memory cache of DECRYPTED api keys keyed by credentialId so the hot request path
 * stays synchronous. Provider rows carry only a `credentialId`, never the secret.
 *
 * OAuth (`type = "oauth"`) columns exist for Phase 9; only api-key credentials are
 * resolved here today.
 */
object CredentialStore {
    // credentialId -> decrypted bearer secret (api key OR current OAuth access token)
    private val apiKeyCache = ConcurrentHashMap<Long, String>()
    @Volatile private var loaded = false

    /** Load & decrypt all credentials into the cache. Call on app/gateway start. */
    suspend fun load(db: AppDatabase) {
        val all = db.credentialDao().getAll()
        apiKeyCache.clear()
        for (c in all) {
            when (c.type) {
                Credential.TYPE_API_KEY -> if (c.encSecret.isNotEmpty()) {
                    val plain = CryptoBox.decrypt(c.encSecret)
                    if (plain.isNotEmpty()) apiKeyCache[c.id] = plain
                }
                Credential.TYPE_OAUTH -> {
                    val enc = c.encOAuthAccess
                    if (!enc.isNullOrEmpty()) {
                        val plain = CryptoBox.decrypt(enc)
                        if (plain.isNotEmpty()) apiKeyCache[c.id] = plain
                    }
                }
            }
        }
        loaded = true
    }

    /**
     * Загрузить кэш, если он ещё не загружен. Обновление квот обязано звать это
     * первым делом: `load` запускается из GatewayApplication асинхронно, и
     * фоновое обновление на холодном старте обгоняло его — адаптеры получали
     * пустые ключи и молча возвращали «нет данных».
     */
    suspend fun ensureLoaded(db: AppDatabase) {
        if (!loaded) load(db)
    }

    /** Synchronous resolution for the request path (cache must be loaded). null if none. */
    fun apiKeyFor(credentialId: Long): String? {
        if (credentialId == 0L) return null
        return apiKeyCache[credentialId]?.ifEmpty { null }
    }

    /** Convenience: resolve the api key for a provider by its credentialId. */
    fun apiKeyForProvider(provider: Provider): String? = apiKeyFor(provider.credentialId)

    /**
     * Create/replace the api-key credential owning [providerId], returning the credentialId
     * to store on the Provider. A blank secret removes the credential (returns 0).
     */
    suspend fun setApiKey(db: AppDatabase, providerId: Long, plainSecret: String?): Long {
        ensureLoaded(db)
        val dao = db.credentialDao()
        val existing = dao.getByProvider(providerId)
        val secret = plainSecret?.trim().orEmpty()
        if (secret.isEmpty()) {
            if (existing != null) { dao.deleteById(existing.id); apiKeyCache.remove(existing.id) }
            return 0
        }
        val enc = CryptoBox.encrypt(secret)
        val id: Long = if (existing != null) {
            dao.update(existing.copy(type = Credential.TYPE_API_KEY, encSecret = enc, updatedAt = System.currentTimeMillis()))
            existing.id
        } else {
            dao.insert(Credential(providerId = providerId, type = Credential.TYPE_API_KEY, encSecret = enc))
        }
        apiKeyCache[id] = secret
        return id
    }

    /** Drop the credential owned by [providerId] (on provider delete). */
    suspend fun deleteForProvider(db: AppDatabase, providerId: Long) {
        val existing = db.credentialDao().getByProvider(providerId)
        if (existing != null) { db.credentialDao().deleteById(existing.id); apiKeyCache.remove(existing.id) }
    }

    // ---- OAuth (Phase 9) ----------------------------------------------------

    /**
     * Store/replace an OAuth credential for [providerId] (initial authorization result).
     * Tokens are Keystore-encrypted at rest; the access token is cached decrypted for the
     * synchronous request path. Returns the credentialId to store on the Provider.
     */
    suspend fun setOAuth(
        db: AppDatabase,
        providerId: Long,
        bundle: TokenBundle,
        accountId: String? = null
    ): Long {
        ensureLoaded(db)
        val dao = db.credentialDao()
        val existing = dao.getByProvider(providerId)
        val encAccess = CryptoBox.encrypt(bundle.accessToken)
        val encRefresh = bundle.refreshToken?.let { CryptoBox.encrypt(it) }
        val id: Long = if (existing != null) {
            dao.update(
                existing.copy(
                    type = Credential.TYPE_OAUTH,
                    encSecret = "",
                    encOAuthAccess = encAccess,
                    encOAuthRefresh = encRefresh ?: existing.encOAuthRefresh,
                    oauthExpiresAt = bundle.expiresAt,
                    accountId = accountId ?: existing.accountId,
                    updatedAt = System.currentTimeMillis()
                )
            )
            existing.id
        } else {
            dao.insert(
                Credential(
                    providerId = providerId,
                    type = Credential.TYPE_OAUTH,
                    encOAuthAccess = encAccess,
                    encOAuthRefresh = encRefresh,
                    oauthExpiresAt = bundle.expiresAt,
                    accountId = accountId
                )
            )
        }
        apiKeyCache[id] = bundle.accessToken
        return id
    }

    /** Persist refreshed OAuth tokens (called by AuthRegistry after single-flight refresh). */
    suspend fun updateOAuthTokens(db: AppDatabase, credentialId: Long, bundle: TokenBundle) {
        val dao = db.credentialDao()
        val existing = dao.getById(credentialId) ?: return
        val encAccess = CryptoBox.encrypt(bundle.accessToken)
        val encRefresh = bundle.refreshToken?.let { CryptoBox.encrypt(it) } ?: existing.encOAuthRefresh
        dao.update(
            existing.copy(
                encOAuthAccess = encAccess,
                encOAuthRefresh = encRefresh,
                oauthExpiresAt = bundle.expiresAt,
                updatedAt = System.currentTimeMillis()
            )
        )
        apiKeyCache[credentialId] = bundle.accessToken
    }
}
