package com.chargeguard.domain.protection

import com.chargeguard.domain.model.HistoryEventType
import com.chargeguard.domain.model.ProtectionState
import com.chargeguard.domain.model.ProtectionState.ALARM_ACTIVE
import com.chargeguard.domain.model.ProtectionState.ARMED
import com.chargeguard.domain.model.ProtectionState.INACTIVE
import com.chargeguard.domain.model.ProtectionState.MONITORING_UNAVAILABLE
import com.chargeguard.domain.model.ProtectionState.RECONNECTED
import com.chargeguard.domain.model.ProtectionState.WAITING_FOR_CHARGER
import com.chargeguard.domain.protection.ProtectionEffect.CancelDisconnectCheck
import com.chargeguard.domain.protection.ProtectionEffect.CancelRearm
import com.chargeguard.domain.protection.ProtectionEffect.Record
import com.chargeguard.domain.protection.ProtectionEffect.ScheduleDisconnectCheck
import com.chargeguard.domain.protection.ProtectionEffect.ScheduleRearm
import com.chargeguard.domain.protection.ProtectionEffect.StartAlarm
import com.chargeguard.domain.protection.ProtectionEffect.StopAlarm
import com.chargeguard.domain.protection.ProtectionEffect.StopMonitoringService
import com.chargeguard.domain.protection.ProtectionEvent.Activate
import com.chargeguard.domain.protection.ProtectionEvent.Deactivate
import com.chargeguard.domain.protection.ProtectionEvent.DisconnectConfirmed
import com.chargeguard.domain.protection.ProtectionEvent.MonitoringLost
import com.chargeguard.domain.protection.ProtectionEvent.MonitoringRestored
import com.chargeguard.domain.protection.ProtectionEvent.OwnerStop
import com.chargeguard.domain.protection.ProtectionEvent.PowerChanged
import com.chargeguard.domain.protection.ProtectionEvent.RearmTimerElapsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionStateMachineTest {

    private val confirm = 1_000L
    private fun reduce(state: ProtectionState, event: ProtectionEvent, pending: Boolean = false, confirmMs: Long = confirm) =
        ProtectionStateMachine.reduce(MachineState(state, pending), event, confirmMs)

    @Test fun inactive_activate_goesToWaiting() {
        val t = reduce(INACTIVE, Activate)
        assertEquals(WAITING_FOR_CHARGER, t.next.state)
        assertEquals(listOf(Record(HistoryEventType.PROTECTION_ACTIVATED)), t.effects)
    }

    @Test fun inactive_ignoresPowerEvents() {
        assertEquals(INACTIVE, reduce(INACTIVE, PowerChanged(false)).next.state)
        assertEquals(INACTIVE, reduce(INACTIVE, PowerChanged(true)).next.state)
        assertTrue(reduce(INACTIVE, DisconnectConfirmed).effects.isEmpty())
    }

    @Test fun waiting_powerConnected_arms() {
        val t = reduce(WAITING_FOR_CHARGER, PowerChanged(true))
        assertEquals(ARMED, t.next.state)
        assertTrue(Record(HistoryEventType.PROTECTION_ARMED) in t.effects)
    }

    @Test fun waiting_powerDisconnected_neverAlarms() {
        val t = reduce(WAITING_FOR_CHARGER, PowerChanged(false))
        assertEquals(WAITING_FOR_CHARGER, t.next.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test fun armed_disconnect_schedulesConfirmation_notAlarm() {
        val t = reduce(ARMED, PowerChanged(false))
        assertEquals(ARMED, t.next.state)
        assertTrue(t.next.disconnectPending)
        assertEquals(listOf(ScheduleDisconnectCheck(confirm)), t.effects)
    }

    @Test fun armed_disconnect_withZeroWindow_alarmsImmediately() {
        val t = reduce(ARMED, PowerChanged(false), confirmMs = 0)
        assertEquals(ALARM_ACTIVE, t.next.state)
        assertEquals(StartAlarm, t.effects.first())
    }

    @Test fun armed_duplicateDisconnectWhilePending_isNoOp() {
        val t = reduce(ARMED, PowerChanged(false), pending = true)
        assertTrue(t.next.disconnectPending)
        assertTrue(t.effects.isEmpty())
    }

    @Test fun armed_confirmed_startsAlarmFirst() {
        val t = reduce(ARMED, DisconnectConfirmed, pending = true)
        assertEquals(ALARM_ACTIVE, t.next.state)
        assertFalse(t.next.disconnectPending)
        assertEquals(StartAlarm, t.effects.first())
        assertTrue(Record(HistoryEventType.CHARGING_DISCONNECTED) in t.effects)
    }

    @Test fun armed_staleConfirmationWithoutPending_isIgnored() {
        val t = reduce(ARMED, DisconnectConfirmed, pending = false)
        assertEquals(ARMED, t.next.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test fun armed_reconnectWithinWindow_cancelsAndLogs() {
        val t = reduce(ARMED, PowerChanged(true), pending = true)
        assertEquals(ARMED, t.next.state)
        assertFalse(t.next.disconnectPending)
        assertEquals(listOf(CancelDisconnectCheck, Record(HistoryEventType.BRIEF_INTERRUPTION_IGNORED)), t.effects)
    }

    @Test fun alarm_duplicateDisconnect_neverRestartsAlarm() {
        repeat(5) {
            val t = reduce(ALARM_ACTIVE, PowerChanged(false))
            assertEquals(ALARM_ACTIVE, t.next.state)
            assertTrue(t.effects.isEmpty())
        }
        assertTrue(reduce(ALARM_ACTIVE, DisconnectConfirmed).effects.isEmpty())
    }

    @Test fun alarm_reconnect_stopsAlarmAndSchedulesRearm() {
        val t = reduce(ALARM_ACTIVE, PowerChanged(true))
        assertEquals(RECONNECTED, t.next.state)
        assertEquals(StopAlarm, t.effects.first())
        assertTrue(Record(HistoryEventType.CHARGER_RECONNECTED) in t.effects)
        assertTrue(Record(HistoryEventType.ALARM_STOPPED) in t.effects)
        assertTrue(ScheduleRearm(ProtectionStateMachine.REARM_DELAY_MILLIS) in t.effects)
    }

    @Test fun alarm_ordinaryDeactivate_isRejected() {
        val t = reduce(ALARM_ACTIVE, Deactivate)
        assertEquals(ALARM_ACTIVE, t.next.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test fun alarm_ownerStop_stopsAndDeactivates() {
        val t = reduce(ALARM_ACTIVE, OwnerStop)
        assertEquals(INACTIVE, t.next.state)
        assertTrue(StopAlarm in t.effects)
        assertTrue(StopMonitoringService in t.effects)
        assertTrue(Record(HistoryEventType.ALARM_STOPPED_BY_OWNER) in t.effects)
    }

    @Test fun ownerStop_outsideAlarm_isIgnored() {
        assertEquals(ARMED, reduce(ARMED, OwnerStop).next.state)
    }

    @Test fun reconnected_rearmTimer_returnsToArmed() {
        assertEquals(ARMED, reduce(RECONNECTED, RearmTimerElapsed).next.state)
    }

    @Test fun reconnected_disconnectAgain_cancelsRearmAndSchedulesCheck() {
        val t = reduce(RECONNECTED, PowerChanged(false))
        assertEquals(ARMED, t.next.state)
        assertTrue(t.next.disconnectPending)
        assertEquals(listOf(CancelRearm, ScheduleDisconnectCheck(confirm)), t.effects)
    }

    @Test fun armed_deactivate_goesInactiveAndStopsService() {
        val t = reduce(ARMED, Deactivate, pending = true)
        assertEquals(INACTIVE, t.next.state)
        assertTrue(CancelDisconnectCheck in t.effects)
        assertTrue(StopMonitoringService in t.effects)
    }

    @Test fun monitoringLost_fromAlarm_stopsAlarmAndIsUnavailable() {
        val t = reduce(ALARM_ACTIVE, MonitoringLost("killed"))
        assertEquals(MONITORING_UNAVAILABLE, t.next.state)
        assertTrue(StopAlarm in t.effects)
    }

    @Test fun monitoringLost_fromEveryMonitoringState_isUnavailable() {
        for (s in listOf(WAITING_FOR_CHARGER, ARMED, ALARM_ACTIVE, RECONNECTED)) {
            assertEquals(s.name, MONITORING_UNAVAILABLE, reduce(s, MonitoringLost("x")).next.state)
        }
    }

    @Test fun unavailable_restore_respectsPreviousArming() {
        assertEquals(ARMED, reduce(MONITORING_UNAVAILABLE, MonitoringRestored(wasArmed = true)).next.state)
        assertEquals(WAITING_FOR_CHARGER, reduce(MONITORING_UNAVAILABLE, MonitoringRestored(wasArmed = false)).next.state)
    }

    @Test fun unavailable_neverAlarms() {
        assertTrue(reduce(MONITORING_UNAVAILABLE, PowerChanged(false)).effects.isEmpty())
        assertEquals(INACTIVE, reduce(MONITORING_UNAVAILABLE, Deactivate).next.state)
    }

    @Test fun fullCycle_listedTransitions() {
        var m = MachineState(INACTIVE)
        fun step(e: ProtectionEvent) { m = ProtectionStateMachine.reduce(m, e, confirm).next }
        step(Activate); assertEquals(WAITING_FOR_CHARGER, m.state)
        step(PowerChanged(true)); assertEquals(ARMED, m.state)
        step(PowerChanged(false)); step(DisconnectConfirmed); assertEquals(ALARM_ACTIVE, m.state)
        step(PowerChanged(true)); assertEquals(RECONNECTED, m.state)
        step(RearmTimerElapsed); assertEquals(ARMED, m.state)
        step(Deactivate); assertEquals(INACTIVE, m.state)
    }
}
