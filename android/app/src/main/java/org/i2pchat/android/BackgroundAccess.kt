package org.i2pchat.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Permissions and OEM battery exemptions needed so [ChatForegroundService] /
 * [I2pdForegroundService] can keep the process alive when the UI is minimized.
 */
object BackgroundAccess {
    data class Status(
        val notificationsGranted: Boolean,
        val batteryUnrestricted: Boolean,
    ) {
        val allowed: Boolean get() = notificationsGranted && batteryUnrestricted
    }

    fun status(context: Context): Status {
        val app = context.applicationContext
        return Status(
            notificationsGranted = notificationsGranted(app),
            batteryUnrestricted = isIgnoringBatteryOptimizations(app),
        )
    }

    fun notificationsGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val app = context.applicationContext
        val pm = app.getSystemService(PowerManager::class.java) ?: return true
        return runCatching { pm.isIgnoringBatteryOptimizations(app.packageName) }.getOrDefault(true)
    }

    fun needsNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && !notificationsGranted(context)

    fun needsBatteryExemption(context: Context): Boolean =
        !isIgnoringBatteryOptimizations(context)

    /** System dialog that asks to ignore battery optimizations for this package. */
    fun batteryExemptionIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    /** Fallback list of apps when the direct request is blocked by the OEM. */
    fun batterySettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun openBatteryExemption(activity: Activity) {
        val direct = batteryExemptionIntent(activity)
        val launched = runCatching {
            activity.startActivity(direct)
            true
        }.getOrDefault(false)
        if (!launched) {
            runCatching { activity.startActivity(batterySettingsIntent()) }
        }
    }

    fun openAppNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}
