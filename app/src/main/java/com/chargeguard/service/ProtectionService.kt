package com.chargeguard.service

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.chargeguard.core.notifications.ProtectionNotifications
import com.chargeguard.domain.model.ProtectionState
import com.chargeguard.domain.model.ProtectionStatus
import com.chargeguard.domain.protection.ProtectionManager
import com.chargeguard.domain.repository.ChargingRepository
import com.chargeguard.domain.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground monitoring service (type mediaPlayback, because it owns the looping siren).
 *
 * Lifecycle:
 * - Started only by [com.chargeguard.core.common.AndroidMonitoringServiceController.start],
 *   i.e. from a user tap while the app is visible.
 * - Owns the charging observer: the dynamic receiver lives exactly as long as monitoring does.
 * - START_STICKY: if the system kills it, Android may recreate it with a null intent; the
 *   manager then decides, from persisted state, whether monitoring is genuinely restored.
 *   If restart is refused, the app shows MONITORING_UNAVAILABLE instead of claiming protection.
 */
@AndroidEntryPoint
class ProtectionService : LifecycleService() {

    @Inject lateinit var manager: ProtectionManager
    @Inject lateinit var charging: ChargingRepository
    @Inject lateinit var notifications: ProtectionNotifications
    @Inject lateinit var settings: SettingsRepository

    private var inForeground = false
    private var sawMonitoringState = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_REPOST_ALARM) {
            val status = manager.status.value
            if (inForeground && status.state == ProtectionState.ALARM_ACTIVE) {
                notifications.showAlarm(fullScreen = false, sirenSilenced = status.sirenTimedOut, audioFailed = status.alarmAudioFailed)
            }
            if (!inForeground) stopSelf(startId)
            return if (inForeground) START_STICKY else START_NOT_STICKY
        }

        if (!inForeground) {
            val restoredBySystem = intent == null
            val started = runCatching { goForeground() }
            if (started.isFailure) {
                val reason = "Android did not allow the monitoring service to start (${started.exceptionOrNull()?.javaClass?.simpleName})."
                Log.e(TAG, reason, started.exceptionOrNull())
                manager.onServiceStartFailed(reason)
                if (restoredBySystem) notifications.showProtectionOff(reason)
                stopSelf()
                return START_NOT_STICKY
            }
            inForeground = true
            manager.onServiceStarted(restoredBySystem)
            observeCharging()
            observeStatus()
        }
        return START_STICKY
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        val initial = manager.status.value.let {
            if (it.state.isMonitoring) it else it.copy(state = ProtectionState.WAITING_FOR_CHARGER)
        }
        ServiceCompat.startForeground(
            this, ProtectionNotifications.ID_MONITORING,
            notifications.monitoringNotification(initial, showBattery = true), type,
        )
    }

    private fun observeCharging() {
        lifecycleScope.launch {
            charging.observe().collect { manager.onChargingSnapshot(it) }
        }
    }

    private fun observeStatus() {
        lifecycleScope.launch {
            combine(manager.status, settings.settings.map { it.showBatteryInNotification }) { status, showBattery ->
                status to showBattery
            }
                .distinctUntilChanged { a, b -> a.second == b.second && a.first.notificationKey() == b.first.notificationKey() }
                .collect { (status, showBattery) ->
                    if (status.state.isMonitoring) {
                        sawMonitoringState = true
                        notifications.updateMonitoring(status, showBattery)
                    } else if (sawMonitoringState) {
                        // Protection ended (deactivated, owner stop, or monitoring lost).
                        stopSelf()
                    }
                }
        }
    }

    override fun onDestroy() {
        val stateAtDestroy = manager.status.value.state
        if (stateAtDestroy.isMonitoring) {
            // Not a user-initiated stop: make sure the user is not left believing they are protected.
            notifications.showProtectionOff("ChargeGuard monitoring stopped unexpectedly. Open the app to turn protection on again.")
        }
        manager.onServiceDestroyed()
        if (inForeground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        inForeground = false
        super.onDestroy()
    }

    private fun ProtectionStatus.notificationKey(): List<Any?> =
        listOf(state, disconnectPending, charging?.batteryPercent, charging?.source, charging?.chargeStatus)

    companion object {
        private const val TAG = "ProtectionService"
        const val ACTION_START = "com.chargeguard.action.START_MONITORING"
        const val ACTION_REPOST_ALARM = "com.chargeguard.action.REPOST_ALARM"
    }
}
