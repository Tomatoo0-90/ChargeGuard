package com.chargeguard.core.common

import android.content.Context
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Short, bounded partial wake locks. Charging does not stop the CPU from suspending when the
 * screen is off, so without these a sleeping CPU could delay the disconnect confirmation timer
 * or the alarm's own timers. Every acquisition has a timeout so a bug can never drain the battery.
 */
@Singleton
class WakeLocks @Inject constructor(@ApplicationContext context: Context) {
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val transition = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChargeGuard:transition")
        .apply { setReferenceCounted(false) }
    private val alarm = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChargeGuard:alarm")
        .apply { setReferenceCounted(false) }

    /** Held across a possible disconnect until it is confirmed or dismissed. */
    fun holdForTransition(timeoutMillis: Long = 15_000L) {
        runCatching { transition.acquire(timeoutMillis) }
    }

    fun holdForAlarm() {
        runCatching { alarm.acquire(ALARM_MAX_MILLIS) }
    }

    fun releaseAlarm() {
        runCatching { if (alarm.isHeld) alarm.release() }
    }

    private companion object {
        const val ALARM_MAX_MILLIS = 2 * 60 * 60 * 1000L
    }
}
