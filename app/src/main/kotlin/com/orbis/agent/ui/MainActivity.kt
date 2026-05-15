package com.orbis.agent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.orbis.agent.R
import com.orbis.agent.databinding.ActivityMainBinding
import com.orbis.agent.mqtt.MqttManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Main activity that shows the agent connection status, device ID, and last heartbeat time.
 * Redirects to OnboardingActivity if the device is not yet configured.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val viewModel: MainViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "Notification permission granted: $granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Check onboarding status
        if (!viewModel.isOnboarded) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            return
        }

        setupUI()
        observeViewModel()
    }

    private fun setupUI() {
        binding.textDeviceId.text = viewModel.deviceId
        binding.textBrokerUrl.text = viewModel.brokerUrl
        binding.textLastHeartbeat.text = "—"

        binding.buttonBatteryOptimization.setOnClickListener {
            startActivity(Intent(this, BatteryOptimizationActivity::class.java))
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.connectionState.collect { state ->
                    val statusText = when (state) {
                        MqttManager.ConnectionState.CONNECTED -> "Connected"
                        MqttManager.ConnectionState.CONNECTING -> "Connecting…"
                        MqttManager.ConnectionState.DISCONNECTED -> "Disconnected"
                    }
                    binding.textConnectionStatus.text = statusText

                    val dotColor = when (state) {
                        MqttManager.ConnectionState.CONNECTED ->
                            ContextCompat.getColor(this@MainActivity, R.color.status_connected)
                        MqttManager.ConnectionState.CONNECTING ->
                            ContextCompat.getColor(this@MainActivity, R.color.status_connecting)
                        MqttManager.ConnectionState.DISCONNECTED ->
                            ContextCompat.getColor(this@MainActivity, R.color.status_disconnected)
                    }
                    (binding.viewStatusDot.background as? GradientDrawable)?.setColor(dotColor)

                    if (state == MqttManager.ConnectionState.CONNECTED) {
                        val formatter = DateTimeFormatter
                            .ofPattern("HH:mm:ss")
                            .withZone(ZoneId.systemDefault())
                        binding.textLastHeartbeat.text = formatter.format(Instant.now())
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh device ID display in case onboarding just completed
        if (viewModel.isOnboarded) {
            binding.textDeviceId.text = viewModel.deviceId
        }
    }
}
