package com.orbis.agent.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.orbis.agent.service.OrbisService

/**
 * Broadcast receiver that starts OrbisService on device boot.
 * Handles both BOOT_COMPLETED (after full unlock) and
 * LOCKED_BOOT_COMPLETED (direct boot, before user unlock).
 * Declared as directBootAware="true" in the manifest.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED" -> {
                Log.i(TAG, "Boot completed (action=${intent.action}), starting OrbisService")
                try {
                    val serviceIntent = Intent(context, OrbisService::class.java)
                    context.startForegroundService(serviceIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start OrbisService on boot: ${e.message}")
                }
            }
        }
    }
}
