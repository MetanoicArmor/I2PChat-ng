package org.i2pchat.android

import android.app.Application

class I2PChatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { NativeKeyring.init(this) }
        runCatching { NotificationHelper.init(this) }
    }
}
