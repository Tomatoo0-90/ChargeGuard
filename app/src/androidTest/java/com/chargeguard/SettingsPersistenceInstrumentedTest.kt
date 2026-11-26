package com.chargeguard

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chargeguard.data.settings.DataStoreSettingsRepository
import com.chargeguard.domain.model.AlarmSound
import com.chargeguard.domain.model.ProtectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real on-disk DataStore persistence, verified on the device's file system. */
@RunWith(AndroidJUnit4::class)
class SettingsPersistenceInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun settingsSurviveANewDataStoreInstance() = runBlocking {
        val file = File(context.cacheDir, "persist_test_${System.nanoTime()}.preferences_pb")
        val scope1 = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val first = DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = scope1) { file })
        first.setAlarmSound(AlarmSound.KLAXON)
        first.setDisconnectConfirmMillis(2_000)
        first.setAlarmVolume(0.5f)
        first.updateProtection { it.copy(enabled = true, lastState = ProtectionState.ARMED, sessionStartedAtMillis = 7L) }
        scope1.coroutineContext.job.cancelAndJoin() // release the file before reopening

        val scope2 = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val second = DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = scope2) { file })
        val s = second.currentSettings()
        val p = second.currentProtection()
        assertEquals(AlarmSound.KLAXON, s.alarm.sound)
        assertEquals(2_000L, s.alarm.disconnectConfirmMillis)
        assertEquals(0.5f, s.alarm.volume, 0.0001f)
        assertTrue(p.enabled)
        assertEquals(ProtectionState.ARMED, p.lastState)
        assertEquals(7L, p.sessionStartedAtMillis)
        scope2.cancel()
        file.delete()
        Unit
    }
}
