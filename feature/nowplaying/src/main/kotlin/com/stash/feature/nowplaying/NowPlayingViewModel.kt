package com.stash.feature.nowplaying

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.palette.graphics.Palette
import com.stash.core.data.lossless.LosslessUpgrader
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.UpgradeResult
import com.stash.core.model.isFlac
import com.stash.core.ui.components.PlaylistInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

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
    private val stashLikedRepository: com.stash.core.data.social.stash.StashLikedPlaylistRepository,
    private val losslessUpgrader: LosslessUpgrader,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NowPlayingUiState())
    val uiState: StateFlow<NowPlayingUiState> = _uiState.asStateFlow()

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

    init {
        observePlayerStateLive()
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
            .map { it.currentTrack?.id }
            .distinctUntilChanged()
            .flatMapLatest { id ->
                if (id == null) flowOf<com.stash.core.model.Track?>(null)
                else musicRepository.observeTrackById(id)
            }

        combine(
            playerRepository.playerState,
            playerRepository.currentPosition,
            liveTrackFlow,
            optimisticLikeState,
        ) { state, positionMs, liveTrack, optimistic ->
            _uiState.update { current ->
                val track = liveTrack ?: state.currentTrack
                val trackKey = if (track?.id == 0L) track.youtubeId.hashCode().toLong() else track?.id
                val finalTrack = if (track != null && trackKey != null && optimistic.containsKey(trackKey)) {
                    val optLiked = optimistic[trackKey]!!
                    track.copy(stashLikedAt = if (optLiked) System.currentTimeMillis() else null)
                } else {
                    track
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
                var finalTrackId = track.id
                if (finalTrackId == 0L) {
                    // Not in library yet (search preview). Insert it first.
                    // This creates a stub TrackEntity in the DB so the
                    // Liked Songs cross-ref has a parent to point to.
                    finalTrackId = musicRepository.insertTrack(track)
                }

                if (wasLiked) {
                    stashLikedRepository.remove(finalTrackId)
                } else {
                    stashLikedRepository.add(finalTrackId)
                }
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

    /**
     * Called when the album art [Bitmap] has been loaded (e.g. via Coil).
     *
     * Extracts dominant, vibrant, and muted colors on [Dispatchers.Default]
     * so the main thread is never blocked by the Palette computation.
     *
     * Passing `null` resets colors to their defaults.
     */
    fun onAlbumArtLoaded(bitmap: Bitmap?) {
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

