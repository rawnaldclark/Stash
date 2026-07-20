package com.stash.feature.nowplaying

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.palette.graphics.Palette
import com.stash.core.data.lossless.LosslessUpgrader
import com.stash.core.data.prefs.NowPlayingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.UpgradeResult
import com.stash.core.model.isFlac
import com.stash.core.ui.components.PlaylistInfo
import com.stash.core.model.Track
import com.stash.data.lyrics.LyricsRepository
import com.stash.data.lyrics.sidecar.LyricsSidecarWriter
import com.stash.data.lyrics.source.LyricsQuery
import com.stash.feature.nowplaying.ui.LyricsViewState
import com.stash.feature.nowplaying.ui.lyricsViewStateFor
import com.stash.feature.nowplaying.ui.lyricsViewStateForResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One-shot target for navigating from Now Playing to an artist profile. */
data class ArtistNavTarget(
    val artistId: String,
    val name: String,
    val avatarUrl: String?,
    val focusAlbum: String?,
)

/**
 * ViewModel for the full-screen Now Playing screen.
 *
 * Observes [PlayerRepository.playerState] and [PlayerRepository.currentPosition],
 * maps them into a single [NowPlayingUiState], and exposes one-shot action functions
 * that delegate to the repository.
 */
@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val musicRepository: MusicRepository,
    private val likeCoordinator: com.stash.core.data.social.LikeCoordinator,
    private val losslessUpgrader: LosslessUpgrader,
    // v0.9.36 Task 12 — lyrics sheet observes the lyrics row and may
    // enqueue a priority on-open fetch. WorkManager is sourced via
    // `WorkManager.getInstance(appContext)` to match the rest of the
    // codebase (it's not Hilt-injectable in this project).
    private val lyricsRepository: LyricsRepository,
    private val lyricsPreference: com.stash.core.data.prefs.LyricsPreference,
    private val nowPlayingPreference: NowPlayingPreference,
    private val lyricsSidecarWriter: LyricsSidecarWriter,
    @ApplicationContext private val appContext: Context,
    // Tap-to-artist: resolves the playing track's artist NAME to a YT
    // browseId so Now Playing can open the artist profile.
    private val ytMusicApiClient: com.stash.data.ytmusic.YTMusicApiClient,
) : ViewModel() {

    // ── Share (fork issue ParaliyzedEvo/Stash#40) ──
    // The player-state Track is a slim media-session reconstruction with
    // null spotifyUri/youtubeId; fetch the FULL DB row before the sheet
    // opens so the link options actually appear.
    private val _shareTrack = kotlinx.coroutines.flow.MutableStateFlow<com.stash.core.model.Track?>(null)
    val shareTrack: kotlinx.coroutines.flow.StateFlow<com.stash.core.model.Track?> = _shareTrack

    fun onShareCurrent() {
        val slim = uiState.value.currentTrack ?: return
        viewModelScope.launch {
            _shareTrack.value = musicRepository.observeTrackById(slim.id)
                .firstOrNull() ?: slim
        }
    }

    fun onShareDismissed() {
        _shareTrack.value = null
    }

    private val _uiState = MutableStateFlow(NowPlayingUiState())
    val uiState: StateFlow<NowPlayingUiState> = _uiState.asStateFlow()

    /**
     * Live synced-line bar opt-in (default OFF — the ticking line can pull
     * the listener out of the music). Off, synced tracks still get the
     * "View lyrics ♪" bar; the toggle lives in the lyrics sheet.
     */
    val liveLyricsBarEnabled: StateFlow<Boolean> = lyricsPreference.liveBarEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setLiveLyricsBarEnabled(enabled: Boolean) {
        viewModelScope.launch { lyricsPreference.setLiveBarEnabled(enabled) }
    }

    val ambientAnimationEnabled: StateFlow<Boolean?> = nowPlayingPreference.ambientAnimationEnabled
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)


    private val _userMessages = MutableSharedFlow<String>(
        // v0.9.18: bumped from 1 → 8. The Find-in-FLAC action emits TWO
        // messages back-to-back ("Looking for FLAC…" → result), and a
        // capacity-of-1 + DROP_OLDEST race meant the first message could
        // get dropped before the Toast collector drained it (especially
        // on fast-fail paths like a known-bad captcha cookie + circuit-
        // broken kennyy, where both emits land within microseconds).
        // Existing single-emit actions (flag, delete) are unaffected by
        // the larger capacity.
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** One-shot snackbar messages (e.g. "Track flagged as wrong match"). */
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    /**
     * v0.9.27: holds optimistic heart-toggle states to prevent flickering.
     */
    private val optimisticLikeState = MutableStateFlow<Map<Long, Boolean>>(emptyMap())

    // ------------------------------------------------------------------
    // Tap-to-artist — resolve the playing artist and navigate to profile
    // ------------------------------------------------------------------

    /**
     * One-shot navigation targets emitted when the user taps the track block.
     * The screen collects this and forwards to its `onNavigateToArtist`
     * callback. `extraBufferCapacity = 1` so an emit that lands a hair before
     * the screen re-subscribes (config change) isn't dropped.
     */
    private val _artistNavEvents = MutableSharedFlow<ArtistNavTarget>(extraBufferCapacity = 1)
    val artistNavEvents: SharedFlow<ArtistNavTarget> = _artistNavEvents.asSharedFlow()

    /**
     * True while an artist resolve is in flight. The screen disables the
     * track block and swaps its chevron for a spinner; also the double-tap
     * guard in [onTrackInfoTapped].
     */
    private val _resolvingArtist = MutableStateFlow(false)
    val resolvingArtist: StateFlow<Boolean> = _resolvingArtist.asStateFlow()

    /**
     * Keys (youtubeId, or DB id when blank) of tracks whose download the user
     * kicked off from Now Playing but that haven't landed on disk yet. Drives
     * the spinner on the download button, since the WorkManager download is
     * fire-and-forget and the plain icon gave no in-progress feedback. Cleared
     * when the track's live row flips `isDownloaded`, or after a watchdog
     * timeout so a failed/stuck job never strands a permanent spinner.
     */
    private val _downloadingKeys = MutableStateFlow<Set<String>>(emptySet())

    /** True while the *currently-shown* track's download is in flight. */
    val isDownloadingCurrent: StateFlow<Boolean> =
        combine(_uiState, _downloadingKeys) { ui, keys ->
            val t = ui.currentTrack ?: return@combine false
            downloadKeyFor(t) in keys && !t.isDownloaded
        }.distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    private val _exportingLyricsTrackId = MutableStateFlow<Long?>(null)
    val exportingLyricsTrackId: StateFlow<Long?> = _exportingLyricsTrackId.asStateFlow()

    private fun downloadKeyFor(t: com.stash.core.model.Track): String =
        t.youtubeId?.takeIf { it.isNotBlank() } ?: t.id.toString()

    /**
     * Tap on the Now Playing track block. Resolves the playing artist's NAME
     * to a YT browseId and emits an [ArtistNavTarget] carrying the current
     * album as `focusAlbum`. Prefers the clean `albumArtist`, falling back to
     * `artist`. No-op when nothing is playing or a resolve is already in
     * flight (double-tap guard). Resolve miss/failure → snackbar, no nav.
     */
    /** Live label of the active radio station (null when no station). */
    val radioSeedLabel: StateFlow<String?> = playerRepository.radioSeedLabel

    /** Start a song radio seeded from the currently-playing track. Streaming-only:
     *  a false return (streaming off/offline) surfaces a hint, not a dead tap. */
    fun startRadioFromCurrent() {
        val track = _uiState.value.currentTrack ?: return
        viewModelScope.launch {
            val started = playerRepository.startRadio(
                com.stash.core.data.radio.RadioSeed.Song(
                    title = track.title, artist = track.artist, ytVideoId = track.youtubeId,
                ),
                // The seed IS the currently-playing track — keep it playing, don't restart.
                keepCurrent = true,
            )
            if (!started) _userMessages.tryEmit("Radio needs Online mode — turn on streaming.")
        }
    }

    /** Stop the active radio station. */
    fun stopRadio() = playerRepository.stopRadio()

    fun onTrackInfoTapped() {
        val track = _uiState.value.currentTrack ?: return
        if (_resolvingArtist.value) return
        val name = track.albumArtist.ifBlank { track.artist }
        if (name.isBlank()) {
            _userMessages.tryEmit("Couldn't find this artist")
            return
        }
        _resolvingArtist.value = true
        viewModelScope.launch {
            try {
                val artist = ytMusicApiClient.resolveArtist(name)
                if (artist != null) {
                    _artistNavEvents.emit(
                        ArtistNavTarget(
                            artistId = artist.id,
                            name = artist.name,
                            avatarUrl = artist.avatarUrl,
                            focusAlbum = track.album.ifBlank { null },
                        ),
                    )
                } else {
                    _userMessages.emit("Couldn't find this artist")
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                _userMessages.emit("Couldn't find this artist")
            } finally {
                _resolvingArtist.value = false
            }
        }
    }

    // ------------------------------------------------------------------
    // v0.9.36 Task 12 — Lyrics sheet state
    // ------------------------------------------------------------------

    /**
     * Whether the lyrics sheet is currently open. Toggled by
     * [onShowLyrics] / [onDismissLyrics]; the Compose layer collects
     * this and conditionally renders [com.stash.feature.nowplaying.ui.LyricsBottomSheet].
     */
    private val _lyricsSheetOpen = MutableStateFlow(false)
    val lyricsSheetOpen: StateFlow<Boolean> = _lyricsSheetOpen.asStateFlow()

    /**
     * Transient lyrics state for streaming-mode tracks (id == 0L), which
     * don't have a persistent `tracks` row to observe. Populated by
     * [fetchStreamingLyrics] on sheet open; cleared (set to null) when
     * the playing track changes so a stale render from the previous
     * streamed song doesn't bleed through. Null = "not fetched yet";
     * downstream [lyricsViewState] maps that to Loading.
     */
    private val _streamingLyricsState = MutableStateFlow<LyricsViewState?>(null)

    /**
     * Track ids with an in-flight inline lyrics resolve ([fetchLibraryLyrics]),
     * so reopening the sheet doesn't stack duplicate fetches for the same track.
     */
    private val lyricsFetchInFlight: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Library-track id whose most recent inline lyrics resolve FAILED
     * (source threw — network/HTTP/parse), as opposed to missing. A failure
     * leaves `lyrics_fetched_at` NULL (still retryable), which alone would
     * render Loading forever; this overlay converts exactly that case to
     * [LyricsViewState.Error] with a Retry CTA. Cleared on track change and
     * at the start of every new fetch attempt.
     */
    private val _lyricsFetchFailedTrackId = MutableStateFlow<Long?>(null)

    /**
     * Re-derives [LyricsViewState] from the currently-playing track and
     * the observed [com.stash.core.data.db.entity.LyricsEntity] row.
     *
     * Tracks the `currentTrack` field of [uiState] (the same field the
     * rest of Now Playing reads) so the sheet always reflects the
     * track that's currently displayed/playing — no separate "selected
     * for lyrics" state. When the track flips, [flatMapLatest] cancels
     * the old observer and subscribes to the new track's row.
     *
     * SharingStarted.WhileSubscribed(5_000) keeps the flow warm for
     * 5 seconds after the sheet closes so a quick close+reopen doesn't
     * re-derive from scratch.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val lyricsViewState: StateFlow<LyricsViewState> = uiState
        .map { it.currentTrack }
        // Streaming-mode tracks all share id==0L; distinguish them by youtubeId so
        // a track change between two streamed Yeat songs still flips the upstream.
        .distinctUntilChanged { old, new -> trackKey(old) == trackKey(new) }
        .onEach { track ->
            // reset transient state on track change
            _streamingLyricsState.value = null
            _lyricsFetchFailedTrackId.value = null
            // Live-lyrics-bar fetch trigger. Deliberately HERE and not in
            // init{}: this onEach only runs while lyricsViewState has
            // collectors (WhileSubscribed) — i.e. while NowPlayingScreen is
            // showing the bar/sheet. The MiniPlayer's activity-scoped VM
            // instance never collects lyricsViewState, so it can never fire
            // fetches app-wide. Covers screen-open (initial emission) and
            // every track change while open.
            when {
                track == null -> Unit
                track.id > 0L && track.lyricsFetchedAt == null -> fetchLibraryLyrics(track)
                track.id == 0L -> fetchStreamingLyrics(track)
            }
        }
        .flatMapLatest { track ->
            when {
                track == null -> flowOf(LyricsViewState.None)
                // Combine the lyrics row with the LIVE lyrics_fetched_at stamp.
                // Deriving the sentinel from the captured track snapshot left the
                // sheet stuck on Loading after a fetch finished (the stamp updated
                // in Room but the captured value never did); watching the stamp
                // live flips the sheet the instant the fetch completes — no
                // close+reopen needed.
                track.id > 0L -> combine(
                    lyricsRepository.observe(track.id),
                    lyricsRepository.observeFetchedAt(track.id),
                    _lyricsFetchFailedTrackId,
                ) { row, fetchedAt, failedId ->
                    // A NULL stamp normally means "fetch in flight" (Loading),
                    // but when the inline fetch failed it stays NULL forever —
                    // surface that as a retryable Error instead.
                    if (fetchedAt == null && failedId == track.id) {
                        LyricsViewState.Error(retryable = true)
                    } else {
                        lyricsViewStateFor(track.copy(lyricsFetchedAt = fetchedAt), row)
                    }
                }
                // Streaming track (id == 0L). The transient flow is seeded by
                // onShowLyrics -> fetchStreamingLyrics. Until then, null means
                // we haven't tried — render Loading.
                else -> _streamingLyricsState.map { it ?: LyricsViewState.Loading }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = LyricsViewState.Loading,
        )

    /**
     * Stable key for a Track regardless of whether it's library-resident
     * (positive `id`) or streaming-only (`id == 0L`). Mirrors the
     * convention already used at [observePlayerStateLive] for the
     * optimistic-like-state map. Returns null only when [track] is null.
     */
    private fun trackKey(track: Track?): Long? = when {
        track == null -> null
        track.id > 0L -> track.id
        else -> track.youtubeId.hashCode().toLong()
    }

    /**
     * Polls [PlayerRepository.currentPosition] at the existing 250ms
     * cadence — same flow the [observePlayerStateLive] combine already
     * consumes, so the player only ticks once.
     *
     * Exposed as a dedicated StateFlow so the lyrics sheet doesn't have
     * to subscribe to the full [uiState] (which re-emits on every
     * heart toggle, every queue change, every album-art recolor). The
     * synced renderer only cares about the position, so this is the
     * narrower subscription that matches what it needs.
     */
    val currentPositionMs: StateFlow<Long> = playerRepository.currentPosition
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = 0L,
        )

    init {
        observePlayerStateLive()
        observeUserPlaylists()
        observeStreamingHaltedEvents()
        observePlayerUserMessages()
        collectMirrorFailures()
    }

    /**
     * v0.9.52: best-effort mirror sync failures surface as one subtle
     * snackbar per session (LikeCoordinator gates the frequency).
     */
    private fun collectMirrorFailures() {
        viewModelScope.launch {
            likeCoordinator.mirrorFailures.collect { _userMessages.tryEmit(it) }
        }
    }

    // ------------------------------------------------------------------
    // Observation
    // ------------------------------------------------------------------

    /**
     * v0.9.13: Combines player-only fields (position, isPlaying, queue) with
     * the canonical Track row from Room.
     *
     * The previous version of this used `state.currentTrack` directly, but
     * `PlayerRepositoryImpl.toTrack()` reconstructs Track from MediaItem
     * extras and only populates 5 fields out of ~25 — every other field
     * (filePath, fileFormat, bitsPerSample, like timestamps, etc.) defaults
     * to the data class default and is silently wrong. That's why every
     * track displayed "OPUS" for the codec and why the heart icon failed
     * to persist across Now Playing close+reopen — Now Playing was reading
     * from MediaItem-derived junk, not the database.
     *
     * Now: take the id from the player (canonical "what's playing"),
     * `flatMapLatest` into Room for the full row. Fall back to the
     * player's snapshot only when Room has no row (e.g., streamed/preview
     * content with synthetic id) so search-tab playback still works.
     *
     * v0.9.27 (fix): merges [optimisticLikeState] so the heart icon doesn't
     * flicker when the 250ms position ticks race with the DB write.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observePlayerStateLive() {
        val liveTrackFlow = playerRepository.playerState
            .map { it.currentTrack?.let { t -> t.id to t.youtubeId } }
            .distinctUntilChanged()
            .flatMapLatest { key ->
                if (key == null) flowOf<com.stash.core.model.Track?>(null)
                else {
                    val (id, ytId) = key
                    // Falls back to a youtube_id lookup so the heart icon
                    // (and other Room-driven fields) update for v0.9.30
                    // streaming-engine tracks whose `id` is a synthetic
                    // `videoId.hashCode().toLong()` that doesn't exist in
                    // `tracks` until `ensureTrackPersisted` writes one
                    // under a real autogen PK (issue #105 follow-up).
                    musicRepository.observeTrackById(id).flatMapLatest { row ->
                        if (row != null || ytId.isNullOrBlank()) flowOf(row)
                        else musicRepository.observeTrackByYoutubeId(ytId)
                    }
                }
            }

        combine(
            playerRepository.playerState,
            playerRepository.currentPosition,
            liveTrackFlow,
            optimisticLikeState,
        ) { state, positionMs, liveTrack, optimistic ->
            _uiState.update { current ->
                val baseTrack = liveTrack ?: state.currentTrack
                // When the active track came from a stream resolver, the Room
                // row's format fields are stale or default ("opus") — the row
                // was synced without downloading the audio, so it has no real
                // codec/bit-depth/sample-rate. The active MediaItem carries the
                // served format in its extras; overlay those so Now Playing
                // shows the right badge. Keyed on stream origin, not the
                // http-only isStreaming flag, so antra's file:// FLAC is also
                // labeled correctly (see [overlayDisplayTrack]).
                val track = overlayDisplayTrack(
                    isHttpStreaming = state.isStreaming,
                    streamFormat = state.currentTrack,
                    baseTrack = baseTrack,
                )
                // ExoPlayer always knows the real duration once the
                // stream loads; the Track-domain durationMs may still be
                // 0 for streaming-engine tracks whose search-result
                // metadata didn't carry one. Prefer the player's truth
                // so `ensureTrackPersisted` writes the right value when
                // the user likes/downloads the currently-playing track
                // (issue #105 follow-up: stream-only Liked Songs were
                // landing with 0:00 duration).
                val durationOverlaid = track?.let {
                    if (it.durationMs <= 0L && state.durationMs > 0L) it.copy(durationMs = state.durationMs)
                    else it
                }
                val trackKey = if (durationOverlaid?.id == 0L) durationOverlaid.youtubeId.hashCode().toLong() else durationOverlaid?.id
                val finalTrack = if (durationOverlaid != null && trackKey != null && optimistic.containsKey(trackKey)) {
                    val optLiked = optimistic[trackKey]!!
                    durationOverlaid.copy(stashLikedAt = if (optLiked) System.currentTimeMillis() else null)
                } else {
                    durationOverlaid
                }

                current.copy(
                    // Prefer Room's live row; fall back to player's MediaItem
                    // snapshot for non-library content (streams, search previews).
                    currentTrack = finalTrack,
                    isPlaying = state.isPlaying,
                    currentPositionMs = positionMs,
                    durationMs = state.durationMs,
                    shuffleEnabled = state.isShuffleEnabled,
                    repeatMode = state.repeatMode,
                    queueSize = state.queue.size,
                    currentIndex = state.currentIndex,
                    queue = state.queue,
                    isStreaming = state.isStreaming,
                    isBuffering = state.isBuffering,
                )
            }
        }.launchIn(viewModelScope)
    }

    /**
     * Observes the "Save to Playlist" picker destination list — custom
     * playlists AND imported Spotify / YT Music playlists.
     * v0.9.23 (issue #42): manual addition of Stash-downloaded tracks
     * to imported playlists is now supported. The new locally_added
     * flag on the cross-ref makes the addition survive REFRESH-mode
     * re-sync of the underlying Spotify / YT Music playlist.
     */
    private fun observeUserPlaylists() {
        musicRepository.getPickablePlaylists()
            .onEach { playlists ->
                _uiState.update { current ->
                    current.copy(
                        userPlaylists = playlists.map { playlist ->
                            PlaylistInfo(
                                id = playlist.id,
                                name = playlist.name,
                                trackCount = playlist.trackCount,
                            )
                        },
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Subscribes to [PlayerRepository.streamingHaltedEvents] and surfaces
     * each halt as a Toast via [_userMessages]. The cascade-guard in
     * PlayerRepositoryImpl already paused the player by the time this
     * fires — the Toast is purely informational so the user understands
     * why playback stopped.
     *
     * Toast (not Snackbar) matches the existing NowPlayingScreen
     * convention — see the comment near the `userMessages` collector in
     * NowPlayingScreen.kt for why we don't wrap this screen in a
     * Scaffold.
     */
    private fun observeStreamingHaltedEvents() {
        playerRepository.streamingHaltedEvents
            .onEach {
                _userMessages.emit(
                    "Streaming is failing \u2014 try a downloaded track or check your connection"
                )
            }
            .launchIn(viewModelScope)
    }

    /**
     * Forwards [PlayerRepository.userMessages] emissions into the screen's
     * Toast channel. Source examples:
     *  - "Couldn't play this track right now." (setQueue tap failure)
     *  - "End of offline Mix" (v0.9.37 auto-advance silent-skip exhausted
     *    the queue while offline)
     * The player layer already paused playback when relevant; this
     * observer is purely informational.
     */
    private fun observePlayerUserMessages() {
        playerRepository.userMessages
            .onEach { msg -> _userMessages.emit(msg) }
            .launchIn(viewModelScope)
    }

    // observeLikeStateForCurrentTrack removed in v0.9.13 — subsumed by
    // observePlayerStateLive's Room-bound currentTrack flow above.

    // ------------------------------------------------------------------
    // User Actions
    // ------------------------------------------------------------------

    /** Toggle between play and pause. */
    fun onPlayPauseClick() {
        viewModelScope.launch {
            if (_uiState.value.isPlaying) {
                playerRepository.pause()
            } else {
                playerRepository.play()
            }
        }
    }

    /** Advance to the next track in the queue. */
    fun onSkipNext() {
        viewModelScope.launch { playerRepository.skipNext() }
    }

    /** Return to the previous track (or restart current). */
    fun onSkipPrevious() {
        viewModelScope.launch { playerRepository.skipPrevious() }
    }

    /**
     * Seek to [positionMs] within the current track.
     *
     * @param positionMs target position in milliseconds, clamped to `[0, durationMs]`.
     */
    fun onSeekTo(positionMs: Long) {
        val clamped = positionMs.coerceIn(0L, _uiState.value.durationMs)
        viewModelScope.launch { playerRepository.seekTo(clamped) }
    }

    /** Toggle shuffle mode on / off. */
    fun onToggleShuffle() {
        viewModelScope.launch { playerRepository.toggleShuffle() }
    }

    /** Cycle repeat mode: OFF -> ALL -> ONE -> OFF. */
    fun onCycleRepeatMode() {
        viewModelScope.launch { playerRepository.cycleRepeatMode() }
    }

    /**
     * Remove the track at [index] from the playback queue.
     * The currently-playing track cannot be removed through this action.
     */
    fun onRemoveFromQueue(index: Int) {
        if (index == _uiState.value.currentIndex) return
        viewModelScope.launch { playerRepository.removeFromQueue(index) }
    }

    /**
     * Move a track within the queue from position [from] to position [to].
     */
    fun onMoveInQueue(from: Int, to: Int) {
        viewModelScope.launch { playerRepository.moveInQueue(from, to) }
    }

    /**
     * Jump playback to the track at [index] in the queue.
     */
    fun onSkipToQueueIndex(index: Int) {
        viewModelScope.launch { playerRepository.skipToQueueIndex(index) }
    }

    // ------------------------------------------------------------------
    // Playlist Actions
    // ------------------------------------------------------------------

    /**
     * Add the currently-playing track to an existing playlist.
     *
     * @param trackId    ID of the track to save.
     * @param playlistId ID of the target playlist.
     */
    fun saveTrackToPlaylist(trackId: Long, playlistId: Long) {
        viewModelScope.launch { musicRepository.addTrackToPlaylist(trackId, playlistId) }
    }

    /**
     * Create a new playlist and immediately add the given track to it.
     *
     * @param name    Name for the new playlist.
     * @param trackId ID of the track to save into the newly created playlist.
     */
    fun createPlaylistAndAddTrack(name: String, trackId: Long) {
        viewModelScope.launch {
            val playlistId = musicRepository.createPlaylist(name)
            musicRepository.addTrackToPlaylist(trackId, playlistId)
        }
    }

    // ------------------------------------------------------------------
    // Wrong-match flagging
    // ------------------------------------------------------------------

    /**
     * Flag the currently-playing track as the wrong song. Surfaces it in
     * the Failed Matches screen so the user can pick a replacement. No-op
     * when nothing is playing. Emits a snackbar message so the user knows
     * where to go next.
     */
    fun flagCurrentTrackAsWrongMatch() {
        val track = _uiState.value.currentTrack ?: return
        viewModelScope.launch {
            musicRepository.setMatchFlagged(track.id, true)
            _userMessages.tryEmit("Flagged. Find it in Sync \u2192 Failed Matches to pick a replacement.")
        }
    }

    /**
     * v0.9.18: upgrade the currently-playing track to FLAC if any
     * lossless source can serve it. Fire-and-forget — emits a "looking"
     * snackbar immediately, then a result snackbar when the resolve +
     * download completes.
     *
     * No-op when nothing is playing or when the current track is already
     * FLAC (UI hides the button in that case, but defensive guard here
     * in case state changes mid-tap).
     */
    /**
     * Toggle download state for the currently-playing track. Queues a
     * download when not yet on disk; removes the file (keeping the row)
     * when already downloaded. Streaming-mode users may never have
     * downloaded the track being played — this is the in-the-moment
     * shortcut to keep what's currently playing.
     */
    fun toggleDownloadForCurrentTrack() {
        val track = _uiState.value.currentTrack ?: return
        viewModelScope.launch {
            if (track.isDownloaded) {
                musicRepository.removeDownload(track.id)
                _userMessages.tryEmit("Download removed.")
            } else {
                val key = downloadKeyFor(track)
                _downloadingKeys.update { it + key }
                // Resolve a synthetic streaming-engine id to a real DB row
                // before queuing — otherwise `getById` returns null and the
                // queue insert silently no-ops while the toast still fires
                // (issue #105).
                val realId = runCatching { musicRepository.ensureTrackPersisted(track) }
                    .getOrElse { e ->
                        android.util.Log.w("NowPlayingViewModel", "ensureTrackPersisted failed", e)
                        _downloadingKeys.update { it - key }
                        _userMessages.tryEmit("Couldn't queue download.")
                        return@launch
                    }
                val queued = musicRepository.queueDownload(realId)
                if (!queued) {
                    _downloadingKeys.update { it - key }
                    _userMessages.tryEmit("Couldn't queue download.")
                    return@launch
                }
                _userMessages.tryEmit("Downloading…")
                // Watchdog: drop the spinner once the row lands on disk, or
                // after a timeout so a failed/stuck job can't strand it.
                viewModelScope.launch {
                    withTimeoutOrNull(180_000L) {
                        musicRepository.observeTrackById(realId).first { it?.isDownloaded == true }
                    }
                    _downloadingKeys.update { it - key }
                }
            }
        }
    }

    fun findInFlacForCurrentTrack() {
        val track = _uiState.value.currentTrack ?: return
        if (track.isFlac) return
        viewModelScope.launch {
            _userMessages.tryEmit("Looking for FLAC\u2026")
            val result = losslessUpgrader.upgradeToLossless(track)
            _userMessages.tryEmit(snackbarCopyFor(result))
        }
    }

    /**
     * Destroy the currently-playing track. Deletes the audio file + row;
     * if [alsoBlock] is set, keeps the row as a blacklist tombstone so
     * future syncs skip the identity forever.
     *
     * Skips to the next track BEFORE the delete so ExoPlayer doesn't
     * error out mid-playback on a file that just disappeared under it.
     * On the last track in the queue, skipNext will stop playback
     * naturally — cleaner than racing the delete.
     */
    fun deleteCurrentTrack(alsoBlock: Boolean) {
        val track = _uiState.value.currentTrack ?: return
        viewModelScope.launch {
            playerRepository.skipNext()
            if (alsoBlock) {
                musicRepository.blacklistTrack(track.id)
                _userMessages.tryEmit("Deleted and blocked from future syncs.")
            } else {
                musicRepository.deleteTrack(track)
                _userMessages.tryEmit("Deleted from your device.")
            }
        }
    }

    // ------------------------------------------------------------------
    // v0.9.36 — Lyrics sheet actions
    // ------------------------------------------------------------------

    /**
     * Open the lyrics sheet for the currently-playing track. Open-only since
     * the live-lyrics bar: fetching is the lyricsViewState chain's
     * subscription-gated trigger's job (plus the sheet's Retry). The old
     * unconditional streaming re-fetch here reset shared state to Loading —
     * with the bar consuming that same state, a bar tap would have hidden
     * the bar mid-tap.
     */
    fun onShowLyrics() {
        _lyricsSheetOpen.value = true
    }

    /** Close the lyrics sheet without affecting playback. */
    fun onDismissLyrics() {
        _lyricsSheetOpen.value = false
    }

    /**
     * Retry the lyrics fetch for the currently-playing track. Resolves inline
     * (library track) or re-runs the transient chain (streaming). No-op when
     * nothing is playing or when the track has no valid id (search-preview rows).
     */
    fun onLyricsRetry() {
        val track = _uiState.value.currentTrack ?: return
        if (track.id > 0L) fetchLibraryLyrics(track, force = true)
        else fetchStreamingLyrics(track)
    }

    fun exportLyricsForCurrentTrack() {
        val track = _uiState.value.currentTrack?.takeIf { it.id > 0L && it.isDownloaded } ?: return
        if (!_exportingLyricsTrackId.compareAndSet(expect = null, update = track.id)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val message = try {
                    val lyrics = lyricsRepository.get(track.id)
                    if (lyrics == null || lyrics.syncedLrc.isNullOrBlank() && lyrics.plainText.isNullOrBlank()) {
                        "No cached lyrics to save"
                    } else {
                        lyricsSidecarWriter.write(track.id, lyrics)
                        "Lyrics sidecar saved"
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { "Couldn't save lyrics sidecar" }
                val suffix = if (_uiState.value.currentTrack?.id != track.id) " for ‘${track.title}’" else ""
                _userMessages.emit("$message$suffix.")
            } finally {
                _exportingLyricsTrackId.compareAndSet(expect = track.id, update = null)
            }
        }
    }

    /**
     * Streaming-track lyrics path. Hits the source chain via
     * [LyricsRepository.resolveTransient] (no Room write, no sidecar) and
     * pushes the resulting [LyricsViewState] into [_streamingLyricsState].
     * A source failure (throw) renders [LyricsViewState.Error] with Retry —
     * distinct from a definitive miss (None). Re-entrant: calling on Retry
     * resets to Loading then runs the chain fresh.
     *
     * Result write is keyed by [trackKey]: since the bar's track-change
     * trigger fires this on every streaming skip, a rapid A→B skip runs two
     * resolves against the same untagged state — without the key check, A's
     * slow resolve landing after B's would overwrite B's lyrics and stick.
     */
    private fun fetchStreamingLyrics(track: Track) {
        val key = trackKey(track)
        _streamingLyricsState.value = LyricsViewState.Loading
        viewModelScope.launch {
            val query = LyricsQuery(
                trackId = 0L,                                    // unused in transient path
                title = track.title,
                artist = track.artist,
                album = track.album.ifBlank { null },
                albumArtist = track.albumArtist.ifBlank { null },
                durationMs = track.durationMs.takeIf { it > 0 },
                youtubeVideoId = track.youtubeId,
            )
            val result = try {
                lyricsViewStateForResult(lyricsRepository.resolveTransient(query))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LyricsViewState.Error(retryable = true)
            }
            if (trackKey(_uiState.value.currentTrack) == key) {
                _streamingLyricsState.value = result
            }
        }
    }

    /**
     * Tap-to-seek on a synced lyric line. Routed through the same
     * player-controller seek path that the progress bar uses
     * ([onSeekTo]) so the seek is bounded by the current duration.
     */
    fun onLyricsLineSeek(timestampMs: Long) {
        onSeekTo(timestampMs)
    }

    /**
     * Library-track on-open lyrics fetch. Resolves INLINE via the repository
     * (writes the Room row + stamps `lyrics_fetched_at`) instead of enqueuing a
     * WorkManager job — the user is watching the sheet, so the fetch must not
     * queue behind background workers (sync/backfill/discovery), which deferred
     * the result by minutes. The [lyricsViewState] combine observes the row +
     * stamp and flips the sheet the moment this finishes.
     *
     * Deduped per track so reopening doesn't stack duplicate resolves. [force]
     * (Retry) bypasses the in-flight guard and clears the confirmed-miss stamp
     * FIRST so the combine visibly returns to Loading while the retry runs.
     * A source failure sets [_lyricsFetchFailedTrackId] (stamp stays NULL) so
     * the sheet renders a retryable Error instead of sticking on Loading.
     */
    private fun fetchLibraryLyrics(track: Track, force: Boolean = false) {
        if (!force && !lyricsFetchInFlight.add(track.id)) return
        if (force) lyricsFetchInFlight.add(track.id)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Snapshot stamps go stale (queue Tracks don't refresh after a
                // fetch stamps Room) — re-check the LIVE stamp so a revisited
                // track doesn't re-hit the network. Retry (force) skips the
                // guard deliberately.
                if (!force && lyricsRepository.observeFetchedAt(track.id).first() != null) {
                    return@launch
                }
                _lyricsFetchFailedTrackId.value = null
                if (force) lyricsRepository.clearFetchStamp(track.id)
                val query = LyricsQuery(
                    trackId = track.id,
                    title = track.title,
                    artist = track.artist,
                    album = track.album.ifBlank { null },
                    albumArtist = track.albumArtist.ifBlank { null },
                    durationMs = track.durationMs.takeIf { it > 0 },
                    youtubeVideoId = track.youtubeId,
                )
                try {
                    lyricsRepository.resolveAndStore(query)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _lyricsFetchFailedTrackId.value = track.id
                }
            } finally {
                lyricsFetchInFlight.remove(track.id)
            }
        }
    }

    // ------------------------------------------------------------------
    // v0.9.13 — Heart / Like actions (Stash-only, standard toggle UX)
    // ------------------------------------------------------------------

    /**
     * Heart toggle. Tap on an unliked track adds it to the local Stash
     * Liked Songs playlist; tap on a liked track removes it. Pure
     * local-DB operation — does NOT propagate to Spotify/YT Music.
     *
     * The Spotify auto-save scrobbler runs separately; un-liking
     * locally never unsaves on Spotify (per v0.9.13 design — keeps
     * the user's external account untouched).
     *
     * Heart-state visual: optimistic update writes the new
     * stashLikedAt value to [_uiState.currentTrack] so the icon flips
     * immediately. Room's observation eventually delivers the same
     * value, so the two converge without a flicker.
     */
    fun onLikeTap() {
        val track = _uiState.value.currentTrack ?: return
        val wasLiked = track.stashLikedAt != null

        // Optimistic UI: flip the state in our local map immediately.
        // The combine() block in observePlayerStateLive merges this so
        // the heart icon updates within one frame and stays updated.
        // We use a temporary key for non-library tracks (id=0) using their videoId.
        val trackKey = if (track.id == 0L) track.youtubeId.hashCode().toLong() else track.id
        optimisticLikeState.update { it + (trackKey to !wasLiked) }

        viewModelScope.launch {
            runCatching {
                // Handles all three id shapes: real Room PK, search-preview
                // id=0 (legacy), and v0.9.30 streaming-engine synthetic
                // `videoId.hashCode().toLong()`. Without this, streaming-only
                // tracks (e.g. Liked Songs played from network with no local
                // file) FK-violate at the cross-ref insert and surface as
                // "Couldn't add to Liked Songs" (issue #105).
                val finalTrackId = musicRepository.ensureTrackPersisted(track)

                // v0.9.52: route through LikeCoordinator. The local Stash
                // like still happens synchronously (same behavior as before);
                // optional Spotify/YT mirroring (off by default) layers on top.
                likeCoordinator.setLiked(finalTrackId, liked = !wasLiked)
                // Once the DB write is confirmed, we can clear the optimistic
                // override; Room's own emission will carry the truth.
                optimisticLikeState.update { it - trackKey }
            }.onFailure { e ->
                android.util.Log.w("NowPlayingViewModel", "stash like toggle failed", e)
                _userMessages.tryEmit(
                    if (wasLiked) "Couldn't remove from Liked Songs"
                    else "Couldn't add to Liked Songs"
                )
                // Roll back: clear the optimistic flip so UI reflects truth.
                optimisticLikeState.update { it - trackKey }
            }
        }
    }

    // ------------------------------------------------------------------
    // Palette Color Extraction
    // ------------------------------------------------------------------

    /** Last bitmap run through Palette — dedup key for [onAlbumArtLoaded]. */
    private var lastPaletteBitmap: Bitmap? = null

    /**
     * Called when the album art [Bitmap] has been loaded (e.g. via Coil).
     *
     * Extracts dominant, vibrant, and muted colors on [Dispatchers.Default]
     * so the main thread is never blocked by the Palette computation.
     *
     * Passing `null` resets colors to their defaults.
     */
    fun onAlbumArtLoaded(bitmap: Bitmap?) {
        // Same-bitmap dedup: Coil re-fires onState Success when the image
        // composable re-enters composition (sheet open/close, config change)
        // with the same cached bitmap — re-running Palette.generate() then
        // is pure waste. Identity comparison is right: a new track's art is
        // always a new Bitmap instance.
        if (bitmap != null && bitmap === lastPaletteBitmap) return
        lastPaletteBitmap = bitmap
        if (bitmap == null) {
            _uiState.update {
                it.copy(
                    dominantColor = Color(0xFF6750A4),
                    vibrantColor = Color(0xFF8E24AA),
                    mutedColor = Color(0xFF37474F),
                )
            }
            return
        }

        viewModelScope.launch {
            val (dominant, vibrant, muted) = withContext(Dispatchers.Default) {
                val palette = Palette.from(bitmap).generate()
                Triple(
                    Color(palette.getDominantColor(0xFF6750A4.toInt())),
                    Color(palette.getVibrantColor(0xFF8E24AA.toInt())),
                    Color(palette.getMutedColor(0xFF37474F.toInt())),
                )
            }
            _uiState.update {
                it.copy(
                    dominantColor = dominant,
                    vibrantColor = vibrant,
                    mutedColor = muted,
                )
            }
        }
    }
}

/**
 * Pure mapping from [UpgradeResult] to the snackbar string shown after
 * a Now Playing "Find in FLAC" attempt. Top-level + `internal` so the
 * test in this module can call it without instantiating the ViewModel.
 */
internal fun snackbarCopyFor(result: UpgradeResult): String = when (result) {
    UpgradeResult.Upgraded -> "Upgraded to FLAC"
    UpgradeResult.NoMatch -> "No lossless match found"
    UpgradeResult.Error -> "Couldn't check lossless sources"
}

/**
 * Decide the Track to DISPLAY in Now Playing, overlaying the actually-served
 * stream format (codec/bit-depth/sample-rate/bitrate) from the active
 * MediaItem onto [baseTrack]. Streaming-only Room rows carry a stale
 * `file_format` ("opus") from sync, so the live stream's real format must win.
 *
 * Keyed on the MediaItem actually carrying a **stream origin** (it was
 * produced by a stream resolver), NOT on [isHttpStreaming]. antra serves its
 * lossless FLAC from a LOCAL cache file (`file://`), so the player's
 * http(s)-only streaming flag is false for it — gating on that flag left a
 * real 24-bit antra FLAC mislabeled "OPUS". http streams (kennyy/squid) keep
 * working because they ALSO set a stream origin; [isHttpStreaming] is retained
 * only as a defensive OR.
 *
 * No overlay (returns [baseTrack] unchanged) when: there is no base track, the
 * active item didn't come from a stream resolver, ids don't match, or the
 * stream's own format is blank/"opus" (a genuine YouTube-fallback opus must
 * not be relabeled FLAC). Top-level + `internal` so the test can call it
 * without booting the ViewModel.
 */
internal fun overlayDisplayTrack(
    isHttpStreaming: Boolean,
    streamFormat: Track?,
    baseTrack: Track?,
): Track? {
    if (baseTrack == null) return null
    if (streamFormat == null) return baseTrack
    val cameFromStreamResolver = isHttpStreaming || streamFormat.streamOrigin != null
    val idMatches = streamFormat.id == baseTrack.id ||
        (!streamFormat.youtubeId.isNullOrBlank() && streamFormat.youtubeId == baseTrack.youtubeId)
    val hasRealFormat = streamFormat.fileFormat.isNotBlank() && streamFormat.fileFormat != "opus"
    return if (cameFromStreamResolver && idMatches && hasRealFormat) {
        baseTrack.copy(
            fileFormat = streamFormat.fileFormat,
            bitsPerSample = streamFormat.bitsPerSample ?: baseTrack.bitsPerSample,
            sampleRateHz = streamFormat.sampleRateHz ?: baseTrack.sampleRateHz,
            qualityKbps = if (streamFormat.qualityKbps > 0) streamFormat.qualityKbps else baseTrack.qualityKbps,
            // streamOrigin only exists on MediaItem-derived tracks (not in
            // Room), so always overlay from the active item when streaming.
            streamOrigin = streamFormat.streamOrigin,
        )
    } else baseTrack
}
