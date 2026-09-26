package com.shaketorch.app.repository

import android.content.Context
import android.content.SharedPreferences
import com.shaketorch.app.model.Sensitivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repository for managing application preferences and settings.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("shake_torch_prefs", Context.MODE_PRIVATE)

    private val _isServiceEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_SERVICE_ENABLED, false)
    )
    val isServiceEnabled: StateFlow<Boolean> = _isServiceEnabled.asStateFlow()

    private val _sensitivity = MutableStateFlow(
        Sensitivity.fromName(prefs.getString(KEY_SENSITIVITY, Sensitivity.MEDIUM.name))
    )
    val sensitivity: StateFlow<Sensitivity> = _sensitivity.asStateFlow()

    private val _isBatteryProtectionEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_BATTERY_PROTECTION, true)
    )
    val isBatteryProtectionEnabled: StateFlow<Boolean> = _isBatteryProtectionEnabled.asStateFlow()

    private val _vibrateOnGesture = MutableStateFlow(
        prefs.getBoolean(KEY_VIBRATE_ON_GESTURE, true)
    )
    val vibrateOnGesture: StateFlow<Boolean> = _vibrateOnGesture.asStateFlow()

    fun setServiceEnabled(enabled: Boolean) {
        _isServiceEnabled.value = enabled
        prefs.edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
    }

    fun setSensitivity(sensitivity: Sensitivity) {
        _sensitivity.value = sensitivity
        prefs.edit().putString(KEY_SENSITIVITY, sensitivity.name).apply()
    }

    fun setBatteryProtectionEnabled(enabled: Boolean) {
        _isBatteryProtectionEnabled.value = enabled
        prefs.edit().putBoolean(KEY_BATTERY_PROTECTION, enabled).apply()
    }

    fun setVibrateOnGesture(vibrate: Boolean) {
        _vibrateOnGesture.value = vibrate
        prefs.edit().putBoolean(KEY_VIBRATE_ON_GESTURE, vibrate).apply()
    }

    companion object {
        private const val KEY_SERVICE_ENABLED = "key_service_enabled"
        private const val KEY_SENSITIVITY = "key_sensitivity"
        private const val KEY_BATTERY_PROTECTION = "key_battery_protection"
        private const val KEY_VIBRATE_ON_GESTURE = "key_vibrate_on_gesture"
    }
}
