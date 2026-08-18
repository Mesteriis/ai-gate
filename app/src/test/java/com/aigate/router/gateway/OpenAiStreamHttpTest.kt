package com.aigate.router.gateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the SSE-compat detection against a real upstream socket: MockWebServer
 * serves a genuine `text/event-stream` response, we fetch it over OkHttp inside a
 * coroutine, and assert the gateway would recognise it as a stream carrying both a
 * real data frame and the terminating [DONE].
 */
class OpenAiStreamHttpTest {

    @Test
    fun detectsEventStreamAndFramesFromRealUpstreamResponse() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream; charset=utf-8")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\ndata: [DONE]\n\n"),
        )
        server.start()
        try {
            val client = OkHttpClient()
            val request = Request.Builder().url(server.url("/v1/chat/completions")).build()

            val (contentType, body) = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { resp ->
                    resp.header("Content-Type") to resp.body?.string().orEmpty()
                }
            }

            assertTrue(OpenAiStreamCompat.isEventStream(contentType))
            assertTrue(OpenAiStreamCompat.hasDataFrame(body))
            assertTrue(OpenAiStreamCompat.hasDoneFrame(body))
        } finally {
            server.shutdown()
        }
    }
}
