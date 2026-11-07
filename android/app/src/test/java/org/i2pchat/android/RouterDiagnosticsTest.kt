package org.i2pchat.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouterDiagnosticsTest {
    private val reseedLog = """
        12:00:01@123/warn - Reseed: SU3 download failed
        12:00:09@123/warn - Reseed: Failed to reseed from servers. Waiting for 45 seconds
    """.trimIndent()
    private val quietLog = "12:00:01@1/info - NetDb: 120 routers loaded (10 floodfils)\n"

    private fun code(log: String) = RouterDiagnostics.diagnoseLogText(log)?.code

    @Test
    fun logPatterns() {
        assertEquals(RouterIssueCode.RESEED_FAILED, code(reseedLog))
        assertEquals(RouterIssueCode.RESEED_FAILED, code("Reseed: SU3 download failed\n".repeat(3)))
        assertEquals(
            RouterIssueCode.CLOCK_SKEW,
            code("NTCP2: SessionCreated time difference 300 exceeds clock skew"),
        )
        assertEquals(RouterIssueCode.CLOCK_SKEW, code("SSU2: Clock skew detected -200. Check your clock"))
        assertEquals(
            RouterIssueCode.PORT_IN_USE,
            code("Clients: Exception in SAM bridge: bind: Address already in use"),
        )
        assertEquals(RouterIssueCode.NO_NETWORK, code("NTCP2: Connect error Network is unreachable"))
        assertEquals(RouterIssueCode.NO_NETWORK, code("Daemon: Failed to start Transports"))
        assertNull(code(quietLog))
        assertNull(code(""))
        val stalePort = buildString {
            append("Clients: Exception in SAM bridge: bind: Address already in use\n")
            repeat(2000) { append("12:01:00@1/info - NetDb: routers loaded\n") }
        }
        assertNull(code(stalePort))
    }

    @Test
    fun bundledRouter() {
        assertNull(RouterDiagnostics.diagnoseBundledRouter(quietLog, true, false, 10))
        assertEquals(
            RouterIssueCode.TUNNELS_SLOW,
            RouterDiagnostics.diagnoseBundledRouter(quietLog, true, false, 500)?.code,
        )
        assertEquals(
            RouterIssueCode.ROUTER_CRASHED,
            RouterDiagnostics.diagnoseBundledRouter(quietLog, false, false, 0)?.code,
        )
        assertNull(RouterDiagnostics.diagnoseBundledRouter(reseedLog, true, true, 0))
    }

    @Test
    fun systemRouter() {
        assertNull(RouterDiagnostics.diagnoseSystemRouter("127.0.0.1", 7656, true))
        assertEquals(
            "127.0.0.1:7656",
            RouterDiagnostics.diagnoseSystemRouter("127.0.0.1", 7656, false)?.arg,
        )
    }
}
