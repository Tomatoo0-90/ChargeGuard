package com.chargeguard.domain.repository

import com.chargeguard.domain.model.AlarmSettings
import com.chargeguard.domain.model.AlarmSound
import com.chargeguard.domain.model.AppSettings
import com.chargeguard.domain.model.AppTheme
import com.chargeguard.domain.model.ChargingSnapshot
import com.chargeguard.domain.model.HistoryEvent
import com.chargeguard.domain.model.HistoryEventType
import com.chargeguard.domain.model.PersistedProtection
import kotlinx.coroutines.flow.Flow

interface ChargingRepository {
    /** Synchronous read of the sticky ACTION_BATTERY_CHANGED broadcast. Null if unavailable. */
    fun currentSnapshot(): ChargingSnapshot?

    /**
     * Cold flow of power readings. Emits the current reading immediately, then on every power
     * broadcast and on a periodic safety poll. Collect it only from the foreground service
     * (monitoring) or a visible screen (display).
     */
    fun observe(): Flow<ChargingSnapshot>
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    val protection: Flow<PersistedProtection>

    suspend fun currentSettings(): AppSettings
    suspend fun currentProtection(): PersistedProtection
    suspend fun updateProtection(transform: (PersistedProtection) -> PersistedProtection)

    suspend fun setOnboardingCompleted(done: Boolean)
    suspend fun setTheme(theme: AppTheme)
    suspend fun setShowBatteryInNotification(show: Boolean)
    suspend fun setAlarmSound(sound: AlarmSound)
    suspend fun setAlarmVolume(volume: Float)
    suspend fun setRaiseSystemAlarmVolume(enabled: Boolean)
    suspend fun setVibrationEnabled(enabled: Boolean)
    suspend fun setSoundDurationMinutes(minutes: Int)
    suspend fun setDisconnectConfirmMillis(millis: Long)
    suspend fun setFullScreenAlarm(enabled: Boolean)
}

interface HistoryRepository {
    fun observe(limit: Int = 300): Flow<List<HistoryEvent>>
    suspend fun record(type: HistoryEventType, detail: String? = null, timestampMillis: Long? = null)
    suspend fun clear()
}

/** Abstraction over AlarmController + VibrationController + alarm notification. */
interface AlarmOutput {
    /** Starts siren (+ vibration, notification). Idempotent. Returns false if audio failed. */
    fun start(settings: AlarmSettings): Boolean
    /** Silences siren and vibration but keeps the alarm notification (sound time limit). */
    fun silence()
    /** Stops everything and removes the alarm notification. Idempotent. */
    fun stop()
}

/** Starts and stops the foreground monitoring service. */
interface MonitoringServiceController {
    /** Must only be called from a user interaction while the app is visible. */
    fun start(): Result<Unit>
    fun stop()
}

/** PIN-gated owner override. */
interface OwnerPinVerifier {
    fun isPinSet(): Boolean
}

fun interface Clock {
    fun now(): Long
}

/** Convenience to read only alarm settings. */
suspend fun SettingsRepository.alarmSettings(): AlarmSettings = currentSettings().alarm
