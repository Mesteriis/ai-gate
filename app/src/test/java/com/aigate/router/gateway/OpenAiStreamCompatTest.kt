package com.aigate.router.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiStreamCompatTest {
    @Test
    fun detectsEventStreamContentTypeWithParameters() {
        assertTrue(OpenAiStreamCompat.isEventStream("text/event-stream; charset=utf-8"))
        assertFalse(OpenAiStreamCompat.isEventStream("application/json"))
        assertFalse(OpenAiStreamCompat.isEventStream(null))
    }

    @Test
    fun doneOnlyStreamDoesNotCountAsResponseData() {
        assertFalse(OpenAiStreamCompat.hasDataFrame("data: [DONE]\n\n"))
        assertTrue(OpenAiStreamCompat.hasDoneFrame("data: [DONE]\n\n"))
    }

    @Test
    fun convertsNonStreamingChatCompletionForHermesSseClients() {
        val body = """{"id":"chatcmpl-1","model":"demo","choices":[{"message":{"role":"assistant","content":"hello from gateway"},"finish_reason":"stop"}]}"""

        val sse = OpenAiStreamCompat.chatCompletionJsonToSse(body).toString(Charsets.UTF_8)

        assertTrue(sse.contains("\"delta\":{\"role\":\"assistant\",\"content\":\"hello from gateway\"}"))
        assertTrue(OpenAiStreamCompat.hasDataFrame(sse))
        assertTrue(OpenAiStreamCompat.hasDoneFrame(sse))
    }

    @Test
    fun convertsArrayMessageContentWithoutDroppingText() {
        val body = """{"choices":[{"message":{"content":[{"type":"text","text":"first"},{"type":"text","text":"second"}]}}]}"""

        val sse = OpenAiStreamCompat.chatCompletionJsonToSse(body).toString(Charsets.UTF_8)

        assertTrue(sse.contains("first\\nsecond"))
        assertTrue(OpenAiStreamCompat.hasDoneFrame(sse))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankNonStreamingCompletionSoFailoverCanRun() {
        OpenAiStreamCompat.chatCompletionJsonToSse(
            """{"choices":[{"message":{"role":"assistant","content":""}}]}""",
        )
    }

    @Test
    fun realDataFrameFollowedByDoneIsBothDataAndDone() {
        val stream = "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\ndata: [DONE]\n\n"

        assertTrue(OpenAiStreamCompat.hasDataFrame(stream))
        assertTrue(OpenAiStreamCompat.hasDoneFrame(stream))
    }

    @Test
    fun doneFrameHasExactSseTerminatorBytes() {
        val done = OpenAiStreamCompat.doneFrame().toString(Charsets.UTF_8)

        assertEquals("data: [DONE]\n\n", done)
        assertTrue(OpenAiStreamCompat.hasDoneFrame(done))
        assertFalse(OpenAiStreamCompat.hasDataFrame(done))
    }

    @Test
    fun emptyStreamErrorFrameIsAnErrorDataFrameNotDone() {
        val frame = OpenAiStreamCompat.emptyStreamErrorFrame().toString(Charsets.UTF_8)

        assertTrue(frame.startsWith("data: "))
        assertTrue(frame.endsWith("\n\n"))
        assertTrue(frame.contains("\"type\":\"error\""))
        // It is a genuine (non-[DONE]) data frame, so clients treat it as payload.
        assertTrue(OpenAiStreamCompat.hasDataFrame(frame))
        assertFalse(OpenAiStreamCompat.hasDoneFrame(frame))
    }
}
