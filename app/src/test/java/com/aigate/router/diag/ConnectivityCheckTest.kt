package com.aigate.router.diag

import org.junit.Assert.assertEquals
import org.junit.Test

/** Сведение шагов проверки в один итог и правило срока жизни сессии. */
class ConnectivityCheckTest {

    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    private fun step(state: ConnectivityCheck.State) =
        ConnectivityCheck.Step("шаг", state, "деталь")

    @Test
    fun `failure outweighs warning and success`() {
        assertEquals(
            ConnectivityCheck.State.FAIL,
            ConnectivityCheck.worst(
                listOf(
                    step(ConnectivityCheck.State.OK),
                    step(ConnectivityCheck.State.WARN),
                    step(ConnectivityCheck.State.FAIL),
                )
            )
        )
    }

    @Test
    fun `warning outweighs success`() {
        assertEquals(
            ConnectivityCheck.State.WARN,
            ConnectivityCheck.worst(listOf(step(ConnectivityCheck.State.OK), step(ConnectivityCheck.State.WARN)))
        )
    }

    @Test
    fun `empty run is not a failure`() {
        assertEquals(ConnectivityCheck.State.OK, ConnectivityCheck.worst(emptyList()))
        assertEquals("Связь в порядке", ConnectivityCheck.summary(emptyList()))
    }

    @Test
    fun `summary names the worst outcome`() {
        assertEquals("Есть неполадки", ConnectivityCheck.summary(listOf(step(ConnectivityCheck.State.FAIL))))
        assertEquals("Работает с замечаниями", ConnectivityCheck.summary(listOf(step(ConnectivityCheck.State.WARN))))
    }

    @Test
    fun `expired session fails the check`() {
        val s = ConnectivityCheck.sessionStep("Codex", expiresAt = now - hour, now = now)
        assertEquals(ConnectivityCheck.State.FAIL, s.state)
    }

    @Test
    fun `session expiring within a day only warns`() {
        val s = ConnectivityCheck.sessionStep("Codex", expiresAt = now + 5 * hour, now = now)
        assertEquals(ConnectivityCheck.State.WARN, s.state)
    }

    @Test
    fun `long lived session passes`() {
        val s = ConnectivityCheck.sessionStep("Codex", expiresAt = now + 72 * hour, now = now)
        assertEquals(ConnectivityCheck.State.OK, s.state)
    }

    @Test
    fun `session without expiry is not a problem`() {
        val s = ConnectivityCheck.sessionStep("Ollama", expiresAt = null, now = now)
        assertEquals(ConnectivityCheck.State.OK, s.state)
    }
}
