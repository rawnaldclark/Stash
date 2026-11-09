package com.stash.core.media.preview

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.stash.core.common.perf.PerfLog
import com.stash.core.media.equalizer.EqController
import com.stash.core.media.equalizer.LoudnessController
import com.stash.core.media.equalizer.StashRenderersFactory
import com.stash.core.media.output.HiResOutputController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

// ---------------------------------------------------------------------------
// State model
// ---------------------------------------------------------------------------

/**
 * Represents the playback state of [PreviewPlayer].
 *
 * [Idle] is the initial state and is re-entered whenever playback ends,
 * is stopped, or an error occurs.
 *
 * [Playing] carries the [videoId] of the track currently being previewed so
 * that UI layers can highlight the correct row without needing a separate
 * "current item" field.
 */
sealed interface PreviewState {
    data object Idle : PreviewState
    data class Playing(val videoId: String) : PreviewState
}

/**
 * Error event surfaced by [PreviewPlayer] when ExoPlayer's [Player.Listener]
 * reports an [onPlayerError]. Emitted on [PreviewPlayer.playerErrors].
 *
 * [attemptId] identifies the exact [playUrl] request that failed. Consumers
 * that retry must compare it with the token returned by [PreviewPlayer.playUrl]
 * so a buffered error from an older URL cannot stop a newer attempt.
 */
data class PreviewErrorEvent(
    val videoId: String,
    val attemptId: Long,
    val error: PlaybackException,
)

// ---------------------------------------------------------------------------
// Player
// ---------------------------------------------------------------------------

/**
 * Lightweight, singleton ExoPlayer wrapper for in-app audio preview.
 *
 * ### Responsibilities
 * - Accepts a stream URL and a logical [videoId] and plays the audio.
 * - Exposes [previewState] so the UI can react to playback transitions.
 * - Configures [AudioAttributes] with [C.USAGE_MEDIA] and `handleAudioFocus = true`
 *   so that Android's audio focus system pauses the main [StashPlaybackService]
 *   player whenever a preview starts — and resumes it when the preview stops.
 *
 * ### Non-responsibilities
 * - Knows nothing about yt-dlp or URL resolution.
 * - Has no [MediaSession] or system notification.
 * - Does not manage playback queues.
 *
 * ### Lifecycle
 * The underlying [ExoPlayer] is created lazily on the first [playUrl] call to
 * avoid allocating native resources until they are needed.  Call [release] when
 * the owning component is permanently destroyed (e.g. process exit or DI graph
 * teardown) to free those resources.
 */
@Singleton
class PreviewPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eqController: EqController,
    private val loudnessController: LoudnessController,
    private val hiResOutputController: HiResOutputController,
    private val tuningController: com.stash.core.media.tuning.TuningController,
) {

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private val _previewState = MutableStateFlow<PreviewState>(PreviewState.Idle)

    /** Observable playback state.  Collected by the Search UI. */
    val previewState: StateFlow<PreviewState> = _previewState

    /**
     * One-shot playback errors. Uses `extraBufferCapacity = 4` (replay = 0)
     * so a burst of errors from the listener thread won't be dropped before
     * the VM's collector drains them on the Main dispatcher. Late subscribers
     * do NOT see pre-subscription emissions — replaying a stale error onto a
     * new VM would be worse than losing it.
     */
    private val _playerErrors = MutableSharedFlow<PreviewErrorEvent>(extraBufferCapacity = 4)
    val playerErrors: SharedFlow<PreviewErrorEvent> = _playerErrors.asSharedFlow()

    // ------------------------------------------------------------------
    // ExoPlayer — lazily created, explicitly released
    // ------------------------------------------------------------------

    /**
     * Null until the first [playUrl] call, null again after [release].
     * All internal helpers check [requirePlayer] or guard against null.
     */
    private var exoPlayer: ExoPlayer? = null
    private var attemptErrorListener: Player.Listener? = null

    /**
     * The current [playUrl] attempt's one-shot "audible" latency probe, held so
     * [clearAttemptErrorListener] can drop it. It only removes itself once the
     * preview plays: an attempt that errored or was stopped first left it (and the
     * player, t0 and videoId it captures) on the singleton player, and they piled
     * up until a later preview played — then every one fired with a stale latency.
     */
    private var audibleListener: Player.Listener? = null

    /**
     * The [videoId] passed to the most recent [playUrl] call.
     * Held here so the [Player.Listener] can reference it when emitting
     * [PreviewState.Playing] without capturing a local variable that may
     * have been replaced by a subsequent [playUrl] call.
     */
    private var currentVideoId: String = ""
    private var requestSequence = 0L
    private var currentRequestId = 0L
    private var attemptSequence = 0L
    private var currentAttemptId = 0L

    // ------------------------------------------------------------------
    // Listener
    // ------------------------------------------------------------------

    /**
     * Single listener instance reused across player lifetime.
     *
     * State transitions:
     * - [Player.STATE_READY] + [Player.isPlaying] → [PreviewState.Playing]
     * - [Player.STATE_ENDED]                       → [PreviewState.Idle]
     * - [onPlayerError]                            → [PreviewState.Idle]
     *
     * Note: [onPlaybackStateChanged] fires on every state change including
     * BUFFERING and IDLE; only the terminal/playback states are acted on.
     */
    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    // Emit Playing only when the player is actually producing audio.
                    // STATE_READY can briefly fire before playWhenReady causes playback to start;
                    // check isPlaying to avoid a spurious Playing emission during buffering.
                    if (exoPlayer?.isPlaying == true) {
                        _previewState.value = PreviewState.Playing(currentVideoId)
                    }
                }
                Player.STATE_ENDED -> {
                    // Track played to completion — return to Idle naturally.
                    _previewState.value = PreviewState.Idle
                }
                // STATE_BUFFERING and STATE_IDLE do not change the published state;
                // the UI keeps showing whatever it showed before (Idle or Playing).
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Supplement onPlaybackStateChanged: if the player transitions from
            // Playing → paused (e.g. audio-focus loss), reflect that as Idle so
            // the UI does not show a "playing" indicator for a paused preview.
            if (!isPlaying && _previewState.value is PreviewState.Playing) {
                _previewState.value = PreviewState.Idle
            } else if (isPlaying) {
                // Also catches the case where STATE_READY fired before isPlaying became true.
                _previewState.value = PreviewState.Playing(currentVideoId)
            }
        }

    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    /**
     * Returns the existing [ExoPlayer] or builds and configures a new one.
     *
     * Audio attributes mirror those used by [StashPlaybackService]:
     * - [C.AUDIO_CONTENT_TYPE_MUSIC] + [C.USAGE_MEDIA] classify this stream
     *   as music for routing and ducking purposes.
     * - `handleAudioFocus = true` causes ExoPlayer to request audio focus on
     *   [playUrl] and release it on [stop]/[release], which in turn triggers
     *   the main player to pause while a preview is active.
     */
    @OptIn(UnstableApi::class)
    private fun requirePlayer(): ExoPlayer {
        return exoPlayer ?: ExoPlayer.Builder(context)
            // The user's EQ, but never the hi-res output: a preview mustn't retune the DAC under
            // the paused song or publish its own signal path (see OutputSession.mainPlayback).
            .setRenderersFactory(
                StashRenderersFactory(
                    context, eqController, loudnessController, hiResOutputController, tuningController,
                    mainPlayback = false,
                ),
            )
            .setLoadControl(PreviewLoadControlFactory.create())
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()
            .also { player ->
                player.addListener(playerListener)
                exoPlayer = player
            }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Stops any current preview, then begins playback of [streamUrl].
     *
     * The [videoId] is a logical identifier (e.g. a YouTube video ID) used
     * only for state reporting — it is never passed to ExoPlayer directly.
     *
     * @param videoId   Logical identifier of the track being previewed.
     * @param streamUrl Direct audio stream URL that ExoPlayer can open.
     * @param onAttemptStarted Called with the attempt token before ExoPlayer
     *                         receives or prepares the media item.
     * @return An opaque token identifying this exact playback attempt.
     */
    @Synchronized
    fun playUrl(
        videoId: String,
        streamUrl: String,
        onAttemptStarted: (Long) -> Unit = {},
    ): Long = checkNotNull(
        playUrlIfClaimed(
            requestId = claimRequest(),
            videoId = videoId,
            streamUrl = streamUrl,
            onAttemptStarted = onAttemptStarted,
        ),
    )

    /**
     * Claims global ownership before a caller begins asynchronous URL/source
     * resolution. A later claim from another screen supersedes this token.
     */
    @Synchronized
    fun claimRequest(): Long {
        requestSequence += 1L
        currentRequestId = requestSequence
        return currentRequestId
    }

    /** Invalidates [requestId] only if no newer screen has claimed playback. */
    @Synchronized
    fun cancelRequest(requestId: Long?) {
        if (requestId != null && requestId == currentRequestId) {
            currentRequestId = 0L
        }
    }

    /** Returns whether [requestId] is still the latest global preview claim. */
    @Synchronized
    fun isRequestCurrent(requestId: Long?): Boolean =
        requestId != null && requestId == currentRequestId

    /**
     * Starts URL playback only while [requestId] remains the latest global
     * request. Returns `null` when another screen won ownership while the
     * caller was suspended.
     */
    @Synchronized
    fun playUrlIfClaimed(
        requestId: Long,
        videoId: String,
        streamUrl: String,
        onAttemptStarted: (Long) -> Unit = {},
    ): Long? {
        if (requestId != currentRequestId) return null
        return playUrlInternal(videoId, streamUrl, onAttemptStarted)
    }

    private fun playUrlInternal(
        videoId: String,
        streamUrl: String,
        onAttemptStarted: (Long) -> Unit,
    ): Long {
        // Capture BEFORE any work so the bookend measures the full
        // tap→audible latency (spec §4.1 target: p50 <500ms / p95 <3s).
        val t0 = SystemClock.elapsedRealtime()
        val player = requirePlayer()

        // Stop any previous playback and clear the queue before loading the
        // new item.  This ensures the listener does not fire stale STATE_ENDED
        // events for the previous track after we replace it.
        clearAttemptErrorListener(player)
        player.stop()
        player.clearMediaItems()

        val attemptId = beginAttempt(videoId)
        installAttemptErrorListener(player, videoId, attemptId)
        onAttemptStarted(attemptId)

        player.setMediaItem(MediaItem.fromUri(streamUrl))
        player.prepare()
        player.playWhenReady = true

        // One-shot listener: fires on the first isPlaying=true and then
        // removes itself. Distinct from [playerListener] (the long-lived
        // class-level listener) so subsequent playUrl calls install a
        // fresh instance rather than reusing a latched one. removeListener
        // MUST be called inside the branch so the instance doesn't leak
        // across playUrl calls.
        val audible = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    PerfLog.d {
                        "Preview audible at ${SystemClock.elapsedRealtime() - t0}ms (vid=$videoId)"
                    }
                    player.removeListener(this)
                    if (audibleListener === this) audibleListener = null
                }
            }
        }
        audibleListener = audible
        player.addListener(audible)
        return attemptId
    }

    /**
     * v0.9.12: MediaSource-based playback for the search-tab path.
     *
     * [SearchPreviewMediaSource] builds a [CacheDataSource]-wrapped source that
     * streams from Qobuz CDN (or yt-dlp fallback) AND caches bytes so that a
     * subsequent download finalise step can read from cache without re-fetching.
     *
     * This entry point is preferred when the caller has a [TrackItem] in scope;
     * [playUrl] remains for the URL-only retry path in [TrackActionsDelegate.onPreviewError].
     *
     * Idempotency: if [videoId] is already playing this call is a no-op (the
     * listener-based guard in [requirePlayer] ensures [currentVideoId] is always
     * up to date). The caller ([TrackActionsDelegate.previewTrack]) performs the
     * same idempotency check before reaching here, so the guard below is a
     * safety net for any future direct callers.
     *
     * @param videoId     Logical identifier of the track, used only for state
     *                    reporting and idempotency; never passed to ExoPlayer directly.
     * @param mediaSource Pre-built [MediaSource] from [SearchPreviewMediaSource.create].
     */
    @OptIn(UnstableApi::class)
    @Synchronized
    fun play(
        videoId: String,
        mediaSource: MediaSource,
        onAttemptStarted: (Long) -> Unit = {},
    ): Long = checkNotNull(
        playIfClaimed(
            requestId = claimRequest(),
            videoId = videoId,
            mediaSource = mediaSource,
            onAttemptStarted = onAttemptStarted,
        ),
    )

    /** MediaSource counterpart to [playUrlIfClaimed]. */
    @OptIn(UnstableApi::class)
    @Synchronized
    fun playIfClaimed(
        requestId: Long,
        videoId: String,
        mediaSource: MediaSource,
        onAttemptStarted: (Long) -> Unit = {},
    ): Long? {
        if (requestId != currentRequestId) return null
        return playInternal(videoId, mediaSource, onAttemptStarted)
    }

    @OptIn(UnstableApi::class)
    private fun playInternal(
        videoId: String,
        mediaSource: MediaSource,
        onAttemptStarted: (Long) -> Unit,
    ): Long {
        val player = requirePlayer()
        // Idempotency: skip if already playing this exact videoId.
        if (currentVideoId == videoId && player.isPlaying) {
            onAttemptStarted(currentAttemptId)
            return currentAttemptId
        }

        // Stop previous playback and clear queue so stale STATE_ENDED events
        // from the prior track cannot fire after we replace the source.
        clearAttemptErrorListener(player)
        player.stop()
        player.clearMediaItems()

        val attemptId = beginAttempt(videoId)
        installAttemptErrorListener(player, videoId, attemptId)
        onAttemptStarted(attemptId)

        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true

        PerfLog.d { "PreviewPlayer.play($videoId) via MediaSource" }
        return attemptId
    }

    private fun beginAttempt(videoId: String): Long {
        attemptSequence += 1L
        currentAttemptId = attemptSequence
        currentVideoId = videoId
        return currentAttemptId
    }

    private fun installAttemptErrorListener(
        player: ExoPlayer,
        videoId: String,
        attemptId: Long,
    ) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                _previewState.value = PreviewState.Idle
                _playerErrors.tryEmit(PreviewErrorEvent(videoId, attemptId, error))
            }
        }
        attemptErrorListener = listener
        player.addListener(listener)
    }

    /** Drops the current attempt's listeners: its error listener and any unfired audible probe. */
    private fun clearAttemptErrorListener(player: ExoPlayer) {
        attemptErrorListener?.let(player::removeListener)
        attemptErrorListener = null
        audibleListener?.let(player::removeListener)
        audibleListener = null
    }

    /**
     * Stops playback immediately and resets state to [PreviewState.Idle].
     *
     * Audio focus is released as a side-effect of stopping the player, which
     * allows the main player (if it had been paused by focus loss) to resume.
     *
     * Safe to call when no playback is active.
     */
    @Synchronized
    fun stop() {
        exoPlayer?.let { player ->
            clearAttemptErrorListener(player)
            player.stop()
            player.clearMediaItems()
        }
        // Clear so a late onPlayerError (fired after stop tears down the
        // source) can't be attributed to the stopped videoId.
        currentVideoId = ""
        currentRequestId = 0L
        currentAttemptId = 0L
        _previewState.value = PreviewState.Idle
    }

    /**
     * Stops playback only when [attemptId] still owns the current source.
     *
     * ViewModels call this from teardown/failure paths so a stale screen cannot
     * stop a newer screen's preview after the singleton player was preempted.
     */
    @Synchronized
    fun stopIfCurrent(attemptId: Long?) {
        if (attemptId != null && attemptId == currentAttemptId) stop()
    }

    /**
     * Releases all ExoPlayer resources and resets state to [PreviewState.Idle].
     *
     * After this call the player is considered destroyed.  A subsequent call
     * to [playUrl] will transparently create a new ExoPlayer instance.
     *
     * Should be called from the DI component's teardown (e.g. [Application.onTerminate]
     * or a [ViewModel.onCleared] that owns this singleton's scope).
     */
    @Synchronized
    fun release() {
        exoPlayer?.let(::clearAttemptErrorListener)
        exoPlayer?.removeListener(playerListener)
        exoPlayer?.release()
        exoPlayer = null
        currentVideoId = ""
        currentRequestId = 0L
        currentAttemptId = 0L
        _previewState.value = PreviewState.Idle
    }
}
