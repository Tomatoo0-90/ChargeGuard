package com.chargeguard.domain.protection

import com.chargeguard.domain.model.AlarmSettings
import com.chargeguard.domain.model.ChargingSnapshot
import com.chargeguard.domain.model.HistoryEventType
import com.chargeguard.domain.model.ProtectionState
import com.chargeguard.domain.model.ProtectionStatus
import com.chargeguard.domain.repository.AlarmOutput
import com.chargeguard.domain.repository.ChargingRepository
import com.chargeguard.domain.repository.Clock
import com.chargeguard.domain.repository.HistoryRepository
import com.chargeguard.domain.repository.MonitoringServiceController
import com.chargeguard.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the protection lifecycle. Every input (user actions, service lifecycle, charging
 * readings, timers) becomes a [Command] processed one at a time by a single actor coroutine,
 * so there are no races between broadcasts, timers and UI taps.
 *
 * The manager is the single authority for protection state and publishes it as [status].
 */
class ProtectionManager(
    private val charging: ChargingRepository,
    private val settings: SettingsRepository,
    private val history: HistoryRepository,
    private val alarm: AlarmOutput,
    private val serviceController: MonitoringServiceController,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    private sealed interface Command {
        data object Initialize : Command
        data object ActivateRequested : Command
        data object DeactivateRequested : Command
        data object OwnerStopRequested : Command
        data class ServiceStarted(val restoredBySystem: Boolean) : Command
        data class ServiceStartFailed(val reason: String) : Command
        data object ServiceDestroyed : Command
        data class Charging(val snapshot: ChargingSnapshot) : Command
        data class DisconnectTimer(val token: Long) : Command
        data class RearmTimer(val token: Long) : Command
        data class SirenTimeout(val token: Long) : Command
        data class ActivationWatchdog(val token: Long) : Command
    }

    private val _status = MutableStateFlow(ProtectionStatus())
    val status: StateFlow<ProtectionStatus> = _status.asStateFlow()

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val historyQueue = Channel<Triple<HistoryEventType, String?, Long>>(Channel.UNLIMITED)

    private var machine = MachineState(ProtectionState.INACTIVE)
    private var lastConnected: Boolean? = null
    private var serviceRunning = false
    private var activationPending = false
    private var sessionStartedAt: Long? = null
    private var unavailableReason: String? = null
    private var cachedAlarmSettings = AlarmSettings()
    /** History entries produced by effects that must be written after the transition's own records. */
    private var deferredRecords: MutableList<Pair<HistoryEventType, String?>>? = null

    private var tokenCounter = 0L
    private var disconnectToken = -1L
    private var rearmToken = -1L
    private var sirenToken = -1L
    private var watchdogToken = -1L
    private var disconnectJob: Job? = null
    private var rearmJob: Job? = null
    private var sirenJob: Job? = null
    private var watchdogJob: Job? = null

    init {
        scope.launch { for (command in commands) handle(command) }
        scope.launch { for ((type, detail, ts) in historyQueue) runCatching { history.record(type, detail, ts) } }
        scope.launch { settings.settings.collect { cachedAlarmSettings = it.alarm } }
        commands.trySend(Command.Initialize)
    }

    // ---- Public API ---------------------------------------------------------------------

    /** User tapped "activate". Must be called while the app is visible. */
    fun activate() { commands.trySend(Command.ActivateRequested) }

    /** User tapped "deactivate". Ignored while the alarm is active. */
    fun deactivate() { commands.trySend(Command.DeactivateRequested) }

    /** Owner verified their PIN on the alarm screen. */
    fun ownerStop() { commands.trySend(Command.OwnerStopRequested) }

    fun onServiceStarted(restoredBySystem: Boolean) { commands.trySend(Command.ServiceStarted(restoredBySystem)) }
    fun onServiceStartFailed(reason: String) { commands.trySend(Command.ServiceStartFailed(reason)) }
    fun onServiceDestroyed() { commands.trySend(Command.ServiceDestroyed) }
    fun onChargingSnapshot(snapshot: ChargingSnapshot) { commands.trySend(Command.Charging(snapshot)) }

    // ---- Actor --------------------------------------------------------------------------

    private suspend fun handle(command: Command) {
        when (command) {
            Command.Initialize -> initialize()

            Command.ActivateRequested -> {
                if (machine.state.isMonitoring || activationPending) return
                serviceController.start().onSuccess {
                    activationPending = true
                    unavailableReason = null
                    val token = ++tokenCounter
                    watchdogToken = token
                    watchdogJob?.cancel()
                    watchdogJob = scope.launch {
                        delay(ACTIVATION_TIMEOUT_MILLIS)
                        commands.send(Command.ActivationWatchdog(token))
                    }
                }.onFailure { error ->
                    markStartFailed("Could not start monitoring: ${error.message ?: error.javaClass.simpleName}")
                }
                publish()
            }

            is Command.ActivationWatchdog -> {
                if (command.token != watchdogToken || !activationPending) return
                markStartFailed("The monitoring service did not start in time.")
                serviceController.stop()
                publish()
            }

            Command.DeactivateRequested -> dispatch(ProtectionEvent.Deactivate)
            Command.OwnerStopRequested -> dispatch(ProtectionEvent.OwnerStop)

            is Command.ServiceStarted -> onServiceStartedInternal(command.restoredBySystem)

            is Command.ServiceStartFailed -> {
                activationPending = false
                watchdogJob?.cancel()
                if (machine.state.isMonitoring) {
                    dispatch(ProtectionEvent.MonitoringLost(command.reason))
                } else {
                    markStartFailed(command.reason)
                    publish()
                }
            }

            Command.ServiceDestroyed -> {
                serviceRunning = false
                if (machine.state.isMonitoring) {
                    dispatch(ProtectionEvent.MonitoringLost("The monitoring service stopped unexpectedly."))
                }
            }

            is Command.Charging -> onCharging(command.snapshot)

            is Command.DisconnectTimer -> {
                if (command.token != disconnectToken) return
                // Confirm with a fresh authoritative reading, never with the cached one.
                val fresh = charging.currentSnapshot()
                if (fresh != null && fresh.isPowerConnected) {
                    onCharging(fresh)
                } else {
                    if (fresh != null) _status.update { it.copy(charging = fresh) }
                    dispatch(ProtectionEvent.DisconnectConfirmed)
                }
            }

            is Command.RearmTimer -> {
                if (command.token != rearmToken) return
                dispatch(ProtectionEvent.RearmTimerElapsed)
            }

            is Command.SirenTimeout -> {
                if (command.token != sirenToken || machine.state != ProtectionState.ALARM_ACTIVE) return
                alarm.silence()
                record(HistoryEventType.ALARM_SOUND_TIMED_OUT, "Siren stopped after ${cachedAlarmSettings.soundDurationMinutes} min; still disconnected")
                _status.update { it.copy(sirenPlaying = false, sirenTimedOut = true) }
            }
        }
    }

    private suspend fun initialize() {
        val persisted = runCatching { settings.currentProtection() }.getOrNull()
        cachedAlarmSettings = runCatching { settings.currentSettings().alarm }.getOrDefault(cachedAlarmSettings)
        if (persisted != null && persisted.enabled && !serviceRunning && !machine.state.isMonitoring) {
            // The user wanted protection, but nothing is monitoring right now: tell the truth.
            machine = MachineState(ProtectionState.MONITORING_UNAVAILABLE)
            unavailableReason = INTERRUPTED_REASON
            if (!persisted.interruptionRecorded) {
                record(HistoryEventType.MONITORING_INTERRUPTED, "App process was stopped (system, update, restart or force stop)")
                settings.updateProtection { it.copy(interruptionRecorded = true) }
            }
        }
        _status.update { it.copy(initialized = true) }
        publish()
    }

    private suspend fun onServiceStartedInternal(restoredBySystem: Boolean) {
        serviceRunning = true
        lastConnected = null // the first reading after (re)start must always be evaluated
        when {
            activationPending -> {
                activationPending = false
                watchdogJob?.cancel()
                sessionStartedAt = clock.now()
                unavailableReason = null
                settings.updateProtection {
                    it.copy(enabled = true, sessionStartedAtMillis = sessionStartedAt, interruptionRecorded = false)
                }
                dispatch(ProtectionEvent.Activate)
            }
            restoredBySystem -> {
                val persisted = settings.currentProtection()
                if (!persisted.enabled) {
                    serviceController.stop()
                    return
                }
                if (machine.state != ProtectionState.MONITORING_UNAVAILABLE) {
                    machine = MachineState(ProtectionState.MONITORING_UNAVAILABLE)
                }
                sessionStartedAt = persisted.sessionStartedAtMillis ?: clock.now()
                unavailableReason = null
                val wasArmed = persisted.lastState == ProtectionState.ARMED ||
                    persisted.lastState == ProtectionState.ALARM_ACTIVE ||
                    persisted.lastState == ProtectionState.RECONNECTED
                dispatch(ProtectionEvent.MonitoringRestored(wasArmed))
            }
            !machine.state.isMonitoring -> {
                // Started without a request we know about: never monitor silently.
                serviceController.stop()
                return
            }
        }
        // Evaluate the initial charging state right away (sticky ACTION_BATTERY_CHANGED).
        charging.currentSnapshot()?.let { onCharging(it) }
    }

    private suspend fun onCharging(snapshot: ChargingSnapshot) {
        _status.update { it.copy(charging = snapshot) }
        val connected = snapshot.isPowerConnected
        if (connected == lastConnected) return // duplicate / level-only update
        lastConnected = connected
        if (!machine.state.isMonitoring) return
        dispatch(ProtectionEvent.PowerChanged(connected))
    }

    private suspend fun dispatch(event: ProtectionEvent) {
        val before = machine
        val transition = ProtectionStateMachine.reduce(before, event, cachedAlarmSettings.disconnectConfirmMillis)
        machine = transition.next
        // StartAlarm runs first (nothing may delay the siren), but its history entries are
        // written after the transition's own records so the log reads "disconnected, alarm started".
        deferredRecords = mutableListOf()
        for (effect in transition.effects) apply(effect)
        deferredRecords?.forEach { (type, detail) -> record(type, detail) }
        deferredRecords = null
        if (machine.state == ProtectionState.MONITORING_UNAVAILABLE && event is ProtectionEvent.MonitoringLost) {
            unavailableReason = event.reason
        }
        // Publish before persisting: persistence suspends, and observers (service lifecycle,
        // UI) must see the new state immediately.
        publish()
        persistIfChanged(before, machine)
    }

    private fun apply(effect: ProtectionEffect) {
        when (effect) {
            ProtectionEffect.StartAlarm -> startAlarm()
            ProtectionEffect.StopAlarm -> {
                sirenJob?.cancel(); sirenToken = -1
                alarm.stop()
                _status.update { it.copy(sirenPlaying = false, sirenTimedOut = false, alarmAudioFailed = false, alarmStartedAtMillis = null) }
            }
            is ProtectionEffect.ScheduleDisconnectCheck -> {
                val token = ++tokenCounter
                disconnectToken = token
                disconnectJob?.cancel()
                disconnectJob = scope.launch {
                    delay(effect.delayMillis)
                    commands.send(Command.DisconnectTimer(token))
                }
            }
            ProtectionEffect.CancelDisconnectCheck -> { disconnectJob?.cancel(); disconnectToken = -1 }
            is ProtectionEffect.ScheduleRearm -> {
                val token = ++tokenCounter
                rearmToken = token
                rearmJob?.cancel()
                rearmJob = scope.launch {
                    delay(effect.delayMillis)
                    commands.send(Command.RearmTimer(token))
                }
            }
            ProtectionEffect.CancelRearm -> { rearmJob?.cancel(); rearmToken = -1 }
            is ProtectionEffect.Record -> record(effect.type, effect.detail)
            ProtectionEffect.StopMonitoringService -> serviceController.stop()
        }
    }

    private fun startAlarm() {
        val config = cachedAlarmSettings
        val audioOk = runCatching { alarm.start(config) }.getOrDefault(false)
        val now = clock.now()
        _status.update {
            it.copy(sirenPlaying = audioOk, alarmAudioFailed = !audioOk, sirenTimedOut = false, alarmStartedAtMillis = now)
        }
        recordAfterTransition(HistoryEventType.ALARM_STARTED, if (audioOk) null else "Vibration and notification only")
        if (!audioOk) recordAfterTransition(HistoryEventType.ALARM_AUDIO_FAILED, null)
        if (audioOk && config.soundDurationMinutes > 0) {
            val token = ++tokenCounter
            sirenToken = token
            sirenJob?.cancel()
            sirenJob = scope.launch {
                delay(config.soundDurationMinutes * 60_000L)
                commands.send(Command.SirenTimeout(token))
            }
        }
    }

    private suspend fun persistIfChanged(before: MachineState, after: MachineState) {
        if (before.state == after.state) return
        when {
            after.state == ProtectionState.INACTIVE -> {
                sessionStartedAt = null
                settings.updateProtection {
                    it.copy(enabled = false, sessionStartedAtMillis = null, interruptionRecorded = false, lastState = ProtectionState.INACTIVE)
                }
            }
            after.state == ProtectionState.MONITORING_UNAVAILABLE ->
                settings.updateProtection { it.copy(interruptionRecorded = true) }
            after.state.isMonitoring ->
                settings.updateProtection { it.copy(lastState = after.state) }
        }
    }

    private fun markStartFailed(reason: String) {
        activationPending = false
        machine = MachineState(ProtectionState.MONITORING_UNAVAILABLE)
        unavailableReason = reason
        record(HistoryEventType.MONITORING_START_FAILED, reason)
    }

    private fun recordAfterTransition(type: HistoryEventType, detail: String?) {
        deferredRecords?.add(type to detail) ?: record(type, detail)
    }

    private fun record(type: HistoryEventType, detail: String? = null) {
        historyQueue.trySend(Triple(type, detail, clock.now()))
    }

    private fun publish() {
        _status.update {
            it.copy(
                state = machine.state,
                disconnectPending = machine.disconnectPending,
                sessionStartedAtMillis = if (machine.state.isMonitoring) sessionStartedAt else null,
                unavailableReason = if (machine.state == ProtectionState.MONITORING_UNAVAILABLE) unavailableReason else null,
            )
        }
    }

    companion object {
        const val ACTIVATION_TIMEOUT_MILLIS = 10_000L
        const val INTERRUPTED_REASON =
            "Monitoring stopped because the app was closed by the system, the phone restarted, the app was updated or force-stopped."
    }
}
