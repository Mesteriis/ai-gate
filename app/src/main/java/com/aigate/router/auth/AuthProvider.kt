package com.aigate.router.auth

import com.aigate.router.data.model.Credential

/**
 * Абстракция обновляемого удостоверения (Phase 9). API-ключи не нуждаются в
 * обновлении и обрабатываются напрямую в CredentialStore; AuthProvider нужен для
 * OAuth-удостоверений, которые надо периодически рефрешить.
 *
 * ВАЖНО (риск из плана): OAuth вокруг недокументированных провайдерских endpoint'ов —
 * экспериментальная территория. Реализации должны быть заменяемыми модулями и НЕ
 * встраиваться в ядро шлюза. Если публичного flow нет — провайдер просто не
 * регистрируется, и система честно сообщает, что токен нельзя обновить.
 */
interface AuthProvider {
    /** Тип провайдера (Provider.type), к которому применим этот адаптер. */
    val providerType: String

    /** Может ли этот адаптер обновить данное удостоверение. */
    fun canRefresh(credential: Credential): Boolean

    /**
     * Обновить токены. Бросает исключение при неудаче (истёкший refresh, отзыв,
     * сетевая ошибка) — вызывающая сторона переводит удостоверение в состояние
     * «нужна повторная авторизация».
     */
    suspend fun refresh(refreshToken: String): TokenBundle

    /** Опционально: сведения об аккаунте. null, если не поддерживается. */
    suspend fun accountInfo(accessToken: String): AccountInfo? = null
}
