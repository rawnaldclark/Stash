package com.stash.feature.nowplaying

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.lossless.LosslessUpgrader
import com.stash.core.data.prefs.NowPlayingAction
import com.stash.core.data.prefs.NowPlayingPreference
import com.stash.core.data.prefs.LyricsSourcePreference
import com.stash.data.lyrics.TtmlUpgradeResult
import com.stash.data.lyrics.TtmlUpgradeSources
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.SleepTimerController
import com.stash.core.model.AlbumNavTarget
import com.stash.core.model.RadioStartResult
import com.stash.core.model.UpgradeResult
import com.stash.core.model.isFlac
import com.stash.core.model.SCANNED_UNKNOWN_ARTIST
import com.stash.core.model.isScannedSong
import com.stash.core.ui.components.PlaylistInfo
import com.stash.core.model.Track
import coil3.SingletonImageLoader
import com.stash.core.data.backcover.AlbumBackCovers
import com.stash.core.data.backcover.AlbumCoverArt
import com.stash.core.data.backcover.BackCoverAlbum
import com.stash.data.lyrics.LyricsRepository
import com.stash.data.lyrics.sidecar.LyricsSidecarWriter
import com.stash.data.lyrics.source.LyricsQuery
import com.stash.feature.nowplaying.ui.LyricsOutputOffset
import com.stash.feature.nowplaying.ui.LyricsViewState
import com.stash.feature.nowplaying.ui.backCoverRequest
import com.stash.feature.nowplaying.ui.clampOutputOffsetMs
import com.stash.feature.nowplaying.ui.effectiveLyricsOffsetMs
import com.stash.feature.nowplaying.ui.lyricsPositionMs
import com.stash.feature.nowplaying.ui.lyricsSeekTargetMs
import com.stash.feature.nowplaying.ui.lyricsViewStateFor
import com.stash.feature.nowplaying.ui.lyricsViewStateForResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    private val sleepTimerController: SleepTimerController,
    private val musicRepository: MusicRepository,
    private val likeCoordinator: com.stash.core.data.social.LikeCoordinator,
    private val losslessUpgrader: LosslessUpgrader,
    // v0.9.36 Task 12 — lyrics view observes the lyrics row and may
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
    // Gates the lyrics prefetch to the foreground. Hilt binds the process lifecycle
    // (ProcessForeground); tests leave the default, which never prefetches.
    private val appForeground: AppForeground = AppForeground.Never,
    // Runs user-asked writes past this screen's lifetime (see [launchUserAction]). Hilt binds the
    // process-wide scope; tests leave the default, which runs on their Main test dispatcher.
    private val userActionScope: UserActionScope = UserActionScope.OnMain,
    // The output lyrics are heard through and its own lyrics offset (Bluetooth latency). Hilt
    // binds the routed output; tests leave the default, which knows no output.
    private val lyricsOutputOffsets: LyricsOutputOffsets = LyricsOutputOffsets.None,
    // The album back covers the art flips to. Hilt binds the Cover Art Archive lookup; tests leave
    // the default, which knows none.
    private val backCovers: AlbumBackCovers = AlbumBackCovers.None,
    private val trackAlbums: com.stash.core.data.lastfm.TrackAlbums = com.stash.core.data.lastfm.TrackAlbums.None,
) : ViewModel() {

    /**
     * Launches work the user asked for that must finish even if they leave Now Playing (this
     * ViewModel is scoped to its back-stack entry, and leaving cancelled viewModelScope mid-job).
     * Its messages still go to [userMessages]; once the screen is gone nobody shows them, but the
     * work itself completes.
     */
    private fun launchUserAction(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
        userActionScope.scope.launch(block = block)

    // ── Share (fork issue ParaliyzedEvo/Stash#40) ──
    // The player-state Track is a slim media-session reconstruction with
    // null spotifyUri/youtubeId; fetch the FULL DB row before the sheet
    // opens so the link options actually appear.
    private val _shareTrack = kotlinx.coroutines.flow.MutableStateFlow<com.stash.core.model.Track?>(null)
    val shareTrack: kotlinx.coroutines.flow.StateFlow<com.stash.core.model.Track?> = _shareTrack

    /** Looks up the current track for the share sheet. False when there's nothing to share. */
    fun onShareCurrent(): Boolean {
        val slim = uiState.value.currentTrack ?: return false
        viewModelScope.launch {
            _shareTrack.value = musicRepository.observeTrackById(slim.id)
                .firstOrNull() ?: slim
        }
        return true
    }

    fun onShareDismissed() {
        _shareTrack.value = null
    }

    private val _uiState = MutableStateFlow(NowPlayingUiState())
    val uiState: StateFlow<NowPlayingUiState> = _uiState.asStateFlow()

    /**
     * Live synced-line bar opt-in (default OFF — the ticking line can pull
     * the listener out of the music). Off, synced tracks still get the
     * "View lyrics ♪" bar; the toggle lives in the lyrics view's menu.
     */
    val liveLyricsBarEnabled: StateFlow<Boolean> = lyricsPreference.liveBarEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setLiveLyricsBarEnabled(enabled: Boolean) {
        viewModelScope.launch { lyricsPreference.setLiveBarEnabled(enabled) }
    }

    val ambientAnimationEnabled: StateFlow<Boolean?> = nowPlayingPreference.ambientAnimationEnabled
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Settings > Appearance > Cycle album art. */
    val cycleAlbumArt: StateFlow<Boolean> = nowPlayingPreference.cycleAlbumArt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * The full art layout (the top row's toggle; on unless turned off), kept for next time. Null
     * until DataStore answers, so Now Playing opens straight into the layout it was left in.
     */
    val fullArt: StateFlow<Boolean?> = nowPlayingPreference.fullArt
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Settings > Appearance > Full art zoom. */
    val fullArtZoom: StateFlow<Float> = nowPlayingPreference.fullArtZoom
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.stash.core.data.prefs.NowPlayingPreference.FULL_ART_ZOOM_DEFAULT)

    /** Settings > Appearance > Full art pan. */
    val fullArtPan: StateFlow<com.stash.core.data.prefs.FullArtPan> = nowPlayingPreference.fullArtPan
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.stash.core.data.prefs.FullArtPan.DEFAULT)

    /** Settings > Appearance > Full art blend. */
    val fullArtBlend: StateFlow<com.stash.core.data.prefs.FullArtBlend> = nowPlayingPreference.fullArtBlend
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.stash.core.data.prefs.FullArtBlend.DEFAULT)

    /** Settings > Appearance > Title alignment. */
    val titleAlign: StateFlow<com.stash.core.data.prefs.NpTitleAlign> = nowPlayingPreference.titleAlign
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.stash.core.data.prefs.NpTitleAlign.DEFAULT)

    /** Settings > Appearance > Button beside the title. */
    val titleButton: StateFlow<com.stash.core.data.prefs.TitleButton> = nowPlayingPreference.titleButton
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.stash.core.data.prefs.TitleButton.SLEEP_TIMER)

    fun onToggleFullArt() {
        val on = fullArt.value != true
        viewModelScope.launch { nowPlayingPreference.setFullArt(on) }
    }

    /** Settings > Appearance > Ambient style. */
    val ambientStyle: StateFlow<com.stash.core.data.prefs.AmbientStyle?> = nowPlayingPreference.ambientStyle
        .map<com.stash.core.data.prefs.AmbientStyle, com.stash.core.data.prefs.AmbientStyle?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The actions pinned to the top row (edited from the ⋮ menu). Null until DataStore answers,
     * like the ambient prefs above, so the screen never draws a frame of the default row for a
     * user who changed it.
     */
    val pinnedActions: StateFlow<Set<NowPlayingAction>?> = nowPlayingPreference.pinnedActions
        .map<Set<NowPlayingAction>, Set<NowPlayingAction>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Pins or unpins [action]. The read, the rule and the write are one DataStore transaction
     * ([NowPlayingPreference.togglePin]), so quick taps queue up instead of racing, and the
     * [NowPlayingAction.MAX_PINS] cap holds in storage. The editor greys out and explains the
     * cap before a tap gets here; a pin refused by a race simply doesn't appear.
     */
    fun onTogglePin(action: NowPlayingAction) {
        viewModelScope.launch { nowPlayingPreference.togglePin(action) }
    }

    /** Back to Radio · ⋮ · Queue. */
    fun onResetPins() {
        viewModelScope.launch { nowPlayingPreference.resetPinnedActions() }
    }

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
    // Tap-to-album — resolve the playing track's album and navigate to it
    // ------------------------------------------------------------------

    /**
     * One-shot navigation targets emitted when the user taps "View Album".
     * Mirrors [_artistNavEvents]/[artistNavEvents] exactly — same buffer
     * reasoning (a config-change re-subscribe landing right after emit
     * shouldn't drop the event).
     */
    private val _albumNavEvents = MutableSharedFlow<AlbumNavTarget>(extraBufferCapacity = 1)
    val albumNavEvents: SharedFlow<AlbumNavTarget> = _albumNavEvents.asSharedFlow()

    /**
     * True while an album resolve is in flight. Mirrors [_resolvingArtist] —
     * the screen can swap the "View Album" row's icon for a spinner and/or
     * use this as a double-tap guard.
     */
    private val _resolvingAlbum = MutableStateFlow(false)
    val resolvingAlbum: StateFlow<Boolean> = _resolvingAlbum.asStateFlow()

    /**
     * "View Album" tap. Resolves the playing track's album NAME (scoped to
     * its artist, to disambiguate same-named albums) to a YT browseId and
     * emits an [AlbumNavTarget]. No-op when nothing is playing, the track
     * has no album tag, or a resolve is already in flight. Resolve
     * miss/failure -> snackbar, no nav. This intentionally does NOT route
     * through the local Albums library tab — it opens the real remote
     * album page, same as [onTrackInfoTapped] opens the real artist page.
     */
    fun onViewAlbumTapped() {
        val track = _uiState.value.currentTrack ?: return
        if (_resolvingAlbum.value) return
        val albumName = track.album
        if (albumName.isBlank()) {
            _userMessages.tryEmit("This track has no album info")
            return
        }
        val artistName = track.albumArtist.ifBlank { track.artist }
        _resolvingAlbum.value = true
        viewModelScope.launch {
            try {
                val album = ytMusicApiClient.resolveAlbum(albumName, artistName)
                if (album != null) {
                    _albumNavEvents.emit(
                        AlbumNavTarget(
                            albumId = album.id,
                            name = album.title,
                            artUrl = album.thumbnailUrl,
                            artistName = artistName,
                        ),
                    )
                } else {
                    _userMessages.emit("Couldn't find this album")
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                _userMessages.emit("Couldn't find this album")
            } finally {
                _resolvingAlbum.value = false
            }
        }
    }

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
     * when the track's live row flips `isDownloaded`, when its queue row says
     * the download stopped (failed, cancelled, held for a lossless copy), or
     * after a watchdog timeout so a stuck job never strands a permanent spinner.
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

    /** True from radio tap until the station build returns (success or not). */
    private val _radioTuning = MutableStateFlow(false)
    val radioTuning: StateFlow<Boolean> = _radioTuning.asStateFlow()

    /** Start a song radio seeded from the currently-playing track. Streaming-only;
     *  each [RadioStartResult] failure surfaces its own hint, not a dead tap. */
    fun startRadioFromCurrent() {
        val track = _uiState.value.currentTrack ?: return
        if (_radioTuning.value) return // ignore taps while tuning
        // Set SYNCHRONOUSLY (not inside launch) so a double-tap in the same
        // frame can't pass the guard twice before the coroutine dispatches.
        _radioTuning.value = true
        viewModelScope.launch {
            try {
                val result = playerRepository.startRadio(
                    com.stash.core.data.radio.RadioSeed.Song(
                        title = track.title, artist = track.artist, ytVideoId = track.youtubeId,
                    ),
                    // The seed IS the currently-playing track — keep it playing, don't restart.
                    keepCurrent = true,
                )
                // Started locks the sweep with no toast; each failure explains itself.
                radioFailureMessage(result)?.let { _userMessages.tryEmit(it) }
            } finally {
                _radioTuning.value = false
            }
        }
    }

    /** User-facing hint for a non-Started radio result; null for Started. */
    private fun radioFailureMessage(result: RadioStartResult): String? = when (result) {
        RadioStartResult.Started -> null
        RadioStartResult.PlayerNotReady -> "Player is still starting — try again."
        RadioStartResult.NoStation -> "Couldn't find similar tracks for this song."
    }

    /** Stop the active radio station. */
    fun stopRadio() = playerRepository.stopRadio()

    // ── Playback speed (session-only; lives in PlayerRepository) ──
    val playbackSpeed: StateFlow<Float> = playerRepository.playbackSpeed

    fun onSetPlaybackSpeed(speed: Float) = playerRepository.setPlaybackSpeed(speed)

    // ------------------------------------------------------------------
    // Sleep timer
    // ------------------------------------------------------------------

    /** Current sleep-timer state, mirrored from the app-wide singleton. */
    val sleepTimerState: StateFlow<SleepTimerController.State> = sleepTimerController.state

    /** Arm a countdown timer for [minutes] minutes. */
    fun onSleepTimerMinutes(minutes: Int) {
        sleepTimerController.startMinutes(minutes)
    }

    /** Arm a timer that pauses at the end of the current track. */
    fun onSleepTimerEndOfTrack() {
        sleepTimerController.stopAtEndOfTrack()
    }

    /** Disarm the sleep timer without touching playback. */
    fun onSleepTimerCancel() {
        sleepTimerController.cancel()
    }

    fun onTrackInfoTapped() {
        val track = _uiState.value.currentTrack ?: return
        if (_resolvingArtist.value) return
        val name = track.albumArtist.ifBlank { track.artist }
        if (name.isBlank()) {
            _userMessages.tryEmit("Couldn't find this artist")
            return
        }
        // The scanner's stand-in for a file with no artist tag isn't an artist to look up.
        if (track.isScannedSong && name == SCANNED_UNKNOWN_ARTIST) {
            _userMessages.tryEmit("This file has no artist tag.")
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
    // v0.9.36 Task 12 — lyrics view state
    // ------------------------------------------------------------------

    /**
     * Whether Now Playing shows its lyrics mode (the full-screen lyrics over the ambient) instead
     * of the big art. Toggled by [onShowLyrics] / [onDismissLyrics] / [onToggleLyrics]. It
     * survives a song change on purpose: the next song's lyrics simply load in place.
     */
    private val _lyricsMode = MutableStateFlow(false)
    val lyricsMode: StateFlow<Boolean> = _lyricsMode.asStateFlow()

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
     * so reopening the lyrics view doesn't stack duplicate fetches for the same track.
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
     * The playing track as lyrics see it: its row in the library when it has one ([stored]),
     * else a stream known only by its YouTube id. Keyed so a track change is one emission.
     *
     * "Stored" means Room answered with a row, not a positive id: a stream from radio or a queue
     * plays under a transient `videoId.hashCode()` id (negative half the time) until a listen
     * persists it about 30s in. Treating that id as a library id fetched nothing for a negative
     * hash (Looking for lyrics for ~30s) and a doomed library fetch for a positive one.
     */
    private data class LyricsTarget(val track: Track, val stored: Boolean) {
        val key: String get() = if (stored) "id:${track.id}" else "yt:${track.youtubeId ?: track.id}"
    }

    private val lyricsTargets: Flow<LyricsTarget?> = uiState
        // Wait for Room's answer: a library track looked like a stream for a few hundred ms
        // after a track change, which set off a needless network lookup.
        .filter { s -> s.currentTrack == null || s.currentTrackStored != null }
        .map { s -> s.currentTrack?.let { LyricsTarget(it, s.currentTrackStored == true) } }
        .distinctUntilChanged { old, new -> old?.key == new?.key }

    /**
     * The last stream's lyrics, by YouTube id. When that stream is persisted (a new library id),
     * the lyrics view keeps showing these while the library row is filled from the repository's
     * memory, instead of flashing back to "Looking for lyrics".
     */
    private val streamLyricsCarry = MutableStateFlow<Pair<String, LyricsViewState>?>(null)

    /**
     * Re-derives [LyricsViewState] from the currently-playing track and
     * the observed [com.stash.core.data.db.entity.LyricsEntity] row.
     *
     * Tracks the `currentTrack` field of [uiState] (the same field the
     * rest of Now Playing reads) so the lyrics view always reflects the
     * track that's currently displayed/playing — no separate "selected
     * for lyrics" state. When the track flips, [flatMapLatest] cancels
     * the old observer and subscribes to the new track's row.
     *
     * SharingStarted.WhileSubscribed(5_000) keeps the flow warm for
     * 5 seconds after the lyrics view closes so a quick close+reopen doesn't
     * re-derive from scratch.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val lyricsViewState: StateFlow<LyricsViewState> = lyricsTargets
        .onEach { target ->
            // Reset the failed-fetch overlay on track change. The stream state is not reset
            // here: fetchStreamingLyrics sets it (Loading or the remembered answer) for a new
            // stream, and nulling it while the previous branch is still subscribed made that
            // branch emit a stray Loading (seen when a stream is persisted mid-song).
            _lyricsFetchFailedTrackId.value = null
            // The carry only bridges a stream to its own persisted row; any other track drops it
            // (else a later Retry on that row would show the old lyrics instead of Loading).
            val carry = streamLyricsCarry.value
            if (carry != null && carry.first != target?.track?.youtubeId) streamLyricsCarry.value = null
            // Fetch on screen-open (initial emission) and on every track change while Now
            // Playing is open. Lyrics are usually here already: see [prefetchLyricsWhileForeground].
            // Both paths share the repository's one walk per song, so this costs no second lookup.
            when {
                target == null -> Unit
                target.stored -> if (target.track.lyricsFetchedAt == null) fetchLibraryLyrics(target.track)
                else -> fetchStreamingLyrics(target.track)
            }
        }
        .flatMapLatest { target ->
            val track = target?.track
            when {
                track == null -> flowOf(LyricsViewState.None)
                // Combine the lyrics row with the LIVE lyrics_fetched_at stamp.
                // Deriving the sentinel from the captured track snapshot left the
                // view stuck on Loading after a fetch finished (the stamp updated
                // in Room but the captured value never did); watching the stamp
                // live flips the lyrics view the instant the fetch completes — no
                // close+reopen needed.
                target.stored -> combine(
                    lyricsRepository.observe(track.id),
                    lyricsRepository.observeFetchedAt(track.id),
                    _lyricsFetchFailedTrackId,
                    streamLyricsCarry,
                ) { row, fetchedAt, failedId, carry ->
                    // A NULL stamp normally means "fetch in flight" (Loading),
                    // but when the inline fetch failed it stays NULL forever —
                    // surface that as a retryable Error instead.
                    val state = if (fetchedAt == null && failedId == track.id) {
                        LyricsViewState.Error(retryable = true)
                    } else {
                        lyricsViewStateFor(track.copy(lyricsFetchedAt = fetchedAt), row)
                    }
                    // A stream just persisted: keep its lyrics on screen while the row fills.
                    val carried = if (state == LyricsViewState.Loading && carry != null && carry.first == track.youtubeId) {
                        carry.second
                    } else {
                        state
                    }
                    // The stamp and the row are separate writes, seen by separate Room queries:
                    // a stamp with no row yet reads as "no lyrics" for a moment. Only believe
                    // that if it lasts (see the transform below).
                    carried to (row == null && fetchedAt != null && fetchedAt > 0L)
                }.transformLatest { (state, rowLagging) ->
                    if (rowLagging) delay(ROW_LAG_GRACE_MS)
                    emit(state)
                }
                // A stream: the transient state, seeded by fetchStreamingLyrics (from the
                // repository's memory at once when the song was already looked up).
                else -> _streamingLyricsState.map { it ?: LyricsViewState.Loading }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = LyricsViewState.Loading,
        )

    /**
     * Looks the playing track's lyrics up early, while the app is in the foreground, so opening
     * Now Playing or its lyrics finds them in hand instead of starting the lookup then (Now
     * Playing used to be the only trigger: a song started from a list and opened five seconds
     * later showed "Looking for lyrics" for the whole lookup).
     *
     * - Waits [PREFETCH_DWELL_MS] on each song first: a skip cancels the wait, so flicking
     *   through a queue looks nothing up (and can't run Apple's lookups into its 10-minute skip
     *   window), and a stream's duration is known by the time the query is built.
     * - Hands the request to [LyricsRepository.prefetch], the one owner: the mini player's
     *   ViewModel and Now Playing's both get here, and the repository runs the song once.
     * - Nothing in the background (screen off, app away).
     *
     * Now Playing's own trigger stays immediate (see [lyricsViewState]); it joins the same walk.
     */
    private fun prefetchLyricsWhileForeground() {
        viewModelScope.launch {
            combine(lyricsTargets, appForeground.foreground) { target, foreground -> target to foreground }
                .distinctUntilChanged { old, new -> old.first?.key == new.first?.key && old.second == new.second }
                .collectLatest { (target, foreground) ->
                    when {
                        // Gone to the background, or this track's lyrics are already stored: stop
                        // any request. (Not for "no track yet": a new ViewModel's first, empty
                        // emission must not revoke the other ViewModel's request.)
                        !foreground -> { lyricsRepository.prefetch(null, store = false); return@collectLatest }
                        target == null -> return@collectLatest
                        target.stored && target.track.lyricsFetchedAt != null -> {
                            lyricsRepository.prefetch(null, store = false)
                            return@collectLatest
                        }
                    }
                    delay(PREFETCH_DWELL_MS)
                    // Build the query now, from the track as it is after the wait (a stream's
                    // duration arrives from the player a moment after it starts).
                    val now = _uiState.value.currentTrack
                        ?.takeIf { LyricsTarget(it, target.stored).key == target.key } ?: target.track
                    lyricsRepository.prefetch(
                        lyricsQuery(now, trackId = if (target.stored) now.id else 0L),
                        store = target.stored,
                    )
                }
        }
    }

    // ── Back cover: a tap on the art turns it over to the album's back, and its pill opens the inside ──

    /**
     * The playing song's album as the back cover lookup knows it, one emission per album. Waits for
     * Room's answer, as [lyricsTargets] does: the player's own track comes first without the ISRC
     * the library row holds, which finds the album more surely than its title.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val backCoverAlbums: Flow<BackCoverAlbum?> = uiState
        .filter { s -> s.currentTrack == null || s.currentTrackStored != null }
        .map { s -> s.currentTrack }
        .distinctUntilChanged { a, b ->
            a?.id == b?.id && a?.title == b?.title && a?.artist == b?.artist &&
                a?.album == b?.album && a?.albumArtist == b?.albumArtist && a?.isrc == b?.isrc
        }
        // A song with no album of its own (a Last.fm playlist's, a mix's) gets Last.fm's.
        .mapLatest { track -> track?.let { BackCoverAlbum.of(it) ?: BackCoverAlbum.of(trackAlbums.withAlbum(it)) } }
        .distinctUntilChanged { old, new -> old?.key == new?.key }

    /**
     * The playing album's back cover and inside pages (Cover Art Archive scans), or null while
     * they aren't known. Now Playing loads the back and turns the art over to it on a tap; the
     * inside opens from the back.
     */
    /**
     * The playing album's key, the art's flip key: from the song's own album, or the one Last.fm
     * names for a song without one (a stream never stored has no row for it to be filled into).
     */
    val backCoverKey: StateFlow<String?> = backCoverAlbums
        .map { it?.key }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val coverArt: StateFlow<AlbumCoverArt?> = backCoverAlbums
        .flatMapLatest { album -> if (album == null) flowOf(null) else backCovers.observe(album) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    /**
     * Looks the playing album's back cover and inside pages up, and fetches the back's image, while
     * the app is in the foreground, after the lyrics prefetch's [PREFETCH_DWELL_MS]: so the flip is
     * ready by the time Now Playing opens, a skip through a queue looks nothing up, and nothing runs
     * with the screen off. Once per album: the answer is stored, the image disk-cached, and a skip
     * to another album cancels the walk (a cancellation is not a failure, so the album isn't held
     * back). The back is stored as soon as it is found, before the walk for the inside ends, so its
     * image is fetched then.
     */
    private fun prefetchBackCoverWhileForeground() {
        viewModelScope.launch {
            combine(backCoverAlbums, appForeground.foreground) { album, foreground -> album to foreground }
                .distinctUntilChanged { old, new -> old.first?.key == new.first?.key && old.second == new.second }
                .collectLatest { (album, foreground) ->
                    if (!foreground || album == null) return@collectLatest
                    delay(PREFETCH_DWELL_MS)
                    coroutineScope {
                        val fetchBack = launch {
                            fetchBackImage(backCovers.observe(album).mapNotNull { it?.back }.first())
                        }
                        var art = backCovers.resolve(album)
                        // A failed lookup (a server's bad moment) is tried again while the album
                        // plays on: past the repository's backoff, a few times.
                        var retries = 0
                        while (art == null && retries++ < BACK_COVER_RETRIES) {
                            delay(com.stash.core.data.backcover.AlbumBackCoverRepository.FAILURE_BACKOFF_MS + 1_000L)
                            art = backCovers.resolve(album)
                        }
                        // The watcher may not have seen a back stored at the walk's very end.
                        if (fetchBack.isActive) {
                            fetchBack.cancel()
                            art?.back?.let(::fetchBackImage)
                        }
                        // The next song's album too, so its flip is ready the moment it starts.
                        lookAhead(album)
                    }
                }
        }
    }

    /** Looks up the album of the song after the playing one, when it is another album. */
    private suspend fun lookAhead(current: BackCoverAlbum) {
        val s = uiState.value
        val next = s.queue.getOrNull(s.currentIndex + 1) ?: return
        val album = BackCoverAlbum.of(next) ?: BackCoverAlbum.of(trackAlbums.withAlbum(next)) ?: return
        if (album.key == current.key) return
        backCovers.resolve(album)?.back?.let(::fetchBackImage)
    }

    private fun fetchBackImage(url: String) {
        SingletonImageLoader.get(appContext).enqueue(backCoverRequest(appContext, url))
    }

    /** The source-chain query for [track]; [trackId] is the row to store under (0 for a stream). */
    private fun lyricsQuery(track: Track, trackId: Long) = LyricsQuery(
        trackId = trackId,
        title = track.title,
        artist = track.artist,
        album = track.album.ifBlank { null },
        albumArtist = track.albumArtist.ifBlank { null },
        durationMs = track.durationMs.takeIf { it > 0 },
        youtubeVideoId = track.youtubeId,
        isrc = track.isrc?.takeIf { it.isNotBlank() },
        interactive = true,
        // The playing track (Now Playing, its prefetch): the on-demand lyrics sources may be asked.
        onDemand = true,
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
        // An All Songs file has no YouTube id, so the fallback below would give every one the
        // same key (0) and the last song's lyrics could land on the next. Its id is unique.
        track.isScannedSong -> track.id
        else -> track.youtubeId.hashCode().toLong()
    }

    /**
     * Polls [PlayerRepository.currentPosition] at the existing 250ms
     * cadence — same flow the [observePlayerStateLive] combine already
     * consumes, so the player only ticks once.
     *
     * Exposed as a dedicated StateFlow so the lyrics view doesn't have
     * to subscribe to the full [uiState] (which re-emits on every
     * heart toggle, every queue change, every album-art recolor). The
     * synced renderer only cares about the position, so this is the
     * narrower subscription that matches what it needs.
     */
    /**
     * Raw per-track sync offset (signed ms; positive delays lyrics), for the lyrics menu's Offset dialog
     * to show the current value. Null when there's nothing to store an offset on — a streaming
     * track (id == 0L) or a track with no lyrics row yet — so the sync dialog offers no song offset
     * (only the output's, [lyricsOutputOffset], when that's known; else the menu hides the item).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val lyricsSyncOffsetMs: StateFlow<Long?> = lyricsTargets
        .flatMapLatest { target ->
            if (target != null && target.stored) lyricsRepository.observeSyncOffsetMs(target.track.id) else flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    /**
     * The output playing now and the lyrics offset set for it (the sync dialog's "Apply to all
     * songs on <device>"), or null while the output isn't known (nothing has played yet). It adds
     * to the song's own [lyricsSyncOffsetMs] for every song on that output, streams included.
     * Declared before [currentPositionMs], which combines it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val lyricsOutputOffset: StateFlow<LyricsOutputOffset?> = lyricsOutputOffsets.output
        .flatMapLatest { output ->
            if (output == null) {
                flowOf<LyricsOutputOffset?>(null)
            } else {
                lyricsOutputOffsets.offsetMs(output).map { LyricsOutputOffset(output, it) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    /**
     * Position fed to the lyrics view + live bar, with the effective lyrics offset baked in: the
     * song's [lyricsSyncOffsetMs] plus the output's [lyricsOutputOffset]
     * ([effectiveLyricsOffsetMs]). Everything else (the progress bar, `onSeekTo`, etc.) keeps
     * using the player's raw position untouched.
     */
    val currentPositionMs: StateFlow<Long> = combine(
        playerRepository.currentPosition,
        lyricsSyncOffsetMs,
        lyricsOutputOffset,
    ) { pos, songOffsetMs, output ->
        lyricsPositionMs(pos, effectiveLyricsOffsetMs(songOffsetMs, output?.offsetMs))
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = 0L,
        )

    /**
     * The player's raw position (no lyrics offset), for the progress bar, the elapsed/remaining
     * times and the MiniPlayer's progress line. Its own StateFlow, not a [uiState] field: it ticks
     * every 250ms while playing, and readers should read it as late as possible (in draw, or in a
     * small child composable) so only they redraw per tick.
     */
    val positionMs: StateFlow<Long> = playerRepository.currentPosition
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = 0L,
        )

    // Player-level notices (PlayerRepository.userMessages, streamingHaltedEvents) and like-mirror
    // failures are NOT forwarded here: MainActivity shows them on every screen. Forwarded into
    // this flow they reached the user only while Now Playing or the queue sheet collected it.
    init {
        observePlayerStateLive()
        prefetchLyricsWhileForeground()
        prefetchBackCoverWhileForeground()
        observeUserPlaylists()
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
                if (key == null) flowOf<com.stash.core.model.Track?>(null).map { key to it }
                else {
                    val (id, ytId) = key
                    // Falls back to a youtube_id lookup so the heart icon
                    // (and other Room-driven fields) update for v0.9.30
                    // streaming-engine tracks whose `id` is a synthetic
                    // `videoId.hashCode().toLong()` that doesn't exist in
                    // `tracks` until `ensureTrackPersisted` writes one
                    // under a real autogen PK (issue #105 follow-up).
                    musicRepository.observeTrackById(id).flatMapLatest { row ->
                        // A transient id is videoId.hashCode(): it can equal an unrelated row's id,
                        // so for a transient id only, a row with another YouTube id isn't this track.
                        // A real id keeps its row even when the YouTube ids differ: a match swap or
                        // re-match changes the row's id while the queued item keeps the old one, and
                        // a Listen Together listener plays its own row under the host's video id.
                        val transientId = !ytId.isNullOrBlank() && id == ytId.hashCode().toLong()
                        val sameRecording = row != null &&
                            (!transientId || row.youtubeId.isNullOrBlank() || row.youtubeId == ytId)
                        if (sameRecording || ytId.isNullOrBlank()) flowOf(row.takeIf { sameRecording })
                        else musicRepository.observeTrackByYoutubeId(ytId)
                    }.map { key to it } // tagged with the player key it answers for
                }
            }

        // The 250ms position tick is NOT combined in here (see [positionMs]): it made uiState,
        // and so every reader of it, re-emit four times a second.
        combine(
            playerRepository.playerState,
            liveTrackFlow,
            optimisticLikeState,
        ) { state, live, optimistic ->
            val (liveKey, liveTrack) = live
            // Room answers a moment after the player moves on: until it has answered for THIS
            // track, whether the track is in the library is unknown (null), not "no".
            val liveResolved = liveKey == state.currentTrack?.let { t -> t.id to t.youtubeId }
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
                    currentTrackStored = if (liveResolved) liveTrack != null else null,
                    isPlaying = state.isPlaying,
                    durationMs = state.durationMs,
                    shuffleEnabled = state.isShuffleEnabled,
                    repeatMode = state.repeatMode,
                    queueSize = state.queue.size,
                    currentIndex = state.currentIndex,
                    queue = state.queue,
                    isStreaming = state.isStreaming,
                    isBuffering = state.isBuffering,
                    playWhenReady = state.playWhenReady,
                    source = state.source,
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

    // observeLikeStateForCurrentTrack removed in v0.9.13 — subsumed by
    // observePlayerStateLive's Room-bound currentTrack flow above.

    // ------------------------------------------------------------------
    // User Actions
    // ------------------------------------------------------------------

    /**
     * Toggle between play and pause, on the player's *intent* ([NowPlayingUiState.playWhenReady]),
     * not on [NowPlayingUiState.isPlaying]: while a stream buffers isPlaying is false, and a tap
     * then went to play(), a no-op, so the user couldn't pause a stalled stream at all.
     */
    fun onPlayPauseClick() {
        val state = _uiState.value
        viewModelScope.launch {
            if (state.playWhenReady || state.isPlaying) {
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

    /** The Back button: return to the previous track, or restart the current one a few seconds in. */
    fun onSkipPrevious() {
        viewModelScope.launch { playerRepository.skipPrevious() }
    }

    /**
     * A swipe to the previous track (the art, the mini player) and the "Previous song"
     * accessibility action that stands in for it: always the previous track. A restart would send
     * the art off the side and spring it back with the same song.
     */
    fun onSwipePrevious() {
        viewModelScope.launch { playerRepository.skipToPreviousSong() }
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
     * The queue sheet's Undo: put [track], swiped away from row [index], back on that row. Never
     * at or before the playing song (the song may have moved on during the snackbar): there it
     * goes back to the top of Up next instead.
     *
     * The sheet's rows are the player's slim copies of the songs (no file path), which would come
     * back as streams; the library row brings back the downloaded file. A transient stream id can
     * equal an unrelated row's id, so a row with another YouTube id isn't taken for it. Runs past
     * this screen (see [launchUserAction]): an Undo tapped as the screen closes still lands.
     */
    fun onRestoreToQueue(index: Int, track: Track) {
        launchUserAction {
            val row = musicRepository.observeTrackById(track.id).firstOrNull()?.takeIf { row ->
                track.youtubeId.isNullOrBlank() || row.youtubeId.isNullOrBlank() || row.youtubeId == track.youtubeId
            }
            val state = _uiState.value
            val at = index.coerceAtLeast(state.currentIndex + 1).coerceAtMost(state.queue.size)
            if (!playerRepository.insertInQueue(at, row ?: track)) {
                _userMessages.tryEmit("Couldn't put that song back in the queue.")
            }
        }
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
     * Add [track] (the playing track, or a queue row) to an existing playlist, then say so.
     *
     * The track is persisted first ([MusicRepository.ensureTrackPersisted], as [onLikeTap] and
     * [toggleDownload] do): a radio/search/queue stream plays under a transient
     * `videoId.hashCode()` id with no row until a listen persists it, and the add then found no
     * row, logged, and returned. The sheet closed as if it had worked and nothing was added.
     */
    fun saveTrackToPlaylist(track: Track, playlistId: Long) {
        launchUserAction {
            val trackId = persistOrNull(track) ?: run {
                _userMessages.tryEmit("Couldn't save to playlist.")
                return@launchUserAction
            }
            musicRepository.addTrackToPlaylist(trackId, playlistId)
            val name = _uiState.value.userPlaylists.firstOrNull { it.id == playlistId }?.name
            _userMessages.tryEmit(if (name != null) "Saved to $name." else "Saved to playlist.")
        }
    }

    /**
     * Create a new playlist and immediately add [track] to it. Persists the track first, before
     * the playlist exists, so a transient stream id can't leave an empty new playlist behind
     * (see [saveTrackToPlaylist]).
     *
     * @param name  Name for the new playlist.
     * @param track The track to save into the newly created playlist.
     */
    fun createPlaylistAndAddTrack(name: String, track: Track) {
        launchUserAction {
            val trackId = persistOrNull(track) ?: run {
                _userMessages.tryEmit("Couldn't create the playlist.")
                return@launchUserAction
            }
            val playlistId = musicRepository.createPlaylist(name)
            musicRepository.addTrackToPlaylist(trackId, playlistId)
            _userMessages.tryEmit("Saved to $name.")
        }
    }

    /**
     * [track]'s real library id, writing a stream-only row for a transient stream id; null (logged)
     * when that write fails. Every id-keyed write from Now Playing goes through this, because a
     * write against a transient id silently does nothing.
     */
    private suspend fun persistOrNull(track: Track): Long? = try {
        musicRepository.ensureTrackPersisted(track)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("NowPlayingViewModel", "ensureTrackPersisted failed", e)
        null
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
        // A cloud song is the user's own file, not a match: nothing to flag or swap.
        if (track.source == com.stash.core.model.MusicSource.CLOUD) return
        launchUserAction {
            // Persisted first: flagging a stream's transient id flagged no row, yet said "Flagged".
            val trackId = persistOrNull(track) ?: run {
                _userMessages.tryEmit("Couldn't flag this song.")
                return@launchUserAction
            }
            musicRepository.setMatchFlagged(trackId, true)
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
        toggleDownload(track)
    }

    // ── Per-track actions for the play-queue ⋮ menu ─────────────────────────
    // The queue sheet acts on an arbitrary upcoming track, not the current one,
    // so these take a Track. Add-to-Queue and Delete are intentionally absent
    // from the queue menu (already queued; swipe removes).

    /** Insert [track] right after the currently-playing track. */
    fun queuePlayNext(track: Track) {
        viewModelScope.launch {
            playerRepository.addNext(track)
            _userMessages.tryEmit("Playing next")
        }
    }

    /** Start a song radio seeded from [track] (a queue row, not the current track). */
    fun startRadioForTrack(track: Track) {
        viewModelScope.launch {
            val result = playerRepository.startRadio(
                com.stash.core.data.radio.RadioSeed.Song(
                    title = track.title, artist = track.artist, ytVideoId = track.youtubeId,
                ),
            )
            radioFailureMessage(result)?.let { _userMessages.tryEmit(it) }
        }
    }

    /** Open the share sheet for [track], fetching its full row for the link options. */
    /** Looks up [track] for the share sheet. False when it can't be shared (no sheet will come). */
    fun onShareTrack(track: Track): Boolean {
        if (track.isScannedSong) return false // only on this phone: nothing a link could open
        viewModelScope.launch {
            _shareTrack.value = musicRepository.observeTrackById(track.id).firstOrNull() ?: track
        }
        return true
    }

    /** Download or remove-download for any [track] (current track or a queue row). */
    fun toggleDownload(track: Track) {
        // An All Songs file is on the phone already and isn't Stash's download to remove.
        if (track.isScannedSong) return
        launchUserAction {
            if (track.isDownloaded) {
                musicRepository.removeDownload(track.id)
                _userMessages.tryEmit("Download removed.")
            } else {
                val key = downloadKeyFor(track)
                _downloadingKeys.update { it + key }
                // Resolve a synthetic streaming-engine id to a real DB row
                // before queuing — otherwise `getById` returns null and the
                // queue insert silently no-ops while the toast still fires
                // (issue #105). persistOrNull rethrows cancellation (runCatching
                // reported it as a failed download).
                val realId = persistOrNull(track) ?: run {
                    _downloadingKeys.update { it - key }
                    _userMessages.tryEmit("Couldn't queue download.")
                    return@launchUserAction
                }
                val queued = musicRepository.queueDownload(realId)
                if (!queued) {
                    _downloadingKeys.update { it - key }
                    _userMessages.tryEmit("Couldn't queue download.")
                    return@launchUserAction
                }
                _userMessages.tryEmit("Downloading…")
                // Watchdog: drop the spinner once the song lands on disk, or as
                // soon as its queue row says the download stopped without it
                // (failed, cancelled, held for a lossless copy, or removed),
                // saying which. It used to wait only for the file, so a failed
                // download spun for the whole timeout and then went quiet. The
                // timeout stays as the last resort for a job that just hangs.
                viewModelScope.launch {
                    val outcome = withTimeoutOrNull(DOWNLOAD_WATCHDOG_MS) {
                        kotlinx.coroutines.flow.merge(
                            musicRepository.observeTrackById(realId)
                                .filter { it?.isDownloaded == true }
                                .map { DownloadWatch.DONE },
                            musicRepository.observeDownloadStatus(realId)
                                .map(::downloadWatchOf)
                                .filter { it != DownloadWatch.RUNNING },
                        ).first()
                    }
                    _downloadingKeys.update { it - key }
                    // A waiting download says why, from its own queue row.
                    val reason = if (outcome == DownloadWatch.WAITING_FOR_LOSSLESS) {
                        runCatching { musicRepository.downloadWaitReason(realId) }
                            .onFailure { if (it is CancellationException) throw it }
                            .getOrNull()
                    } else {
                        null
                    }
                    outcome?.let { downloadWatchMessage(it, track.title, reason) }?.let { _userMessages.tryEmit(it) }
                }
            }
        }
    }

    fun findInFlacForCurrentTrack() {
        val track = _uiState.value.currentTrack ?: return
        if (track.isFlac) return
        // A cloud song (Dropbox / OneDrive) is the user's own file: never replaced by an online match.
        if (track.source == com.stash.core.model.MusicSource.CLOUD) return
        // An All Songs file is the user's own file: never replaced by an online match.
        if (track.isScannedSong) return
        launchUserAction {
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
        val state = _uiState.value
        val track = state.currentTrack ?: return
        // An All Songs file isn't in the library: there is no row to delete or block, and its
        // words must never pick a library song of the same name to delete instead.
        if (track.isScannedSong) {
            _userMessages.tryEmit("Songs from your folders can't be deleted or blocked here.")
            return
        }
        // A stream with no library row (transient id): both deletes were no-ops that still
        // toasted "Deleted…". Block persists it first so the block has an identity to keep;
        // a plain delete has nothing to remove, so it says so and leaves playback alone.
        val notInLibrary = state.currentTrackStored == false
        if (!alsoBlock && notInLibrary) {
            _userMessages.tryEmit("This song isn't in your library, so there's nothing to delete.")
            return
        }
        launchUserAction {
            if (alsoBlock) {
                val trackId = persistOrNull(track) ?: run {
                    _userMessages.tryEmit("Couldn't block this song.")
                    return@launchUserAction
                }
                playerRepository.skipNext()
                musicRepository.blacklistTrack(trackId)
                _userMessages.tryEmit(
                    if (notInLibrary) "Blocked from future syncs." else "Deleted and blocked from future syncs.",
                )
            } else {
                playerRepository.skipNext()
                val deleted = musicRepository.deleteTrack(track)
                _userMessages.tryEmit(if (deleted) "Deleted from your device." else "Couldn't delete this song.")
            }
        }
    }

    // ------------------------------------------------------------------
    // v0.9.36 — lyrics view actions
    // ------------------------------------------------------------------

    /**
     * Switch Now Playing to its lyrics view for the current track. Open-only since
     * the live-lyrics bar: fetching is the lyricsViewState chain's
     * subscription-gated trigger's job (plus the lyrics view's Retry). The old
     * unconditional streaming re-fetch here reset shared state to Loading —
     * with the bar consuming that same state, a bar tap would have hidden
     * the bar mid-tap.
     */
    fun onShowLyrics() {
        _lyricsMode.value = true
    }

    /** Back to the big art, without affecting playback. */
    fun onDismissLyrics() {
        _lyricsMode.value = false
    }

    /** The Lyrics action: in from the art, or back out when already reading. */
    fun onToggleLyrics() {
        _lyricsMode.value = !_lyricsMode.value
    }

    /**
     * Retry the lyrics fetch for the currently-playing track. Resolves inline
     * (library track) or re-runs the transient chain (streaming). No-op when
     * nothing is playing or when the track has no valid id (search-preview rows).
     */
    fun onLyricsRetry() {
        val state = _uiState.value
        val track = state.currentTrack ?: return
        when (state.currentTrackStored) {
            true -> fetchLibraryLyrics(track, force = true)
            false -> fetchStreamingLyrics(track, fresh = true)
            null -> Unit // Room hasn't answered for this track yet (a few ms): the trigger will
        }
    }

    /** Track id with a word-synced lookup in flight: the double-tap guard, and the menu row's "Looking…". */
    private val _upgradingLyricsTrackId = MutableStateFlow<Long?>(null)
    val upgradingLyricsTrackId: StateFlow<Long?> = _upgradingLyricsTrackId.asStateFlow()

    /** False under the LRC-only setting: Apple is never asked, so the menu hides the action. */
    val appleLyricsEnabled: StateFlow<Boolean> = lyricsPreference.sourcePreference
        .map { it != LyricsSourcePreference.LRC_ONLY }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), true)

    /**
    * Get Apple's word-synced version of the playing track's stored lyrics: the library-wide
    * upgrade, for this one song. Library tracks only. One lookup at a time: each live timeout
    * counts toward the source's app-wide skip window.
    */
    fun getWordSyncedLyricsForCurrentTrack() {
        val state = _uiState.value
        val track = state.currentTrack ?: return
        if (state.currentTrackStored != true) return
        if (!_upgradingLyricsTrackId.compareAndSet(expect = null, update = track.id)) return
        launchUserAction {
            try {
                _userMessages.tryEmit("Looking for word-synced lyrics\u2026")
                val message = if (lyricsRepository.get(track.id) == null) {
                    // No row: a miss stamps 0, anything else is "not fetched yet".
                    if (lyricsRepository.observeFetchedAt(track.id).first() == 0L) {
                        "No lyrics found for this song"
                    } else {
                        "Lyrics aren't loaded yet, try again from the lyrics view"
                    }
                } else {
                    when (lyricsRepository.upgradeToTtml(
                        track.id,
                        moveCursor = false,
                        interactive = true,
                        sources = TtmlUpgradeSources.APPLE_THEN_ON_DEMAND,
                    )) {
                        TtmlUpgradeResult.UPGRADED -> "Word-synced lyrics added"
                        TtmlUpgradeResult.NO_TTML -> "No word-synced lyrics available for this song"
                        TtmlUpgradeResult.NOT_READY -> "Word-synced lyrics aren't ready yet. Try again in a minute"
                        TtmlUpgradeResult.PAUSED -> "Word-synced lyrics are paused for a few minutes after repeated timeouts"
                        TtmlUpgradeResult.RATE_LIMITED -> "Word-synced lyrics are rate-limiting, try again later"
                        TtmlUpgradeResult.UNCONFIRMED_MISS -> "A word-synced lyrics service didn't respond, and the others have none for this song"
                        TtmlUpgradeResult.FAILED -> "Couldn't reach the word-synced lyrics services"
                        TtmlUpgradeResult.SKIPPED -> "Nothing to upgrade for this song"
                    }
                }
                val suffix = if (_uiState.value.currentTrack?.id != track.id) " for '${track.title}'" else ""
                _userMessages.emit("$message$suffix.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _userMessages.emit("Couldn't get word-synced lyrics.")
            } finally {
                _upgradingLyricsTrackId.compareAndSet(expect = track.id, update = null)
            }
        }
    }

    fun exportLyricsForCurrentTrack() {
        val track = _uiState.value.currentTrack?.takeIf { it.id > 0L && it.isDownloaded } ?: return
        if (!_exportingLyricsTrackId.compareAndSet(expect = null, update = track.id)) return
        viewModelScope.launch {
            try {
                val message = try {
                    val lyrics = lyricsRepository.get(track.id)
                    when {
                        lyrics != null && !(lyrics.syncedLrc.isNullOrBlank() && lyrics.plainText.isNullOrBlank()) -> {
                            // Explicit save always writes .lrc. The writer hops to Dispatchers.IO itself.
                            lyricsSidecarWriter.writeLrcSidecar(track.id, lyrics)
                            "Lyrics saved with the song file"
                        }
                        // 0L = every source definitively answered "no lyrics"; distinct from a fetch
                        // that simply hasn't succeeded yet (stamp stays null on a source failure).
                        track.lyricsFetchedAt == 0L -> "No lyrics found for this track"
                        else -> "Couldn't fetch lyrics yet — try again from the lyrics view"
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { "Couldn't save lyrics" }
                val suffix = if (_uiState.value.currentTrack?.id != track.id) " for '${track.title}'" else ""
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
    private fun fetchStreamingLyrics(track: Track, fresh: Boolean = false) {
        val key = trackKey(track)
        val query = lyricsQuery(track, trackId = 0L)
        // Already looked up (the prefetch, or an earlier visit): show it now, no Loading frame.
        if (!fresh) {
            lyricsRepository.cached(query)?.let { cached ->
                showStreamLyrics(track, lyricsViewStateForResult(cached.result))
                return
            }
        }
        _streamingLyricsState.value = LyricsViewState.Loading
        viewModelScope.launch {
            val result = try {
                lyricsViewStateForResult(lyricsRepository.resolveTransient(query, fresh))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LyricsViewState.Error(retryable = true)
            }
            if (trackKey(_uiState.value.currentTrack) == key) showStreamLyrics(track, result)
        }
    }

    /** Shows a stream's lyrics, and keeps them for the moment it is persisted (see [streamLyricsCarry]). */
    private fun showStreamLyrics(track: Track, state: LyricsViewState) {
        _streamingLyricsState.value = state
        val yt = track.youtubeId
        if (yt != null && state !is LyricsViewState.Error && state != LyricsViewState.Loading) {
            streamLyricsCarry.value = yt to state
        }
    }

    /** Adjust sync for the current track. No-op for streaming tracks (id == 0L) — see [lyricsSyncOffsetMs]. */
    fun setLyricsSyncOffsetMs(offsetMs: Long) {
        val track = _uiState.value.currentTrack ?: return
        if (_uiState.value.currentTrackStored != true) return
        viewModelScope.launch { lyricsRepository.setSyncOffsetMs(track.id, offsetMs) }
    }

    /**
     * Adjust sync for every song on the output playing now ("Apply to all songs on <device>"),
     * held within [com.stash.feature.nowplaying.ui.OUTPUT_OFFSET_CAP_MS]. No-op while the output
     * isn't known — see [lyricsOutputOffset].
     */
    fun setLyricsOutputOffsetMs(offsetMs: Long) {
        val output = lyricsOutputOffset.value?.output ?: return
        viewModelScope.launch { lyricsOutputOffsets.setOffsetMs(output, clampOutputOffsetMs(offsetMs)) }
    }

    /**
     * Tap-to-seek on a synced lyric line. Routed through the same
     * player-controller seek path that the progress bar uses
     * ([onSeekTo]) so the seek is bounded by the current duration.
     */
    fun onLyricsLineSeek(timestampMs: Long) {
        // currentPositionMs (what a tapped line's timestamp is measured against) is the raw player
        // position MINUS the effective offset (the song's plus the output's) — undo that here so
        // seeking lands the DISPLAYED position, not the raw one, on the tapped line. With a
        // non-zero offset, seeking to the raw timestamp alone lights up the wrong line, and on
        // Bluetooth starts the song ahead of or behind the words.
        val offsetMs = effectiveLyricsOffsetMs(lyricsSyncOffsetMs.value, lyricsOutputOffset.value?.offsetMs)
        onSeekTo(lyricsSeekTargetMs(timestampMs, offsetMs))
    }

    /**
     * Library-track on-open lyrics fetch. Resolves INLINE via the repository
     * (writes the Room row + stamps `lyrics_fetched_at`) instead of enqueuing a
     * WorkManager job — the user is watching the lyrics view, so the fetch must not
     * queue behind background workers (sync/backfill/discovery), which deferred
     * the result by minutes. The [lyricsViewState] combine observes the row +
     * stamp and flips the lyrics view the moment this finishes.
     *
     * Deduped per track so reopening doesn't stack duplicate resolves. [force]
     * (Retry) bypasses the in-flight guard and clears the confirmed-miss stamp
     * FIRST so the combine visibly returns to Loading while the retry runs.
     * A source failure sets [_lyricsFetchFailedTrackId] (stamp stays NULL) so
     * the lyrics view renders a retryable Error instead of sticking on Loading.
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
                try {
                    lyricsRepository.resolveAndStore(
                        lyricsQuery(track, trackId = track.id),
                        fresh = force,
                        skipIfStamped = !force,
                    )
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
        if (track.isScannedSong) return // no heart is shown for one; ensureTrackPersisted refuses it too
        val wasLiked = track.stashLikedAt != null

        // Optimistic UI: flip the state in our local map immediately.
        // The combine() block in observePlayerStateLive merges this so
        // the heart icon updates within one frame and stays updated.
        // We use a temporary key for non-library tracks (id=0) using their videoId.
        val trackKey = if (track.id == 0L) track.youtubeId.hashCode().toLong() else track.id
        optimisticLikeState.update { it + (trackKey to !wasLiked) }

        launchUserAction {
            // try/catch, not runCatching: a cancellation must propagate, not read as a failed
            // like that rolls the heart back and toasts "Couldn't add to Liked Songs".
            try {
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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
    // Only a background sweep is paced; a tap never is. Worded for completeness.
    UpgradeResult.Paced -> "Lossless is busy — try again later"
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
    // `"opus"` is BOTH the Room default for a never-downloaded row AND a real
    // codec YouTube serves (itag 251) — so the VALUE alone cannot tell a
    // resolved format from an unset one. Treating it as "not a real format"
    // meant that once the YouTube chain settled on Opus, this whole overlay was
    // skipped for every YouTube track — including [streamOrigin] below, which
    // is why Now Playing showed a bare "OPUS" and never "· via YT".
    //
    // streamOrigin is set only by a stream resolver and never lives in Room, so
    // its presence is positive proof the format came from a real resolve. Fall
    // back to the old value test for resolver-less tracks that still carry a
    // non-default codec.
    val hasRealFormat = streamFormat.streamOrigin != null ||
        (streamFormat.fileFormat.isNotBlank() && streamFormat.fileFormat != "opus")
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

/**
 * How long "no lyrics" must last before Now Playing believes it, when the fetch stamp is set
 * but the lyrics row hasn't been read back yet (two writes, two Room queries: they can land a
 * few ms apart). A real miss stamps 0 with no row and shows at once.
 */
private const val ROW_LAG_GRACE_MS = 400L

/**
 * How long a song plays before its lyrics are prefetched. Long enough that skipping through a
 * queue looks nothing up, short enough that lyrics are in hand well before most people open them.
 */
private const val PREFETCH_DWELL_MS = 1_800L

/** A failed back cover lookup is tried again this many times while its album plays. */
private const val BACK_COVER_RETRIES = 3

/**
 * The last resort for Now Playing's download ring: a download whose queue row never settles
 * (a job that hangs) stops spinning after this. Every outcome the row records ends it sooner.
 */
private const val DOWNLOAD_WATCHDOG_MS = 180_000L
