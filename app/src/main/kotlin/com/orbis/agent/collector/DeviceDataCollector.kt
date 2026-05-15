package com.orbis.agent.collector

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.app.ActivityManager
import android.util.Log
import com.orbis.agent.model.DeviceStatus
import com.orbis.agent.model.LogEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.FileReader
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collects all device metrics for heartbeat and log payloads.
 * Provides battery, CPU, RAM, storage, network and system information.
 */
@Singleton
class DeviceDataCollector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "DeviceDataCollector"
        private const val APP_VERSION = "1.0.0"
    }

    /**
     * Returns the current battery level as a percentage (0-100).
     */
    fun getBatteryLevel(): Int {
        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return -1

        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)

        return if (level >= 0 && scale > 0) {
            (level * 100 / scale)
        } else {
            -1
        }
    }

    /**
     * Returns true if the battery is currently charging or fully charged.
     */
    fun isBatteryCharging(): Boolean {
        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return false

        val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }

    /**
     * Returns the current active network type as a string.
     * Possible values: "WIFI", "MOBILE", "ETHERNET", "NONE".
     */
    fun getNetworkType(): String {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val network = connectivityManager.activeNetwork ?: return "NONE"
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "NONE"

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "NONE"
        }
    }

    /**
     * Reads /proc/stat twice with a 200ms gap and computes CPU usage percentage.
     * Returns a value between 0.0 and 100.0.
     */
    suspend fun getCpuUsage(): Float = withContext(Dispatchers.IO) {
        try {
            val stats1 = readCpuStats()
            delay(200)
            val stats2 = readCpuStats()

            val totalDelta = stats2.total - stats1.total
            val idleDelta = stats2.idle - stats1.idle

            if (totalDelta <= 0) return@withContext 0f

            val usage = (1.0f - idleDelta.toFloat() / totalDelta.toFloat()) * 100f
            usage.coerceIn(0f, 100f)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read CPU stats: ${e.message}")
            0f
        }
    }

    private data class CpuStats(val total: Long, val idle: Long)

    private fun readCpuStats(): CpuStats {
        val reader = BufferedReader(FileReader("/proc/stat"))
        val line = reader.readLine()
        reader.close()

        val parts = line.trim().split("\\s+".toRegex())
        // cpu  user nice system idle iowait irq softirq steal guest guest_nice
        val user = parts[1].toLong()
        val nice = parts[2].toLong()
        val system = parts[3].toLong()
        val idle = parts[4].toLong()
        val iowait = parts[5].toLong()
        val irq = parts[6].toLong()
        val softirq = parts[7].toLong()
        val steal = if (parts.size > 8) parts[8].toLong() else 0L

        val total = user + nice + system + idle + iowait + irq + softirq + steal
        return CpuStats(total, idle)
    }

    /**
     * Returns RAM usage as a percentage (0.0-100.0).
     */
    fun getRamUsage(): Float {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        if (memInfo.totalMem <= 0) return 0f

        val usedMem = memInfo.totalMem - memInfo.availMem
        return (usedMem.toFloat() / memInfo.totalMem.toFloat()) * 100f
    }

    /**
     * Returns internal storage usage as a percentage (0.0-100.0).
     */
    fun getStorageUsage(): Float {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalBytes = stat.totalBytes
            val freeBytes = stat.freeBytes

            if (totalBytes <= 0) return 0f

            val usedBytes = totalBytes - freeBytes
            (usedBytes.toFloat() / totalBytes.toFloat()) * 100f
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read storage stats: ${e.message}")
            0f
        }
    }

    /**
     * Returns the device uptime in seconds since last boot.
     */
    fun getUptimeSec(): Long {
        return SystemClock.elapsedRealtime() / 1000L
    }

    /**
     * Returns the device model name as hostname identifier.
     */
    fun getHostname(): String {
        return Build.MODEL
    }

    /**
     * Returns the Android OS version string with API level.
     * Example: "Android 13 (API 33)"
     */
    fun getOsVersion(): String {
        return "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }

    /**
     * Returns the primary CPU architecture from supported ABIs.
     */
    fun getArchitecture(): String {
        return Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
    }

    /**
     * Builds the full heartbeat payload as [DeviceStatus] with all required Android fields.
     * @param deviceId The unique device identifier.
     */
    suspend fun buildHeartbeat(deviceId: String): DeviceStatus {
        val cpuPercent = getCpuUsage()
        return DeviceStatus(
            deviceId = deviceId,
            timestamp = Instant.now().toString(),
            status = "online",
            version = APP_VERSION,
            uptimeSec = getUptimeSec(),
            hostname = getHostname(),
            plateforme = "android",
            osVersion = getOsVersion(),
            architecture = getArchitecture(),
            cpuPercent = cpuPercent,
            ramPercent = getRamUsage(),
            storagePercent = getStorageUsage(),
            batteryLevel = getBatteryLevel(),
            batteryCharging = isBatteryCharging(),
            networkType = getNetworkType()
        )
    }

    /**
     * Builds a log entry containing current device metrics.
     * @param deviceId The unique device identifier.
     */
    suspend fun buildLogEntry(deviceId: String): LogEntry {
        val cpuPercent = getCpuUsage()
        return LogEntry(
            deviceId = deviceId,
            timestamp = Instant.now().toString(),
            level = "INFO",
            source = "DeviceDataCollector",
            message = "Device metrics collected",
            metadata = mapOf(
                "cpu_percent" to cpuPercent.toString(),
                "ram_percent" to getRamUsage().toString(),
                "storage_percent" to getStorageUsage().toString(),
                "battery_level" to getBatteryLevel().toString(),
                "battery_charging" to isBatteryCharging().toString(),
                "network_type" to getNetworkType(),
                "uptime_sec" to getUptimeSec().toString()
            )
        )
    }

    /**
     * Builds the registration payload for the onboarding handshake.
     * @param deviceId The unique device identifier (generated UUID).
     */
    fun buildRegisterPayload(deviceId: String): Map<String, Any> {
        return mapOf(
            "device_id" to deviceId,
            "hostname" to getHostname(),
            "plateforme" to "android",
            "os_version" to getOsVersion(),
            "architecture" to getArchitecture(),
            "version_agent" to APP_VERSION,
            "timestamp" to Instant.now().toString()
        )
    }
}
