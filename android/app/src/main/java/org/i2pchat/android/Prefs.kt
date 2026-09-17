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

    companion object {
        fun file(appRoot: File) = File(appRoot, "router_prefs.json")

        fun load(appRoot: File): RouterPrefs {
            val path = file(appRoot)
            if (!path.isFile) return RouterPrefs()
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
                ).normalized()
            } catch (_: Exception) {
                RouterPrefs()
            }
        }
    }

    fun normalized(): RouterPrefs {
        val backendOk = if (backend == "bundled") "bundled" else "system"
        val systemHost = if (systemSamHost == "localhost" || systemSamHost.startsWith("127.")) {
            systemSamHost.ifBlank { "127.0.0.1" }
        } else {
            "127.0.0.1"
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
        )
    }

    fun save(appRoot: File) {
        file(appRoot).writeText(normalized().toJson().toString())
    }
}

data class UiPrefs(
    val theme: String = "dark",
    val historyMaxMessages: Int = 1000,
    val historyRetentionDays: Int = 0,
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
                .toString(),
        )
    }
}
