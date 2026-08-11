package com.stash.core.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class AuthExpiryState(
    val spotifyExpired: Boolean,
    val youtubeExpired: Boolean,
) {
    val anyExpired: Boolean get() = spotifyExpired || youtubeExpired
}

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
 * - VerifyingLibrary :  5%
 * - Downloading      : 65%  (interpolated per-track)
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

    private val _authExpiry = MutableStateFlow(AuthExpiryState(false, false))

    /**
     * Per-source auth expiry state. The Sync tab's AuthExpiredBanner
     * (composable) subscribes to this flow to show a "Re-authenticate"
     * CTA when either Spotify or YouTube returns expired credentials.
     * Updated by PlaylistFetchWorker at the head of each sync after
     * running the AuthHealthProbe pair.
     */
    val authExpiry: StateFlow<AuthExpiryState> = _authExpiry.asStateFlow()

    /** Probed at sync start by PlaylistFetchWorker. */
    fun onAuthExpiryProbed(state: AuthExpiryState) {
        _authExpiry.value = state
    }

    /** Transition to [SyncPhase.Authenticating]. */
    fun onAuthenticating() {
        _phase.value = SyncPhase.Authenticating
    }

    /**
     * Transition to (or update the live counter within) [SyncPhase.FetchingPlaylists].
     * Safe to call repeatedly with an increasing [playlistsFetched] as the
     * fetch worker processes each playlist/mix, so the UI shows a running
     * count instead of an apparently-frozen bar during a long fetch.
     */
    fun onFetchingPlaylists(playlistsFetched: Int = 0) {
        _phase.value = SyncPhase.FetchingPlaylists(playlistsFetched)
    }

    /**
     * Transition to (or update progress within) [SyncPhase.Diffing]. Unlike
     * fetch, the diff worker knows its total playlist count upfront, so this
     * reports a real fraction, not just a live count.
     */
    fun onDiffing(playlistsDiffed: Int = 0, totalPlaylists: Int = 0) {
        _phase.value = SyncPhase.Diffing(playlistsDiffed, totalPlaylists)
    }

    /** Default mirrors LibraryReconciliationUseCase.TOTAL_STEPS (sweep, reset,
     *  resume-stale, disk-check, requeue = 5 steps). Kept as a literal rather
     *  than importing across the sync/library boundary — update this if
     *  TOTAL_STEPS changes. */
    fun onVerifyingLibrary(step: Int = 0, total: Int = 5) {
        _phase.value = SyncPhase.VerifyingLibrary(step, total)
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
