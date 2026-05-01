package com.sightsense.feedback.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.sightsense.core.Priority

class HapticAlerter(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun vibrate(priority: Priority) {
        val pattern = when (priority) {
            Priority.HIGH -> longArrayOf(0, 180, 80, 180)
            Priority.MEDIUM -> longArrayOf(0, 120)
            Priority.LOW -> return
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
}
