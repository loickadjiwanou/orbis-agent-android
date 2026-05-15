package com.orbis.agent.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a single log entry sent from the device to the Orbis server.
 * Log batches are limited to 100 entries per message, max 500KB.
 */
@Serializable
data class LogEntry(
    @SerialName("device_id") val deviceId: String,
    @SerialName("timestamp") val timestamp: String,
    @SerialName("level") val level: String,
    @SerialName("source") val source: String,
    @SerialName("message") val message: String,
    @SerialName("metadata") val metadata: Map<String, String> = emptyMap()
)
