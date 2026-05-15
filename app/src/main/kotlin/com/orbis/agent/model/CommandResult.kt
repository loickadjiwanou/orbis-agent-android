package com.orbis.agent.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents the result of a command execution sent back to the Orbis server.
 * Uses 'statut' (French) as per SESSION_0 contract.
 * Lifecycle: acknowledged → executing → success|failed
 */
@Serializable
data class CommandResult(
    @SerialName("command_id") val commandId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("statut") val statut: String,
    @SerialName("output") val output: String = "",
    @SerialName("error") val error: String = "",
    @SerialName("exit_code") val exitCode: Int = 0,
    @SerialName("timestamp") val timestamp: String
)
