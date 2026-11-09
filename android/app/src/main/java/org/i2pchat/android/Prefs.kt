package org.i2pchat.android

import org.json.JSONObject
import java.io.File

data class RouterPrefs(
    val backend: String = "bundled",
    val systemSamHost: String = "127.0.0.1",
    val systemSamPort: Int = 7656,
    val bundledSamHost: String = "127.0.0.1",
    val bundledSamPort: Int = 17656,
    val bundledHttpProxyPort: Int = 14444,
    val bundledSocksProxyPort: Int = 14447,
    val bundledControlHttpPort: Int = 17070,
    val bundledAutoStart: Boolean = true,
    /** Hops per inbound and outbound tunnel (I2CP inbound.length / outbound.length). */
    val tunnelLength: Int = DEFAULT_TUNNEL_LENGTH,
    /** System SAM may point at another machine. SAM itself is not encrypted. */
    val allowRemoteSam: Boolean = false,
) {
    val usingBundled: Boolean get() = backend == "bundled"
    val samHost: String get() = if (usingBundled) bundledSamHost else systemSamHost
    val samPort: Int get() = if (usingBundled) bundledSamPort else systemSamPort

    fun toJson(): JSONObject = JSONObject()
        .put("backend", backend)
        .put("system_sam_host", systemSamHost)
        .put("system_sam_port", systemSamPort)
        .put("bundled_sam_host", bundledSamHost)
        .put("bundled_sam_port", bundledSamPort)
        .put("bundled_http_proxy_port", bundledHttpProxyPort)
        .put("bundled_socks_proxy_port", bundledSocksProxyPort)
        .put("bundled_control_http_port", bundledControlHttpPort)
        .put("bundled_auto_start", bundledAutoStart)
        .put("tunnel_length", tunnelLength)
        .put("allow_remote_sam", allowRemoteSam)

    companion object {
        const val DEFAULT_TUNNEL_LENGTH = 4
        const val MIN_TUNNEL_LENGTH = 1
        const val MAX_TUNNEL_LENGTH = 7

        fun file(appRoot: File) = File(appRoot, "router_prefs.json")

        fun load(appRoot: File): RouterPrefs {
            val path = file(appRoot)
            if (!path.isFile) return RouterPrefs().normalized()
            return try {
                val raw = JSONObject(path.readText())
                RouterPrefs(
                    backend = raw.optString("backend", "bundled"),
                    systemSamHost = raw.optString("system_sam_host", "127.0.0.1"),
                    systemSamPort = raw.optInt("system_sam_port", 7656),
                    bundledSamHost = raw.optString("bundled_sam_host", "127.0.0.1"),
                    bundledSamPort = raw.optInt("bundled_sam_port", 17656),
                    bundledHttpProxyPort = raw.optInt("bundled_http_proxy_port", 14444),
                    bundledSocksProxyPort = raw.optInt("bundled_socks_proxy_port", 14447),
                    bundledControlHttpPort = raw.optInt("bundled_control_http_port", 17070),
                    bundledAutoStart = raw.optBoolean("bundled_auto_start", false),
                    tunnelLength = raw.optInt("tunnel_length", DEFAULT_TUNNEL_LENGTH),
                    allowRemoteSam = raw.optBoolean("allow_remote_sam", false),
                ).normalized()
            } catch (_: Exception) {
                RouterPrefs().normalized()
            }
        }
    }

    fun normalized(): RouterPrefs {
        val backendOk = when {
            !BuildConfig.BUNDLED_I2PD -> "system"
            backend == "bundled" -> "bundled"
            else -> "system"
        }
        val systemHost = when (systemSamProblem()) {
            null -> systemSamHost.trim().ifBlank { "127.0.0.1" }
            else -> "127.0.0.1"
        }
        val bundledHost = if (bundledSamHost.startsWith("127.") || bundledSamHost == "localhost") {
            bundledSamHost.ifBlank { "127.0.0.1" }
        } else {
            "127.0.0.1"
        }
        return copy(
            backend = backendOk,
            systemSamHost = systemHost,
            bundledSamHost = bundledHost,
            bundledAutoStart = backendOk == "bundled",
            tunnelLength = tunnelLength.coerceIn(MIN_TUNNEL_LENGTH, MAX_TUNNEL_LENGTH),
        )
    }

    /** Why the typed system SAM host cannot be saved. Load still falls back to loopback. */
    fun systemSamProblem(): SamHostProblem? {
        val host = systemSamHost.trim()
        if (host.isEmpty()) return SamHostProblem.EMPTY
        if (!acceptableSamHost(host)) return SamHostProblem.INVALID
        if (isLoopbackHost(host) || allowRemoteSam || remoteSamAllowedByEnv()) return null
        return SamHostProblem.REMOTE_NOT_ALLOWED
    }

    fun save(appRoot: File) {
        file(appRoot).writeText(normalized().toJson().toString())
    }
}

enum class SamHostProblem { EMPTY, INVALID, REMOTE_NOT_ALLOWED }

private fun remoteSamAllowedByEnv(): Boolean =
    System.getenv("I2PCHAT_ALLOW_REMOTE_SAM")?.trim()?.lowercase() in
        setOf("1", "true", "yes", "on")

private fun isLoopbackHost(host: String): Boolean {
    val text = host.trim().lowercase()
    return text == "localhost" || text == "::1" || text == "[::1]" || text.startsWith("127.")
}

private fun acceptableSamHost(host: String): Boolean {
    if (host.isEmpty() || host.length > 253 || host.any { it.code < 33 }) return false
    if (isLoopbackHost(host)) return true
    if (host.contains(':')) {
        return host.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }
    }
    if (host.count { it == '.' } == 3 && host.all { it.isDigit() || it == '.' }) return true
    if (".." in host) return false
    return host.split('.').all { label ->
        label.isNotEmpty() && label.length <= 63 &&
            label.all { it.isLetterOrDigit() || it == '-' } &&
            !label.startsWith('-') && !label.endsWith('-')
    }
}

data class UiPrefs(
    val theme: String = "dark",
    val historyMaxMessages: Int = 1000,
    val historyRetentionDays: Int = 0,
    val verboseLog: Boolean = false,
    val filesDir: String = "",
    val imagesDir: String = "",
    val ephemeralMedia: Boolean = false,
) {
    companion object {
        fun file(appRoot: File) = File(appRoot, "ui_prefs.json")

        fun load(appRoot: File): UiPrefs {
            val path = file(appRoot)
            if (!path.isFile) return UiPrefs()
            return try {
                val raw = JSONObject(path.readText())
                UiPrefs(
                    theme = raw.optString("theme", "dark"),
                    historyMaxMessages = raw.optInt("history_max_messages", 1000),
                    historyRetentionDays = raw.optInt("history_retention_days", 0),
                    verboseLog = raw.optBoolean("verbose_log", false),
                    filesDir = raw.optString("files_dir", ""),
                    imagesDir = raw.optString("images_dir", ""),
                    ephemeralMedia = raw.optBoolean("ephemeral_media", false),
                )
            } catch (_: Exception) {
                UiPrefs()
            }
        }
    }

    fun save(appRoot: File) {
        file(appRoot).writeText(
            JSONObject()
                .put("theme", theme)
                .put("history_max_messages", historyMaxMessages)
                .put("history_retention_days", historyRetentionDays)
                .put("verbose_log", verboseLog)
                .put("files_dir", filesDir)
                .put("images_dir", imagesDir)
                .put("ephemeral_media", ephemeralMedia)
                .toString(),
        )
    }
}
