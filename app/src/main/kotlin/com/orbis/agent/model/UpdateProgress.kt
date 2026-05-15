package com.orbis.agent.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Payload published on devices/{device_id}/update_progress during an OTA update.
 * The dashboard Live Progress panel subscribes to the WebSocket event
 * agent_update_progress which relays these messages.
 */
@Serializable
data class UpdateProgress(
    @SerialName("command_id") val commandId: String,
    @SerialName("device_id")  val deviceId: String,
    @SerialName("statut")     val statut: String = "executing",
    @SerialName("step")       val step: String,
    @SerialName("output")     val output: String? = null,
    @SerialName("error")      val error: String? = null,
    @SerialName("timestamp")  val timestamp: String,
)
