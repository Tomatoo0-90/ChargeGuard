package com.chargeguard.domain.protection

import com.chargeguard.domain.model.AlarmSettings
import com.chargeguard.domain.model.AppSettings
import com.chargeguard.domain.model.ChargeStatus
import com.chargeguard.domain.model.HistoryEventType
import com.chargeguard.domain.model.PersistedProtection
import com.chargeguard.domain.model.PowerSource
import com.chargeguard.domain.model.ProtectionState
import com.chargeguard.testing.FakeAlarmOutput
import com.chargeguard.testing.FakeChargingRepository
import com.chargeguard.testing.FakeClock
import com.chargeguard.testing.FakeHistoryRepository
import com.chargeguard.testing.FakeServiceController
import com.chargeguard.testing.FakeSettingsRepository
import com.chargeguard.testing.snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProtectionManagerTest {

    private val connected = snapshot(PowerSource.AC)
    private val unplugged = snapshot(PowerSource.NONE)

    private class Env(
        val scope: TestScope,
        val charging: FakeChargingRepository,
        val settings: FakeSettingsRepository,
        val history: FakeHistoryRepository,
        val alarm: FakeAlarmOutput,
        val service: FakeServiceController,
        val clock: FakeClock,
    ) {
        val manager = ProtectionManager(charging, settings, history, alarm, service, clock, scope.backgroundScope)
        val state get() = manager.status.value.state

        /** User taps activate and the service comes up. */
        fun activateAndStart() {
            manager.activate()
            scope.runCurrent()
            manager.onServiceStarted(restoredBySystem = false)
            scope.runCurrent()
        }

        /** The receiver reports a reading, and the sticky value agrees with it. */
        fun power(s: com.chargeguard.domain.model.ChargingSnapshot) {
            charging.current = s
            manager.onChargingSnapshot(s)
            scope.runCurrent()
        }
    }

    private fun TestScope.env(
        confirmMs: Long = 1_000L,
        durationMin: Int = 0,
        initialPower: com.chargeguard.domain.model.ChargingSnapshot = connected,
        persisted: PersistedProtection = PersistedProtection(),
        audioWorks: Boolean = true,
    ): Env {
        val settings = FakeSettingsRepository(
            AppSettings(alarm = AlarmSettings(disconnectConfirmMillis = confirmMs, soundDurationMinutes = durationMin)),
            persisted,
        )
        val e = Env(this, FakeChargingRepository(initialPower), settings, FakeHistoryRepository(), FakeAlarmOutput(audioWorks), FakeServiceController(), FakeClock())
        runCurrent()
        return e
    }

    private fun Env.armed() {
        activateAndStart()
        assertEquals(ProtectionState.ARMED, state)
    }

    // ---- Activation -------------------------------------------------------------------------

    @Test fun activate_withChargerConnected_armsOnceServiceRuns() = runTest {
        val e = env()
        e.manager.activate(); runCurrent()
        assertEquals(1, e.service.startCount)
        assertEquals("not armed until the service actually runs", ProtectionState.INACTIVE, e.state)
        e.manager.onServiceStarted(false); runCurrent()
        assertEquals(ProtectionState.ARMED, e.state)
        assertTrue(e.settings.protectionState.value.enabled)
        assertTrue(e.manager.status.value.sessionStartedAtMillis != null)
        assertEquals(listOf(HistoryEventType.PROTECTION_ACTIVATED, HistoryEventType.PROTECTION_ARMED), e.history.types)
    }

    @Test fun activate_withoutCharger_waitsThenArmsOnConnect() = runTest {
        val e = env(initialPower = unplugged)
        e.activateAndStart()
        assertEquals(ProtectionState.WAITING_FOR_CHARGER, e.state)
        assertEquals(0, e.alarm.startCount)
        e.power(connected)
        assertEquals(ProtectionState.ARMED, e.state)
    }

    @Test fun activate_twice_startsServiceOnce() = runTest {
        val e = env()
        e.manager.activate(); e.manager.activate(); runCurrent()
        assertEquals(1, e.service.startCount)
    }

    @Test fun serviceStartFailure_showsUnavailable_andNeverClaimsProtection() = runTest {
        val e = env()
        e.service.startResult = Result.failure(IllegalStateException("ForegroundServiceStartNotAllowed"))
        e.manager.activate(); runCurrent()
        assertEquals(ProtectionState.MONITORING_UNAVAILABLE, e.state)
        assertTrue(e.manager.status.value.unavailableReason!!.contains("ForegroundServiceStartNotAllowed"))
        assertFalse(e.settings.protectionState.value.enabled)
        assertTrue(HistoryEventType.MONITORING_START_FAILED in e.history.types)
    }

    @Test fun serviceNeverStarts_watchdogReportsUnavailable() = runTest {
        val e = env()
        e.manager.activate(); runCurrent()
        advanceTimeBy(ProtectionManager.ACTIVATION_TIMEOUT_MILLIS + 1); runCurrent()
        assertEquals(ProtectionState.MONITORING_UNAVAILABLE, e.state)
        assertEquals(1, e.service.stopCount)
    }

    @Test fun serviceReportsStartFailure_showsUnavailable() = runTest {
        val e = env()
        e.manager.activate(); runCurrent()
        e.manager.onServiceStartFailed("startForeground refused"); runCurrent()
        assertEquals(ProtectionState.MONITORING_UNAVAILABLE, e.state)
        assertEquals("startForeground refused", e.manager.status.value.unavailableReason)
    }

    // ---- Disconnect / alarm ---------------------------------------------------------------

    @Test fun disconnect_whileArmed_alarmsOnceAfterConfirmationWindow() = runTest {
        val e = env(confirmMs = 1_000)
        e.armed()
        e.power(unplugged)
        assertEquals(ProtectionState.ARMED, e.state)
        assertTrue(e.manager.status.value.disconnectPending)
        advanceTimeBy(900); runCurrent()
        assertEquals(0, e.alarm.startCount)
        advanceTimeBy(200); runCurrent()
        assertEquals(ProtectionState.ALARM_ACTIVE, e.state)
        assertEquals(1, e.alarm.startCount)
        assertTrue(e.manager.status.value.sirenPlaying)
        assertTrue(HistoryEventType.CHARGING_DISCONNECTED in e.history.types)
        assertTrue(HistoryEventType.ALARM_STARTED in e.history.types)
    }

    @Test fun history_logsDisconnectBeforeAlarmStarted() = runTest {
        val e = env(confirmMs = 0)
        e.armed()
        e.power(unplugged)
        e.power(connected)
        val t = e.history.types
        assertTrue(t.indexOf(HistoryEventType.CHARGING_DISCONNECTED) < t.indexOf(HistoryEventType.ALARM_STARTED))
        assertTrue(t.indexOf(HistoryEventType.ALARM_STARTED) < t.indexOf(HistoryEventType.CHARGER_RECONNECTED))
        assertTrue(t.indexOf(HistoryEventType.CHARGER_RECONNECTED) < t.indexOf(HistoryEventType.ALARM_STOPPED))
    }

    @Test fun instantMode_alarmsWithoutDelay() = runTest {
        val e = env(confirmMs = 0)
        e.armed()
        e.power(unplugged)
        assertEquals(ProtectionState.ALARM_ACTIVE, e.state)
        assertEquals(1, e.alarm.startCount)
    }

    @Test fun duplicateBroadcasts_neverStartSecondAlarm() = runTest {
        val e = env(confirmMs = 500)
        e.armed()
        repeat(5) { e.power(unplugged) } // duplicates during the window
        advanceTimeBy(600); runCurrent()
        repeat(20) { i -> e.power(unplugged.copy(batteryPercent = 80 - i % 3)) } // duplicates + level changes during alarm
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, e.alarm.startCount)
        assertEquals(1, e.history.types.count { it == HistoryEventType.ALARM_STARTED })
        assertEquals(ProtectionState.ALARM_ACTIVE, e.state)
    }

    @Test fun briefCableWobble_withinWindow_doesNotAlarm() = runTest {
        val e = env(confirmMs = 1_000)
        e.armed()
        e.power(unplugged)
        advanceTimeBy(300); runCurrent()
        e.power(connected)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(ProtectionState.ARMED, e.state)
        assertEquals(0, e.alarm.startCount)
        assertTrue(HistoryEventType.BRIEF_INTERRUPTION_IGNORED in e.history.types)
    }

    @Test fun confirmation_usesFreshReading_notCachedEvent() = runTest {
        val e = env(confirmMs = 1_000)
        e.armed()
        e.manager.onChargingSnapshot(unplugged); runCurrent() // receiver saw a disconnect...
        e.charging.current = connected // ...but power is actually back (missed connect broadcast)
        advanceTimeBy(1_100); runCurrent()
        assertEquals(0, e.alarm.startCount)
        assertEquals(ProtectionState.ARMED, e.state)
    }

    @Test fun repeatedUnstableCable_eventuallyAlarmsWhenItStaysOff() = runTest {
        val e = env(confirmMs = 1_000)
        e.armed()
        repeat(4) {
            e.power(unplugged); advanceTimeBy(200); runCurrent()
            e.power(connected); advanceTimeBy(200); runCurrent()
        }
        assertEquals(0, e.alarm.startCount)
        e.power(unplugged); advanceTimeBy(1_100); runCurrent()
        assertEquals(1, e.alarm.startCount)
    }

    @Test fun batteryLevelChanges_andFullBattery_doNotTrigger() = runTest {
        val e = env(confirmMs = 0)
        e.armed()
        e.power(connected.copy(batteryPercent = 99))
        e.power(connected.copy(batteryPercent = 100, chargeStatus = ChargeStatus.FULL))
        e.power(connected.copy(batteryPercent = 100, chargeStatus = ChargeStatus.NOT_CHARGING))
        assertEquals(ProtectionState.ARMED, e.state)
        assertEquals(0, e.alarm.startCount)
    }

    @Test fun switchingPowerSource_usbToAc_doesNotTrigger() = runTest {
        val e = env(confirmMs = 0)
        e.armed()
        e.power(snapshot(PowerSource.USB))
        e.power(snapshot(PowerSource.WIRELESS))
        assertEquals(0, e.alarm.startCount)
    }

    @Test fun audioFailure_stillAlarms_withVibrationAndNotification() = runTest {
        val e = env(confirmMs = 0, audioWorks = false)
        e.armed()
        e.power(unplugged)
        assertEquals(ProtectionState.ALARM_ACTIVE, e.state)
        assertTrue(e.manager.status.value.alarmAudioFailed)
        assertFalse(e.manager.status.value.sirenPlaying)
        assertTrue(HistoryEventType.ALARM_AUDIO_FAILED in e.history.types)
    }

    @Test fun sirenTimeLimit_silencesButKeepsAlarmState() = runTest {
        val e = env(confirmMs = 0, durationMin = 1)
        e.armed()
        e.power(unplugged)
        advanceTimeBy(59_000); runCurrent()
        assertEquals(0, e.alarm.silenceCount)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, e.alarm.silenceCount)
        assertEquals(ProtectionState.ALARM_ACTIVE, e.state)
        assertTrue(e.manager.status.value.sirenTimedOut)
        assertTrue(HistoryEventType.ALARM_SOUND_TIMED_OUT in e.history.types)
        e.power(connected)
        assertEquals(ProtectionState.RECONNECTED, e.state)
    }

    // ---- Reconnection ---------------------------------------------------------------------

    @Test fun reconnect_stopsAlarm_thenReturnsToArmed() = runTest {
        val e = env(confirmMs = 0)
        e.armed()
        e.power(unplugged)
        e.power(connected)
        assertEquals(ProtectionState.RECONNECTED, e.state)
        assertEquals(1, e.alarm.stopCount)
        assertFalse(e.manager.status.value.sirenPlaying)
        advanceTimeBy(ProtectionStateMachine.REARM_DELAY_MILLIS + 10); runCurrent()
        assertEquals(ProtectionState.ARMED, e.state)
        assertTrue(HistoryEventType.CHARGER_RECONNECTED in e.history.types)
        assertTrue(HistoryEventType.ALARM_STOPPED in e.history.types)
    }

    @Test fun repeatedCycles_eachAlarmStartsAndStopsExactlyOnce() = runTest {
        val e = env(confirmMs = 0)
        e.armed()
        repeat(5) {
            e.power(unplugged)
            e.power(connected)
            advanceTimeBy(ProtectionStateMachine.REARM_DELAY_MILLIS + 10); runCurrent()
        }
        assertEquals(5, e.alarm.startCount)
        assertEquals(5, e.alarm.stopCount)
        assertEquals(ProtectionState.ARMED, e.state)
    }

    @Test fun unplugAgainWhileReconnected_alarmsAgain() = runTest {
        val e = env(confirmMs = 500)
        e.armed()
        e.power(unplugged); advanceTimeBy(600); runCurrent()
        e.power(connected)
        e.power(unplugged); advanceTimeBy(600); runCurrent()
        assertEquals(ProtectionState.ALARM_ACTIVE, e.state)
        assertEquals(2, e.alarm.startCount)
    }

    // ---- Deactivation ----------------------------------------------------------------------

    @Test fun deactivate_whenArmed_stopsServiceAndClearsPersistence() = runTest {
        val e = env()
        e.armed()
        e.manager.deactivate(); runCurrent()
        assertEquals(ProtectionState.INACTIVE, e.state)
        assertEquals(1, e.service.stopCount)
        assertFalse(e.settings.protectionState.value.enabled)
        assertNull(e.manager.status.value.sessionStartedAtMillis)
    }

    @Test fun deactivate_duringAlarm_isIgnored() = runTest {
        val e = env(confirmMs = 0)
        e.armed()
        e.power(unplugged)
        e.manager.deactivate(); runCurrent()
        assertEquals(ProtectionState.ALARM_ACTIVE, e.state)
        assertEquals(0, e.alarm.stopCount)
        assertEquals(0, e.service.stopCount)
    }

    @Test fun ownerStop_duringAlarm_stopsEverything() = runTest {
        val e = env(confirmMs = 0)
        e.armed()
        e.power(unplugged)
        e.manager.ownerStop(); runCurrent()
        assertEquals(ProtectionState.INACTIVE, e.state)
        assertEquals(1, e.alarm.stopCount)
        assertEquals(1, e.service.stopCount)
        assertTrue(HistoryEventType.ALARM_STOPPED_BY_OWNER in e.history.types)
    }

    @Test fun pendingDisconnect_thenDeactivate_neverAlarms() = runTest {
        val e = env(confirmMs = 1_000)
        e.armed()
        e.power(unplugged)
        e.manager.deactivate(); runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(0, e.alarm.startCount)
        assertEquals(ProtectionState.INACTIVE, e.state)
    }

    // ---- Interruption / process death ------------------------------------------------------

    @Test fun serviceDestroyedUnexpectedly_isUnavailable_andStopsAlarm() = runTest {
        val e = env(confirmMs = 0)
        e.armed()
        e.power(unplugged)
        e.manager.onServiceDestroyed(); runCurrent()
        assertEquals(ProtectionState.MONITORING_UNAVAILABLE, e.state)
        assertEquals(1, e.alarm.stopCount)
        assertTrue(HistoryEventType.MONITORING_INTERRUPTED in e.history.types)
        assertTrue("protection intent kept so the user is told", e.settings.protectionState.value.enabled)
    }

    @Test fun processRestart_withProtectionEnabled_reportsUnavailable_andLogsOnce() = runTest {
        val persisted = PersistedProtection(enabled = true, sessionStartedAtMillis = 1L, lastState = ProtectionState.ARMED)
        val e = env(persisted = persisted)
        assertEquals(ProtectionState.MONITORING_UNAVAILABLE, e.state)
        assertTrue(e.manager.status.value.initialized)
        assertEquals(1, e.history.types.count { it == HistoryEventType.MONITORING_INTERRUPTED })

        // A second process start must not log the same interruption again.
        val again = ProtectionManager(e.charging, e.settings, e.history, e.alarm, e.service, e.clock, backgroundScope)
        runCurrent()
        assertEquals(ProtectionState.MONITORING_UNAVAILABLE, again.status.value.state)
        assertEquals(1, e.history.types.count { it == HistoryEventType.MONITORING_INTERRUPTED })
    }

    @Test fun processRestart_withProtectionOff_staysInactive() = runTest {
        val e = env()
        assertEquals(ProtectionState.INACTIVE, e.state)
        assertTrue(e.history.types.isEmpty())
    }

    @Test fun systemRestoresService_whileArmedAndStillPlugged_rearms() = runTest {
        val e = env(persisted = PersistedProtection(enabled = true, sessionStartedAtMillis = 5L, lastState = ProtectionState.ARMED))
        e.manager.onServiceStarted(restoredBySystem = true); runCurrent()
        assertEquals(ProtectionState.ARMED, e.state)
        assertEquals(5L, e.manager.status.value.sessionStartedAtMillis)
        assertTrue(HistoryEventType.MONITORING_RESTORED in e.history.types)
        assertEquals(0, e.alarm.startCount)
    }

    @Test fun systemRestoresService_whileArmedButUnplugged_alarms() = runTest {
        val e = env(confirmMs = 1_000, initialPower = unplugged,
            persisted = PersistedProtection(enabled = true, lastState = ProtectionState.ARMED))
        e.manager.onServiceStarted(restoredBySystem = true); runCurrent()
        advanceTimeBy(1_100); runCurrent()
        assertEquals(ProtectionState.ALARM_ACTIVE, e.state)
        assertEquals(1, e.alarm.startCount)
    }

    @Test fun systemRestoresService_afterUserTurnedOff_stopsIt() = runTest {
        val e = env()
        e.manager.onServiceStarted(restoredBySystem = true); runCurrent()
        assertEquals(ProtectionState.INACTIVE, e.state)
        assertEquals(1, e.service.stopCount)
    }

    @Test fun reactivate_afterUnavailable_works() = runTest {
        val e = env(persisted = PersistedProtection(enabled = true, lastState = ProtectionState.ARMED))
        assertEquals(ProtectionState.MONITORING_UNAVAILABLE, e.state)
        e.activateAndStart()
        assertEquals(ProtectionState.ARMED, e.state)
        assertNull(e.manager.status.value.unavailableReason)
    }

    @Test fun lastState_isPersistedForRestore() = runTest {
        val e = env(confirmMs = 0)
        e.armed()
        assertEquals(ProtectionState.ARMED, e.settings.protectionState.value.lastState)
        e.power(unplugged)
        advanceUntilIdle()
        assertEquals(ProtectionState.ALARM_ACTIVE, e.settings.protectionState.value.lastState)
    }
}
