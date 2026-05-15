package com.orbis.agent.logger

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.json.JSONObject
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight wrapper that logs to Android logcat AND publishes
 * to MQTT topic: devices/{device_id}/agent_logs
 *
 * Only INFO and above are forwarded to MQTT to avoid flooding.
 */
@Singleton
class AgentLogger @Inject constructor() {

    private val TAG = "AgentLogger"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var mqttClient: MqttAsyncClient? = null
    @Volatile private var deviceId: String = ""

    fun init(client: MqttAsyncClient, deviceId: String) {
        this.mqttClient = client
        this.deviceId = deviceId
    }

    fun info(source: String, message: String) {
        Log.i(source, message)
        publish("INFO", source, message)
    }

    fun warn(source: String, message: String) {
        Log.w(source, message)
        publish("WARNING", source, message)
    }

    fun error(source: String, message: String) {
        Log.e(source, message)
        publish("ERROR", source, message)
    }

    fun debug(source: String, message: String) {
        Log.d(source, message)
        // DEBUG not forwarded to MQTT
    }

    private fun publish(level: String, source: String, message: String) {
        val client = mqttClient ?: return
        if (!client.isConnected) return
        if (deviceId.isEmpty()) return

        scope.launch {
            try {
                val payload = JSONObject().apply {
                    put("device_id", deviceId)
                    put("timestamp", Instant.now().toString())
                    put("level", level)
                    put("source", source)
                    put("message", message)
                }.toString()

                val topic = "devices/$deviceId/agent_logs"
                val msg = MqttMessage(payload.toByteArray())
                msg.qos = 0
                msg.isRetained = false
                client.publish(topic, msg, null, null)
            } catch (_: Exception) {
                // Never let the logger throw
            }
        }
    }
}
