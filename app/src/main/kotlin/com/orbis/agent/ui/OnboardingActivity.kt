package com.orbis.agent.ui

import android.content.Intent
import android.os.Bundle
import com.orbis.agent.service.OrbisService
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.orbis.agent.databinding.ActivityOnboardingBinding
import com.orbis.agent.prefs.OrbisPreferences
import com.orbis.agent.repository.DeviceRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Onboarding activity for initial device configuration.
 * Collects broker URL and register secret, then performs the registration handshake.
 */
@AndroidEntryPoint
class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OnboardingActivity"
    }

    @Inject lateinit var deviceRepository: DeviceRepository
    @Inject lateinit var prefs: OrbisPreferences

    private lateinit var binding: ActivityOnboardingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Pre-fill with existing values if available
        binding.editBrokerUrl.setText(prefs.brokerUrl)
        binding.editRegisterSecret.setText(prefs.registerSecret)

        binding.buttonConfigure.setOnClickListener {
            startOnboarding()
        }
    }

    private fun startOnboarding() {
        val brokerUrl = binding.editBrokerUrl.text.toString().trim()
        val registerSecret = binding.editRegisterSecret.text.toString().trim()

        if (brokerUrl.isEmpty()) {
            binding.editBrokerUrl.error = "Broker URL is required"
            return
        }

        if (registerSecret.isEmpty()) {
            binding.editRegisterSecret.error = "Register secret is required"
            return
        }

        // Save config to prefs
        deviceRepository.saveBrokerConfig(brokerUrl, registerSecret)

        // Show loading state
        binding.buttonConfigure.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.textStatus.text = "Connecting to broker..."

        lifecycleScope.launch {
            val result = deviceRepository.performOnboarding()

            when (result) {
                is DeviceRepository.OrbisResult.Success -> {
                    Log.i(TAG, "Onboarding successful: device_id=${result.data}")
                    binding.textStatus.text = "Registration successful!"
                    Toast.makeText(
                        this@OnboardingActivity,
                        "Device registered successfully",
                        Toast.LENGTH_SHORT
                    ).show()

                    // Restart OrbisService so it picks up the saved credentials.
                    // The service started at app launch before onboarding was done and
                    // returned early from initializeService(). Restarting it now lets it
                    // run the full flow: connect MQTT, start heartbeat loop, etc.
                    val serviceIntent = Intent(this@OnboardingActivity, OrbisService::class.java)
                    stopService(serviceIntent)
                    startForegroundService(serviceIntent)

                    // Launch MainActivity
                    val intent = Intent(this@OnboardingActivity, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }

                is DeviceRepository.OrbisResult.Failure -> {
                    Log.e(TAG, "Onboarding failed: ${result.error}")
                    binding.textStatus.text = "Error: ${result.error}"
                    binding.buttonConfigure.isEnabled = true
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@OnboardingActivity,
                        "Registration failed: ${result.error}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
