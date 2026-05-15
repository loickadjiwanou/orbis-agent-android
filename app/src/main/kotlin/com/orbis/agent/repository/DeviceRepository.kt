package com.orbis.agent.repository

import android.util.Log
import com.orbis.agent.collector.DeviceDataCollector
import com.orbis.agent.mqtt.MqttManager
import com.orbis.agent.prefs.OrbisPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository responsible for device identity management and the onboarding flow.
 * Wraps OrbisPreferences and orchestrates the registration handshake via MqttManager.
 */
@Singleton
class DeviceRepository @Inject constructor(
    private val prefs: OrbisPreferences,
    private val mqttManager: MqttManager,
    private val deviceDataCollector: DeviceDataCollector
) {
    companion object {
        private const val TAG = "DeviceRepository"
        private val json = Json { encodeDefaults = true }
    }

    /**
     * Sealed class representing the result of an operation.
     */
    sealed class OrbisResult<out T> {
        data class Success<T>(val data: T) : OrbisResult<T>()
        data class Failure(val error: String, val exception: Exception? = null) : OrbisResult<Nothing>()
    }

    /**
     * Returns the current device_id, or empty string if not onboarded.
     */
    fun getDeviceId(): String = prefs.deviceId

    /**
     * Returns whether the device has completed onboarding.
     */
    fun isOnboarded(): Boolean = prefs.isOnboarded()

    /**
     * Saves broker URL and register secret to encrypted preferences.
     */
    fun saveBrokerConfig(brokerUrl: String, registerSecret: String) {
        prefs.brokerUrl = brokerUrl
        prefs.registerSecret = registerSecret
        Log.d(TAG, "Broker config saved: $brokerUrl")
    }

    /**
     * Performs the full onboarding flow:
     * 1. Generates a new UUID device_id
     * 2. Connects to broker with register credentials
     * 3. Publishes registration payload
     * 4. Waits for register_ack with token
     * 5. Saves device_id and token to preferences
     * 6. Reconnects with device credentials
     *
     * @return OrbisResult.Success(deviceId) or OrbisResult.Failure with error message.
     */
    suspend fun performOnboarding(): OrbisResult<String> {
        return try {
            val deviceId = UUID.randomUUID().toString()
            val brokerUrl = prefs.brokerUrl
            val registerSecret = prefs.registerSecret

            Log.i(TAG, "Starting onboarding for device: $deviceId")

            val registerPayload = deviceDataCollector.buildRegisterPayload(deviceId)
            val registerPayloadJson = buildRegisterJson(registerPayload)

            val token = mqttManager.performOnboarding(
                deviceId = deviceId,
                brokerUrl = brokerUrl,
                registerSecret = registerSecret,
                registerPayload = registerPayloadJson
            )

            if (token == null) {
                Log.e(TAG, "Onboarding failed: no token received")
                return OrbisResult.Failure("Registration failed: no token received from server")
            }

            // Save credentials
            prefs.deviceId = deviceId
            prefs.token = token
            Log.i(TAG, "Onboarding successful, device_id=$deviceId")

            // Reconnect with device credentials
            mqttManager.connectWithDeviceCredentials()

            OrbisResult.Success(deviceId)
        } catch (e: Exception) {
            Log.e(TAG, "Onboarding exception: ${e.message}")
            OrbisResult.Failure("Onboarding failed: ${e.message}", e)
        }
    }

    private fun buildRegisterJson(payload: Map<String, Any>): String {
        val sb = StringBuilder("{")
        payload.entries.forEachIndexed { index, entry ->
            if (index > 0) sb.append(",")
            sb.append("\"${entry.key}\":\"${entry.value}\"")
        }
        sb.append("}")
        return sb.toString()
    }

    /**
     * Clears all stored preferences to reset onboarding state.
     */
    fun resetOnboarding() {
        prefs.clear()
        Log.i(TAG, "Onboarding state cleared")
    }
}
