package org.i2pchat.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the main process (and with it [NativeEngine] / the SAM chat session)
 * at foreground priority while a profile is open.
 *
 * The bundled router lives in a separate `:i2pd` process via
 * [I2pdForegroundService]. Without this service the chat process is free for
 * the OEM to reclaim after the UI is backgrounded (GitHub issue #46).
 */
class ChatForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                held.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val profile = intent?.getStringExtra(EXTRA_PROFILE).orEmpty()
            .ifBlank { SessionPrefs.load(File(filesDir, "i2pchat")).lastProfile }
        val text = getString(
            R.string.chat_notification,
            profile.ifBlank { getString(R.string.app_name) },
        )
        startForeground(NotificationHelper.CHAT_ID, NotificationHelper.chatNotification(this, text))
        held.set(true)
        return START_STICKY
    }

    override fun onDestroy() {
        held.set(false)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "org.i2pchat.android.action.STOP_CHAT"
        const val EXTRA_PROFILE = "profile"

        private val held = AtomicBoolean(false)

        /** True while this service has raised the main-process priority. */
        fun isHeld(): Boolean = held.get()

        fun start(context: Context, profile: String) {
            val app = context.applicationContext
            val intent = Intent(app, ChatForegroundService::class.java)
                .putExtra(EXTRA_PROFILE, profile)
            app.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            held.set(false)
            val stop = Intent(app, ChatForegroundService::class.java).setAction(ACTION_STOP)
            runCatching { app.startService(stop) }
            app.stopService(Intent(app, ChatForegroundService::class.java))
        }
    }
}
