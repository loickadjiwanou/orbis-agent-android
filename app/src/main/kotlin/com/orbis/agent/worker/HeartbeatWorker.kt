package com.orbis.agent.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import com.orbis.agent.collector.DeviceDataCollector
import com.orbis.agent.mqtt.MqttManager
import com.orbis.agent.prefs.OrbisPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that publishes a heartbeat to the MQTT broker.
 * Scheduled every 15 minutes (WorkManager minimum interval).
 * Note: The actual 30-second heartbeat loop is managed by OrbisService;
 * this worker acts as a backup to ensure periodic updates even if the service restarts.
 */
@HiltWorker
class HeartbeatWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val mqttManager: MqttManager,
    private val deviceDataCollector: DeviceDataCollector,
    private val prefs: OrbisPreferences
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val WORK_NAME = "orbis_heartbeat_worker"
        private const val TAG = "HeartbeatWorker"
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        /**
         * Builds the periodic work request scheduled every 15 minutes.
         */
        fun buildPeriodicRequest(): PeriodicWorkRequest {
            return PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
                .build()
        }
    }

    override suspend fun doWork(): Result {
        if (!prefs.isOnboarded()) {
            Log.d(TAG, "Device not onboarded, skipping heartbeat")
            return Result.success()
        }

        return try {
            val deviceId = prefs.deviceId
            val heartbeat = deviceDataCollector.buildHeartbeat(deviceId)
            val payload = json.encodeToString(heartbeat)
            mqttManager.publish("devices/$deviceId/status", payload, qos = 1, retained = true)
            Log.d(TAG, "HeartbeatWorker: heartbeat published for $deviceId")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "HeartbeatWorker failed: ${e.message}")
            Result.retry()
        }
    }
}
