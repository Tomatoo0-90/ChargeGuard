package com.chargeguard.domain.model

/** Where external power comes from, derived from BatteryManager.EXTRA_PLUGGED. */
enum class PowerSource { NONE, AC, USB, WIRELESS, DOCK, UNKNOWN }

/** Whether the battery is gaining charge, derived from BatteryManager.EXTRA_STATUS. */
enum class ChargeStatus { CHARGING, FULL, NOT_CHARGING, DISCHARGING, UNKNOWN }

/**
 * One reading of the device's power state.
 *
 * [isPowerConnected] answers "is a power source physically connected?" and is the ONLY field
 * used for protection decisions. [chargeStatus] answers "is the battery gaining charge?" and is
 * informational: a full battery, or a phone with battery-protection limiting charge to 80%,
 * reports NOT_CHARGING/FULL while still plugged in, and must not trigger an alarm.
 */
data class ChargingSnapshot(
    val source: PowerSource,
    val chargeStatus: ChargeStatus,
    val batteryPercent: Int?,
    val timestampMillis: Long,
) {
    val isPowerConnected: Boolean get() = source != PowerSource.NONE

    companion object {
        // Raw constant values from android.os.BatteryManager, duplicated here so parsing is
        // pure Kotlin and unit-testable on the JVM.
        const val PLUGGED_AC = 1
        const val PLUGGED_USB = 2
        const val PLUGGED_WIRELESS = 4
        const val PLUGGED_DOCK = 8

        const val STATUS_UNKNOWN = 1
        const val STATUS_CHARGING = 2
        const val STATUS_DISCHARGING = 3
        const val STATUS_NOT_CHARGING = 4
        const val STATUS_FULL = 5

        /**
         * Builds a snapshot from raw ACTION_BATTERY_CHANGED extras.
         * @param plugged EXTRA_PLUGGED (0 = on battery). Bit flags; any non-zero value means
         *   external power is connected, including unknown future flags.
         */
        fun fromRaw(plugged: Int, status: Int, level: Int, scale: Int, timestampMillis: Long): ChargingSnapshot {
            val source = when {
                plugged <= 0 -> PowerSource.NONE
                plugged and PLUGGED_AC != 0 -> PowerSource.AC
                plugged and PLUGGED_USB != 0 -> PowerSource.USB
                plugged and PLUGGED_WIRELESS != 0 -> PowerSource.WIRELESS
                plugged and PLUGGED_DOCK != 0 -> PowerSource.DOCK
                else -> PowerSource.UNKNOWN
            }
            val chargeStatus = when (status) {
                STATUS_CHARGING -> ChargeStatus.CHARGING
                STATUS_FULL -> ChargeStatus.FULL
                STATUS_NOT_CHARGING -> ChargeStatus.NOT_CHARGING
                STATUS_DISCHARGING -> ChargeStatus.DISCHARGING
                else -> ChargeStatus.UNKNOWN
            }
            val percent = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else null
            return ChargingSnapshot(source, chargeStatus, percent, timestampMillis)
        }
    }
}
