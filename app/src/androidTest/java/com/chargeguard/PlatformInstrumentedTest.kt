package com.chargeguard

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chargeguard.alarm.AlarmController
import com.chargeguard.alarm.VibrationController
import com.chargeguard.core.common.WakeLocks
import com.chargeguard.data.charging.AndroidChargingRepository
import com.chargeguard.domain.model.AlarmSound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a real device. The alarm test plays the siren very quietly for about a second. */
@RunWith(AndroidJUnit4::class)
class PlatformInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun stickyBatteryRead_matchesSystem() {
        val repo = AndroidChargingRepository(context, { System.currentTimeMillis() }, WakeLocks(context))
        val snap = repo.currentSnapshot()
        assertNotNull(snap)
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))!!
        val plugged = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        assertEquals(plugged, snap!!.isPowerConnected)
        assertTrue(snap.batteryPercent in 0..100)
    }

    @Test fun alarmController_singlePlayer_startStop() {
        val controller = AlarmController(context)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(controller.start(AlarmSound.PULSE, 0.05f, raiseSystemAlarmVolume = false))
            assertTrue(controller.isActive)
            // A second start (duplicate event) must not create another player.
            assertTrue(controller.start(AlarmSound.SIREN, 0.05f, raiseSystemAlarmVolume = false))
            assertFalse("preview refused during a real alarm", controller.preview(AlarmSound.KLAXON, 0.05f))
        }
        Thread.sleep(800)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(controller.isPlaying)
            controller.stop()
            assertFalse(controller.isActive)
            controller.stop() // idempotent
        }
    }

    @Test fun vibrationController_idempotent() {
        val v = VibrationController(context)
        if (!v.hasVibrator) return
        v.start(); v.start()
        assertTrue(v.isVibrating)
        Thread.sleep(300)
        v.stop(); v.stop()
        assertFalse(v.isVibrating)
    }
}
