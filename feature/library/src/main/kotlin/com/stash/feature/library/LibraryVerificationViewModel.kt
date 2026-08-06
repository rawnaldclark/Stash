package com.stash.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.library.LibraryMaintenanceScheduler
import com.stash.core.data.library.LibraryVerificationState
import com.stash.core.data.library.LibraryVerificationStateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class LibraryVerificationViewModel @Inject constructor(
    private val scheduler: LibraryMaintenanceScheduler,
    stateManager: LibraryVerificationStateManager,
) : ViewModel() {

    val state: StateFlow<LibraryVerificationState> = stateManager.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryVerificationState.Idle)

    /** Returns false (no-op) if a sync or another verification is already running. */
    fun verifyLibrary(): Boolean = scheduler.triggerVerification()
}