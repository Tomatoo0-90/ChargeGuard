package com.chargeguard.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.chargeguard.domain.model.AlarmSettings
import com.chargeguard.domain.model.AlarmSound
import com.chargeguard.domain.model.AppTheme
import com.chargeguard.domain.model.ProtectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repository logic tests (mapping, defaults, clamping, round-trips across repository instances).
 *
 * Uses an in-memory DataStore: DataStore's JVM file storage relies on File.renameTo, which cannot
 * replace an existing file on a Windows build host. Real on-disk persistence is verified on the
 * device by SettingsPersistenceInstrumentedTest (androidTest).
 */
class DataStoreSettingsRepositoryTest {

    private class InMemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(state.value).toPreferences().also { state.value = it } }
    }

    @Test fun defaults_whenEmpty() = runBlocking {
        val r = DataStoreSettingsRepository(InMemoryDataStore())
        val s = r.currentSettings()
        assertFalse(s.onboardingCompleted)
        assertEquals(AppTheme.DARK, s.theme)
        assertTrue(s.showBatteryInNotification)
        assertEquals(AlarmSettings(), s.alarm)
        assertFalse(r.currentProtection().enabled)
        assertEquals(ProtectionState.INACTIVE, r.currentProtection().lastState)
    }

    @Test fun allSettings_roundTripThroughAFreshRepository() = runBlocking {
        val store = InMemoryDataStore()
        val first = DataStoreSettingsRepository(store)
        first.setAlarmSound(AlarmSound.KLAXON)
        first.setAlarmVolume(0.6f)
        first.setVibrationEnabled(false)
        first.setSoundDurationMinutes(5)
        first.setDisconnectConfirmMillis(2_000)
        first.setRaiseSystemAlarmVolume(true)
        first.setFullScreenAlarm(false)
        first.setTheme(AppTheme.AMOLED)
        first.setOnboardingCompleted(true)
        first.setShowBatteryInNotification(false)

        val second = DataStoreSettingsRepository(store).currentSettings()
        assertEquals(AlarmSound.KLAXON, second.alarm.sound)
        assertEquals(0.6f, second.alarm.volume, 0.0001f)
        assertFalse(second.alarm.vibrationEnabled)
        assertEquals(5, second.alarm.soundDurationMinutes)
        assertEquals(2_000L, second.alarm.disconnectConfirmMillis)
        assertTrue(second.alarm.raiseSystemAlarmVolume)
        assertFalse(second.alarm.fullScreenAlarm)
        assertEquals(AppTheme.AMOLED, second.theme)
        assertTrue(second.onboardingCompleted)
        assertFalse(second.showBatteryInNotification)
    }

    @Test fun values_areClamped() = runBlocking {
        val r = DataStoreSettingsRepository(InMemoryDataStore())
        r.setAlarmVolume(0f)
        r.setDisconnectConfirmMillis(60_000)
        r.setSoundDurationMinutes(-3)
        val a = r.currentSettings().alarm
        assertEquals(DataStoreSettingsRepository.MIN_VOLUME, a.volume, 0.0001f)
        assertEquals(DataStoreSettingsRepository.MAX_CONFIRM, a.disconnectConfirmMillis)
        assertEquals(0, a.soundDurationMinutes)
    }

    @Test fun protectionState_roundTrips_andClears() = runBlocking {
        val store = InMemoryDataStore()
        DataStoreSettingsRepository(store).updateProtection {
            it.copy(enabled = true, sessionStartedAtMillis = 42L, lastState = ProtectionState.ALARM_ACTIVE, interruptionRecorded = true)
        }
        val r2 = DataStoreSettingsRepository(store)
        val p = r2.currentProtection()
        assertTrue(p.enabled)
        assertEquals(42L, p.sessionStartedAtMillis)
        assertEquals(ProtectionState.ALARM_ACTIVE, p.lastState)
        assertTrue(p.interruptionRecorded)

        r2.updateProtection { it.copy(enabled = false, sessionStartedAtMillis = null) }
        assertNull(r2.currentProtection().sessionStartedAtMillis)
        assertFalse(r2.currentProtection().enabled)
    }

    @Test fun settingsChanges_doNotTouchProtectionState() = runBlocking {
        val r = DataStoreSettingsRepository(InMemoryDataStore())
        r.updateProtection { it.copy(enabled = true, lastState = ProtectionState.ARMED) }
        r.setAlarmSound(AlarmSound.PULSE)
        assertTrue(r.currentProtection().enabled)
        assertEquals(ProtectionState.ARMED, r.currentProtection().lastState)
    }
}
