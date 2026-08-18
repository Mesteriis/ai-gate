package com.aigate.router.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [CryptoBox] — the AES-256-GCM AEAD is backed by the
 * AndroidKeyStore, so these must run on a device/emulator:
 *
 *   ./gradlew connectedAndroidTest
 *
 * They cannot run under the JVM `test` task (no Keystore there).
 */
@RunWith(AndroidJUnit4::class)
class CryptoBoxTest {

    @Test
    fun emptyInputRoundTripsToEmptyString() {
        assertEquals("", CryptoBox.encrypt(""))
        assertEquals("", CryptoBox.decrypt(""))
    }

    @Test
    fun encryptThenDecryptRecoversSecret() {
        val secret = "sk-secret-123"
        val cipher = CryptoBox.encrypt(secret)

        // Ciphertext must not leak the plaintext and must be reversible.
        assertEquals(secret, CryptoBox.decrypt(cipher))
    }

    @Test
    fun decryptOfGarbageReturnsEmptyAndNeverThrows() {
        assertEquals("", CryptoBox.decrypt("not-valid-base64!!"))
    }
}
