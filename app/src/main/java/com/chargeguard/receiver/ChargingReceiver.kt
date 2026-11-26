package com.chargeguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Receives power broadcasts. It is registered dynamically by the charging observer that the
 * foreground service owns, because since Android 8 ACTION_POWER_CONNECTED/DISCONNECTED are not
 * delivered to manifest-declared receivers. ACTION_BATTERY_CHANGED can only ever be registered
 * dynamically.
 *
 * The receiver does no decision-making: it forwards the intent, and the observer re-reads the
 * sticky ACTION_BATTERY_CHANGED to get the single authoritative state.
 */
class ChargingReceiver(
    private val onPowerEvent: (context: Context, intent: Intent) -> Unit,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            Intent.ACTION_BATTERY_CHANGED -> onPowerEvent(context, intent)
        }
    }

    companion object {
        fun intentFilter(): IntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
    }
}
