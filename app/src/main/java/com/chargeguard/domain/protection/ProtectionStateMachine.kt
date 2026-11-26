package com.chargeguard.domain.protection

import com.chargeguard.domain.model.HistoryEventType
import com.chargeguard.domain.model.ProtectionState
import com.chargeguard.domain.model.ProtectionState.ALARM_ACTIVE
import com.chargeguard.domain.model.ProtectionState.ARMED
import com.chargeguard.domain.model.ProtectionState.INACTIVE
import com.chargeguard.domain.model.ProtectionState.MONITORING_UNAVAILABLE
import com.chargeguard.domain.model.ProtectionState.RECONNECTED
import com.chargeguard.domain.model.ProtectionState.WAITING_FOR_CHARGER

/** Machine state = user-visible state + whether a disconnect is inside its confirmation window. */
data class MachineState(
    val state: ProtectionState,
    val disconnectPending: Boolean = false,
)

sealed interface ProtectionEvent {
    /** Monitoring service is running at the user's request. */
    data object Activate : ProtectionEvent

    /** User turned protection off. Rejected while the alarm is active. */
    data object Deactivate : ProtectionEvent

    /** The authoritative "external power connected" value changed. Already de-duplicated. */
    data class PowerChanged(val connected: Boolean) : ProtectionEvent

    /** Confirmation window elapsed and a fresh reading still shows no external power. */
    data object DisconnectConfirmed : ProtectionEvent

    /** Short RECONNECTED display period is over. */
    data object RearmTimerElapsed : ProtectionEvent

    /** Monitoring stopped without the user turning protection off. */
    data class MonitoringLost(val reason: String) : ProtectionEvent

    /** The system restarted the service after process death. */
    data class MonitoringRestored(val wasArmed: Boolean) : ProtectionEvent

    /** Owner verified their PIN on the alarm screen. */
    data object OwnerStop : ProtectionEvent
}

sealed interface ProtectionEffect {
    data object StartAlarm : ProtectionEffect
    data object StopAlarm : ProtectionEffect
    data class ScheduleDisconnectCheck(val delayMillis: Long) : ProtectionEffect
    data object CancelDisconnectCheck : ProtectionEffect
    data class ScheduleRearm(val delayMillis: Long) : ProtectionEffect
    data object CancelRearm : ProtectionEffect
    data class Record(val type: HistoryEventType, val detail: String? = null) : ProtectionEffect
    data object StopMonitoringService : ProtectionEffect
}

data class Transition(
    val next: MachineState,
    val effects: List<ProtectionEffect> = emptyList(),
)

/**
 * Pure, deterministic protection state machine. No Android, no clocks, no coroutines.
 * Every event in every state has a defined outcome; unknown combinations are no-ops,
 * which is what makes duplicate broadcasts harmless.
 */
object ProtectionStateMachine {

    const val REARM_DELAY_MILLIS = 2_500L

    fun reduce(current: MachineState, event: ProtectionEvent, confirmMillis: Long): Transition {
        val noOp = Transition(current)
        return when (current.state) {
            INACTIVE -> when (event) {
                ProtectionEvent.Activate -> Transition(
                    MachineState(WAITING_FOR_CHARGER),
                    listOf(ProtectionEffect.Record(HistoryEventType.PROTECTION_ACTIVATED)),
                )
                else -> noOp
            }

            MONITORING_UNAVAILABLE -> when (event) {
                ProtectionEvent.Activate -> Transition(
                    MachineState(WAITING_FOR_CHARGER),
                    listOf(ProtectionEffect.Record(HistoryEventType.PROTECTION_ACTIVATED)),
                )
                is ProtectionEvent.MonitoringRestored -> Transition(
                    MachineState(if (event.wasArmed) ARMED else WAITING_FOR_CHARGER),
                    listOf(ProtectionEffect.Record(HistoryEventType.MONITORING_RESTORED)),
                )
                ProtectionEvent.Deactivate -> Transition(
                    MachineState(INACTIVE),
                    listOf(
                        ProtectionEffect.Record(HistoryEventType.PROTECTION_DEACTIVATED),
                        ProtectionEffect.StopMonitoringService,
                    ),
                )
                else -> noOp
            }

            WAITING_FOR_CHARGER -> when (event) {
                is ProtectionEvent.PowerChanged ->
                    if (event.connected) Transition(
                        MachineState(ARMED),
                        listOf(ProtectionEffect.Record(HistoryEventType.PROTECTION_ARMED)),
                    ) else noOp
                ProtectionEvent.Deactivate -> deactivate()
                is ProtectionEvent.MonitoringLost -> lost(event)
                else -> noOp
            }

            ARMED -> reduceArmed(current, event, confirmMillis)

            ALARM_ACTIVE -> when (event) {
                is ProtectionEvent.PowerChanged ->
                    if (event.connected) Transition(
                        MachineState(RECONNECTED),
                        listOf(
                            ProtectionEffect.StopAlarm,
                            ProtectionEffect.Record(HistoryEventType.CHARGER_RECONNECTED),
                            ProtectionEffect.Record(HistoryEventType.ALARM_STOPPED),
                            ProtectionEffect.ScheduleRearm(REARM_DELAY_MILLIS),
                        ),
                    ) else noOp // duplicate disconnect: never restart the alarm
                ProtectionEvent.OwnerStop -> Transition(
                    MachineState(INACTIVE),
                    listOf(
                        ProtectionEffect.StopAlarm,
                        ProtectionEffect.Record(HistoryEventType.ALARM_STOPPED_BY_OWNER),
                        ProtectionEffect.Record(HistoryEventType.PROTECTION_DEACTIVATED),
                        ProtectionEffect.StopMonitoringService,
                    ),
                )
                is ProtectionEvent.MonitoringLost -> Transition(
                    MachineState(MONITORING_UNAVAILABLE),
                    listOf(
                        ProtectionEffect.StopAlarm,
                        ProtectionEffect.Record(HistoryEventType.MONITORING_INTERRUPTED, event.reason),
                    ),
                )
                // Deactivate is deliberately ignored: no ordinary dismiss while alarming.
                else -> noOp
            }

            RECONNECTED -> when (event) {
                ProtectionEvent.RearmTimerElapsed -> Transition(current.copy(state = ARMED))
                is ProtectionEvent.PowerChanged ->
                    if (!event.connected) {
                        // Pulled again before re-arming finished: behave exactly like ARMED.
                        val armed = reduceArmed(MachineState(ARMED), event, confirmMillis)
                        armed.copy(effects = listOf(ProtectionEffect.CancelRearm) + armed.effects)
                    } else noOp
                ProtectionEvent.Deactivate -> deactivate(extra = listOf(ProtectionEffect.CancelRearm))
                is ProtectionEvent.MonitoringLost -> lost(event, extra = listOf(ProtectionEffect.CancelRearm))
                else -> noOp
            }
        }
    }

    private fun reduceArmed(current: MachineState, event: ProtectionEvent, confirmMillis: Long): Transition {
        val noOp = Transition(current)
        return when (event) {
            is ProtectionEvent.PowerChanged -> when {
                !event.connected && current.disconnectPending -> noOp
                !event.connected && confirmMillis <= 0 -> triggerAlarm()
                !event.connected -> Transition(
                    current.copy(disconnectPending = true),
                    listOf(ProtectionEffect.ScheduleDisconnectCheck(confirmMillis)),
                )
                current.disconnectPending -> Transition(
                    current.copy(disconnectPending = false),
                    listOf(
                        ProtectionEffect.CancelDisconnectCheck,
                        ProtectionEffect.Record(HistoryEventType.BRIEF_INTERRUPTION_IGNORED),
                    ),
                )
                else -> noOp
            }
            ProtectionEvent.DisconnectConfirmed ->
                if (current.disconnectPending) triggerAlarm() else noOp
            ProtectionEvent.Deactivate -> deactivate(extra = listOf(ProtectionEffect.CancelDisconnectCheck))
            is ProtectionEvent.MonitoringLost -> lost(event, extra = listOf(ProtectionEffect.CancelDisconnectCheck))
            else -> noOp
        }
    }

    private fun triggerAlarm() = Transition(
        MachineState(ALARM_ACTIVE),
        listOf(
            // StartAlarm first: nothing (history, persistence) may delay the siren.
            ProtectionEffect.StartAlarm,
            ProtectionEffect.Record(HistoryEventType.CHARGING_DISCONNECTED),
        ),
    )

    private fun deactivate(extra: List<ProtectionEffect> = emptyList()) = Transition(
        MachineState(INACTIVE),
        extra + listOf(
            ProtectionEffect.Record(HistoryEventType.PROTECTION_DEACTIVATED),
            ProtectionEffect.StopMonitoringService,
        ),
    )

    private fun lost(event: ProtectionEvent.MonitoringLost, extra: List<ProtectionEffect> = emptyList()) = Transition(
        MachineState(MONITORING_UNAVAILABLE),
        extra + ProtectionEffect.Record(HistoryEventType.MONITORING_INTERRUPTED, event.reason),
    )
}
