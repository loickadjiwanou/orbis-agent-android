package com.orbis.agent.mqtt

import android.util.Log
import com.orbis.agent.logger.AgentLogger
import com.orbis.agent.model.DeviceStatus
import com.orbis.agent.prefs.OrbisPreferences
import com.orbis.agent.repository.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/**
 * Manages MQTT connectivity for the Orbis agent.
 *
 * Features:
 * - Exponential backoff reconnect: 1s → 2s → 4s → ... → 60s max
 * - Last Will configuration for offline presence
 * - Offline message buffering via [MessageRepository]
 * - Full onboarding flow: register credentials → device credentials
 * - Thread-safe topic subscription registry
 */
@Singleton
class MqttManager @Inject constructor(
    private val prefs: OrbisPreferences,
    private val messageRepository: MessageRepository,
    private val agentLogger: AgentLogger,
) {
    companion object {
        private const val TAG = "MqttManager"
        private const val APP_VERSION = "1.0.0"
        private const val KEEPALIVE_SECONDS = 60
        private const val RECONNECT_MIN_DELAY_MS = 1_000L
        private const val RECONNECT_MAX_DELAY_MS = 60_000L
    }

    /** Connection state enum for consumers to observe. */
    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val mqttScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private var mqttClient: MqttAsyncClient? = null
    private val topicCallbacks = ConcurrentHashMap<String, (String, ByteArray) -> Unit>()

    private var reconnectAttempt = 0
    private var reconnecting = false

    // Incremented every time connectInternal() starts a new connection.
    // Each callback closure captures its own generation; stale callbacks from
    // old/replaced clients are silently dropped when the values don't match.
    @Volatile private var connectionGeneration = 0

    /**
     * Connects to the MQTT broker using saved device credentials.
     * Configures Last Will and Testament for offline presence.
     */
    suspend fun connect() {
        val deviceId = prefs.deviceId
        val brokerUrl = prefs.brokerUrl
        val token = prefs.token

        if (deviceId.isEmpty() || token.isEmpty()) {
            Log.w(TAG, "Cannot connect: device not onboarded")
            return
        }

        connectInternal(
            brokerUrl = brokerUrl,
            clientId = "orbis-agent-android-$deviceId",
            username = deviceId,
            password = token,
            deviceIdForWill = deviceId
        )
    }

    /**
     * Connects using the registration credentials for the onboarding handshake.
     * Uses username="register" and the register_secret as password.
     * @param brokerUrl The MQTT broker URL.
     * @param registerSecret The registration secret.
     */
    suspend fun connectWithRegisterCredentials(brokerUrl: String, registerSecret: String) {
        val tempClientId = "orbis-register-android-${UUID.randomUUID().toString().take(8)}"
        connectInternal(
            brokerUrl = brokerUrl,
            clientId = tempClientId,
            username = "register",
            password = registerSecret,
            deviceIdForWill = null
        )
    }

    /**
     * Reconnects using device credentials after successful onboarding.
     */
    suspend fun connectWithDeviceCredentials() {
        disconnect()
        connect()
    }

    private suspend fun connectInternal(
        brokerUrl: String,
        clientId: String,
        username: String,
        password: String,
        deviceIdForWill: String?
    ) {
        // Close the previous client before creating a new one so its callbacks
        // stop firing. Increment the generation so any in-flight callbacks from
        // the old client are recognised as stale and dropped.
        val oldClient = mqttClient
        mqttClient = null
        try { oldClient?.close() } catch (_: Exception) {}

        val generation = ++connectionGeneration
        _connectionState.value = ConnectionState.CONNECTING

        try {
            val client = MqttAsyncClient(brokerUrl, clientId, MemoryPersistence())
            mqttClient = client

            val options = MqttConnectOptions().apply {
                this.userName = username
                this.password = password.toCharArray()
                isCleanSession = true
                keepAliveInterval = KEEPALIVE_SECONDS
                isAutomaticReconnect = false // We handle reconnect ourselves

                // Configure Last Will if this is a device connection
                if (deviceIdForWill != null) {
                    val willPayload = buildOfflinePayload(deviceIdForWill)
                    setWill(
                        "devices/$deviceIdForWill/status",
                        willPayload.toByteArray(Charsets.UTF_8),
                        1,
                        true
                    )
                }
            }

            client.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String) {
                    if (connectionGeneration != generation) return // stale — ignore
                    Log.i(TAG, "MQTT connected to $serverURI (reconnect=$reconnect)")
                    agentLogger.info(TAG, "MQTT connected to $serverURI (reconnect=$reconnect)")
                    _connectionState.value = ConnectionState.CONNECTED
                    reconnectAttempt = 0
                    reconnecting = false

                    // Re-subscribe all registered topics
                    for ((topic, callback) in topicCallbacks) {
                        subscribeInternal(topic, callback)
                    }

                    // Flush any buffered offline messages
                    mqttScope.launch {
                        messageRepository.flushToMqtt { topic, payload, qos, retained ->
                            publishDirect(topic, payload, qos, retained)
                        }
                    }
                }

                override fun connectionLost(cause: Throwable?) {
                    if (connectionGeneration != generation) return // stale — ignore
                    Log.w(TAG, "MQTT connection lost: ${cause?.message}")
                    agentLogger.warn(TAG, "MQTT connection lost: ${cause?.message}")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    scheduleReconnect()
                }

                override fun messageArrived(topic: String, message: MqttMessage) {
                    if (connectionGeneration != generation) return // stale — ignore
                    Log.d(TAG, "Message arrived on topic: $topic")
                    val callback = topicCallbacks.entries.firstOrNull { (pattern, _) ->
                        topicMatchesPattern(topic, pattern)
                    }?.value
                    callback?.invoke(topic, message.payload)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    // Delivery confirmation — no action needed
                }
            })

            suspendCancellableCoroutine<Unit> { cont ->
                client.connect(options, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        // Set state here (on the Paho callback thread) so it is
                        // visible immediately when the coroutine resumes, before
                        // connectComplete fires on a separate thread.
                        if (connectionGeneration == generation) {
                            _connectionState.value = ConnectionState.CONNECTED
                        }
                        cont.resume(Unit)
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        if (connectionGeneration == generation) {
                            _connectionState.value = ConnectionState.DISCONNECTED
                        }
                        cont.resumeWithException(
                            exception ?: Exception("MQTT connect failed")
                        )
                    }
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "MQTT connection error: ${e.message}")
            if (connectionGeneration == generation) {
                _connectionState.value = ConnectionState.DISCONNECTED
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnecting) return
        reconnecting = true

        mqttScope.launch {
            val delayMs = min(
                RECONNECT_MIN_DELAY_MS * (1L shl reconnectAttempt),
                RECONNECT_MAX_DELAY_MS
            )
            reconnectAttempt++
            Log.i(TAG, "Reconnecting in ${delayMs}ms (attempt $reconnectAttempt)")
            agentLogger.info(TAG, "Reconnecting in ${delayMs}ms (attempt $reconnectAttempt)")
            delay(delayMs)
            reconnecting = false

            if (prefs.isOnboarded()) {
                try {
                    connect()
                } catch (e: Exception) {
                    Log.e(TAG, "Reconnect attempt failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Publishes a message to the given MQTT topic.
     * Falls back to local buffering if the broker is unreachable.
     *
     * @param topic The MQTT topic.
     * @param payload The JSON payload string.
     * @param qos Quality of Service level (default 1).
     * @param retained Whether to retain the message on the broker (default false).
     */
    suspend fun publish(topic: String, payload: String, qos: Int = 1, retained: Boolean = false) {
        if (_connectionState.value == ConnectionState.CONNECTED) {
            val success = publishDirect(topic, payload, qos, retained)
            if (!success) {
                messageRepository.saveOfflineMessage(topic, payload, qos, retained)
            }
        } else {
            Log.d(TAG, "MQTT not connected, buffering message for topic: $topic")
            messageRepository.saveOfflineMessage(topic, payload, qos, retained)
        }
    }

    private suspend fun publishDirect(
        topic: String,
        payload: String,
        qos: Int,
        retained: Boolean
    ): Boolean {
        return try {
            val client = mqttClient ?: return false
            if (!client.isConnected) return false

            suspendCancellableCoroutine { cont ->
                val message = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
                    this.qos = qos
                    isRetained = retained
                }
                client.publish(topic, message, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        cont.resume(true)
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        Log.e(TAG, "Publish failed on $topic: ${exception?.message}")
                        cont.resume(false)
                    }
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "publishDirect exception: ${e.message}")
            false
        }
    }

    /**
     * Subscribes to an MQTT topic, storing the callback for re-subscription on reconnect.
     * @param topic The MQTT topic (supports wildcards: + and #).
     * @param callback Invoked with (actualTopic, payloadBytes) on message arrival.
     */
    fun subscribe(topic: String, callback: (String, ByteArray) -> Unit) {
        topicCallbacks[topic] = callback
        if (_connectionState.value == ConnectionState.CONNECTED) {
            subscribeInternal(topic, callback)
        }
    }

    private fun subscribeInternal(topic: String, callback: (String, ByteArray) -> Unit) {
        try {
            val client = mqttClient ?: return
            if (!client.isConnected) return

            client.subscribe(topic, 1, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "Subscribed to: $topic")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Subscribe failed for $topic: ${exception?.message}")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "subscribeInternal exception: ${e.message}")
        }
    }

    /**
     * Unsubscribes from a topic and removes the callback.
     */
    fun unsubscribe(topic: String) {
        topicCallbacks.remove(topic)
        try {
            mqttClient?.unsubscribe(topic)
        } catch (e: Exception) {
            Log.w(TAG, "Unsubscribe failed for $topic: ${e.message}")
        }
    }

    /**
     * Disconnects from the MQTT broker gracefully.
     */
    suspend fun disconnect() {
        try {
            val client = mqttClient ?: return
            if (client.isConnected) {
                suspendCancellableCoroutine<Unit> { cont ->
                    client.disconnect(null, object : IMqttActionListener {
                        override fun onSuccess(asyncActionToken: IMqttToken?) {
                            cont.resume(Unit)
                        }

                        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                            cont.resume(Unit) // Ignore disconnect failure
                        }
                    })
                }
            }
            client.close()
            mqttClient = null
        } catch (e: Exception) {
            Log.w(TAG, "Disconnect error: ${e.message}")
        } finally {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    /**
     * Performs the full onboarding registration flow:
     * 1. Connect with register credentials
     * 2. Subscribe to register_ack topic
     * 3. Publish register request
     * 4. Wait for acknowledgment
     * 5. Disconnect and reconnect with device credentials
     *
     * @param deviceId The generated UUID device identifier.
     * @param brokerUrl The MQTT broker URL.
     * @param registerSecret The registration secret.
     * @param registerPayload The registration payload map.
     * @return The received token on success, null on failure.
     */
    suspend fun performOnboarding(
        deviceId: String,
        brokerUrl: String,
        registerSecret: String,
        registerPayload: String
    ): String? {
        return try {
            connectWithRegisterCredentials(brokerUrl, registerSecret)

            if (_connectionState.value != ConnectionState.CONNECTED) {
                Log.e(TAG, "Failed to connect with register credentials")
                return null
            }

            var receivedToken: String? = null
            val ackTopic = "devices/$deviceId/register_ack"

            val tokenReceived = suspendCancellableCoroutine<String?> { cont ->
                subscribe(ackTopic) { _, payloadBytes ->
                    try {
                        val payloadStr = String(payloadBytes, Charsets.UTF_8)
                        val jsonObj = Json.parseToJsonElement(payloadStr)
                        val obj = jsonObj.jsonObject

                        // Reject error acks from the backend
                        val status = obj["status"]?.toString()?.trim('"')
                        if (status != "ok") {
                            val msg = obj["message"]?.toString()?.trim('"') ?: "unknown error"
                            Log.e(TAG, "Registration rejected by server: $msg")
                            unsubscribe(ackTopic)
                            cont.resume(null)
                            return@subscribe
                        }

                        // Extract token — guard against JSON null
                        val tokenElement = obj["token"]
                        val token = if (tokenElement == null || tokenElement is JsonNull) null
                                    else tokenElement.toString().trim('"')

                        if (token == null) {
                            Log.e(TAG, "Register ack ok but token is null")
                            unsubscribe(ackTopic)
                            cont.resume(null)
                            return@subscribe
                        }

                        Log.i(TAG, "Registration ACK received, token obtained")
                        unsubscribe(ackTopic)
                        cont.resume(token)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse register_ack: ${e.message}")
                        cont.resume(null)
                    }
                }

                // Publish the registration request
                mqttScope.launch {
                    publish("devices/register", registerPayload, qos = 1)
                }
            }

            receivedToken = tokenReceived

            disconnect()
            receivedToken
        } catch (e: Exception) {
            Log.e(TAG, "Onboarding failed: ${e.message}")
            null
        }
    }

    /**
     * Returns whether the MQTT client is currently connected.
     */
    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED

    private fun buildOfflinePayload(deviceId: String): String {
        return """{"device_id":"$deviceId","timestamp":"${Instant.now()}","status":"offline","version":"$APP_VERSION","uptime_sec":0}"""
    }

    private fun topicMatchesPattern(topic: String, pattern: String): Boolean {
        if (topic == pattern) return true
        val topicParts = topic.split("/")
        val patternParts = pattern.split("/")
        return matchesParts(topicParts, 0, patternParts, 0)
    }

    private fun matchesParts(
        topic: List<String>, ti: Int,
        pattern: List<String>, pi: Int
    ): Boolean {
        if (pi == pattern.size && ti == topic.size) return true
        if (pi == pattern.size) return false
        if (pattern[pi] == "#") return true
        if (ti == topic.size) return false
        if (pattern[pi] == "+" || pattern[pi] == topic[ti]) {
            return matchesParts(topic, ti + 1, pattern, pi + 1)
        }
        return false
    }
}
