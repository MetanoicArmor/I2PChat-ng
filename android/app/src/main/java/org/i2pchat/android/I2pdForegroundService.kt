package org.i2pchat.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import org.purplei2p.i2pd.I2PD_JNI
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class I2pdForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT)
            ?: getString(R.string.router_notification)
        startForeground(NotificationHelper.ROUTER_ID, NotificationHelper.routerNotification(this, text))
        val bundled = intent?.getBooleanExtra(EXTRA_BUNDLED, false) == true
        if (bundled) {
            startBundled(intent)
        } else {
            publish("Using external SAM", alive = false)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { I2PD_JNI.stopDaemon() }
        started.set(false)
        publish("Stopped", alive = false)
        super.onDestroy()
    }

    private fun startBundled(intent: Intent?) {
        if (!started.compareAndSet(false, true)) {
            publish(lastMessage, alive = lastAlive)
            return
        }
        val appRoot = File(intent?.getStringExtra(EXTRA_APP_ROOT) ?: filesDir.resolve("i2pchat").absolutePath)
        val prefs = RouterPrefs.load(appRoot)
        val dataDir = File(appRoot, "router").apply { mkdirs() }
        val prepared = NativeEngine.nativePrepareRouter(
            dataDir.absolutePath,
            prefs.bundledSamHost,
            prefs.bundledSamPort,
            prefs.bundledHttpProxyPort,
            prefs.bundledSocksProxyPort,
            prefs.bundledControlHttpPort,
        )
        val json = JSONObject(prepared)
        if (!json.optBoolean("ok", false)) {
            started.set(false)
            val error = json.optString("error", "Router config failed")
            publish(error, alive = false)
            notify(error)
            return
        }
        appendMobileConf(File(json.getString("conf")))
        copyCertificates(dataDir)
        File(dataDir, "assets.ready").writeText("2.61.0")
        try {
            I2PD_JNI.loadLibraries()
        } catch (ex: Throwable) {
            started.set(false)
            val error = "Cannot load libi2pd.so: ${ex.message}"
            publish(error, alive = false)
            notify(error)
            return
        }
        publish("Starting bundled i2pd…", alive = false)
        notify("Starting bundled i2pd…")
        thread(name = "i2pd-daemon", isDaemon = true) {
            try {
                I2PD_JNI.setDataDir(dataDir.absolutePath)
                I2PD_JNI.setLanguage("english")
                val result = I2PD_JNI.startDaemon()
                if (result == "ok") {
                    runCatching { I2PD_JNI.onNetworkStateChanged(true) }
                    var samUp = false
                    val until = System.currentTimeMillis() + 10_000
                    while (System.currentTimeMillis() < until) {
                        if (runCatching { I2PD_JNI.getSAMState() }.getOrDefault(false)) {
                            samUp = true
                            break
                        }
                        Thread.sleep(250)
                    }
                    val text = if (samUp) {
                        "Bundled i2pd running on SAM ${prefs.bundledSamPort}"
                    } else {
                        "i2pd started, waiting for SAM ${prefs.bundledSamPort}"
                    }
                    publish(text, alive = true)
                    notify(text)
                } else {
                    started.set(false)
                    val error = "i2pd: ${result.ifBlank { "start failed" }}"
                    publish(error, alive = false)
                    notify(error)
                }
            } catch (ex: Throwable) {
                started.set(false)
                val error = "i2pd crashed: ${ex.message}"
                Log.e(TAG, error, ex)
                publish(error, alive = false)
                notify(error)
            }
        }
    }

    private fun appendMobileConf(conf: File) {
        val extra = """
            ipv4 = true
            ipv6 = false
            nat = true
            ntcp2.enabled = true
            ssu = false
            loglevel = info
        """.trimIndent()
        val current = if (conf.isFile) conf.readText() else ""
        if (!current.contains("ntcp2.enabled")) {
            conf.appendText("\n$extra\n")
        }
    }

    private fun copyCertificates(dataDir: File) {
        try {
            for (prefix in listOf("i2pd/certificates/reseed", "i2pd/certificates/family")) {
                val names = assets.list(prefix) ?: continue
                val dest = File(dataDir, prefix.removePrefix("i2pd/"))
                dest.mkdirs()
                for (name in names) {
                    val out = File(dest, name)
                    if (out.exists()) continue
                    assets.open("$prefix/$name").use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                }
            }
        } catch (_: Exception) {
        }
        File(dataDir, "certificates").mkdirs()
    }

    private fun notify(text: String) {
        startForeground(NotificationHelper.ROUTER_ID, NotificationHelper.routerNotification(this, text))
    }

    private fun publish(text: String, alive: Boolean) {
        message.set(text)
        lastAlive = alive
        val appRoot = File(filesDir, "i2pchat")
        statusFile(appRoot).parentFile?.mkdirs()
        statusFile(appRoot).writeText(
            JSONObject()
                .put("message", text)
                .put("alive", alive)
                .toString(),
        )
    }

    companion object {
        private const val TAG = "I2pdForegroundService"
        const val EXTRA_BUNDLED = "bundled"
        const val EXTRA_APP_ROOT = "appRoot"
        const val EXTRA_TEXT = "text"

        private val message = AtomicReference("Stopped")
        private val started = AtomicBoolean(false)
        val lastMessage: String get() = message.get()
        @Volatile
        var lastAlive: Boolean = false
            private set

        fun statusFile(appRoot: File) = File(appRoot, "router/i2pd.status")

        fun start(context: Context, appRoot: File, bundled: Boolean, text: String) {
            val intent = Intent(context, I2pdForegroundService::class.java)
                .putExtra(EXTRA_BUNDLED, bundled)
                .putExtra(EXTRA_APP_ROOT, appRoot.absolutePath)
                .putExtra(EXTRA_TEXT, text)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, I2pdForegroundService::class.java))
        }

        fun readStatus(appRoot: File): String {
            val file = statusFile(appRoot)
            if (!file.isFile) return lastMessage
            return try {
                JSONObject(file.readText()).optString("message", lastMessage)
            } catch (_: Exception) {
                lastMessage
            }
        }
    }
}
