package com.shaketorch.app.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.shaketorch.app.MainActivity
import com.shaketorch.app.R
import com.shaketorch.app.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service for best-effort background gesture detection.
 */
class ShakeTorchService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var sensorManager: SensorManager
    private lateinit var powerManager: PowerManager
    private var tempWakeLock: PowerManager.WakeLock? = null

    lateinit var torchManager: TorchManager
        private set

    lateinit var batteryMonitor: BatteryMonitor
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    private var shakeDetector: ShakeDetector? = null
    private val _shakeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val shakeEvents = _shakeEvents.asSharedFlow()
    private val _sensorReady = MutableStateFlow(false)
    val sensorReady: StateFlow<Boolean> = _sensorReady.asStateFlow()
    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "key_sensitivity") shakeDetector?.updateSensitivity(
            com.shaketorch.app.model.Sensitivity.fromName(
                getSharedPreferences("shake_torch_prefs", MODE_PRIVATE).getString(key, "MEDIUM")
            )
        )
    }

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private var isScreenOn = true

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    registerSensorListener(lowPower = true)
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    registerSensorListener(lowPower = false)
                }
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): ShakeTorchService = this@ShakeTorchService
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        settingsRepository = SettingsRepository(applicationContext)
        getSharedPreferences("shake_torch_prefs", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(preferenceListener)
        torchManager = TorchManager(applicationContext)

        batteryMonitor = BatteryMonitor(applicationContext) {
            onBatteryProtectionTriggered()
        }

        initializeShakeDetector()
        batteryMonitor.startMonitoring()
        registerScreenStateReceiver()

        serviceScope.launch {
            settingsRepository.sensitivity.collect { sensitivity ->
                shakeDetector?.updateSensitivity(sensitivity)
            }
        }

        serviceScope.launch {
            torchManager.isTorchOn.collect { isOn ->
                updateNotification(isOn)
            }
        }
    }

    private fun registerScreenStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenStateReceiver, filter)
    }

    private fun unregisterScreenStateReceiver() {
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            // Ignored
        }
    }

    private fun initializeShakeDetector() {
        shakeDetector = ShakeDetector(
            sensitivity = settingsRepository.sensitivity.value,
            onShakeDetected = {
                handleShakeGesture()
            }
        )
    }

    private fun handleShakeGesture() {
        acquireBriefWakeLock()
        _shakeEvents.tryEmit(Unit)

        val isLowBattery = batteryMonitor.isBatteryLow.value
        val isProtectionActive = settingsRepository.isBatteryProtectionEnabled.value

        if (isLowBattery && isProtectionActive && !batteryMonitor.isCharging.value) {
            if (torchManager.isTorchOn.value) {
                torchManager.setTorchEnabled(false)
            }
            // Avoid transient background toast on a locked device.
            triggerVibration(longArrayOf(0, 100, 100, 100))
            return
        }

        val success = torchManager.toggleTorch()
        if (success) {
            if (settingsRepository.vibrateOnGesture.value) {
                triggerVibration(longArrayOf(0, 80, 50, 80))
            }
        }
    }

    private fun onBatteryProtectionTriggered() {
        if (settingsRepository.isBatteryProtectionEnabled.value) {
            if (torchManager.isTorchOn.value) {
                torchManager.setTorchEnabled(false)
            }
            Toast.makeText(
                applicationContext,
                "ShakeTorch: Torch auto-turned off (Battery < 15%)",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopSelfService()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_TORCH -> {
                handleShakeGesture()
                return START_STICKY
            }
        }

        startForegroundWithNotification(torchManager.isTorchOn.value)
        registerSensorListener(lowPower = !powerManager.isInteractive)
        _isServiceRunning.value = true
        activeServiceInstance = this

        return START_STICKY
    }

    private fun registerSensorListener(lowPower: Boolean) {
        val detector = shakeDetector ?: return

        var sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (sensor == null) {
            sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }

        if (sensor != null) {
            sensorManager.unregisterListener(detector)
            val samplingRate = if (lowPower) SensorManager.SENSOR_DELAY_NORMAL else SensorManager.SENSOR_DELAY_GAME
            val maxLatency = if (lowPower) 200_000 else 0

            _sensorReady.value = sensorManager.registerListener(
                detector,
                sensor,
                samplingRate,
                maxLatency
            )
        } else { _sensorReady.value = false }
    }

    private fun unregisterSensorListener() {
        shakeDetector?.let { detector ->
            sensorManager.unregisterListener(detector)
        }
        _sensorReady.value = false
    }

    private fun acquireBriefWakeLock() {
        try {
            tempWakeLock?.release()
            tempWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ShakeTorch::BriefWakeLock"
            ).apply {
                acquire(2500L)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun startForegroundWithNotification(isTorchOn: Boolean) {
        createNotificationChannel()
        val notification = buildNotification(isTorchOn)
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(isTorchOn: Boolean) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(isTorchOn))
    }

    private fun buildNotification(isTorchOn: Boolean): android.app.Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleTorchIntent = Intent(this, ShakeTorchService::class.java).apply {
            action = ACTION_TOGGLE_TORCH
        }
        val pendingToggle = PendingIntent.getService(
            this, 1, toggleTorchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isTorchOn) "ShakeTorch: TORCH ON" else "ShakeTorch Active"
        val text = if (isTorchOn) "Flashlight active — Double-chop or tap to turn off" else "Listening for double-chop gesture"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingOpenApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(
                0,
                if (isTorchOn) "Turn Off Torch" else "Toggle Torch",
                pendingToggle
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ShakeTorch Background Listener",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Shows when gesture listening is active"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun triggerVibration(pattern: LongArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, -1)
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (settingsRepository.isServiceEnabled.value) {
            val restartServiceIntent = Intent(applicationContext, ShakeTorchService::class.java).apply {
                setPackage(packageName)
            }
            val restartServicePendingIntent = PendingIntent.getService(
                applicationContext, 1, restartServiceIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmService = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmService.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 1000,
                restartServicePendingIntent
            )
        }
    }

    private fun stopSelfService() {
        _isServiceRunning.value = false
        settingsRepository.setServiceEnabled(false)
        unregisterSensorListener()
        unregisterScreenStateReceiver()
        batteryMonitor.stopMonitoring()
        torchManager.unregisterCallback()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        activeServiceInstance = null
    }

    override fun onDestroy() {
        _isServiceRunning.value = false
        unregisterSensorListener()
        unregisterScreenStateReceiver()
        batteryMonitor.stopMonitoring()
        torchManager.unregisterCallback()
        activeServiceInstance = null
        getSharedPreferences("shake_torch_prefs", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(preferenceListener)
        serviceScope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    fun getShakeDetector(): ShakeDetector? = shakeDetector

    companion object {
        const val CHANNEL_ID = "shake_torch_background_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_SERVICE = "com.shaketorch.app.ACTION_START"
        const val ACTION_STOP_SERVICE = "com.shaketorch.app.ACTION_STOP"
        const val ACTION_TOGGLE_TORCH = "com.shaketorch.app.ACTION_TOGGLE_TORCH"

        var activeServiceInstance: ShakeTorchService? = null
            private set
    }
}
