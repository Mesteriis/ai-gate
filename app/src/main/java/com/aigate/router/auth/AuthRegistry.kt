package com.aigate.router.auth

import android.util.Log
import com.aigate.router.data.credential.CredentialStore
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.data.model.Credential
import com.aigate.router.data.model.Provider
import com.aigate.router.security.CryptoBox
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Реестр адаптеров аутентификации + оркестратор обновления OAuth-токенов с
 * single-flight: параллельные запросы к одному удостоверению не рефрешат его
 * одновременно (гонки исключены мьютексом на credentialId).
 *
 * Пустой реестр — валидное состояние: для провайдеров без публичного OAuth-flow
 * адаптер не регистрируется, токен используется до истечения, а после — честный
 * отказ (upstream вернёт 401), без подделки «обновления».
 */
object AuthRegistry {
    private const val TAG = "AuthRegistry"

    private val providers = ConcurrentHashMap<String, AuthProvider>()
    private val refreshLocks = ConcurrentHashMap<Long, Mutex>()

    /** Зарегистрировать адаптер (например, из GatewayApplication на старте). */
    fun register(provider: AuthProvider) {
        providers[provider.providerType.lowercase()] = provider
    }

    fun providerFor(providerType: String): AuthProvider? =
        providers[providerType.lowercase()]

    fun isEmpty(): Boolean = providers.isEmpty()

    private fun mutexFor(credentialId: Long): Mutex =
        refreshLocks.getOrPut(credentialId) { Mutex() }

    /**
     * Гарантировать свежий access-токен для провайдера перед upstream-вызовом.
     * Для api-key и не-OAuth — no-op. Возвращает true, если после вызова в кэше
     * CredentialStore лежит пригодный к использованию токен.
     */
    suspend fun ensureFreshForProvider(db: AppDatabase, provider: Provider): Boolean {
        if (provider.credentialId == 0L) return true
        val credential = db.credentialDao().getById(provider.credentialId) ?: return true
        if (credential.type != Credential.TYPE_OAUTH) return true

        // Токен ещё валиден?
        if (!isExpiring(credential)) return true

        val adapter = providerFor(provider.type)
        if (adapter == null || !adapter.canRefresh(credential)) {
            // Нельзя обновить — честно оставляем как есть (может быть просрочен).
            return false
        }

        return mutexFor(credential.id).withLock {
            // Повторная проверка внутри критической секции (single-flight).
            val fresh = db.credentialDao().getById(credential.id) ?: return@withLock false
            if (!isExpiring(fresh)) return@withLock true
            val refreshEnc = fresh.encOAuthRefresh
            if (refreshEnc.isNullOrEmpty()) return@withLock false
            val refreshToken = CryptoBox.decrypt(refreshEnc)
            if (refreshToken.isEmpty()) return@withLock false
            try {
                val bundle = adapter.refresh(refreshToken)
                CredentialStore.updateOAuthTokens(db, fresh.id, bundle)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось обновить OAuth-токен (credential ${fresh.id}): ${e.message}")
                false
            }
        }
    }

    /** Скоро ли истекает access-токен (или уже истёк). null-срок считаем «не истекает». */
    private fun isExpiring(credential: Credential): Boolean {
        val expiresAt = credential.oauthExpiresAt ?: return false
        return System.currentTimeMillis() >= expiresAt - REFRESH_SKEW_MS
    }

    private const val REFRESH_SKEW_MS = 60_000L
}
