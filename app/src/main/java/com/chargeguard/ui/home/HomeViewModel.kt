package com.chargeguard.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chargeguard.core.permissions.PermissionChecker
import com.chargeguard.core.permissions.PermissionSnapshot
import com.chargeguard.data.security.PinResult
import com.chargeguard.data.security.PinVerifier
import com.chargeguard.domain.model.ChargingSnapshot
import com.chargeguard.domain.model.ProtectionState
import com.chargeguard.domain.model.ProtectionStatus
import com.chargeguard.domain.protection.ProtectionManager
import com.chargeguard.domain.repository.ChargingRepository
import com.chargeguard.ui.pinMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class HomeUiState(
    val status: ProtectionStatus = ProtectionStatus(),
    val charging: ChargingSnapshot? = null,
    val now: Long = System.currentTimeMillis(),
    val permissions: PermissionSnapshot = PermissionSnapshot(),
    val pinSet: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val manager: ProtectionManager,
    chargingRepository: ChargingRepository,
    private val permissionChecker: PermissionChecker,
    private val pinVerifier: PinVerifier,
) : ViewModel() {

    private val permissions = MutableStateFlow(permissionChecker.snapshot())
    private val pinSet = MutableStateFlow(pinVerifier.isPinSet())

    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000)
        }
    }

    /** Display-only reading so charging info is visible even while protection is off. */
    private val displayCharging = chargingRepository.observe()

    val uiState: StateFlow<HomeUiState> = combine(
        manager.status, displayCharging, ticker, permissions, pinSet,
    ) { status, local, now, perms, pin ->
        val newest = listOfNotNull(status.charging, local).maxByOrNull { it.timestampMillis }
        HomeUiState(status, newest, now, perms, pin)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState(status = manager.status.value))

    private val _pinError = MutableStateFlow<String?>(null)
    val pinError: StateFlow<String?> = _pinError.asStateFlow()

    fun refresh() {
        permissions.value = permissionChecker.snapshot()
        pinSet.value = pinVerifier.isPinSet()
    }

    fun activate() = manager.activate()

    /** Returns true if a PIN is required before deactivating. */
    fun requestDeactivate(): Boolean {
        if (manager.status.value.state == ProtectionState.ALARM_ACTIVE) return false
        if (pinVerifier.isPinSet()) return true
        manager.deactivate()
        return false
    }

    /** @return true when deactivation went ahead. */
    fun deactivateWithPin(pin: String): Boolean {
        val result = pinVerifier.verify(pin)
        return if (result == PinResult.Success) {
            _pinError.value = null
            manager.deactivate()
            true
        } else {
            _pinError.update { pinMessage(result) }
            false
        }
    }

    fun clearPinError() { _pinError.value = null }
}
