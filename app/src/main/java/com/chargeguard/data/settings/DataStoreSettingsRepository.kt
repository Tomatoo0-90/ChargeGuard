package com.chargeguard.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.chargeguard.domain.model.AlarmSettings
import com.chargeguard.domain.model.AlarmSound
import com.chargeguard.domain.model.AppSettings
import com.chargeguard.domain.model.AppTheme
import com.chargeguard.domain.model.PersistedProtection
import com.chargeguard.domain.model.ProtectionState
import com.chargeguard.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    private object Keys {
        val onboarding = booleanPreferencesKey("onboarding_completed")
        val theme = stringPreferencesKey("theme")
        val batteryInNotification = booleanPreferencesKey("battery_in_notification")
        val sound = stringPreferencesKey("alarm_sound")
        val volume = floatPreferencesKey("alarm_volume")
        val raiseVolume = booleanPreferencesKey("raise_system_alarm_volume")
        val vibration = booleanPreferencesKey("vibration_enabled")
        val duration = intPreferencesKey("sound_duration_minutes")
        val confirm = longPreferencesKey("disconnect_confirm_ms")
        val fullScreen = booleanPreferencesKey("full_screen_alarm")

        val enabled = booleanPreferencesKey("protection_enabled")
        val sessionStart = longPreferencesKey("session_started_at")
        val interruptionRecorded = booleanPreferencesKey("interruption_recorded")
        val lastState = stringPreferencesKey("last_state")
    }

    private val prefs: Flow<Preferences> = dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    override val settings: Flow<AppSettings> = prefs.map { it.toSettings() }.distinctUntilChanged()
    override val protection: Flow<PersistedProtection> = prefs.map { it.toProtection() }.distinctUntilChanged()

    override suspend fun currentSettings(): AppSettings = settings.first()
    override suspend fun currentProtection(): PersistedProtection = protection.first()

    override suspend fun updateProtection(transform: (PersistedProtection) -> PersistedProtection) {
        dataStore.edit { p ->
            val updated = transform(p.toProtection())
            p[Keys.enabled] = updated.enabled
            val start = updated.sessionStartedAtMillis
            if (start != null) p[Keys.sessionStart] = start else p.remove(Keys.sessionStart)
            p[Keys.interruptionRecorded] = updated.interruptionRecorded
            p[Keys.lastState] = updated.lastState.name
        }
    }

    override suspend fun setOnboardingCompleted(done: Boolean) = put(Keys.onboarding, done)
    override suspend fun setTheme(theme: AppTheme) = put(Keys.theme, theme.name)
    override suspend fun setShowBatteryInNotification(show: Boolean) = put(Keys.batteryInNotification, show)
    override suspend fun setAlarmSound(sound: AlarmSound) = put(Keys.sound, sound.name)
    override suspend fun setAlarmVolume(volume: Float) = put(Keys.volume, volume.coerceIn(MIN_VOLUME, 1f))
    override suspend fun setRaiseSystemAlarmVolume(enabled: Boolean) = put(Keys.raiseVolume, enabled)
    override suspend fun setVibrationEnabled(enabled: Boolean) = put(Keys.vibration, enabled)
    override suspend fun setSoundDurationMinutes(minutes: Int) = put(Keys.duration, minutes.coerceIn(0, 60))
    override suspend fun setDisconnectConfirmMillis(millis: Long) = put(Keys.confirm, millis.coerceIn(0L, MAX_CONFIRM))
    override suspend fun setFullScreenAlarm(enabled: Boolean) = put(Keys.fullScreen, enabled)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        dataStore.edit { it[key] = value }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AlarmSettings()
        return AppSettings(
            onboardingCompleted = this[Keys.onboarding] ?: false,
            theme = enumOr(this[Keys.theme], AppTheme.DARK),
            showBatteryInNotification = this[Keys.batteryInNotification] ?: true,
            alarm = AlarmSettings(
                sound = enumOr(this[Keys.sound], d.sound),
                volume = (this[Keys.volume] ?: d.volume).coerceIn(MIN_VOLUME, 1f),
                raiseSystemAlarmVolume = this[Keys.raiseVolume] ?: d.raiseSystemAlarmVolume,
                vibrationEnabled = this[Keys.vibration] ?: d.vibrationEnabled,
                soundDurationMinutes = this[Keys.duration] ?: d.soundDurationMinutes,
                disconnectConfirmMillis = this[Keys.confirm] ?: d.disconnectConfirmMillis,
                fullScreenAlarm = this[Keys.fullScreen] ?: d.fullScreenAlarm,
            ),
        )
    }

    private fun Preferences.toProtection() = PersistedProtection(
        enabled = this[Keys.enabled] ?: false,
        sessionStartedAtMillis = this[Keys.sessionStart],
        interruptionRecorded = this[Keys.interruptionRecorded] ?: false,
        lastState = enumOr(this[Keys.lastState], ProtectionState.INACTIVE),
    )

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    companion object {
        const val MIN_VOLUME = 0.1f
        const val MAX_CONFIRM = 5_000L
    }
}
