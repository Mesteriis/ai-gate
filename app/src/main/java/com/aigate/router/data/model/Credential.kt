package com.aigate.router.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Encrypted credential for a provider. Deliberately NOT @Serializable and NOT part
 * of BackupData — secrets live only here, encrypted at rest via [com.aigate.router.security.CryptoBox]
 * (AndroidKeyStore). A [Provider] references a credential by id, so a Provider row can be
 * serialized / logged / backed up without ever carrying the secret.
 *
 * OAuth columns are present from the start (Phase 9) but only `type = "api_key"` is
 * wired for now.
 */
@Entity(
    tableName = "credentials",
    indices = [Index("provider_id")]
)
data class Credential(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "provider_id")
    val providerId: Long,
    @ColumnInfo(name = "type")
    val type: String = TYPE_API_KEY,           // "api_key" | "oauth" | "none"
    @ColumnInfo(name = "enc_secret")
    val encSecret: String = "",                // CryptoBox(api key)
    @ColumnInfo(name = "enc_oauth_access")
    val encOAuthAccess: String? = null,        // CryptoBox(OAuth access token)
    @ColumnInfo(name = "enc_oauth_refresh")
    val encOAuthRefresh: String? = null,       // CryptoBox(OAuth refresh token)
    @ColumnInfo(name = "oauth_expires_at")
    val oauthExpiresAt: Long? = null,
    @ColumnInfo(name = "account_id")
    val accountId: String? = null,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_API_KEY = "api_key"
        const val TYPE_OAUTH = "oauth"
        const val TYPE_NONE = "none"
    }
}
