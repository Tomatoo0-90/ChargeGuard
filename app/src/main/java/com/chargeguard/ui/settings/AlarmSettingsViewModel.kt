package com.chargeguard.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chargeguard.alarm.AlarmController
import com.chargeguard.core.permissions.PermissionChecker
import com.chargeguard.domain.model.AlarmSettings
import com.chargeguard.domain.model.AlarmSound
import com.chargeguard.domain.model.ProtectionState
import com.chargeguard.domain.protection.ProtectionManager
import com.chargeguard.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlarmSettingsUiState(
    val settings: AlarmSettings = AlarmSettings(),
    val alarmActive: Boolean = false,
    val systemAlarmVolume: Float = 1f,
    val fullScreenAllowed: Boolean = true,
)

@HiltViewModel
class AlarmSettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val alarmController: AlarmController,
    private val permissionChecker: PermissionChecker,
    manager: ProtectionManager,
) : ViewModel() {

    private val systemVolume = MutableStateFlow(alarmController.systemAlarmVolumeFraction())
    private val fullScreen = MutableStateFlow(permissionChecker.snapshot().fullScreenAllowed)

    val uiState: StateFlow<AlarmSettingsUiState> = combine(
        repo.settings.map { it.alarm },
        manager.status.map { it.state == ProtectionState.ALARM_ACTIVE || it.state == ProtectionState.RECONNECTED },
        systemVolume,
        fullScreen,
    ) { s, active, vol, fs -> AlarmSettingsUiState(s, active, vol, fs) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlarmSettingsUiState())

    fun refresh() {
        systemVolume.value = alarmController.systemAlarmVolumeFraction()
        fullScreen.value = permissionChecker.snapshot().fullScreenAllowed
    }

    fun setSound(sound: AlarmSound) = launch { repo.setAlarmSound(sound) }
    fun setVolume(v: Float) = launch { repo.setAlarmVolume(v) }
    fun setRaiseVolume(b: Boolean) = launch { repo.setRaiseSystemAlarmVolume(b) }
    fun setVibration(b: Boolean) = launch { repo.setVibrationEnabled(b) }
    fun setDuration(m: Int) = launch { repo.setSoundDurationMinutes(m) }
    fun setConfirm(ms: Long) = launch { repo.setDisconnectConfirmMillis(ms) }
    fun setFullScreen(b: Boolean) = launch { repo.setFullScreenAlarm(b) }

    /** Preview is refused while a real alarm is active (controller enforces it too). */
    fun preview(sound: AlarmSound) {
        if (uiState.value.alarmActive) return
        alarmController.preview(sound, uiState.value.settings.volume)
    }

    fun stopPreview() = alarmController.stopPreview()

    fun openFullScreenSettings(activityContext: android.content.Context) =
        permissionChecker.launch(activityContext, permissionChecker.fullScreenSettingsIntent())

    override fun onCleared() {
        alarmController.stopPreview()
    }

    private fun launch(block: suspend () -> Unit) { viewModelScope.launch { block() } }
}
