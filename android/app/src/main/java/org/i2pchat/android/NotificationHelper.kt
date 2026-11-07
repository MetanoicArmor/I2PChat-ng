package org.i2pchat.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationHelper {
    const val ROUTER_CHANNEL = "i2pchat_router"
    const val MESSAGE_CHANNEL = "i2pchat_messages"
    const val ROUTER_ID = 41

    fun init(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                ROUTER_CHANNEL,
                context.getString(R.string.router_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                MESSAGE_CHANNEL,
                context.getString(R.string.messages_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    fun routerNotification(context: Context, text: String): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, ROUTER_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    fun notifyTrustPrompt(context: Context, peer: String) {
        val open = PendingIntent.getActivity(
            context,
            peer.hashCode() xor 0x7f,
            Intent(context, MainActivity::class.java).putExtra("openPeer", peer),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("Подтвердите ключ собеседника: ${peer.take(18)}…")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(context).notify((peer.hashCode() xor 0x7f), notification)
        } catch (_: SecurityException) {
        }
    }

    fun notifyMessage(context: Context, peer: String, preview: String) {
        val open = PendingIntent.getActivity(
            context,
            peer.hashCode(),
            Intent(context, MainActivity::class.java).putExtra("openPeer", peer),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(peer.take(18))
            .setContentText(preview)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(peer.hashCode(), notification)
        } catch (_: SecurityException) {
        }
    }
}
