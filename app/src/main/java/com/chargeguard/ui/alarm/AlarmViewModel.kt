package com.chargeguard.ui.alarm

import androidx.lifecycle.ViewModel
import com.chargeguard.data.security.PinResult
import com.chargeguard.data.security.PinVerifier
import com.chargeguard.domain.model.ProtectionStatus
import com.chargeguard.domain.protection.ProtectionManager
import com.chargeguard.ui.pinMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class AlarmViewModel @Inject constructor(
    private val manager: ProtectionManager,
    private val pinVerifier: PinVerifier,
) : ViewModel() {
    val status: StateFlow<ProtectionStatus> = manager.status

    private val _pinError = MutableStateFlow<String?>(null)
    val pinError: StateFlow<String?> = _pinError.asStateFlow()

    val pinSet: Boolean get() = pinVerifier.isPinSet()

    /** Owner override. Only possible with the correct PIN; there is no PIN-less dismiss. */
    fun ownerStop(pin: String): Boolean {
        val result = pinVerifier.verify(pin)
        return if (result == PinResult.Success) {
            _pinError.value = null
            manager.ownerStop()
            true
        } else {
            _pinError.value = pinMessage(result)
            false
        }
    }

    fun clearPinError() { _pinError.value = null }
}
