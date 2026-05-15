package com.orbis.agent.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents the full Android heartbeat payload sent to the MQTT broker.
 * All fields are required as per SESSION_0 contract.
 */
@Serializable
data class DeviceStatus(
    @SerialName("device_id") val deviceId: String,
    @SerialName("timestamp") val timestamp: String,
    @SerialName("status") val status: String,
    @SerialName("version") val version: String,
    @SerialName("uptime_sec") val uptimeSec: Long,
    @SerialName("hostname") val hostname: String,
    @SerialName("plateforme") val plateforme: String = "android",
    @SerialName("os_version") val osVersion: String,
    @SerialName("architecture") val architecture: String,
    @SerialName("cpu_percent") val cpuPercent: Float,
    @SerialName("ram_percent") val ramPercent: Float,
    @SerialName("storage_percent") val storagePercent: Float,
    @SerialName("battery_level") val batteryLevel: Int,
    @SerialName("battery_charging") val batteryCharging: Boolean,
    @SerialName("network_type") val networkType: String
)
