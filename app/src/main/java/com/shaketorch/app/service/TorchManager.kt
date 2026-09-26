package com.shaketorch.app.service

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages physical camera torch/flashlight operations with auto-reconnect fallback.
 */
class TorchManager(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val _isTorchOn = MutableStateFlow(false)
    val isTorchOn: StateFlow<Boolean> = _isTorchOn.asStateFlow()

    private val _isFlashAvailable = MutableStateFlow(true)
    val isFlashAvailable: StateFlow<Boolean> = _isFlashAvailable.asStateFlow()

    private val _torchErrorState = MutableStateFlow<String?>(null)
    val torchErrorState: StateFlow<String?> = _torchErrorState.asStateFlow()

    private var cameraId: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (cameraId == null || id == cameraId) {
                cameraId = id
                _isTorchOn.value = enabled
                _torchErrorState.value = null
            }
        }

        override fun onTorchModeUnavailable(id: String) {
            if (id == cameraId) {
                _torchErrorState.value = "Flashlight temporarily in use by another app."
            }
        }
    }

    init {
        findCameraWithFlash()
        registerCallback()
    }

    fun findCameraWithFlash(): String? {
        try {
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false

                if (facing == CameraCharacteristics.LENS_FACING_BACK && hasFlash) {
                    cameraId = id
                    _isFlashAvailable.value = true
                    return id
                }
            }

            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false

                if (hasFlash) {
                    cameraId = id
                    _isFlashAvailable.value = true
                    return id
                }
            }

            _isFlashAvailable.value = false
            _torchErrorState.value = "No camera flash unit found on device."
        } catch (e: Exception) {
            _isFlashAvailable.value = false
            _torchErrorState.value = "Camera access error: ${e.localizedMessage}"
        }
        return cameraId
    }

    private fun registerCallback() {
        try {
            cameraManager.registerTorchCallback(torchCallback, mainHandler)
        } catch (e: Exception) {
            _torchErrorState.value = "Failed to register torch callback: ${e.localizedMessage}"
        }
    }

    fun unregisterCallback() {
        try {
            cameraManager.unregisterTorchCallback(torchCallback)
        } catch (e: Exception) {
            // Ignored
        }
    }

    fun setTorchEnabled(enabled: Boolean): Boolean {
        var targetId = cameraId ?: findCameraWithFlash()
        if (targetId == null) {
            _torchErrorState.value = "No camera with flash unit available."
            return false
        }

        return try {
            cameraManager.setTorchMode(targetId, enabled)
            _isTorchOn.value = enabled
            _torchErrorState.value = null
            true
        } catch (e: CameraAccessException) {
            // Re-find camera and retry once
            targetId = findCameraWithFlash()
            if (targetId != null) {
                try {
                    cameraManager.setTorchMode(targetId, enabled)
                    _isTorchOn.value = enabled
                    _torchErrorState.value = null
                    return true
                } catch (retryEx: Exception) {
                    _torchErrorState.value = "Camera in use: ${retryEx.localizedMessage}"
                }
            } else {
                _torchErrorState.value = "Camera access error: ${e.localizedMessage}"
            }
            false
        } catch (e: Exception) {
            _torchErrorState.value = "Failed to toggle flashlight: ${e.localizedMessage}"
            false
        }
    }

    fun toggleTorch(): Boolean {
        return setTorchEnabled(!_isTorchOn.value)
    }
}
