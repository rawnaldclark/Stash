package com.stash.feature.search

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.common.perf.PerfLog
import com.stash.core.data.cache.AlbumCache
import com.stash.core.data.cache.ArtistCache
import com.stash.core.data.cache.CachedProfile
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.actions.TrackActionsDelegate
import com.stash.core.media.preview.LosslessUrlPrefetcher
import com.stash.core.model.MusicSource
import com.stash.core.model.Playlist
import com.stash.core.model.Track
import com.stash.core.model.TrackItem
import com.stash.data.ytmusic.model.AlbumSource
import com.stash.data.ytmusic.model.ArtistProfile
import com.stash.data.ytmusic.model.TrackSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private val albumCache: AlbumCache,
    private val prefetcher: PreviewPrefetcher,
    private val playerRepository: PlayerRepository,
    private val musicRepository: MusicRepository,
    private val streamingPreference: com.stash.core.data.prefs.StreamingPreference,
    val delegate: TrackActionsDelegate,
    val losslessPrefetcher: LosslessUrlPrefetcher,
) : ViewModel() {

    private val artistId: String = requireNotNull(savedStateHandle["artistId"]) {
        "SearchArtistRoute requires a non-null artistId nav arg"
    }
    private val initialName: String = savedStateHandle["name"] ?: ""
    private val initialAvatar: String? = savedStateHandle["avatarUrl"]
    private val initialFocusAlbum: String? = savedStateHandle["focusAlbum"]

    private val _uiState = MutableStateFlow(
        ArtistProfileUiState(
            hero = HeroState(
                name = initialName,
                avatarUrl = initialAvatar,
                subscribersText = null,
            ),
            status = ArtistProfileStatus.Loading,
            focusAlbum = initialFocusAlbum,
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

    /** Online/Offline mode for the profile-header chip. */
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
     * Job running the current background catalog-fill for [playArtist]. Stored
     * so a rapid second tap on the Play Artist button cancels the prior fill
     * before relaunching — prevents addToQueue racing against itself with
     * stale album-cache-hit data.
     */
    private var fillCatalogJob: Job? = null

    /**
     * Start playing this artist. Hybrid strategy:
     *  1. Instant start with the cached popular tracks (immediate setQueue).
     *  2. Background-fill the queue from albums then singles via [albumCache].
     *  3. Stop at [CATALOG_CAP] tracks total (soft cap, ~hours of playback).
     *
     * Double-tap-safe: a second invocation cancels the prior fill job before
     * relaunching. Per spec, no Snackbar — actual playback IS the feedback.
     */
    /**
     * Start an artist radio seeded from this artist. The browseId is already in
     * hand (nav arg), so the generator skips a resolveArtist hop. On a false
     * return (streaming off/offline, or no seed tracks) we surface a one-shot
     * hint instead of a dead tap.
     */
    fun startRadio() {
        viewModelScope.launch {
            val name = _uiState.value.hero.name.ifBlank { initialName }
            val started = playerRepository.startRadio(
                com.stash.core.data.radio.RadioSeed.Artist(name, ytBrowseId = artistId),
            )
            if (!started) _userMessages.emit("Radio needs Online mode — turn on streaming.")
        }
    }

    fun playArtist() {
        fillCatalogJob?.cancel()
        fillCatalogJob = viewModelScope.launch {
            val state = _uiState.value
            val artistName = state.hero.name
            val seen = mutableSetOf<String>()

            // Popular is always YT-sourced (real videoIds), so keying on
            // videoId is safe here.
            val popularTracks = state.popular
                .filter { seen.add(trackKey(it)) }
                .map { it.toDomainTrack(albumFallback = artistName) }

            if (popularTracks.isEmpty() && state.albums.isEmpty() && state.singles.isEmpty()) {
                _userMessages.emit("No tracks available for this artist")
                return@launch
            }

            var appended = popularTracks.size
            if (popularTracks.isNotEmpty()) {
                playerRepository.setQueue(popularTracks, startIndex = 0)
            }

            val catalog = state.albums + state.singles
            for (album in catalog) {
                if (appended >= CATALOG_CAP) break
                // Route the album fetch by its source — a merged-in Qobuz album
                // (numeric id, source=QOBUZ) would otherwise 404 on the YT path
                // and be silently dropped from Play Artist.
                val detail = runCatching { albumCache.get(album.id, album.source) }
                    .getOrNull() ?: continue
                val remaining = CATALOG_CAP - appended
                val domain = detail.tracks
                    // trackKey, not videoId: Qobuz tracks share blank videoIds,
                    // so a raw-videoId seen-set would drop all but the first.
                    .filter { seen.add(trackKey(it)) }
                    .take(remaining)
                    .map { domainTrackFor(it, album.source, album.title, artistName) }
                if (domain.isEmpty()) continue

                // Qobuz tracks carry no videoId — persist by canonical identity
                // to a real PK so the queue survives resume (same as the album
                // screen's play path). YT tracks keep their synthetic id.
                val albumTracks = if (album.source == AlbumSource.QOBUZ) {
                    domain.map { it.copy(id = musicRepository.ensureTrackPersisted(it)) }
                } else {
                    domain
                }

                if (appended == 0) {
                    playerRepository.setQueue(albumTracks, startIndex = 0)
                } else {
                    playerRepository.addToQueue(albumTracks)
                }
                appended += albumTracks.size
            }
        }
    }

    /** Dedup key for the catalog fill: videoId when present (YT), else a
     *  title|artist identity (Qobuz tracks all carry a blank videoId). */
    private fun trackKey(t: TrackSummary): String =
        t.videoId.ifBlank { "${t.title}|${t.artist}" }

    /** Build a domain [Track] for a catalog track, branching on the album's
     *  source. Qobuz → no videoId, id=0 (a real PK is assigned by
     *  [MusicRepository.ensureTrackPersisted] before queueing); YT → the
     *  existing synthetic-id mapping. */
    private fun domainTrackFor(
        t: TrackSummary,
        source: AlbumSource,
        albumTitle: String,
        artistName: String,
    ): Track = if (source == AlbumSource.QOBUZ) {
        Track(
            id = 0L,
            title = t.title,
            artist = t.artist.ifBlank { artistName },
            album = albumTitle,
            durationMs = (t.durationSeconds * 1000.0).toLong(),
            albumArtUrl = t.thumbnailUrl,
            youtubeId = null,
            source = MusicSource.YOUTUBE,
            isStreamable = true,
        )
    } else {
        t.toDomainTrack(
            albumFallback = artistName,
            albumTitle = albumTitle,
            albumArtist = artistName,
        )
    }

    private fun TrackSummary.toDomainTrack(
        albumFallback: String,
        albumTitle: String = album ?: "",
        albumArtist: String = albumFallback,
    ): Track = Track(
        id = videoId.hashCode().toLong(),
        title = title,
        artist = artist.ifBlank { albumArtist },
        album = albumTitle,
        durationMs = (durationSeconds * 1000.0).toLong(),
        albumArtUrl = thumbnailUrl,
        youtubeId = videoId,
        source = MusicSource.YOUTUBE,
        isStreamable = true,
    )

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
            about = profile.about,
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

        /**
         * Soft cap on the total tracks the Play Artist hybrid-fill will
         * append. 30 tracks ≈ enough hours of playback for a normal session
         * without over-queuing or burning album-detail network calls on
         * artists with deep discographies.
         */
        private const val CATALOG_CAP = 30
    }
}
