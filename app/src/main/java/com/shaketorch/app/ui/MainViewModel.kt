package com.shaketorch.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shaketorch.app.model.Sensitivity
import com.shaketorch.app.repository.SettingsRepository
import com.shaketorch.app.service.ShakeTorchService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(
    private val context: Context
) : ViewModel() {

    private val settingsRepository = SettingsRepository(context)

    private val _serviceBound = MutableStateFlow(false)
    val serviceBound: StateFlow<Boolean> = _serviceBound.asStateFlow()

    private var boundService: ShakeTorchService? = null
    private val manualTorch = com.shaketorch.app.service.TorchManager(context)
    private val _shakeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val shakeEvents = _shakeEvents.asSharedFlow()
    private val _sensorReady = MutableStateFlow(false)
    val sensorReady = _sensorReady.asStateFlow()
    private var serviceJobs = mutableListOf<kotlinx.coroutines.Job>()
    private var binding = false

    val isServiceEnabled: StateFlow<Boolean> = settingsRepository.isServiceEnabled
    val sensitivity: StateFlow<Sensitivity> = settingsRepository.sensitivity
    val isBatteryProtectionEnabled: StateFlow<Boolean> = settingsRepository.isBatteryProtectionEnabled
    val vibrateOnGesture: StateFlow<Boolean> = settingsRepository.vibrateOnGesture

    private val _isTorchOn = MutableStateFlow(false)
    val isTorchOn: StateFlow<Boolean> = _isTorchOn.asStateFlow()

    private val _isFlashAvailable = MutableStateFlow(true)
    val isFlashAvailable: StateFlow<Boolean> = _isFlashAvailable.asStateFlow()

    private val _torchErrorState = MutableStateFlow<String?>(null)
    val torchErrorState: StateFlow<String?> = _torchErrorState.asStateFlow()

    private val _batteryLevel = MutableStateFlow(100)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _isBatteryLow = MutableStateFlow(false)
    val isBatteryLow: StateFlow<Boolean> = _isBatteryLow.asStateFlow()

    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

    private val _isIgnoringBatteryOptimizations = MutableStateFlow(true)
    val isIgnoringBatteryOptimizations: StateFlow<Boolean> = _isIgnoringBatteryOptimizations.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? ShakeTorchService.LocalBinder ?: return
            val s = binder.getService()
            boundService = s
            _serviceBound.value = true
            binding = true
            serviceJobs.forEach { it.cancel() }
            serviceJobs.clear()
            serviceJobs += viewModelScope.launch { s.sensorReady.collect { _sensorReady.value = it } }
            serviceJobs += viewModelScope.launch { s.shakeEvents.collect { _shakeEvents.emit(Unit) } }

            serviceJobs += viewModelScope.launch {
                s.torchManager.isTorchOn.collect { _isTorchOn.value = it }
            }
            serviceJobs += viewModelScope.launch {
                s.torchManager.isFlashAvailable.collect { _isFlashAvailable.value = it }
            }
            serviceJobs += viewModelScope.launch {
                s.torchManager.torchErrorState.collect { _torchErrorState.value = it }
            }
            serviceJobs += viewModelScope.launch {
                s.batteryMonitor.batteryLevel.collect { _batteryLevel.value = it }
            }
            serviceJobs += viewModelScope.launch {
                s.batteryMonitor.isBatteryLow.collect { _isBatteryLow.value = it }
            }
            serviceJobs += viewModelScope.launch {
                s.batteryMonitor.isCharging.collect { _isCharging.value = it }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceJobs.forEach { it.cancel() }
            serviceJobs.clear()
            boundService = null
            binding = false
            _sensorReady.value = false
            _serviceBound.value = false
        }
    }

    init {
        checkBatteryOptimization()
        bindOrStartServiceIfEnabled()
        viewModelScope.launch { manualTorch.isTorchOn.collect { if (boundService == null) _isTorchOn.value = it } }
        viewModelScope.launch { manualTorch.isFlashAvailable.collect { if (boundService == null) _isFlashAvailable.value = it } }
        viewModelScope.launch { manualTorch.torchErrorState.collect { if (boundService == null) _torchErrorState.value = it } }
    }

    fun checkBatteryOptimization() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        _isIgnoringBatteryOptimizations.value = pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun openBatterySettings(activityContext: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        activityContext.startActivity(intent)
    }

    fun requestIgnoreBatteryOptimizations(activityContext: Context) {
        try {
            val intent = Intent().apply {
                action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            activityContext.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                activityContext.startActivity(intent)
            } catch (ex: Exception) {
                // Ignore fallback
            }
        }
    }

    fun bindOrStartServiceIfEnabled() {
        if (isServiceEnabled.value && !binding) {
            startAndBindService()
        }
    }

    fun toggleService() {
        val newState = !isServiceEnabled.value
        settingsRepository.setServiceEnabled(newState)
        if (newState) {
            startAndBindService()
        } else {
            stopAndUnbindService()
        }
    }

    private fun startAndBindService() {
        val intent = Intent(context, ShakeTorchService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        if (!binding) {
            binding = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun stopAndUnbindService() {
        if (binding) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                // Ignore
            }
            _serviceBound.value = false
            binding = false
            boundService = null
            _sensorReady.value = false
            serviceJobs.forEach { it.cancel() }
            serviceJobs.clear()
            _isTorchOn.value = manualTorch.isTorchOn.value
        }
        val intent = Intent(context, ShakeTorchService::class.java).apply {
            action = ShakeTorchService.ACTION_STOP_SERVICE
        }
        context.startService(intent)
    }

    fun toggleTorch() {
        (boundService?.torchManager ?: manualTorch).toggleTorch()
    }

    fun setSensitivity(sens: Sensitivity) {
        settingsRepository.setSensitivity(sens)
    }

    fun setBatteryProtection(enabled: Boolean) {
        settingsRepository.setBatteryProtectionEnabled(enabled)
    }

    fun setVibrateOnGesture(enabled: Boolean) {
        settingsRepository.setVibrateOnGesture(enabled)
    }

    override fun onCleared() {
        if (binding) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                // Ignore
            }
        }
        manualTorch.unregisterCallback()
        serviceJobs.forEach { it.cancel() }
        super.onCleared()
    }
}
