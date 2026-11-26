package com.chargeguard.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chargeguard.domain.model.HistoryEvent
import com.chargeguard.domain.repository.HistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repo: HistoryRepository,
) : ViewModel() {
    /** Null while loading. */
    val events: StateFlow<List<HistoryEvent>?> = repo.observe(300)
        .map<List<HistoryEvent>, List<HistoryEvent>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun clear() { viewModelScope.launch { repo.clear() } }
}
