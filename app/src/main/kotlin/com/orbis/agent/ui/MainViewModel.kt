package com.orbis.agent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orbis.agent.mqtt.MqttManager
import com.orbis.agent.prefs.OrbisPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * ViewModel for MainActivity.
 * Exposes MQTT connection state and device identity for the UI.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val mqttManager: MqttManager,
    private val prefs: OrbisPreferences
) : ViewModel() {

    /**
     * Observed connection state from MqttManager.
     * Values: DISCONNECTED, CONNECTING, CONNECTED.
     */
    val connectionState: StateFlow<MqttManager.ConnectionState> = mqttManager.connectionState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MqttManager.ConnectionState.DISCONNECTED
        )

    /**
     * The unique device identifier, empty if not onboarded.
     */
    val deviceId: String get() = prefs.deviceId

    /**
     * The broker URL currently configured.
     */
    val brokerUrl: String get() = prefs.brokerUrl

    /**
     * Returns true if the device has completed onboarding.
     */
    val isOnboarded: Boolean get() = prefs.isOnboarded()
}
