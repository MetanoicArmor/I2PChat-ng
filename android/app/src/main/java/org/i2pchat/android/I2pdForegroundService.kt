package org.i2pchat.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.purplei2p.i2pd.nativelib.FatalHandler
import org.purplei2p.i2pd.nativelib.I2pdNative
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.system.exitProcess

class I2pdForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        holdProcessLock()
        val text = intent?.getStringExtra(EXTRA_TEXT)
            ?: getString(R.string.router_notification)
        startForeground(NotificationHelper.ROUTER_ID, NotificationHelper.routerNotification(this, text))
        when (intent?.action) {
            ACTION_STOP -> {
                beginExit()
                return START_NOT_STICKY
            }
        }
        if (exiting.get() || intent?.getBooleanExtra(EXTRA_FORCE_RESTART, false) == true) {
            beginExit()
            return START_NOT_STICKY
        }
        val bundled = intent?.getBooleanExtra(EXTRA_BUNDLED, false) == true
        if (bundled) {
            thread(name = "i2pd-start", isDaemon = true) {
                startBundled(intent)
            }
        } else {
            publish(getString(R.string.svc_external), alive = false)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (!exiting.get()) {
            shutdownDaemon()
        }
        super.onDestroy()
    }

    /**
     * libi2pd 3.0.0 can be initialized once per process. Stop, then exit so the
     * next start runs in a fresh :i2pd process. [reportStopped] stays false when
     * the caller already published the real error.
     */
    private fun beginExit(reportStopped: Boolean = true) {
        if (!exiting.compareAndSet(false, true)) return
        thread(name = "i2pd-stop", isDaemon = true) {
            if (nativeLoaded) {
                runCatching { I2pdNative.stop() }
            }
            started.set(false)
            if (reportStopped) {
                publish(getString(R.string.svc_stopped), alive = false)
            }
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(0)
        }
    }

    private fun shutdownDaemon() {
        if (nativeLoaded) {
            runCatching { I2pdNative.stop() }
        }
        started.set(false)
        publish(getString(R.string.svc_stopped), alive = false)
    }

    private fun holdProcessLock() {
        if (processLock != null) return
        val dir = filesDir.resolve("i2pchat/router").apply { mkdirs() }
        val channel = FileOutputStream(File(dir, "i2pd.lock")).channel
        channel.lock()
        processLock = channel
    }

    private fun ensureLoaded() {
        if (nativeLoaded) return
        // Touches the class, which loads libi2pd.so and runs JNI_OnLoad.
        I2pdNative.version()
        nativeLoaded = true
    }

    private fun samReady(): Boolean =
        runCatching {
            if (!nativeLoaded) return false
            val raw = LongArray(I2pdNative.Status.SIZE)
            I2pdNative.status(raw) && I2pdNative.Status.samRunning(raw)
        }.getOrDefault(false)

    private fun startBundled(intent: Intent?) {
        if (exiting.get()) return
        if (started.get()) {
            if (samReady()) {
                publish(lastMessage.ifBlank { getString(R.string.svc_running) }, alive = true)
                notify(lastMessage.ifBlank { getString(R.string.svc_running) })
                return
            }
            // init() cannot run twice in this process. Leave the daemon up and
            // keep reporting; a real restart exits the process first.
            publish(getString(R.string.svc_waiting_sam, RouterPrefs.load(
                File(intent?.getStringExtra(EXTRA_APP_ROOT) ?: filesDir.resolve("i2pchat").absolutePath),
            ).bundledSamPort), alive = false)
            return
        }
        if (!started.compareAndSet(false, true)) {
            if (samReady()) {
                publish(lastMessage, alive = lastAlive)
            }
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
            val error = json.optString("error").ifBlank { getString(R.string.svc_config_failed) }
            publish(error, alive = false, failed = true)
            notify(error)
            return
        }
        val conf = File(json.getString("conf"))
        appendMobileConf(conf)
        copyCertificates(dataDir)
        File(dataDir, "tunnels.d").mkdirs()
        File(dataDir, "assets.ready").writeText("3.0.0")
        try {
            ensureLoaded()
        } catch (ex: Throwable) {
            started.set(false)
            val error = getString(R.string.svc_load_failed, ex.message.orEmpty())
            publish(error, alive = false, failed = true)
            notify(error)
            return
        }
        publish(getString(R.string.svc_starting), alive = false)
        notify(getString(R.string.svc_starting))
        thread(name = "i2pd-daemon", isDaemon = true) {
            try {
                if (exiting.get()) return@thread
                I2pdNative.setFatalHandler(FatalHandler { message ->
                    Log.e(TAG, "i2pd fatal: $message")
                })
                val args = arrayOf(
                    "--datadir=${dataDir.absolutePath}",
                    "--conf=${conf.absolutePath}",
                    "--tunconf=${File(dataDir, "tunnels.conf").absolutePath}",
                    "--tunnelsdir=${File(dataDir, "tunnels.d").absolutePath}",
                    "--certsdir=${File(dataDir, "certificates").absolutePath}",
                )
                val result = I2pdNative.init(dataDir.absolutePath, args, "english")
                    ?: I2pdNative.start()
                if (result == null) {
                    runCatching { I2pdNative.setOnline(true) }
                    var samUp = false
                    val until = System.currentTimeMillis() + 90_000
                    while (System.currentTimeMillis() < until && !exiting.get()) {
                        if (samReady()) {
                            samUp = true
                            break
                        }
                        Thread.sleep(150)
                    }
                    val text = if (samUp) {
                        getString(R.string.svc_running)
                    } else {
                        getString(R.string.svc_waiting_sam, prefs.bundledSamPort)
                    }
                    publish(text, alive = samUp)
                    notify(text)
                } else {
                    val error = getString(R.string.svc_start_failed, result.ifBlank { "start failed" })
                    publish(error, alive = false, failed = true)
                    notify(error)
                    beginExit(reportStopped = false)
                }
            } catch (ex: Throwable) {
                val error = getString(R.string.svc_crashed, ex.message.orEmpty())
                Log.e(TAG, error, ex)
                publish(error, alive = false, failed = true)
                notify(error)
                beginExit(reportStopped = false)
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
            upnp.enabled = false
            bandwidth = low
            loglevel = info
        """.trimIndent()
        val current = if (conf.isFile) conf.readText() else ""
        if (!current.contains("ntcp2.enabled")) {
            conf.appendText("\n$extra\n")
        } else if (!current.contains("upnp.enabled")) {
            conf.appendText("\nupnp.enabled = false\nbandwidth = low\n")
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

    private fun publish(text: String, alive: Boolean, failed: Boolean = false) {
        message.set(text)
        lastAlive = alive
        lastFailed = failed
        val appRoot = File(filesDir, "i2pchat")
        statusFile(appRoot).parentFile?.mkdirs()
        statusFile(appRoot).writeText(
            JSONObject()
                .put("message", text)
                .put("alive", alive)
                .put("failed", failed)
                .toString(),
        )
    }

    companion object {
        private const val TAG = "I2pdForegroundService"
        const val ACTION_STOP = "org.i2pchat.android.action.STOP_I2PD"
        const val EXTRA_BUNDLED = "bundled"
        const val EXTRA_APP_ROOT = "appRoot"
        const val EXTRA_TEXT = "text"
        const val EXTRA_FORCE_RESTART = "forceRestart"

        private val message = AtomicReference("Stopped")
        private val started = AtomicBoolean(false)
        private val exiting = AtomicBoolean(false)
        @Volatile
        private var nativeLoaded = false
        @Volatile
        private var processLock: FileChannel? = null
        val lastMessage: String get() = message.get()
        @Volatile
        var lastAlive: Boolean = false
            private set
        @Volatile
        var lastFailed: Boolean = false
            private set

        fun statusFile(appRoot: File) = File(appRoot, "router/i2pd.status")

        fun isAlive(appRoot: File): Boolean {
            val file = statusFile(appRoot)
            if (!file.isFile) return lastAlive
            return try {
                JSONObject(file.readText()).optBoolean("alive", lastAlive)
            } catch (_: Exception) {
                lastAlive
            }
        }

        fun start(context: Context, appRoot: File, bundled: Boolean, text: String) {
            val intent = Intent(context, I2pdForegroundService::class.java)
                .putExtra(EXTRA_BUNDLED, bundled)
                .putExtra(EXTRA_APP_ROOT, appRoot.absolutePath)
                .putExtra(EXTRA_TEXT, text)
            context.startForegroundService(intent)
        }

        fun restartBundled(
            context: Context,
            appRoot: File,
            text: String = context.getString(R.string.svc_restarting),
        ) {
            val app = context.applicationContext
            if (routerProcessLocked(app)) {
                app.startService(Intent(app, I2pdForegroundService::class.java).setAction(ACTION_STOP))
                waitUntilRouterProcessExits(app, 20_000)
            }
            start(context, appRoot, true, text)
        }

        private fun routerLockFile(context: Context) =
            File(context.filesDir, "i2pchat/router/i2pd.lock")

        /** True while the :i2pd process holds the lock taken in [holdProcessLock]. */
        private fun routerProcessLocked(context: Context): Boolean {
            val file = routerLockFile(context)
            if (!file.isFile) return false
            return try {
                FileInputStream(file).channel.use { channel ->
                    val lock = channel.tryLock()
                    if (lock != null) {
                        lock.release()
                        false
                    } else {
                        true
                    }
                }
            } catch (_: OverlappingFileLockException) {
                true
            } catch (_: Exception) {
                false
            }
        }

        private fun waitUntilRouterProcessExits(context: Context, timeoutMs: Long) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (!routerProcessLocked(context)) return
                Thread.sleep(200)
            }
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            val stopIntent = Intent(app, I2pdForegroundService::class.java).setAction(ACTION_STOP)
            app.startService(stopIntent)
            Handler(Looper.getMainLooper()).postDelayed({
                app.stopService(Intent(app, I2pdForegroundService::class.java))
            }, 500)
        }

        /** True when the last start attempt of the built-in router ended in an error. */
        fun hasFailed(appRoot: File): Boolean {
            val file = statusFile(appRoot)
            if (!file.isFile) return lastFailed
            return try {
                JSONObject(file.readText()).optBoolean("failed", lastFailed)
            } catch (_: Exception) {
                lastFailed
            }
        }

        fun logFile(appRoot: File) = File(appRoot, "router/i2pd.log")

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
