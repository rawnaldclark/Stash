package com.stash.feature.search

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.cache.AlbumCache
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.actions.TrackActionsDelegate
import com.stash.core.media.actions.TrackItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Album Discovery screen.
 *
 * Responsibilities (spec §8.4):
 *  - Hydrate [AlbumDiscoveryUiState.hero] from the five nav args (`browseId`
 *    required; `title`, `artist`, `thumbnailUrl`, `year` with sensible
 *    defaults) in `init` so the first frame paints the cover art + title
 *    while the network request is still in flight.
 *  - Call [AlbumCache.get] once per screen (the cache itself handles TTL +
 *    in-flight dedupe). Fold the resulting [com.stash.data.ytmusic.model.AlbumDetail]
 *    into state, flipping [AlbumDiscoveryUiState.status] from
 *    [AlbumDiscoveryStatus.Loading] to [AlbumDiscoveryStatus.Fresh].
 *  - Kick [PreviewPrefetcher.prefetch] exactly once with the top 6 track
 *    `videoId`s on the first emission with a non-empty tracklist, so tapping
 *    any of those rows hits a warm preview-URL cache.
 *  - Cross-reference the full tracklist against the local DB via
 *    [TrackActionsDelegate.refreshDownloadedIds] so already-downloaded rows
 *    paint with the green checkmark.
 *  - On a cache failure (cold miss + network error), transition to
 *    [AlbumDiscoveryStatus.Error] and emit a Snackbar-bound userMessage;
 *    [retry] flips back to Loading and re-runs the fetch.
 *  - Snapshot non-downloaded tracks into [AlbumDiscoveryUiState.downloadConfirmQueue]
 *    when the user taps "Download all" so the confirm step enqueues exactly
 *    what the user saw in the dialog, not a racy re-read of the delegate's
 *    `downloadedIds` after mid-dialog individual-track downloads.
 *  - Shuffle-play only the downloaded subset of the album's tracks via
 *    [PlayerRepository.setQueue] when the user taps the shuffle FAB (offline
 *    path — does not extract streaming URLs).
 *
 * Per-row preview + download state is owned by [TrackActionsDelegate] so this
 * VM's code paths match [SearchViewModel] and [ArtistProfileViewModel]
 * exactly. The screen reads `downloadingIds`, `downloadedIds`,
 * `previewLoadingId`, and `previewState` from `vm.delegate.*` directly.
 */
@HiltViewModel
class AlbumDiscoveryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val albumCache: AlbumCache,
    private val prefetcher: PreviewPrefetcher,
    private val playerRepository: PlayerRepository,
    private val musicRepository: MusicRepository,
    val delegate: TrackActionsDelegate,
) : ViewModel() {

    private val browseId: String = requireNotNull(savedStateHandle["browseId"]) {
        "SearchAlbumRoute requires a non-null browseId nav arg"
    }
    private val initialTitle: String = savedStateHandle["title"] ?: ""
    private val initialArtist: String = savedStateHandle["artist"] ?: ""
    private val initialThumb: String? = savedStateHandle["thumbnailUrl"]
    private val initialYear: String? = savedStateHandle["year"]

    private val _uiState = MutableStateFlow(
        AlbumDiscoveryUiState(
            hero = AlbumHeroState(
                title = initialTitle,
                artist = initialArtist,
                thumbnailUrl = initialThumb,
                year = initialYear,
                trackCount = 0,
                totalDurationMs = 0L,
            ),
            status = AlbumDiscoveryStatus.Loading,
        ),
    )
    val uiState: StateFlow<AlbumDiscoveryUiState> = _uiState.asStateFlow()

    /**
     * One-shot user-facing messages (snackbars). Uses a [MutableSharedFlow]
     * with a small buffer so rapid emissions during startup aren't dropped
     * before the UI subscribes.
     *
     * The screen merges this with [TrackActionsDelegate.userMessages] so
     * preview/download errors surface through the same snackbar host.
     */
    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    /**
     * Guards against kicking the preview prefetcher more than once per screen
     * lifetime — a retry-then-success path should NOT fire prefetch again
     * (the first emission already warmed the cache).
     */
    private var prefetchKicked = false

    /**
     * Job running the current [observeAlbum] call. Stored so [retry] can
     * cancel the in-flight fetch before relaunching.
     */
    private var loadJob: Job? = null

    init {
        // Must happen before any delegate action — the delegate reads
        // `scope()` lazily and throws if invoked before binding.
        delegate.bindToScope(viewModelScope)
        loadJob = viewModelScope.launch { observeAlbum() }
    }

    /**
     * Re-runs the cache fetch after a cold-miss failure. The screen calls
     * this from its error-card "Retry" button. Flips status back to
     * [AlbumDiscoveryStatus.Loading] before relaunching so the error card
     * disappears while the new fetch is in flight.
     */
    fun retry() {
        loadJob?.cancel()
        _uiState.update { it.copy(status = AlbumDiscoveryStatus.Loading) }
        loadJob = viewModelScope.launch { observeAlbum() }
    }

    /**
     * Snapshots the set of tracks that are NOT yet downloaded and flips the
     * confirm dialog flag. The screen reads [AlbumDiscoveryUiState.downloadConfirmQueue]
     * to render the dialog's "X tracks will be downloaded" line.
     *
     * Snapshot-based (not re-read on confirm) to prevent mid-dialog individual
     * downloads from skewing the batch.
     */
    fun onDownloadAllClicked() {
        val snapshot = _uiState.value.tracks.filter {
            it.videoId !in delegate.downloadedIds.value
        }
        _uiState.update { it.copy(showDownloadConfirm = true, downloadConfirmQueue = snapshot) }
    }

    /** User cancelled the download-all confirm dialog — reset both flags. */
    fun onDownloadAllDismissed() {
        _uiState.update { it.copy(showDownloadConfirm = false, downloadConfirmQueue = emptyList()) }
    }

    /**
     * User confirmed the download-all dialog. Enqueues the snapshot captured
     * at [onDownloadAllClicked] time, NOT a re-filter of [AlbumDiscoveryUiState.tracks]
     * against `delegate.downloadedIds.value`.
     */
    fun onDownloadAllConfirmed() {
        val queue = _uiState.value.downloadConfirmQueue
        _uiState.update { it.copy(showDownloadConfirm = false, downloadConfirmQueue = emptyList()) }
        queue.forEach { track ->
            delegate.downloadTrack(
                TrackItem(
                    videoId = track.videoId,
                    title = track.title,
                    artist = track.artist,
                    durationSeconds = track.durationSeconds,
                    thumbnailUrl = track.thumbnailUrl,
                ),
            )
        }
    }

    /**
     * Shuffle-plays the downloaded subset of this album's tracks. Intersect
     * [TrackActionsDelegate.downloadedIds] with the album's current track
     * videoIds, resolve to full [com.stash.core.model.Track] rows via
     * [MusicRepository.findByYoutubeIds], shuffle, and hand to
     * [PlayerRepository.setQueue]. No-op when the intersection is empty
     * (the screen hides the FAB in that case anyway).
     */
    fun shuffleDownloaded() {
        viewModelScope.launch {
            val downloadedVideoIds = delegate.downloadedIds.value
                .intersect(_uiState.value.tracks.map { it.videoId }.toSet())
            if (downloadedVideoIds.isEmpty()) return@launch
            val tracks = musicRepository.findByYoutubeIds(downloadedVideoIds)
            if (tracks.isEmpty()) return@launch
            playerRepository.setQueue(tracks.shuffled(), 0)
        }
    }

    override fun onCleared() {
        super.onCleared()
        delegate.onOwnerCleared()
        prefetcher.cancelAll()
    }

    private suspend fun observeAlbum() {
        try {
            val detail = albumCache.get(browseId)
            val totalMs = detail.tracks.sumOf { (it.durationSeconds * 1000).toLong() }
            _uiState.update {
                it.copy(
                    hero = AlbumHeroState(
                        title = detail.title,
                        artist = detail.artist,
                        thumbnailUrl = detail.thumbnailUrl ?: it.hero.thumbnailUrl,
                        year = detail.year ?: it.hero.year,
                        trackCount = detail.tracks.size,
                        totalDurationMs = totalMs,
                    ),
                    tracks = detail.tracks,
                    moreByArtist = detail.moreByArtist,
                    status = AlbumDiscoveryStatus.Fresh,
                )
            }
            if (!prefetchKicked && detail.tracks.isNotEmpty()) {
                prefetchKicked = true
                prefetcher.prefetch(detail.tracks.take(6).map { it.videoId })
            }
            delegate.refreshDownloadedIds(detail.tracks.map { it.videoId })
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.e(TAG, "album fetch failed for $browseId", t)
            _uiState.update {
                it.copy(
                    status = AlbumDiscoveryStatus.Error(
                        t.message ?: "Something went wrong.",
                    ),
                )
            }
            _userMessages.emit("Couldn't load album — tap Retry.")
        }
    }

    companion object {
        private const val TAG = "AlbumDiscoveryVM"
    }
}
