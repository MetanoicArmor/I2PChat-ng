package org.i2pchat.android

import java.io.File
import java.io.RandomAccessFile

/**
 * Turns router state and the i2pd log into advice the user can act on.
 *
 * Patterns follow upstream i2pd log lines (Reseed.cpp, NTCP2.cpp, SSU2Session.cpp,
 * ClientContext.cpp). The desktop app has the same rules in
 * `i2pchat/router/diagnostics.py`; keep them in sync.
 */
enum class RouterIssueCode {
    SYSTEM_SAM_UNREACHABLE,
    RESEED_FAILED,
    CLOCK_SKEW,
    PORT_IN_USE,
    NO_NETWORK,
    TUNNELS_SLOW,
    ROUTER_CRASHED,
}

data class RouterIssue(
    val code: RouterIssueCode,
    /** `host:port` for [RouterIssueCode.SYSTEM_SAM_UNREACHABLE]. */
    val arg: String = "",
) {
    val offersRestart: Boolean
        get() = code == RouterIssueCode.PORT_IN_USE || code == RouterIssueCode.ROUTER_CRASHED
}

object RouterDiagnostics {
    private const val LOG_TAIL_BYTES = 256 * 1024
    // Bind failures only matter at the latest startup; older "Address already
    // in use" lines in a large router.log must not stick after a good restart.
    private const val PORT_IN_USE_RECENT_BYTES = 24 * 1024
    // Several reseed hosts failing in a row means none of them is reachable.
    private const val SU3_FAILURES_FOR_RESEED_ISSUE = 3
    private const val SLOW_AFTER_SECONDS = 120

    private val portInUse = Regex(
        "Exception in SAM bridge|Unable to start SAM bridge|NTCP2: Failed to bind" +
            "|SSU2: Failed to open socket|Address already in use",
    )
    private val clockSkew = Regex("exceeds clock skew|Clock skew detected")
    private val reseedGaveUp = Regex(
        "Reseed: Failed to reseed from servers|Reseed: Failed to reseed from \\d+ servers" +
            "|Reseed: No reseed servers specified",
    )
    private val su3Failed = Regex("Reseed: SU3 download failed")
    private val noNetwork = Regex(
        "Network is unreachable|No route to host|Failed to start Transports",
    )

    fun readLogTail(file: File, maxBytes: Int = LOG_TAIL_BYTES): String {
        if (!file.isFile) return ""
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val start = (raf.length() - maxBytes).coerceAtLeast(0)
                raf.seek(start)
                val buf = ByteArray((raf.length() - start).toInt())
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        }.getOrDefault("")
    }

    fun diagnoseLogText(log: String): RouterIssue? {
        if (log.isEmpty()) return null
        val recent = if (log.length <= PORT_IN_USE_RECENT_BYTES) {
            log
        } else {
            log.substring(log.length - PORT_IN_USE_RECENT_BYTES)
        }
        if (portInUse.containsMatchIn(recent)) return RouterIssue(RouterIssueCode.PORT_IN_USE)
        if (clockSkew.containsMatchIn(log)) return RouterIssue(RouterIssueCode.CLOCK_SKEW)
        val reseedFailed = reseedGaveUp.containsMatchIn(log) ||
            su3Failed.findAll(log).count() >= SU3_FAILURES_FOR_RESEED_ISSUE
        if (noNetwork.containsMatchIn(log) && !reseedFailed) return RouterIssue(RouterIssueCode.NO_NETWORK)
        if (reseedFailed) return RouterIssue(RouterIssueCode.RESEED_FAILED)
        return null
    }

    fun diagnoseSystemRouter(host: String, port: Int, samReachable: Boolean): RouterIssue? =
        if (samReachable) null else RouterIssue(RouterIssueCode.SYSTEM_SAM_UNREACHABLE, "$host:$port")

    fun diagnoseBundledRouter(
        log: String,
        processAlive: Boolean,
        networkReady: Boolean,
        warmingSeconds: Long,
    ): RouterIssue? {
        if (networkReady) return null
        diagnoseLogText(log)?.let { return it }
        if (!processAlive) return RouterIssue(RouterIssueCode.ROUTER_CRASHED)
        if (warmingSeconds >= SLOW_AFTER_SECONDS) return RouterIssue(RouterIssueCode.TUNNELS_SLOW)
        return null
    }
}
