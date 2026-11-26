package com.chargeguard.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chargeguard.core.permissions.PermissionChecker
import com.chargeguard.core.permissions.PermissionSnapshot
import com.chargeguard.data.security.PinResult
import com.chargeguard.data.security.PinVerifier
import com.chargeguard.domain.model.AppSettings
import com.chargeguard.domain.model.AppTheme
import com.chargeguard.domain.model.ProtectionState
import com.chargeguard.domain.protection.ProtectionManager
import com.chargeguard.domain.repository.SettingsRepository
import com.chargeguard.ui.pinMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val permissions: PermissionSnapshot = PermissionSnapshot(),
    val pinSet: Boolean = false,
    val protectionOn: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val permissionChecker: PermissionChecker,
    private val pinVerifier: PinVerifier,
    manager: ProtectionManager,
) : ViewModel() {
    private val permissions = MutableStateFlow(permissionChecker.snapshot())
    private val pinSet = MutableStateFlow(pinVerifier.isPinSet())

    val uiState: StateFlow<SettingsUiState> = combine(repo.settings, permissions, pinSet, manager.status) { s, p, pin, st ->
        SettingsUiState(s, p, pin, st.state != ProtectionState.INACTIVE && st.state != ProtectionState.MONITORING_UNAVAILABLE)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    val checker: PermissionChecker get() = permissionChecker

    fun refresh() {
        permissions.value = permissionChecker.snapshot()
        pinSet.value = pinVerifier.isPinSet()
    }

    fun setTheme(theme: AppTheme) { viewModelScope.launch { repo.setTheme(theme) } }
    fun setShowBattery(show: Boolean) { viewModelScope.launch { repo.setShowBatteryInNotification(show) } }

    /** @return error message or null on success. */
    fun setPin(newPin: String, confirm: String, current: String?): String? {
        if (newPin != confirm) return "The two PINs don't match."
        val r = pinVerifier.setPin(newPin, current)
        refresh()
        return if (r == PinResult.Success) null else pinMessage(r)
    }

    fun removePin(current: String): String? {
        val r = pinVerifier.removePin(current)
        refresh()
        return if (r == PinResult.Success) null else pinMessage(r)
    }
}
