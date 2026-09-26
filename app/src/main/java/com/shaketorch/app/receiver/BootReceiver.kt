package com.shaketorch.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.shaketorch.app.repository.SettingsRepository
import com.shaketorch.app.service.ShakeTorchService

/**
 * BroadcastReceiver that automatically restarts ShakeTorchService when the device reboots or updates.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val repository = SettingsRepository(context)
            if (repository.isServiceEnabled.value) {
                val serviceIntent = Intent(context, ShakeTorchService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    // Fallback to startService if foreground service creation restrictions apply before unlock
                    try {
                        context.startService(serviceIntent)
                    } catch (ex: Exception) {
                        // Handle Direct Boot or Android 12+ background start restrictions
                    }
                }
            }
        }
    }
}
