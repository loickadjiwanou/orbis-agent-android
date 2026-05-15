package com.orbis.agent.worker

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import com.orbis.agent.service.OrbisService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * WorkManager watchdog worker that checks whether OrbisService is running.
 * If the service has stopped unexpectedly, this worker restarts it.
 * Scheduled every 15 minutes (WorkManager minimum interval).
 */
@HiltWorker
class ServiceWatchdogWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val WORK_NAME = "orbis_service_watchdog"
        private const val TAG = "ServiceWatchdogWorker"

        /**
         * Builds the periodic work request scheduled every 15 minutes.
         */
        fun buildPeriodicRequest(): PeriodicWorkRequest {
            return PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(15, TimeUnit.MINUTES)
                .build()
        }
    }

    override suspend fun doWork(): Result {
        return try {
            if (!isOrbisServiceRunning()) {
                Log.w(TAG, "OrbisService not running — restarting")
                val intent = Intent(context, OrbisService::class.java)
                context.startForegroundService(intent)
            } else {
                Log.d(TAG, "OrbisService is running — no action needed")
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "ServiceWatchdogWorker failed: ${e.message}")
            Result.retry()
        }
    }

    @Suppress("DEPRECATION")
    private fun isOrbisServiceRunning(): Boolean {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningServices = activityManager.getRunningServices(Int.MAX_VALUE)
        return runningServices.any { serviceInfo ->
            serviceInfo.service.className == OrbisService::class.java.name
        }
    }
}
