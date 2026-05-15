package com.orbis.agent.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure preferences storage for Orbis agent configuration.
 * Uses AES256_GCM encryption via EncryptedSharedPreferences.
 *
 * Stores: device_id, token, broker_url, register_secret.
 */
@Singleton
class OrbisPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_FILE = "orbis_secure_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_TOKEN = "token"
        private const val KEY_BROKER_URL = "broker_url"
        private const val KEY_REGISTER_SECRET = "register_secret"
        private const val DEFAULT_BROKER_URL = "tcp://localhost:1883"
        private const val DEFAULT_REGISTER_SECRET = "orbis_register_secret"
    }

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Unique device identifier assigned during onboarding. */
    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    /** Authentication token received after successful registration. */
    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    /** MQTT broker URL, e.g. tcp://192.168.1.100:1883. */
    var brokerUrl: String
        get() = prefs.getString(KEY_BROKER_URL, DEFAULT_BROKER_URL) ?: DEFAULT_BROKER_URL
        set(value) = prefs.edit().putString(KEY_BROKER_URL, value).apply()

    /** Secret used for the initial registration handshake. */
    var registerSecret: String
        get() = prefs.getString(KEY_REGISTER_SECRET, DEFAULT_REGISTER_SECRET) ?: DEFAULT_REGISTER_SECRET
        set(value) = prefs.edit().putString(KEY_REGISTER_SECRET, value).apply()

    /**
     * Returns true if the device has completed onboarding (has device_id and token).
     */
    fun isOnboarded(): Boolean = deviceId.isNotEmpty() && token.isNotEmpty()

    /** Clears all stored preferences (useful for factory reset / re-onboarding). */
    fun clear() {
        prefs.edit().clear().apply()
    }
}
