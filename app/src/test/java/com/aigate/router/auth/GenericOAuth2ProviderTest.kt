package com.aigate.router.auth

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Проверяет стандартный OAuth2 refresh_token grant против MockWebServer. */
class GenericOAuth2ProviderTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun providerFor(): GenericOAuth2Provider =
        GenericOAuth2Provider(
            OAuth2Config(
                providerType = "test",
                tokenUrl = server.url("/token").toString(),
                clientId = "cid",
                clientSecret = "secret"
            )
        )

    @Test
    fun `refresh parses new tokens and expiry`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"newA","refresh_token":"newR","expires_in":3600}""")
        )
        val before = System.currentTimeMillis()
        val bundle = providerFor().refresh("oldR")
        assertEquals("newA", bundle.accessToken)
        assertEquals("newR", bundle.refreshToken)
        assertNotNull(bundle.expiresAt)
        assertTrue(bundle.expiresAt!! >= before + 3600_000 - 5_000)

        // грант отправлен корректно
        val recorded = server.takeRequest()
        val sentBody = recorded.body.readUtf8()
        assertTrue(sentBody.contains("grant_type=refresh_token"))
        assertTrue(sentBody.contains("refresh_token=oldR"))
        assertTrue(sentBody.contains("client_id=cid"))
    }

    @Test
    fun `refresh keeps old refresh token when server omits it`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"a2","expires_in":100}""")
        )
        val bundle = providerFor().refresh("keepMe")
        assertEquals("a2", bundle.accessToken)
        assertEquals("keepMe", bundle.refreshToken)
    }

    @Test(expected = Exception::class)
    fun `refresh throws on error response`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        providerFor().refresh("bad")
        Unit
    }
}
