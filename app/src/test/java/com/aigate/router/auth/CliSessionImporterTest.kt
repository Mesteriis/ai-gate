package com.aigate.router.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CliSessionImporterTest {

    @Test
    fun `parses codex-style nested tokens`() {
        val json = """
            {"tokens":{"access_token":"acc123","refresh_token":"ref123","account_id":"acct-9","id_token":"x"},"last_refresh":"2026-08-18T00:00:00Z"}
        """.trimIndent()
        val s = CliSessionImporter.parse(json)!!
        assertEquals("acc123", s.accessToken)
        assertEquals("ref123", s.refreshToken)
        assertEquals("acct-9", s.accountId)
    }

    @Test
    fun `parses gemini-style expiry_date millis`() {
        val future = System.currentTimeMillis() + 3_600_000
        val json = """{"access_token":"g_acc","refresh_token":"g_ref","expiry_date":$future,"token_type":"Bearer"}"""
        val s = CliSessionImporter.parse(json)!!
        assertEquals("g_acc", s.accessToken)
        assertEquals(future, s.expiresAt)
    }

    @Test
    fun `parses generic expires_in seconds into future millis`() {
        val before = System.currentTimeMillis()
        val json = """{"access_token":"a","refresh_token":"r","expires_in":3600}"""
        val s = CliSessionImporter.parse(json)!!
        assertNotNull(s.expiresAt)
        assertTrue(s.expiresAt!! >= before + 3600_000 - 5_000)
    }

    @Test
    fun `expires_at in seconds is normalized to millis`() {
        val secs = 2_000_000_000L // ~2033, in seconds
        val json = """{"access_token":"a","expires_at":$secs}"""
        val s = CliSessionImporter.parse(json)!!
        assertEquals(secs * 1000, s.expiresAt)
    }

    @Test
    fun `no access token returns null`() {
        assertNull(CliSessionImporter.parse("""{"refresh_token":"r"}"""))
        assertNull(CliSessionImporter.parse("not json"))
        assertNull(CliSessionImporter.parse(""))
    }
}
