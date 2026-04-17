package com.stash.feature.search

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.common.perf.PerfLog
import com.stash.core.data.cache.ArtistCache
import com.stash.core.data.cache.CachedProfile
import com.stash.core.media.actions.TrackActionsDelegate
import com.stash.data.ytmusic.model.ArtistProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Artist Profile screen.
 *
 * Responsibilities (spec §8.3):
 *  - Hydrate the [ArtistProfileUiState.hero] from the three nav args
 *    (`artistId`, `name`, `avatarUrl`) on construction so the first frame
 *    after navigation paints a name + avatar — the < 50 ms hero target.
 *  - Subscribe to [ArtistCache.get] for the full profile and fold each
 *    [CachedProfile] emission into the state, flipping `status` between
 *    [ArtistProfileStatus.Fresh] and [ArtistProfileStatus.Stale].
 *  - Kick [PreviewPrefetcher.prefetch] exactly once with the Popular
 *    `videoId`s on the first emission that has a non-empty Popular list,
 *    so a tap on a Popular row hits a warm preview-URL cache.
 *  - On a [CachedProfile.Stale] with `refreshFailed = true`, emit a
 *    one-shot snackbar message via [userMessages] WITHOUT flipping status
 *    to [ArtistProfileStatus.Error] — the cached data keeps rendering.
 *
 * Per-row preview + download state was extracted to [TrackActionsDelegate]
 * in the Album Discovery phase-1 migration so this VM (and [SearchViewModel])
 * share the exact same code paths. The screen reads `downloadingIds`,
 * `downloadedIds`, `previewLoadingId`, and `previewState` from
 * `vm.delegate.*` directly.
 */
@HiltViewModel
class ArtistProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val artistCache: ArtistCache,
    private val prefetcher: PreviewPrefetcher,
    val delegate: TrackActionsDelegate,
) : ViewModel() {

    private val artistId: String = requireNotNull(savedStateHandle["artistId"]) {
        "SearchArtistRoute requires a non-null artistId nav arg"
    }
    private val initialName: String = savedStateHandle["name"] ?: ""
    private val initialAvatar: String? = savedStateHandle["avatarUrl"]

    private val _uiState = MutableStateFlow(
        ArtistProfileUiState(
            hero = HeroState(
                name = initialName,
                avatarUrl = initialAvatar,
                subscribersText = null,
            ),
            status = ArtistProfileStatus.Loading,
        ),
    )
    val uiState: StateFlow<ArtistProfileUiState> = _uiState.asStateFlow()

    /**
     * One-shot user-facing messages (snackbars). Uses a [MutableSharedFlow]
     * with a small buffer so rapid emissions during startup aren't dropped
     * when the UI hasn't subscribed yet.
     *
     * The screen merges this with [TrackActionsDelegate.userMessages] so
     * preview/download errors surface through the same snackbar host.
     */
    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    /**
     * Guards against kicking the prefetcher more than once per screen
     * lifetime — a `Stale -> Fresh` transition should NOT fire prefetch
     * a second time (the first emission already warmed the cache).
     */
    private var prefetchKicked = false

    /**
     * Job running the current [observeCache] subscription. Stored so
     * [retry] can cancel the old subscription before relaunching.
     */
    private var cacheJob: Job? = null

    init {
        // Must happen before any delegate action — the delegate reads
        // `scope()` lazily and throws if invoked before binding.
        delegate.bindToScope(viewModelScope)

        // Nav-args hero already painted by the MutableStateFlow seed above —
        // this bookend marks the "first-frame" moment so latency verification
        // can diff skeleton → paint against spec §4.1 <50ms hero target.
        PerfLog.d { "ArtistProfile hero first-frame nav-args (name=$initialName)" }
        cacheJob = viewModelScope.launch { observeCache() }
    }

    /**
     * Subscribes to [ArtistCache.get] for [artistId] and folds each emission
     * into the UI state. Extracted into a suspend function so [retry] can
     * re-run the exact same pipeline after a cold-miss failure without
     * duplicating the body.
     */
    private suspend fun observeCache() {
        val t0 = SystemClock.elapsedRealtime()
        artistCache.get(artistId)
            .catch { t ->
                // Cold miss with no cached fallback — the flow throws and
                // would otherwise crash viewModelScope. Flip to Error and
                // let the screen render a message instead. `flow.catch`
                // intentionally does not swallow CancellationException.
                Log.e(TAG, "cache failure for $artistId", t)
                _uiState.value = _uiState.value.copy(
                    status = ArtistProfileStatus.Error(
                        t.message ?: "Something went wrong.",
                    ),
                )
                _userMessages.emit("Couldn't load artist — tap Retry.")
            }
            .collect { cached ->
                when (cached) {
                    is CachedProfile.Fresh -> apply(
                        profile = cached.profile,
                        status = ArtistProfileStatus.Fresh,
                        t0 = t0,
                    )
                    is CachedProfile.Stale -> {
                        apply(
                            profile = cached.profile,
                            status = ArtistProfileStatus.Stale,
                            t0 = t0,
                        )
                        if (cached.refreshFailed) {
                            _userMessages.emit("Couldn't refresh — showing cached.")
                        }
                    }
                }
            }
    }

    /**
     * Re-runs the cache subscription after a cold-miss failure. The screen
     * calls this from its error-card "Retry" button. Flips status back to
     * [ArtistProfileStatus.Loading] before relaunching so the error card
     * disappears while the new subscription is in flight.
     */
    fun retry() {
        cacheJob?.cancel()
        _uiState.update { it.copy(status = ArtistProfileStatus.Loading) }
        cacheJob = viewModelScope.launch { observeCache() }
    }

    /**
     * Fold a freshly-arrived profile into the UI state and kick the preview
     * prefetcher on the first non-empty Popular list we see.
     */
    private fun apply(
        profile: ArtistProfile,
        status: ArtistProfileStatus,
        t0: Long,
    ) {
        _uiState.value = _uiState.value.copy(
            hero = HeroState(
                name = profile.name,
                avatarUrl = profile.avatarUrl,
                subscribersText = profile.subscribersText,
            ),
            popular = profile.popular,
            albums = profile.albums,
            singles = profile.singles,
            related = profile.related,
            status = status,
        )
        if (!prefetchKicked && profile.popular.isNotEmpty()) {
            prefetchKicked = true
            val videoIds = profile.popular.map { it.videoId }
            prefetcher.prefetch(videoIds)
            // Cross-reference Popular against the local DB so already-downloaded
            // rows paint with the green checkmark — mirrors SearchViewModel's
            // refreshDownloadedIds path for the sectioned results list.
            viewModelScope.launch { delegate.refreshDownloadedIds(videoIds) }
        }
        PerfLog.d {
            "ArtistProfile paint after ${SystemClock.elapsedRealtime() - t0}ms (status=$status)"
        }
    }

    override fun onCleared() {
        super.onCleared()
        delegate.onOwnerCleared()
        prefetcher.cancelAll()
    }

    companion object {
        /**
         * Error log tag for cache-failure paths. Latency bookends route
         * through [PerfLog] (tag `Perf`) and do NOT use [TAG].
         */
        private const val TAG = "ArtistProfileVM"
    }
}
