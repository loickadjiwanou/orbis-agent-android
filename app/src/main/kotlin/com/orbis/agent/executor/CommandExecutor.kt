package com.orbis.agent.executor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.orbis.agent.collector.DeviceDataCollector
import com.orbis.agent.logger.AgentLogger
import com.orbis.agent.model.Command
import com.orbis.agent.model.CommandResult
import com.orbis.agent.model.UpdateProgress
import com.orbis.agent.mqtt.MqttManager
import com.orbis.agent.prefs.OrbisPreferences
import com.orbis.agent.service.OrbisService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes commands received from the Orbis server via MQTT.
 *
 * Supported commands: collect_now, scan_network, restart_service, agent_update, get_info.
 * Each command follows the lifecycle: acknowledged → executing → success|failed.
 * Results are published using 'statut' (French) as per SESSION_0 contract.
 */
@Singleton
class CommandExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mqttManager: MqttManager,
    private val deviceDataCollector: DeviceDataCollector,
    private val prefs: OrbisPreferences,
    private val agentLogger: AgentLogger,
) {
    companion object {
        private const val TAG = "CommandExecutor"
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Executes a command following the full lifecycle:
     * acknowledged → executing → success|failed.
     * @param command The command to execute.
     */
    suspend fun executeCommand(command: Command) {
        val deviceId = prefs.deviceId
        agentLogger.info(TAG, "Executing command: ${command.type} (${command.commandId})")

        publishResult(command.commandId, deviceId, "acknowledged")

        when (command.type) {
            "collect_now" -> executeCollectNow(command, deviceId)
            "scan_network" -> executeScanNetwork(command, deviceId)
            "restart_service" -> executeRestartService(command, deviceId)
            "agent_update" -> executeAgentUpdate(command, deviceId)
            "get_info" -> executeGetInfo(command, deviceId)
            else -> {
                publishResult(
                    command.commandId, deviceId, "failed",
                    error = "Unknown command type: ${command.type}",
                    exitCode = 1
                )
            }
        }
    }

    private suspend fun executeCollectNow(command: Command, deviceId: String) {
        publishResult(command.commandId, deviceId, "executing")
        try {
            val heartbeat = deviceDataCollector.buildHeartbeat(deviceId)
            val heartbeatJson = json.encodeToString(heartbeat)
            mqttManager.publish("devices/$deviceId/status", heartbeatJson, qos = 1, retained = true)
            publishResult(
                command.commandId, deviceId, "success",
                output = "Heartbeat collected and published"
            )
        } catch (e: Exception) {
            agentLogger.error(TAG, "collect_now failed: ${e.message}")
            publishResult(command.commandId, deviceId, "failed", error = e.message ?: "Unknown error", exitCode = 1)
        }
    }

    private suspend fun executeScanNetwork(command: Command, deviceId: String) {
        publishResult(command.commandId, deviceId, "executing")
        try {
            val results = withContext(Dispatchers.IO) {
                performNetworkScan()
            }
            publishResult(
                command.commandId, deviceId, "success",
                output = results.joinToString("\n")
            )
        } catch (e: Exception) {
            agentLogger.error(TAG, "scan_network failed: ${e.message}")
            publishResult(command.commandId, deviceId, "failed", error = e.message ?: "Unknown error", exitCode = 1)
        }
    }

    private fun performNetworkScan(): List<String> {
        val reachableHosts = mutableListOf<String>()
        val commonPorts = listOf(22, 80, 443, 8080, 3000, 5000, 8443)

        // Scan common ports on local subnet (simplified scan)
        // Get local subnet prefix from system properties
        val localIp = getLocalIpAddress()
        if (localIp == null) {
            return listOf("No network connection available")
        }

        val subnetPrefix = localIp.substringBeforeLast(".")
        val timeoutMs = 500

        for (host in 1..254) {
            val ipStr = "$subnetPrefix.$host"
            try {
                val address = InetAddress.getByName(ipStr)
                if (address.isReachable(timeoutMs)) {
                    val openPorts = mutableListOf<Int>()
                    for (port in commonPorts) {
                        try {
                            Socket(ipStr, port).use {
                                openPorts.add(port)
                            }
                        } catch (_: Exception) {
                            // Port closed or filtered
                        }
                    }
                    if (openPorts.isNotEmpty()) {
                        reachableHosts.add("$ipStr: ports ${openPorts.joinToString(", ")}")
                    } else {
                        reachableHosts.add("$ipStr: reachable (no open ports)")
                    }
                }
            } catch (_: Exception) {
                // Host unreachable
            }
        }

        return if (reachableHosts.isEmpty()) {
            listOf("Scan complete: no reachable hosts found on $subnetPrefix.0/24")
        } else {
            reachableHosts
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            agentLogger.warn(TAG, "Failed to get local IP: ${e.message}")
        }
        return null
    }

    private suspend fun executeRestartService(command: Command, deviceId: String) {
        publishResult(command.commandId, deviceId, "executing")
        try {
            // Publish success before stopping to ensure it's sent
            publishResult(
                command.commandId, deviceId, "success",
                output = "OrbisService restart initiated"
            )
            // Stop and restart OrbisService
            val stopIntent = Intent(context, OrbisService::class.java)
            context.stopService(stopIntent)
            val startIntent = Intent(context, OrbisService::class.java)
            context.startForegroundService(startIntent)
        } catch (e: Exception) {
            agentLogger.error(TAG, "restart_service failed: ${e.message}")
            publishResult(command.commandId, deviceId, "failed", error = e.message ?: "Unknown error", exitCode = 1)
        }
    }

    private suspend fun executeAgentUpdate(command: Command, deviceId: String) {
        publishResult(command.commandId, deviceId, "executing")
        try {
            val apkUrl = command.payload["url"]
                ?: run {
                    publishResult(command.commandId, deviceId, "failed", error = "Missing 'url' in payload", exitCode = 1)
                    return
                }
            val expectedSha256 = command.payload["sha256"]
                ?: run {
                    publishResult(
                        command.commandId, deviceId, "failed",
                        error = "Missing 'sha256' in payload — update rejected for security",
                        exitCode = 1
                    )
                    return
                }

            // Step: downloading
            publishProgress(command.commandId, deviceId, "downloading", "Downloading $apkUrl")
            val apkFile = withContext(Dispatchers.IO) {
                downloadApk(apkUrl)
            }

            // Step: verifying (mandatory — update is rejected if sha256 does not match)
            publishProgress(command.commandId, deviceId, "verifying", "Verifying SHA-256")
            val actualSha256 = computeSha256(apkFile)
            if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                apkFile.delete()
                publishProgress(command.commandId, deviceId, "failed", error = "SHA256 mismatch: expected $expectedSha256, got $actualSha256")
                publishResult(
                    command.commandId, deviceId, "failed",
                    error = "SHA256 mismatch: expected $expectedSha256, got $actualSha256",
                    exitCode = 1
                )
                return
            }

            // Step: installing
            publishProgress(command.commandId, deviceId, "installing", "Launching install prompt")
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)

            publishProgress(command.commandId, deviceId, "success", "APK downloaded and install prompt launched")
            publishResult(
                command.commandId, deviceId, "success",
                output = "APK downloaded and install prompt launched"
            )
        } catch (e: Exception) {
            agentLogger.error(TAG, "agent_update failed: ${e.message}")
            publishProgress(command.commandId, deviceId, "failed", error = e.message ?: "Unknown error")
            publishResult(command.commandId, deviceId, "failed", error = e.message ?: "Unknown error", exitCode = 1)
        }
    }

    private fun downloadApk(url: String): File {
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code} downloading APK")
        }

        val apkFile = File(context.cacheDir, "update_${System.currentTimeMillis()}.apk")
        response.body?.byteStream()?.use { input ->
            apkFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw Exception("Empty response body")

        return apkFile
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read = input.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun executeGetInfo(command: Command, deviceId: String) {
        publishResult(command.commandId, deviceId, "executing")
        try {
            val heartbeat = deviceDataCollector.buildHeartbeat(deviceId)
            val info = buildString {
                appendLine("device_id: ${heartbeat.deviceId}")
                appendLine("hostname: ${heartbeat.hostname}")
                appendLine("plateforme: ${heartbeat.plateforme}")
                appendLine("os_version: ${heartbeat.osVersion}")
                appendLine("architecture: ${heartbeat.architecture}")
                appendLine("version: ${heartbeat.version}")
                appendLine("uptime_sec: ${heartbeat.uptimeSec}")
                appendLine("cpu_percent: ${heartbeat.cpuPercent}")
                appendLine("ram_percent: ${heartbeat.ramPercent}")
                appendLine("storage_percent: ${heartbeat.storagePercent}")
                appendLine("battery_level: ${heartbeat.batteryLevel}")
                appendLine("battery_charging: ${heartbeat.batteryCharging}")
                appendLine("network_type: ${heartbeat.networkType}")
            }
            publishResult(command.commandId, deviceId, "success", output = info.trim())
        } catch (e: Exception) {
            agentLogger.error(TAG, "get_info failed: ${e.message}")
            publishResult(command.commandId, deviceId, "failed", error = e.message ?: "Unknown error", exitCode = 1)
        }
    }

    private suspend fun publishProgress(
        commandId: String,
        deviceId: String,
        step: String,
        output: String = "",
        error: String = ""
    ) {
        val progress = UpdateProgress(
            commandId = commandId,
            deviceId  = deviceId,
            step      = step,
            output    = output.ifEmpty { null },
            error     = error.ifEmpty { null },
            timestamp = Instant.now().toString(),
        )
        val payload = json.encodeToString(progress)
        mqttManager.publish("devices/$deviceId/update_progress", payload, qos = 0)
        Log.d(TAG, "Published progress step=$step for $commandId")
    }

    private suspend fun publishResult(
        commandId: String,
        deviceId: String,
        statut: String,
        output: String = "",
        error: String = "",
        exitCode: Int = 0
    ) {
        val result = CommandResult(
            commandId = commandId,
            deviceId = deviceId,
            statut = statut,
            output = output,
            error = error,
            exitCode = exitCode,
            timestamp = Instant.now().toString()
        )
        val payload = json.encodeToString(result)
        mqttManager.publish("devices/$deviceId/results", payload, qos = 1)
        Log.d(TAG, "Published result for $commandId: statut=$statut")
    }
}
