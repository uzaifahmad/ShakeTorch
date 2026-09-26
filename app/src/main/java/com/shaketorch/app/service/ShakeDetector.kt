package com.shaketorch.app.service

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import com.shaketorch.app.model.Sensitivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * Bulletproof double-chop gesture detector.
 * Handles continuous rapid chopping without breaking the internal state machine.
 */
class ShakeDetector(
    private var sensitivity: Sensitivity = Sensitivity.MEDIUM,
    private val onShakeDetected: () -> Unit
) : SensorEventListener {

    private val _currentMagnitude = MutableStateFlow(0f)
    val currentMagnitude: StateFlow<Float> = _currentMagnitude.asStateFlow()

    private val _detectedShakeCount = MutableStateFlow(0)
    val detectedShakeCount: StateFlow<Int> = _detectedShakeCount.asStateFlow()

    // High-pass filter variables
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var isGravityInitialized = false

    // State machine tracking
    private var firstChopTime: Long = 0L
    private var lastPeakTime: Long = 0L
    private var chopState = ChopState.IDLE
    private var cooldownUntil: Long = 0L

    private enum class ChopState {
        IDLE,
        FIRST_CHOP_PEAK,
        WAITING_FOR_SECOND_CHOP
    }

    fun updateSensitivity(newSensitivity: Sensitivity) {
        this.sensitivity = newSensitivity
        reset()
    }

    fun reset() {
        chopState = ChopState.IDLE
        firstChopTime = 0L
        lastPeakTime = 0L
        cooldownUntil = 0L
        isGravityInitialized = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        val now = System.currentTimeMillis()

        // During cooldown, lock-out further gesture recognition and force state machine reset
        if (now < cooldownUntil) {
            chopState = ChopState.IDLE
            firstChopTime = 0L
            lastPeakTime = 0L
            _currentMagnitude.value = 0f
            return
        }

        val linX: Float
        val linY: Float
        val linZ: Float

        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            linX = event.values[0]
            linY = event.values[1]
            linZ = event.values[2]
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val alpha = 0.8f
            if (!isGravityInitialized) {
                gravityX = event.values[0]
                gravityY = event.values[1]
                gravityZ = event.values[2]
                isGravityInitialized = true
            } else {
                gravityX = alpha * gravityX + (1 - alpha) * event.values[0]
                gravityY = alpha * gravityY + (1 - alpha) * event.values[1]
                gravityZ = alpha * gravityZ + (1 - alpha) * event.values[2]
            }

            linX = event.values[0] - gravityX
            linY = event.values[1] - gravityY
            linZ = event.values[2] - gravityZ
        } else {
            return
        }

        val magnitude = sqrt(linX * linX + linY * linY + linZ * linZ)
        _currentMagnitude.value = magnitude

        val threshold = sensitivity.threshold
        val maxWindowMs = sensitivity.timeWindowMs
        val minDelayBetweenChopsMs = 180L

        // Self-healing reset if motion decays back to resting state for over max window
        if (magnitude < threshold * 0.4f && (now - lastPeakTime > maxWindowMs)) {
            chopState = ChopState.IDLE
            firstChopTime = 0L
        }

        when (chopState) {
            ChopState.IDLE -> {
                if (magnitude >= threshold) {
                    firstChopTime = now
                    lastPeakTime = now
                    chopState = ChopState.FIRST_CHOP_PEAK
                }
            }

            ChopState.FIRST_CHOP_PEAK -> {
                // Wait for magnitude recoil (decay below 60% of threshold)
                if (magnitude < threshold * 0.6f) {
                    chopState = ChopState.WAITING_FOR_SECOND_CHOP
                } else if (now - firstChopTime > 350L) {
                    // Continuous sustained movement without recoil -> reset to prevent false lock
                    chopState = ChopState.IDLE
                    firstChopTime = 0L
                }
            }

            ChopState.WAITING_FOR_SECOND_CHOP -> {
                val elapsedSinceFirst = now - firstChopTime

                if (elapsedSinceFirst > maxWindowMs) {
                    // Time window expired without second chop -> reset cleanly
                    chopState = ChopState.IDLE
                    firstChopTime = 0L
                } else if (magnitude >= threshold && elapsedSinceFirst >= minDelayBetweenChopsMs) {
                    // Second chop detected cleanly!
                    _detectedShakeCount.value = _detectedShakeCount.value + 1
                    cooldownUntil = now + COOLDOWN_PERIOD_MS
                    
                    // Reset state before firing trigger
                    chopState = ChopState.IDLE
                    firstChopTime = 0L
                    lastPeakTime = 0L
                    
                    onShakeDetected()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    companion object {
        const val COOLDOWN_PERIOD_MS = 1200L
    }
}
