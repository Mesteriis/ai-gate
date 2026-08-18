package com.aigate.router.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

/**
 * Keystore-backed AEAD for secrets at rest.
 *
 * AES-256-GCM with a non-exportable key held in the AndroidKeyStore (alias
 * [KEY_ALIAS]). [encrypt] returns base64("<12-byte IV><ciphertext+tag>");
 * [decrypt] reverses it. The key never leaves the Keystore, so even a full
 * data backup of the ciphertext is useless off-device.
 *
 * Used to wrap provider credentials, the LAN token, inbound gateway keys and
 * proxy credentials before they touch Room / SharedPreferences.
 */
object CryptoBox {
    private const val KEY_ALIAS = "aigate_master"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    /** Encrypt UTF-8 [plain] → base64("IV||ciphertext+tag"). Returns "" for empty input. */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    /** Reverse of [encrypt]. Returns "" on empty/invalid input (never throws to callers). */
    fun decrypt(encoded: String): String {
        if (encoded.isEmpty()) return ""
        return try {
            val all = Base64.decode(encoded, Base64.NO_WRAP)
            if (all.size <= IV_SIZE) return ""
            val iv = all.copyOfRange(0, IV_SIZE)
            val ct = all.copyOfRange(IV_SIZE, all.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}
