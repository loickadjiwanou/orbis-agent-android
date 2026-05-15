package com.orbis.agent.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a command received from the Orbis server via MQTT.
 * Supported types: collect_now, scan_network, restart_service, agent_update, get_info.
 */
@Serializable
data class Command(
    @SerialName("command_id") val commandId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("type") val type: String,
    @SerialName("payload") val payload: Map<String, String> = emptyMap(),
    @SerialName("timeout_sec") val timeoutSec: Int = 60,
    @SerialName("timestamp") val timestamp: String
)
