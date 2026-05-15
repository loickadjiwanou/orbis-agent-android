package com.orbis.agent.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.orbis.agent.databinding.ActivityBatteryOptimizationBinding
import dagger.hilt.android.AndroidEntryPoint

/**
 * Activity providing instructions for disabling battery optimization.
 * Includes manufacturer-specific guidance for MIUI, Samsung, and OnePlus.
 * Provides a button to open system battery optimization settings.
 */
@AndroidEntryPoint
class BatteryOptimizationActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BatteryOptimizationActivity"
    }

    private lateinit var binding: ActivityBatteryOptimizationBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatteryOptimizationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupManufacturerInstructions()

        binding.buttonOpenBatterySettings.setOnClickListener {
            openBatterySettings()
        }

        binding.buttonOpenAppSettings.setOnClickListener {
            openAppSettings()
        }
    }

    private fun setupManufacturerInstructions() {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val instructions = buildString {
            appendLine("To keep Orbis Agent running in the background, disable battery optimization:")
            appendLine()

            when {
                manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> {
                    appendLine("MIUI (Xiaomi/Redmi):")
                    appendLine("1. Go to Settings → Apps → Manage Apps")
                    appendLine("2. Find 'Orbis Agent' → Battery Saver")
                    appendLine("3. Select 'No restrictions'")
                    appendLine("4. Also: Settings → Battery & Performance → App Battery Saver → Orbis Agent → No restrictions")
                    appendLine("5. Enable 'Autostart' for Orbis Agent in Security app")
                }

                manufacturer.contains("samsung") -> {
                    appendLine("Samsung (One UI):")
                    appendLine("1. Go to Settings → Battery → Background Usage Limits")
                    appendLine("2. Tap 'Never sleeping apps' → Add Orbis Agent")
                    appendLine("3. Also: Settings → Apps → Orbis Agent → Battery → Unrestricted")
                    appendLine("4. Disable 'Put unused apps to sleep' if enabled")
                }

                manufacturer.contains("oneplus") || manufacturer.contains("oppo") -> {
                    appendLine("OnePlus/OPPO (OxygenOS/ColorOS):")
                    appendLine("1. Go to Settings → Battery → Battery Optimization")
                    appendLine("2. Find Orbis Agent → Don't Optimize")
                    appendLine("3. Settings → Apps → App Management → Orbis Agent → Battery → Allow background activity")
                    appendLine("4. Check Phone Manager → Startup Manager and enable Orbis Agent")
                }

                manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                    appendLine("Huawei/Honor (EMUI):")
                    appendLine("1. Go to Phone Manager → Protected Apps → enable Orbis Agent")
                    appendLine("2. Settings → Apps → Orbis Agent → Battery → Remove from power-intensive prompt")
                    appendLine("3. Settings → Battery → App Launch → Orbis Agent → Manage Manually → enable all")
                }

                else -> {
                    appendLine("Standard Android:")
                    appendLine("1. Go to Settings → Apps → Orbis Agent → Battery")
                    appendLine("2. Select 'Unrestricted' or 'Don't optimize'")
                    appendLine("3. Settings → Battery → Battery Optimization → All Apps → Orbis Agent → Don't Optimize")
                }
            }

            appendLine()
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        }

        binding.textInstructions.text = instructions
    }

    private fun openBatterySettings() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open battery optimization settings: ${e.message}")
            try {
                // Fallback to general battery settings
                startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            } catch (e2: Exception) {
                Log.e(TAG, "Cannot open any battery settings: ${e2.message}")
                Toast.makeText(
                    this,
                    "Please manually disable battery optimization in Settings → Apps → Orbis Agent → Battery",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open app settings: ${e.message}")
            Toast.makeText(this, "Unable to open app settings", Toast.LENGTH_SHORT).show()
        }
    }
}
