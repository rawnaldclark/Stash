package com.stash.core.media.preview

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
) {

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private val _previewState = MutableStateFlow<PreviewState>(PreviewState.Idle)

    /** Observable playback state.  Collected by the Search UI. */
    val previewState: StateFlow<PreviewState> = _previewState

    // ------------------------------------------------------------------
    // ExoPlayer — lazily created, explicitly released
    // ------------------------------------------------------------------

    /**
     * Null until the first [playUrl] call, null again after [release].
     * All internal helpers check [requirePlayer] or guard against null.
     */
    private var exoPlayer: ExoPlayer? = null

    /**
     * The [videoId] passed to the most recent [playUrl] call.
     * Held here so the [Player.Listener] can reference it when emitting
     * [PreviewState.Playing] without capturing a local variable that may
     * have been replaced by a subsequent [playUrl] call.
     */
    private var currentVideoId: String = ""

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

        override fun onPlayerError(error: PlaybackException) {
            // Any playback error resets state so the UI does not remain stuck in Playing.
            _previewState.value = PreviewState.Idle
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
    private fun requirePlayer(): ExoPlayer {
        return exoPlayer ?: ExoPlayer.Builder(context)
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
     */
    fun playUrl(videoId: String, streamUrl: String) {
        val player = requirePlayer()

        // Stop any previous playback and clear the queue before loading the
        // new item.  This ensures the listener does not fire stale STATE_ENDED
        // events for the previous track after we replace it.
        player.stop()
        player.clearMediaItems()

        currentVideoId = videoId

        player.setMediaItem(MediaItem.fromUri(streamUrl))
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * Stops playback immediately and resets state to [PreviewState.Idle].
     *
     * Audio focus is released as a side-effect of stopping the player, which
     * allows the main player (if it had been paused by focus loss) to resume.
     *
     * Safe to call when no playback is active.
     */
    fun stop() {
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        _previewState.value = PreviewState.Idle
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
    fun release() {
        exoPlayer?.removeListener(playerListener)
        exoPlayer?.release()
        exoPlayer = null
        _previewState.value = PreviewState.Idle
    }
}
