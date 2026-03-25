package com.stash.core.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized, in-memory manager for the current sync phase.
 *
 * All sync workers and UI components observe [phase] to render progress.
 * Only sync infrastructure should call the mutation methods.
 *
 * Progress weight distribution across phases:
 * - Authenticating   :  5%
 * - FetchingPlaylists: 15%
 * - Diffing          :  5%
 * - Downloading      : 70%  (interpolated per-track)
 * - Finalizing       :  5%
 */
@Singleton
class SyncStateManager @Inject constructor() {

    private val _phase = MutableStateFlow<SyncPhase>(SyncPhase.Idle)

    /** Observable stream of the current [SyncPhase]. */
    val phase: StateFlow<SyncPhase> = _phase.asStateFlow()

    /** True when any phase other than [SyncPhase.Idle] is active. */
    val isSyncing: Boolean
        get() = _phase.value !is SyncPhase.Idle &&
            _phase.value !is SyncPhase.Completed &&
            _phase.value !is SyncPhase.Error

    /** Transition to [SyncPhase.Authenticating]. */
    fun onAuthenticating() {
        _phase.value = SyncPhase.Authenticating
    }

    /** Transition to [SyncPhase.FetchingPlaylists]. */
    fun onFetchingPlaylists() {
        _phase.value = SyncPhase.FetchingPlaylists
    }

    /** Transition to [SyncPhase.Diffing]. */
    fun onDiffing() {
        _phase.value = SyncPhase.Diffing
    }

    /**
     * Transition to or update [SyncPhase.Downloading].
     *
     * @param downloaded Tracks downloaded so far.
     * @param total      Total tracks to download.
     */
    fun onDownloading(downloaded: Int, total: Int) {
        _phase.value = SyncPhase.Downloading(downloaded = downloaded, total = total)
    }

    /** Transition to [SyncPhase.Finalizing]. */
    fun onFinalizing() {
        _phase.value = SyncPhase.Finalizing
    }

    /** Mark sync as successfully completed. */
    fun onCompleted() {
        _phase.value = SyncPhase.Completed
    }

    /**
     * Mark sync as failed.
     *
     * @param message Human-readable error description.
     * @param cause   Optional underlying throwable.
     */
    fun onError(message: String, cause: Throwable? = null) {
        _phase.value = SyncPhase.Error(message = message, cause = cause)
    }

    /** Reset back to [SyncPhase.Idle]. */
    fun reset() {
        _phase.value = SyncPhase.Idle
    }
}
