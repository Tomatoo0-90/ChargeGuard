package com.chargeguard.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chargeguard.domain.model.AppSettings
import com.chargeguard.domain.model.ProtectionStatus
import com.chargeguard.domain.protection.ProtectionManager
import com.chargeguard.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    manager: ProtectionManager,
) : ViewModel() {
    /** Null until DataStore has been read. */
    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .map<AppSettings, AppSettings?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val status: StateFlow<ProtectionStatus> = manager.status
}
