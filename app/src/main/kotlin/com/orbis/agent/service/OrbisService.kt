package com.orbis.agent.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.orbis.agent.R
import com.orbis.agent.collector.DeviceDataCollector
import com.orbis.agent.executor.CommandExecutor
import com.orbis.agent.mqtt.MqttManager
import com.orbis.agent.mqtt.MqttMessageHandler
import com.orbis.agent.prefs.OrbisPreferences
import com.orbis.agent.repository.MessageRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * The core Orbis agent foreground service.
 *
 * Responsibilities:
 * - Maintains persistent MQTT connection to the broker
 * - Publishes heartbeats every 30 seconds with QoS=1 retained=true
 * - Collects and publishes device logs every 60 seconds
 * - Flushes the local offline message buffer on start
 * - Schedules restart via AlarmManager on destruction (2s delay)
 * - Notification channel CHANNEL_ID="orbis_service"
 */
@AndroidEntryPoint
class OrbisService : LifecycleService() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "orbis_service"
        private const val TAG = "OrbisService"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val LOG_COLLECTION_INTERVAL_MS = 60_000L
        private const val RESTART_DELAY_MS = 2_000L
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }

    @Inject lateinit var mqttManager: MqttManager
    @Inject lateinit var mqttMessageHandler: MqttMessageHandler
    @Inject lateinit var deviceDataCollector: DeviceDataCollector
    @Inject lateinit var commandExecutor: CommandExecutor
    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var prefs: OrbisPreferences

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var initialized = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
        Log.i(TAG, "OrbisService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.i(TAG, "OrbisService started")

        if (!initialized) {
            initialized = true
            serviceScope.launch {
                initializeService()
            }
        } else {
            Log.d(TAG, "OrbisService already initialized, skipping duplicate start")
        }

        return START_STICKY
    }

    private suspend fun initializeService() {
        if (!prefs.isOnboarded()) {
            Log.w(TAG, "Device not onboarded, skipping MQTT connection")
            updateNotification("Not configured — open app to onboard")
            return
        }

        try {
            // Connect to MQTT
            mqttManager.connect()
            updateNotification("Connected")

            // Register command subscriptions
            mqttMessageHandler.registerSubscriptions()

            // Flush offline buffer
            messageRepository.flushToMqtt { topic, payload, qos, retained ->
                if (mqttManager.isConnected()) {
                    mqttManager.publish(topic, payload, qos, retained)
                    true
                } else {
                    false
                }
            }

            // Start heartbeat loop
            serviceScope.launch {
                heartbeatLoop()
            }

            // Start log collection loop
            serviceScope.launch {
                logCollectionLoop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Service initialization error: ${e.message}")
            updateNotification("Connection error — retrying")
        }
    }

    private suspend fun heartbeatLoop() {
        val deviceId = prefs.deviceId
        Log.i(TAG, "Heartbeat loop started for device: $deviceId")

        while (serviceScope.isActive) {
            try {
                val heartbeat = deviceDataCollector.buildHeartbeat(deviceId)
                val payload = json.encodeToString(heartbeat)
                mqttManager.publish("devices/$deviceId/status", payload, qos = 1, retained = true)
                Log.d(TAG, "Heartbeat published")
                updateNotification("Online — last heartbeat: ${java.time.Instant.now()}")
            } catch (e: Exception) {
                Log.e(TAG, "Heartbeat error: ${e.message}")
            }
            delay(HEARTBEAT_INTERVAL_MS)
        }
    }

    private suspend fun logCollectionLoop() {
        val deviceId = prefs.deviceId
        Log.i(TAG, "Log collection loop started")

        while (serviceScope.isActive) {
            try {
                delay(LOG_COLLECTION_INTERVAL_MS)
                val logEntry = deviceDataCollector.buildLogEntry(deviceId)
                val payload = json.encodeToString(logEntry)
                mqttManager.publish("devices/$deviceId/logs", payload, qos = 1)
                Log.d(TAG, "Log entry published")
            } catch (e: Exception) {
                Log.e(TAG, "Log collection error: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "OrbisService destroyed, scheduling restart")
        initialized = false
        mqttMessageHandler.unregisterSubscriptions()
        serviceScope.cancel()

        serviceScope.launch {
            mqttManager.disconnect()
        }

        scheduleRestart()
        super.onDestroy()
    }

    private fun scheduleRestart() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(applicationContext, OrbisService::class.java)
        val pendingIntent = PendingIntent.getForegroundService(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerTime = SystemClock.elapsedRealtime() + RESTART_DELAY_MS
        try {
            // setExact() requires explicit user approval on Android 12+ (API 31+).
            // Fall back to setAndAllowWhileIdle on SecurityException to avoid crash.
            alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pendingIntent)
            Log.i(TAG, "Restart scheduled (exact) in ${RESTART_DELAY_MS}ms")
        } catch (e: SecurityException) {
            Log.w(TAG, "setExact denied, falling back to setAndAllowWhileIdle: ${e.message}")
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pendingIntent)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Orbis Agent Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification for the Orbis monitoring agent"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): android.app.Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Orbis Agent")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_orbis_notification)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (pendingIntent != null) setContentIntent(pendingIntent)
            }
            .build()
    }

    private fun updateNotification(status: String) {
        val notification = buildNotification(status)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
