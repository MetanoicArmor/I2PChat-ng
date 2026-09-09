package org.i2pchat.android

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object NativeKeyring {
    private const val PREFS = "i2pchat_keyring"
    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
        val master = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS,
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    @JvmStatic
    fun get(service: String, account: String): String? {
        if (!::prefs.isInitialized) return null
        return prefs.getString(key(service, account), null)
    }

    @JvmStatic
    fun set(service: String, account: String, secret: String): Boolean {
        if (!::prefs.isInitialized) return false
        prefs.edit().putString(key(service, account), secret).apply()
        return true
    }

    @JvmStatic
    fun erase(service: String, account: String): Boolean {
        if (!::prefs.isInitialized) return false
        prefs.edit().remove(key(service, account)).apply()
        return true
    }

    private fun key(service: String, account: String) = "$service|$account"
}
