package com.chargeguard.core.common

import com.chargeguard.domain.model.ChargeStatus
import com.chargeguard.domain.model.PowerSource
import java.text.DateFormat
import java.util.Date

object Formatting {
    fun duration(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    fun source(source: PowerSource): String = when (source) {
        PowerSource.NONE -> "Not connected"
        PowerSource.AC -> "AC charger"
        PowerSource.USB -> "USB"
        PowerSource.WIRELESS -> "Wireless"
        PowerSource.DOCK -> "Dock"
        PowerSource.UNKNOWN -> "External power"
    }

    fun chargeStatus(status: ChargeStatus): String = when (status) {
        ChargeStatus.CHARGING -> "Charging"
        ChargeStatus.FULL -> "Fully charged"
        ChargeStatus.NOT_CHARGING -> "Connected, not charging"
        ChargeStatus.DISCHARGING -> "On battery"
        ChargeStatus.UNKNOWN -> "Unknown"
    }

    fun time(millis: Long): String = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(millis))
    fun day(millis: Long): String = DateFormat.getDateInstance(DateFormat.FULL).format(Date(millis))
}
