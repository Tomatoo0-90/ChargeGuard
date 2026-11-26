package com.chargeguard.testing

import com.chargeguard.domain.model.AlarmSettings
import com.chargeguard.domain.model.AlarmSound
import com.chargeguard.domain.model.AppSettings
import com.chargeguard.domain.model.AppTheme
import com.chargeguard.domain.model.ChargeStatus
import com.chargeguard.domain.model.ChargingSnapshot
import com.chargeguard.domain.model.HistoryEvent
import com.chargeguard.domain.model.HistoryEventType
import com.chargeguard.domain.model.PersistedProtection
import com.chargeguard.domain.model.PowerSource
import com.chargeguard.domain.repository.AlarmOutput
import com.chargeguard.domain.repository.ChargingRepository
import com.chargeguard.domain.repository.Clock
import com.chargeguard.domain.repository.HistoryRepository
import com.chargeguard.domain.repository.MonitoringServiceController
import com.chargeguard.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class FakeClock(var time: Long = 1_700_000_000_000L) : Clock {
    override fun now(): Long = time
}

fun snapshot(
    source: PowerSource,
    status: ChargeStatus = if (source == PowerSource.NONE) ChargeStatus.DISCHARGING else ChargeStatus.CHARGING,
    percent: Int = 80,
    ts: Long = 0L,
) = ChargingSnapshot(source, status, percent, ts)

class FakeChargingRepository(var current: ChargingSnapshot? = snapshot(PowerSource.NONE)) : ChargingRepository {
    val events = MutableSharedFlow<ChargingSnapshot>(extraBufferCapacity = 64)
    override fun currentSnapshot(): ChargingSnapshot? = current
    override fun observe(): Flow<ChargingSnapshot> = events
}

class FakeSettingsRepository(
    initialSettings: AppSettings = AppSettings(),
    initialProtection: PersistedProtection = PersistedProtection(),
) : SettingsRepository {
    val settingsState = MutableStateFlow(initialSettings)
    val protectionState = MutableStateFlow(initialProtection)

    override val settings: Flow<AppSettings> = settingsState
    override val protection: Flow<PersistedProtection> = protectionState
    override suspend fun currentSettings() = settingsState.value
    override suspend fun currentProtection() = protectionState.value
    override suspend fun updateProtection(transform: (PersistedProtection) -> PersistedProtection) = protectionState.update(transform)

    private fun alarm(block: AlarmSettings.() -> AlarmSettings) = settingsState.update { it.copy(alarm = it.alarm.block()) }
    override suspend fun setOnboardingCompleted(done: Boolean) = settingsState.update { it.copy(onboardingCompleted = done) }
    override suspend fun setTheme(theme: AppTheme) = settingsState.update { it.copy(theme = theme) }
    override suspend fun setShowBatteryInNotification(show: Boolean) = settingsState.update { it.copy(showBatteryInNotification = show) }
    override suspend fun setAlarmSound(sound: AlarmSound) = alarm { copy(sound = sound) }
    override suspend fun setAlarmVolume(volume: Float) = alarm { copy(volume = volume) }
    override suspend fun setRaiseSystemAlarmVolume(enabled: Boolean) = alarm { copy(raiseSystemAlarmVolume = enabled) }
    override suspend fun setVibrationEnabled(enabled: Boolean) = alarm { copy(vibrationEnabled = enabled) }
    override suspend fun setSoundDurationMinutes(minutes: Int) = alarm { copy(soundDurationMinutes = minutes) }
    override suspend fun setDisconnectConfirmMillis(millis: Long) = alarm { copy(disconnectConfirmMillis = millis) }
    override suspend fun setFullScreenAlarm(enabled: Boolean) = alarm { copy(fullScreenAlarm = enabled) }
}

class FakeHistoryRepository : HistoryRepository {
    val recorded = mutableListOf<HistoryEvent>()
    private val state = MutableStateFlow<List<HistoryEvent>>(emptyList())
    val types: List<HistoryEventType> get() = recorded.map { it.type }

    override fun observe(limit: Int): Flow<List<HistoryEvent>> = state.map { it.take(limit) }
    override suspend fun record(type: HistoryEventType, detail: String?, timestampMillis: Long?) {
        recorded += HistoryEvent(recorded.size + 1L, type, timestampMillis ?: 0L, detail)
        state.value = recorded.reversed()
    }
    override suspend fun clear() { recorded.clear(); state.value = emptyList() }
}

class FakeAlarmOutput(var audioWorks: Boolean = true) : AlarmOutput {
    var startCount = 0
    var stopCount = 0
    var silenceCount = 0
    var active = false
    var lastSettings: AlarmSettings? = null

    override fun start(settings: AlarmSettings): Boolean {
        startCount++
        active = true
        lastSettings = settings
        return audioWorks
    }
    override fun silence() { silenceCount++ }
    override fun stop() { stopCount++; active = false }
}

class FakeServiceController(var startResult: Result<Unit> = Result.success(Unit)) : MonitoringServiceController {
    var startCount = 0
    var stopCount = 0
    override fun start(): Result<Unit> { startCount++; return startResult }
    override fun stop() { stopCount++ }
}
