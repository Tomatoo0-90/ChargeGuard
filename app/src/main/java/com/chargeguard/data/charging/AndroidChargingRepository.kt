package com.chargeguard.data.charging

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.chargeguard.core.common.WakeLocks
import com.chargeguard.domain.model.ChargingSnapshot
import com.chargeguard.domain.repository.ChargingRepository
import com.chargeguard.domain.repository.Clock
import com.chargeguard.receiver.ChargingReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidChargingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
    private val wakeLocks: WakeLocks,
) : ChargingRepository {

    /**
     * ACTION_BATTERY_CHANGED is sticky: registering a null receiver returns the last broadcast
     * without registering anything. This is the authoritative source of "is power connected".
     */
    override fun currentSnapshot(): ChargingSnapshot? {
        val intent = runCatching {
            ContextCompat.registerReceiver(
                context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.getOrNull() ?: return null
        return parse(intent)
    }

    override fun observe(): Flow<ChargingSnapshot> = callbackFlow {
        currentSnapshot()?.let { trySend(it) }

        val receiver = ChargingReceiver { _, intent ->
            val snapshot = when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> parse(intent)
                else -> currentSnapshot()
            }
            if (intent.action == Intent.ACTION_POWER_DISCONNECTED || snapshot?.isPowerConnected == false) {
                // Keep the CPU awake until the protection manager has decided.
                wakeLocks.holdForTransition()
            }
            snapshot?.let { trySend(it) }
            if (intent.action == Intent.ACTION_POWER_CONNECTED || intent.action == Intent.ACTION_POWER_DISCONNECTED) {
                // The connect/disconnect broadcast can race the sticky update; re-read shortly after.
                launch {
                    delay(STICKY_RECHECK_MILLIS)
                    currentSnapshot()?.let { trySend(it) }
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, ChargingReceiver.intentFilter(), ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Safety net in case a broadcast is ever missed (some OEM builds). Duplicates are
        // filtered by the protection manager, so polling never causes repeated alarms.
        val poller = launch {
            while (isActive) {
                delay(POLL_INTERVAL_MILLIS)
                currentSnapshot()?.let { trySend(it) }
            }
        }

        awaitClose {
            poller.cancel()
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun parse(intent: Intent): ChargingSnapshot = ChargingSnapshot.fromRaw(
        plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
        status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
        level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
        scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
        timestampMillis = clock.now(),
    )

    private companion object {
        const val POLL_INTERVAL_MILLIS = 5_000L
        const val STICKY_RECHECK_MILLIS = 400L
    }
}
