package com.chargeguard.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repeating alarm vibration. Idempotent start/stop.
 *
 * Android cancels app vibrations when the screen turns off (observed on device as
 * `cancelled_by_screen_off`, even with USAGE_ALARM). While the alarm is active this controller
 * listens for ACTION_SCREEN_OFF and re-issues the vibration, so pressing the power button or a
 * screen timeout does not silently end it.
 */
@Singleton
class VibrationController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private val handler = Handler(Looper.getMainLooper())
    private var screenOffReceiver: BroadcastReceiver? = null

    @Volatile
    var isVibrating: Boolean = false
        private set

    val hasVibrator: Boolean get() = vibrator?.hasVibrator() == true

    @Synchronized
    fun start() {
        val v = vibrator ?: return
        if (isVibrating || !v.hasVibrator()) return
        if (vibrateNow()) {
            isVibrating = true
            watchScreenOff()
        }
    }

    @Synchronized
    fun stop() {
        unwatchScreenOff()
        handler.removeCallbacksAndMessages(RESTART_TOKEN)
        if (!isVibrating) return
        runCatching { vibrator?.cancel() }
        isVibrating = false
    }

    private fun vibrateNow(): Boolean {
        val v = vibrator ?: return false
        val effect = VibrationEffect.createWaveform(PATTERN, 0)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(
                    effect,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
            }
        }.isSuccess
    }

    private fun watchScreenOff() {
        if (screenOffReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                // The system cancels the running vibration as the screen goes off; restart it shortly after.
                handler.postAtTime({ restartIfActive() }, RESTART_TOKEN, android.os.SystemClock.uptimeMillis() + RESTART_DELAY_MS)
            }
        }
        runCatching {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
            screenOffReceiver = receiver
        }
    }

    private fun unwatchScreenOff() {
        screenOffReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        screenOffReceiver = null
    }

    @Synchronized
    private fun restartIfActive() {
        if (isVibrating) vibrateNow()
    }

    private companion object {
        val PATTERN = longArrayOf(0, 700, 350, 700, 350)
        const val RESTART_DELAY_MS = 400L
        val RESTART_TOKEN = Any()
    }
}
