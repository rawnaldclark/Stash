package com.stash.feature.search

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.cache.AlbumCache
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.actions.TrackActionsDelegate
import com.stash.core.media.preview.LosslessUrlPrefetcher
import com.stash.core.model.Playlist
import com.stash.core.model.Track
import com.stash.core.model.TrackItem
import com.stash.data.ytmusic.model.AlbumSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private val streamingPreference: com.stash.core.data.prefs.StreamingPreference,
    val delegate: TrackActionsDelegate,
    val losslessPrefetcher: LosslessUrlPrefetcher,
) : ViewModel() {

    private val browseId: String = requireNotNull(savedStateHandle["browseId"]) {
        "SearchAlbumRoute requires a non-null browseId nav arg"
    }
    private val initialTitle: String = savedStateHandle["title"] ?: ""
    private val initialArtist: String = savedStateHandle["artist"] ?: ""
    private val initialThumb: String? = savedStateHandle["thumbnailUrl"]
    private val initialYear: String? = savedStateHandle["year"]

    /** Which catalog this album came from — routes the cache load + play path. */
    private val albumSource: AlbumSource =
        savedStateHandle["source"] ?: AlbumSource.YOUTUBE

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

    // Add-to-playlist picker: the item awaiting a playlist choice (null = sheet closed).
    private val _playlistSheetItem = MutableStateFlow<TrackItem?>(null)
    val playlistSheetItem: StateFlow<TrackItem?> = _playlistSheetItem.asStateFlow()

    val userPlaylists: StateFlow<List<Playlist>> =
        delegate.userPlaylists.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** youtubeId of the currently-playing track, for the SongRow now-playing indicator. */
    val currentPlayingYoutubeId: StateFlow<String?> =
        playerRepository.playerState
            .map { it.currentTrack?.youtubeId }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    /** Online/Offline mode for the album-header chip. */
    val streamingEnabled: StateFlow<Boolean> =
        streamingPreference.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    fun applyStreamingMode(enabled: Boolean) {
        viewModelScope.launch { streamingPreference.setEnabled(enabled) }
    }

    fun onPlayNext(item: TrackItem) = delegate.playNext(item)

    fun onStartRadio(item: TrackItem) = delegate.startRadio(item)
    fun onAddToQueue(item: TrackItem) = delegate.addToQueue(item)
    fun onRequestAddToPlaylist(item: TrackItem) { _playlistSheetItem.value = item }
    fun onDismissPlaylistSheet() { _playlistSheetItem.value = null }
    fun onSaveToPlaylist(playlistId: Long) {
        _playlistSheetItem.value?.let { delegate.addToPlaylist(it, playlistId) }
        _playlistSheetItem.value = null
    }
    fun onCreatePlaylistAndAdd(name: String) {
        _playlistSheetItem.value?.let { delegate.createPlaylistAndAdd(it, name) }
        _playlistSheetItem.value = null
    }

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
        // Download keys on videoId, which Qobuz tracks lack — download-by-id is
        // out of scope for Phase 1, so the action is a no-op for Qobuz albums
        // AND playlists (the screen also hides the button; this is the guard).
        if (isNativeAlbum) return
        val snapshot = _uiState.value.tracks.filter {
            it.videoId !in delegate.downloadedIds.value
        }
        _uiState.update { it.copy(showDownloadConfirm = true, downloadConfirmQueue = snapshot) }
    }

    /** Whether download affordances should show — false for Qobuz albums (no videoId). */
    val downloadSupported: Boolean get() = albumSource == AlbumSource.YOUTUBE

    /**
     * True for a native Qobuz album OR a Qobuz playlist. Their tracks carry no
     * videoId, so the screen must NOT render the videoId-keyed preview/download
     * row (all rows would share the blank-videoId identity — one preview would
     * light up every row and play the same track). A simpler play-on-tap row is
     * used instead, and playback synthesises real persisted ids per track.
     */
    val isNativeAlbum: Boolean get() =
        albumSource == AlbumSource.QOBUZ || albumSource == AlbumSource.QOBUZ_PLAYLIST

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
        val albumTitle = _uiState.value.hero.title
        val albumArtist = _uiState.value.hero.artist
        _uiState.update { it.copy(showDownloadConfirm = false, downloadConfirmQueue = emptyList()) }
        queue.forEach { track ->
            delegate.downloadTrack(
                TrackItem(
                    videoId = track.videoId,
                    title = track.title,
                    artist = track.artist,
                    durationSeconds = track.durationSeconds,
                    thumbnailUrl = track.thumbnailUrl,
                    album = albumTitle,
                    albumArtist = albumArtist,
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

    /**
     * Play this album in streaming mode (or downloaded-only mode), starting
     * at [startIndex] within the album's track list. The screen exposes
     * this two ways:
     *  - "Play album" button in the [AlbumHero] action row (startIndex = 0)
     *  - tap on an individual track row (startIndex = that row's index)
     *
     * Album tracks are synthesised into [com.stash.core.model.Track]
     * domain objects with a `videoId.hashCode()` synthetic id — matches
     * the same pattern [PlayerRepositoryImpl.playFromStream] uses for
     * search-tab single-track playback. `setQueue` then resolves URLs
     * through Kennyy/squid via the standard streaming routing.
     */
    fun playAlbum(startIndex: Int = 0) {
        viewModelScope.launch {
            val tracks = buildQueueTracks()
            if (tracks.isEmpty()) return@launch
            val safeStart = startIndex.coerceIn(0, tracks.size - 1)
            playerRepository.setQueue(tracks, safeStart)
        }
    }

    /**
     * Append this album's tracks to the end of the current playback queue
     * without interrupting playback. Sibling to [playAlbum]; both share
     * [synthesizeDomainTracks] for track synthesis.
     */
    fun addAlbumToQueue() {
        viewModelScope.launch {
            val tracks = buildQueueTracks()
            if (tracks.isEmpty()) return@launch
            val added = playerRepository.addToQueue(tracks)
            _userMessages.emit(
                if (added) {
                    "Added album to queue"
                } else {
                    "Couldn't add album to queue"
                },
            )
        }
    }

    /**
     * Build the playable queue for [playAlbum]/[addAlbumToQueue].
     *
     * YouTube tracks keep the existing `videoId.hashCode()` synthetic id and
     * stream via the videoId. Qobuz tracks have no videoId, so they are
     * persisted by canonical identity ([MusicRepository.ensureTrackPersisted])
     * to obtain a REAL `tracks.id` before entering the queue — the persisted
     * queue is a list of track ids, so without a real row a Qobuz-native queue
     * would resume as nothing after a process kill, and the Now Playing heart
     * (which observes by id) couldn't reflect state. These streaming stubs are
     * `is_downloaded = 0`, so the downloaded-only orphan reaper never touches
     * them and the resume survives.
     */
    private suspend fun buildQueueTracks(): List<Track> {
        val base = synthesizeDomainTracks()
        // Qobuz albums AND playlists lack videoIds — persist each by canonical
        // identity to get a REAL, DISTINCT tracks.id. Without this every track
        // shares id=0 (from the blank videoId) and the queue can't advance past
        // the first row.
        if (!isNativeAlbum) return base
        return base.map { it.copy(id = musicRepository.ensureTrackPersisted(it)) }
    }

    /**
     * Synthesize [Track] domain objects from the loaded album's tracklist.
     * Empty when the album hasn't loaded yet. YouTube tracks carry the videoId
     * (+ synthetic hashCode id); Qobuz tracks carry `youtubeId = null` and
     * `id = 0` (a real PK is assigned by [buildQueueTracks] via persistence).
     * Both use `MusicSource.YOUTUBE` in Phase 1 — `MusicSource.QOBUZ` is a
     * Phase-2 concern; the label is inert for qbdlx metadata resolution.
     */
    private fun synthesizeDomainTracks(): List<Track> {
        val state = _uiState.value
        val tracks = state.tracks
        if (tracks.isEmpty()) return emptyList()
        val albumTitle = state.hero.title
        val albumArtist = state.hero.artist
        val albumArt = state.hero.thumbnailUrl
        val qobuz = isNativeAlbum
        return tracks.map { t ->
            Track(
                id = if (qobuz) 0L else t.videoId.hashCode().toLong(),
                title = t.title,
                artist = t.artist.ifBlank { albumArtist },
                album = albumTitle,
                durationMs = (t.durationSeconds * 1000L).toLong(),
                albumArtUrl = t.thumbnailUrl ?: albumArt,
                youtubeId = if (qobuz) null else t.videoId,
                source = com.stash.core.model.MusicSource.YOUTUBE,
                isStreamable = true,
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        delegate.onOwnerCleared()
        prefetcher.cancelAll()
    }

    private suspend fun observeAlbum() {
        try {
            val detail = albumCache.get(browseId, albumSource)
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
            // These are all keyed on `videoId`, which is blank ("") for Qobuz
            // tracks (they resolve by title/artist metadata, not a YouTube id).
            // Running them for a QOBUZ album would prefetch/refresh/backfill
            // against empty ids — at best wasted work, at worst mis-keying the
            // album backfill against other blank-youtubeId library rows. So the
            // videoId-keyed side-effects run for YouTube albums only.
            if (albumSource == AlbumSource.YOUTUBE) {
                if (!prefetchKicked && detail.tracks.isNotEmpty()) {
                    prefetchKicked = true
                    prefetcher.prefetch(detail.tracks.take(6).map { it.videoId })
                }
                delegate.refreshDownloadedIds(detail.tracks.map { it.videoId })

                // Backfill the `album` column on any of this album's tracks
                // that are already in the local library with an empty album.
                // This covers the case where the user previously downloaded a
                // track via a non-album-context path (loose search row, sync
                // from a service that didn't carry album metadata, an earlier
                // build that dropped the field) and is now visiting the album
                // page — we now know the album name, so the Library Albums
                // tab can group these tracks correctly without requiring a
                // re-download.
                val knownAlbum = detail.title
                val knownAlbumArtist = detail.artist
                if (knownAlbum.isNotBlank() || knownAlbumArtist.isNotBlank()) {
                    viewModelScope.launch {
                        runCatching {
                            musicRepository.backfillAlbumForTracks(
                                videoIds = detail.tracks.map { it.videoId },
                                album = knownAlbum,
                                albumArtist = knownAlbumArtist,
                            )
                        }.onFailure { e ->
                            Log.w(TAG, "backfillAlbumForTracks failed: ${e.message}")
                        }
                    }
                }
            }
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
