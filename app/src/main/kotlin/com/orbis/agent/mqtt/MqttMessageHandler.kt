package com.orbis.agent.mqtt

import android.util.Log
import com.orbis.agent.executor.CommandExecutor
import com.orbis.agent.model.Command
import com.orbis.agent.prefs.OrbisPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles incoming MQTT messages and dispatches commands to [CommandExecutor].
 * Subscribes to device-specific command topics on initialization.
 */
@Singleton
class MqttMessageHandler @Inject constructor(
    private val mqttManager: MqttManager,
    private val commandExecutor: CommandExecutor,
    private val prefs: OrbisPreferences
) {
    companion object {
        private const val TAG = "MqttMessageHandler"
    }

    private val handlerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /**
     * Registers subscriptions for all device-specific MQTT topics.
     * Must be called after MQTT connection is established and device is onboarded.
     */
    fun registerSubscriptions() {
        val deviceId = prefs.deviceId
        if (deviceId.isEmpty()) {
            Log.w(TAG, "Cannot subscribe: device_id not set")
            return
        }

        // Subscribe to command topics (commands + update alias)
        mqttManager.subscribe("devices/$deviceId/commands") { topic, payloadBytes ->
            handleCommandMessage(topic, payloadBytes)
        }
        mqttManager.subscribe("devices/$deviceId/update") { topic, payloadBytes ->
            handleCommandMessage(topic, payloadBytes)
        }

        Log.i(TAG, "Subscribed to command topics for device: $deviceId")
    }

    private fun handleCommandMessage(topic: String, payloadBytes: ByteArray) {
        handlerScope.launch {
            try {
                val payloadStr = String(payloadBytes, Charsets.UTF_8)
                Log.d(TAG, "Received command on $topic: $payloadStr")

                val command = json.decodeFromString<Command>(payloadStr)
                commandExecutor.executeCommand(command)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle command message: ${e.message}")
            }
        }
    }

    /**
     * Unregisters all subscriptions — called on service stop.
     */
    fun unregisterSubscriptions() {
        val deviceId = prefs.deviceId
        if (deviceId.isNotEmpty()) {
            mqttManager.unsubscribe("devices/$deviceId/commands")
            mqttManager.unsubscribe("devices/$deviceId/update")
        }
    }
}
