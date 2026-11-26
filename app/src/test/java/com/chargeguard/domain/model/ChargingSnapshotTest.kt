package com.chargeguard.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingSnapshotTest {
    private fun raw(plugged: Int, status: Int = ChargingSnapshot.STATUS_CHARGING, level: Int = 50, scale: Int = 100) =
        ChargingSnapshot.fromRaw(plugged, status, level, scale, 0L)

    @Test fun unplugged_isNotConnected() {
        val s = raw(0, ChargingSnapshot.STATUS_DISCHARGING)
        assertFalse(s.isPowerConnected)
        assertEquals(PowerSource.NONE, s.source)
        assertEquals(ChargeStatus.DISCHARGING, s.chargeStatus)
    }

    @Test fun acUsbWirelessDock_areConnected() {
        assertEquals(PowerSource.AC, raw(ChargingSnapshot.PLUGGED_AC).source)
        assertEquals(PowerSource.USB, raw(ChargingSnapshot.PLUGGED_USB).source)
        assertEquals(PowerSource.WIRELESS, raw(ChargingSnapshot.PLUGGED_WIRELESS).source)
        assertEquals(PowerSource.DOCK, raw(ChargingSnapshot.PLUGGED_DOCK).source)
        listOf(1, 2, 4, 8).forEach { assertTrue(raw(it).isPowerConnected) }
    }

    @Test fun unknownFuturePluggedFlag_isStillConnected() {
        val s = raw(64)
        assertTrue(s.isPowerConnected)
        assertEquals(PowerSource.UNKNOWN, s.source)
    }

    @Test fun fullBattery_whilePlugged_isConnected() {
        val s = raw(ChargingSnapshot.PLUGGED_AC, ChargingSnapshot.STATUS_FULL, 100)
        assertTrue(s.isPowerConnected)
        assertEquals(ChargeStatus.FULL, s.chargeStatus)
        assertEquals(100, s.batteryPercent)
    }

    @Test fun pluggedButNotCharging_batteryProtection_isConnected() {
        val s = raw(ChargingSnapshot.PLUGGED_USB, ChargingSnapshot.STATUS_NOT_CHARGING, 80)
        assertTrue("80% charge limit must not look like a disconnect", s.isPowerConnected)
        assertEquals(ChargeStatus.NOT_CHARGING, s.chargeStatus)
    }

    @Test fun phoneConnectedToComputer_usb_isConnected() {
        assertTrue(raw(ChargingSnapshot.PLUGGED_USB, ChargingSnapshot.STATUS_NOT_CHARGING).isPowerConnected)
    }

    @Test fun percent_scalesAndHandlesMissingValues() {
        assertEquals(50, raw(1, level = 25, scale = 50).batteryPercent)
        assertNull(raw(1, level = -1, scale = 100).batteryPercent)
        assertNull(raw(1, level = 10, scale = 0).batteryPercent)
        assertEquals(100, raw(1, level = 120, scale = 100).batteryPercent)
    }
}
