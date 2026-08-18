package com.aigate.router.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the auth-bypass contract: only a real loopback client skips key auth.
 * Private-network (RFC1918) callers must NOT be treated as loopback — they may
 * reach the gateway only in LAN mode and only with a token.
 */
class KeyManagerTest {

    @Test
    fun realLoopbackAddressesBypassAuth() {
        assertTrue(KeyManager.isLoopback("127.0.0.1"))
        assertTrue(KeyManager.isLoopback("127.0.0.5"))
        assertTrue(KeyManager.isLoopback("::1"))
        assertTrue(KeyManager.isLoopback("localhost"))
    }

    @Test
    fun privateAndPublicAddressesNeverBypassAuth() {
        assertFalse(KeyManager.isLoopback("192.168.1.10"))
        assertFalse(KeyManager.isLoopback("10.0.0.2"))
        assertFalse(KeyManager.isLoopback("172.16.0.1"))
        assertFalse(KeyManager.isLoopback("8.8.8.8"))
        assertFalse(KeyManager.isLoopback("0.0.0.0"))
        assertFalse(KeyManager.isLoopback(""))
    }
}
