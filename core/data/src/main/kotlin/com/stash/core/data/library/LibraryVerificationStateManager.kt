package com.stash.core.data.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface LibraryVerificationState {
    data object Idle : LibraryVerificationState
    data class Running(val step: Int, val total: Int) : LibraryVerificationState
    data class Done(val result: ReconciliationResult, val atEpochMs: Long) : LibraryVerificationState
    data class Failed(val message: String) : LibraryVerificationState
}

@Singleton
class LibraryVerificationStateManager @Inject constructor() {
    private val _state = MutableStateFlow<LibraryVerificationState>(LibraryVerificationState.Idle)
    val state: StateFlow<LibraryVerificationState> = _state.asStateFlow()

    val isRunning: Boolean get() = _state.value is LibraryVerificationState.Running

    fun onProgress(step: Int, total: Int) {
        _state.value = LibraryVerificationState.Running(step, total)
    }

    fun onDone(result: ReconciliationResult) {
        _state.value = LibraryVerificationState.Done(result, System.currentTimeMillis())
    }

    fun onFailed(message: String) {
        _state.value = LibraryVerificationState.Failed(message)
    }

    fun reset() {
        _state.value = LibraryVerificationState.Idle
    }
}