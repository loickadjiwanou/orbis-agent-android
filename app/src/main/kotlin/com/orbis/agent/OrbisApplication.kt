package com.orbis.agent

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import com.orbis.agent.service.OrbisService
import com.orbis.agent.worker.HeartbeatWorker
import com.orbis.agent.worker.ServiceWatchdogWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application class for the Orbis agent.
 * Initializes Hilt, starts OrbisService, and schedules WorkManager jobs.
 */
@HiltAndroidApp
class OrbisApplication : Application(), Configuration.Provider {

    companion object {
        private const val TAG = "OrbisApplication"
    }

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "OrbisApplication starting")

        startOrbisService()
        scheduleWorkers()
    }

    private fun startOrbisService() {
        try {
            val intent = Intent(this, OrbisService::class.java)
            startForegroundService(intent)
            Log.i(TAG, "OrbisService start requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start OrbisService: ${e.message}")
        }
    }

    private fun scheduleWorkers() {
        val workManager = WorkManager.getInstance(this)

        workManager.enqueueUniquePeriodicWork(
            HeartbeatWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            HeartbeatWorker.buildPeriodicRequest()
        )

        workManager.enqueueUniquePeriodicWork(
            ServiceWatchdogWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            ServiceWatchdogWorker.buildPeriodicRequest()
        )

        Log.i(TAG, "WorkManager jobs scheduled")
    }
}
