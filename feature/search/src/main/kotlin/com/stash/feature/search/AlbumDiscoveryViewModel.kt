package com.stash.feature.search

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.cache.AlbumCache
import com.stash.core.data.discography.QobuzAlbumUnavailableException
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.actions.TrackActionsDelegate
import com.stash.core.media.preview.LosslessUrlPrefetcher
import com.stash.core.model.Playlist
import com.stash.core.model.Track
import com.stash.core.model.TrackItem
import com.stash.data.download.lossless.qobuz.QobuzCandidateMatcher
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.AlbumDetail
import com.stash.data.ytmusic.model.AlbumSearch
import com.stash.data.ytmusic.model.AlbumSource
import com.stash.data.ytmusic.model.AlbumSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.Normalizer
import javax.inject.Inject
import kotlin.math.abs

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
 *    [AlbumDiscoveryStatus.Error] in plain words and emit a Snackbar-bound
 *    userMessage; [retry] flips back to Loading and re-runs the fetch.
 *  - #481: open a Qobuz album that isn't sold in the user's country from
 *    YouTube Music instead ([loadAlbum]), or say it isn't available, with no
 *    Retry, when there's no confident YouTube Music copy.
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
    private val ytMusicApiClient: YTMusicApiClient,
    private val prefetcher: PreviewPrefetcher,
    private val playerRepository: PlayerRepository,
    private val musicRepository: MusicRepository,
    private val streamingPreference: com.stash.core.data.prefs.StreamingPreference,
    val delegate: TrackActionsDelegate,
    val losslessPrefetcher: LosslessUrlPrefetcher,
) : ViewModel() {

    private val initialTitle: String = savedStateHandle["title"] ?: ""
    private val initialArtist: String = savedStateHandle["artist"] ?: ""
    private val initialThumb: String? = savedStateHandle["thumbnailUrl"]
    private val initialYear: String? = savedStateHandle["year"]

    /**
     * The Qobuz release's track count, when the card that opened this screen knew it
     * (Home's Qobuz rows, the artist page's Qobuz albums). Qobuz sends no release
     * type, so this is how a Qobuz single is told from an album (#481).
     */
    private val qobuzTrackCount: Int? = savedStateHandle["qobuzTrackCount"]

    /**
     * The album on screen: the nav args' browse id and catalog, until #481 swaps a
     * Qobuz album that isn't sold in the user's country for its YouTube Music copy.
     * State rather than vals, so everything keyed on them follows the swap: the
     * cache load, the saved-album key, download support, the YouTube-only steps.
     */
    private val album = MutableStateFlow(
        AlbumRef(
            id = requireNotNull(savedStateHandle["browseId"]) {
                "SearchAlbumRoute requires a non-null browseId nav arg"
            },
            source = savedStateHandle["source"] ?: AlbumSource.YOUTUBE,
        ),
    )
    /** The album the nav args named, kept for its library key after a #481 switch. */
    private val navAlbum: AlbumRef = album.value
    private val browseId: String get() = album.value.id

    /** Which catalog this album came from — routes the cache load + play path. */
    private val albumSource: AlbumSource get() = album.value.source

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

    /**
     * #304: true once this album sits in the library as a saved playlist, under the
     * album on screen or, after a #481 switch, under the Qobuz album it replaced
     * (saved in a country Qobuz sells in, or over a VPN), so Save doesn't file it twice.
     */
    val isSaved: StateFlow<Boolean> =
        combine(userPlaylists, album) { lists, ref ->
            lists.any { it.sourceId == ref.savedSourceId || it.sourceId == navAlbum.savedSourceId }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

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
    /**
     * #304: keep this album in the library WITHOUT downloading it — a custom
     * playlist named after the album, wearing its art, holding its tracks.
     * Tracks are persisted by identity, so one already in the library is
     * reused, never duplicated. Downloading stays a separate, explicit step.
     */
    fun saveAlbum() {
        viewModelScope.launch {
            if (isSaved.value) {
                _userMessages.emit("Already in your library")
                return@launch
            }
            val tracks = synthesizeDomainTracks()
            if (tracks.isEmpty()) return@launch
            val ids = tracks.map { musicRepository.ensureTrackPersisted(it.copy(id = 0L)) }
            val hero = _uiState.value.hero
            // #479: once the playlist exists it must get its tracks, or isSaved blocks a re-save.
            withContext(NonCancellable) {
                val playlistId = musicRepository.ensureCustomPlaylist(
                    name = hero.title,
                    sourceId = album.value.savedSourceId,
                    artUrl = hero.thumbnailUrl,
                )
                musicRepository.addTracksToPlaylist(ids, playlistId)
            }
            _userMessages.emit("Saved to your library")
        }
    }

    fun addAlbumToQueue() {
        viewModelScope.launch {
            val tracks = buildQueueTracks()
            if (tracks.isEmpty()) return@launch
            val added = playerRepository.addToQueue(tracks)
            if (added && delegate.inListenTogether) return@launch // the session confirms it
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
            val detail = loadAlbum()
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
            if (t is QobuzAlbumUnavailableException) {
                // Not sold here and no YouTube Music copy. Retry can't change that,
                // so the card offers none, and there's nothing to snackbar about.
                Log.i(TAG, "album $browseId isn't sold in this country and has no YouTube Music copy")
                _uiState.update {
                    it.copy(status = AlbumDiscoveryStatus.Error(NOT_AVAILABLE, canRetry = false))
                }
                return
            }
            Log.e(TAG, "album fetch failed for $browseId", t)
            // Plain words: the exception's text can be Qobuz's raw JSON reply (#481).
            _uiState.update { it.copy(status = AlbumDiscoveryStatus.Error(LOAD_FAILED)) }
            _userMessages.emit("Couldn't load album — tap Retry.")
        }
    }

    /**
     * Loads [album], or its YouTube Music copy when Qobuz doesn't sell it in the
     * user's country (#481). Qobuz picks its store from the caller's IP: where it
     * doesn't sell, Home still lists its new releases, but album/get 404s for every
     * one of them. Playback never needed Qobuz, only the track list did, so the
     * same album from YouTube Music is a whole album page. The status stays
     * Loading throughout, so the 404 never flashes up as an error. Rethrows the
     * [QobuzAlbumUnavailableException] when there's no confident copy, and throws
     * an IOException when YouTube didn't answer or its album page didn't load
     * (both retryable).
     */
    private suspend fun loadAlbum(): AlbumDetail {
        val ref = album.value
        return try {
            albumCache.get(ref.id, ref.source)
        } catch (e: QobuzAlbumUnavailableException) {
            val copy = findYouTubeCopy() ?: throw e
            Log.i(TAG, "Qobuz album ${ref.id} isn't sold here; opening its YouTube Music copy ${copy.id}")
            album.value = AlbumRef(copy.id, AlbumSource.YOUTUBE)
            albumCache.get(copy.id, AlbumSource.YOUTUBE)
        }
    }

    /**
     * #481: this album on YouTube Music, picked by [pickYouTubeCopy] from what the
     * search turns up (its top-result card, then the Albums shelf), or null when none
     * is this album. Throws when YouTube didn't answer at all, so the screen offers a
     * Retry instead of saying the album isn't available.
     */
    private suspend fun findYouTubeCopy(): AlbumSummary? {
        val candidates = when (val search = ytMusicApiClient.searchAlbums(initialTitle, initialArtist)) {
            AlbumSearch.Failed -> throw IOException("YouTube Music didn't answer the search for '$initialTitle'")
            is AlbumSearch.Answered -> search.albums
        }
        val copy = pickYouTubeCopy(candidates, initialTitle, initialArtist, initialYear, qobuzTrackCount)
        if (copy == null) {
            Log.i(TAG, "none of ${candidates.size} YouTube Music albums is '$initialTitle' by '$initialArtist' ($initialYear)")
        }
        return copy
    }

    /** Which album the screen shows: a catalog id and the catalog it belongs to. */
    private data class AlbumRef(val id: String, val source: AlbumSource) {
        /** The library playlist that stands for this album: one per source + browse id. */
        val savedSourceId: String get() = "album:${source.name.lowercase()}:$id"
    }

    companion object {
        private const val TAG = "AlbumDiscoveryVM"

        /** A failure Retry can fix. Plain words, whatever the exception said. */
        internal const val LOAD_FAILED = "Check your connection and try again."

        /** #481: not sold in the user's country, and no YouTube Music copy. */
        internal const val NOT_AVAILABLE = "This album isn't available in your country."
    }
}

/**
 * #481: whether [candidate] is the album [title] by [artist] from [year]. Strict on
 * purpose, because opening the wrong album is worse than saying this one isn't
 * available. It has to have:
 *  - the same artist ([sameArtist]), and the same title once case, punctuation,
 *    accents, a featured credit ("feat. X") and an edition ("(Super Deluxe)",
 *    "- 2009 Remaster") are set aside. Anything else, "(Live)", "(Remixes)",
 *    "(Instrumental)", "(Sped Up)", "(Vol. 2)", makes it a different release;
 *  - the "Single" label only when the Qobuz release is known to be a single too
 *    ([qobuzTrackCount] 1 to 3). For an album, or when the length is unknown, a
 *    single that shares the title is its title track;
 *  - a release year within one of [year], when both are known, unless an edition
 *    was set aside on either side: the two catalogs date a remaster differently
 *    (YouTube calls "Abbey Road (Remastered 2009)" 1969, Qobuz calls "Rumours
 *    (2004 Remaster)" 1977).
 */
internal fun isSameAlbum(
    candidate: AlbumSummary,
    title: String,
    artist: String,
    year: String? = null,
    qobuzTrackCount: Int? = null,
): Boolean {
    val want = albumTitleKey(title)
    val got = albumTitleKey(candidate.title)
    return want.key.isNotEmpty() && got.key == want.key &&
        sameArtist(candidate.artist, artist) &&
        (!candidate.releaseType.equals("Single", ignoreCase = true) || isSingleLength(qobuzTrackCount)) &&
        (want.dropsEdition || got.dropsEdition || yearsAgree(candidate.year, year))
}

/**
 * #481: the YouTube Music copy of a Qobuz release among [candidates] (YouTube's best
 * guess first): of the ones [isSameAlbum] accepts, the one whose label suits the Qobuz
 * release best ([labelRank]), first in YouTube's order among equals; null when none is.
 */
internal fun pickYouTubeCopy(
    candidates: List<AlbumSummary>,
    title: String,
    artist: String,
    year: String?,
    qobuzTrackCount: Int?,
): AlbumSummary? = candidates
    .filter { isSameAlbum(it, title, artist, year, qobuzTrackCount) }
    .sortedBy { labelRank(it.releaseType, qobuzTrackCount) } // a stable sort keeps YouTube's order
    .firstOrNull()

/**
 * Which label to take first when several candidates are this album. A Qobuz single
 * takes YouTube's Single or EP, then an unlabelled release, then the album it's on
 * (its song is on that too). A Qobuz album takes the Album before an EP of the same
 * name. With the length unknown, YouTube's order stands.
 */
private fun labelRank(label: String?, qobuzTrackCount: Int?): Int {
    if (qobuzTrackCount == null || qobuzTrackCount <= 0) return 0
    val type = label?.lowercase()
    return if (isSingleLength(qobuzTrackCount)) {
        when (type) {
            "single", "ep" -> 0
            "album" -> 2
            else -> 1
        }
    } else {
        if (type == "album") 0 else 1
    }
}

/** Qobuz sends no release type, so its track count says: 1 to 3 tracks is a single. */
private fun isSingleLength(trackCount: Int?): Boolean = trackCount != null && trackCount in 1..3

/**
 * The same artist as each catalog writes it. "and" counts as "&" ("Echo And The
 * Bunnymen" is "Echo & the Bunnymen"), and since YouTube's search results name only a
 * collaboration's first artist, [candidate] may match the first one Qobuz credits
 * ("Jay Z and Kanye West", live 2026-10-03: "Watch The Throne").
 */
private fun sameArtist(candidate: String, qobuz: String): Boolean {
    val got = artistKey(candidate)
    return got.isNotEmpty() && (got == artistKey(qobuz) || got == artistKey(firstCredited(qobuz)))
}

private fun artistKey(name: String): String = matchKey(AND.replace(name, " & "))

private fun firstCredited(artist: String): String = COLLAB_SEPARATOR.split(artist, limit = 2).first()

/** [QobuzCandidateMatcher.normalize] after [foldForMatch]. */
private fun matchKey(s: String): String = QobuzCandidateMatcher.normalize(foldForMatch(s))

/** An album title as a match key, and whether an edition was set aside to get it. */
private class TitleKey(val key: String, val dropsEdition: Boolean)

/**
 * An album title as a match key. [QobuzCandidateMatcher.normalize] drops every bracket
 * and everything after a "feat.", so "Loveless (Live)" and "Loveless feat. X (Live)"
 * would both equal "Loveless". Here a featured credit is cut only up to the next
 * bracket or " - " ([CREDIT]), and a bracketed or " - " part only drops out when it
 * names an edition ([isEdition]); its words stay in the key otherwise.
 */
private fun albumTitleKey(title: String): TitleKey {
    var dropsEdition = false
    val creditsCut = CREDIT.replace(foldForMatch(title), " ")
    val bracketsSettled = BRACKETED.replace(creditsCut) { group ->
        val inside = group.value.substring(1, group.value.length - 1)
        if (isEdition(inside)) {
            dropsEdition = true
            " "
        } else {
            " $inside "
        }
    }
    val dashSettled = DASH_SUFFIX.replace(bracketsSettled) { suffix ->
        if (isEdition(suffix.groupValues[1])) {
            dropsEdition = true
            " "
        } else {
            suffix.value
        }
    }
    return TitleKey(QobuzCandidateMatcher.normalize(dashSettled), dropsEdition)
}

/** "Super Deluxe", "2009 Remaster", "20th Anniversary Edition". */
private fun isEdition(part: String): Boolean {
    val words = part.lowercase().replace("'", "").split(NON_WORD).filter { it.isNotEmpty() }
    return words.isNotEmpty() && words.all { it in EDITION_WORDS || NUMBERING.matches(it) }
}

/** Within a year of each other, or unknown on either side. Qobuz may send "1991-11-04". */
private fun yearsAgree(candidate: String?, wanted: String?): Boolean {
    val a = candidate?.let { YEAR.find(it) }?.value?.toInt() ?: return true
    val b = wanted?.let { YEAR.find(it) }?.value?.toInt() ?: return true
    return abs(a - b) <= 1
}

/** No accents or curly quotes: Qobuz writes "Victoria Monet", YouTube "Monét"; one writes "Don’t", the other "Don't". */
private fun foldForMatch(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(COMBINING_MARKS, "").replace(CURLY_QUOTES, "'")

private val COMBINING_MARKS = Regex("\\p{Mn}+")
private val CURLY_QUOTES = Regex("[\u2018\u2019]")
private val BRACKETED = Regex("""\([^()]*\)|\[[^\[\]]*]""")
private val DASH_SUFFIX = Regex("""\s[-\u2013\u2014]\s(.*)$""")

/** A featured credit, cut up to the next bracket or " - ": "Title feat. X (Live)" keeps "(Live)". */
private val CREDIT = Regex(
    """\b(?:feat|ft|featuring)\b\.?[^()\[\]]*?(?=\s*[()\[\]]|\s[-\u2013\u2014]\s|$)""",
    RegexOption.IGNORE_CASE,
)
private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
private val NUMBERING = Regex("\\d{4}|\\d+(st|nd|rd|th)")
private val YEAR = Regex("\\d{4}")
private val AND = Regex("\\s+and\\s+", RegexOption.IGNORE_CASE)
private val COLLAB_SEPARATOR = Regex("\\s+(?:&|x|and|with)\\s+|,\\s+", RegexOption.IGNORE_CASE)

/** Words that name an edition of an album, not a different recording of it. */
private val EDITION_WORDS = setOf(
    "deluxe", "super", "expanded", "edition", "remaster", "remastered", "anniversary", "version",
    "bonus", "track", "tracks", "special", "collector", "collectors", "limited", "legacy",
    "platinum", "reissue", "standard", "complete", "the", "and",
)
