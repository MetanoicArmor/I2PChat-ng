package org.i2pchat.android

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * Application-scoped [ViewModelStore] so [ChatBridge] (and the native SAM
 * listener) survive Activity recreation while [ChatForegroundService] keeps
 * the process alive in the background.
 */
class I2PChatApp : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    override fun onCreate() {
        super.onCreate()
        runCatching { NativeKeyring.init(this) }
        runCatching { NotificationHelper.init(this) }
    }

    override fun onTerminate() {
        viewModelStore.clear()
        super.onTerminate()
    }
}
