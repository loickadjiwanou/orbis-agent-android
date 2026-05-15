package com.orbis.agent.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.orbis.agent.service.OrbisService

/**
 * Broadcast receiver that restarts OrbisService after the app package is updated.
 * Listens for MY_PACKAGE_REPLACED to handle agent_update restarts cleanly.
 */
class PackageReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PackageReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.i(TAG, "Package replaced, restarting OrbisService")
            try {
                val serviceIntent = Intent(context, OrbisService::class.java)
                context.startForegroundService(serviceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start OrbisService after package replace: ${e.message}")
            }
        }
    }
}
