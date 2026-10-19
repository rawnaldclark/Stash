package com.stash.core.media.recap

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.stash.core.media.equalizer.EqController
import com.stash.core.media.equalizer.LoudnessController
import com.stash.core.media.equalizer.StashRenderersFactory
import com.stash.core.media.output.HiResOutputController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * The player under [RecapAudio]: plays one song at a time, at a volume [RecapAudio] fades.
 * An interface so the story state machine is unit-tested without ExoPlayer.
 *
 * Every method is called on the main thread.
 */
interface RecapPreviewEngine {

    /** What the engine tells [RecapAudio]. Called on the main thread. */
    interface Listener {
        /**
         * Something outside the story stopped it: headphones came out (becoming noisy) or another
         * app took audio focus for good. The story stays quiet from here on.
         */
        fun onInterrupted()

        /** The current song couldn't be played (stream failed, file unreadable). Its card stays quiet. */
        fun onError(message: String)
    }

    fun setListener(listener: Listener?)

    /** Loads [source] at [startPositionMs], paused and at volume 0. Replaces whatever was loaded. */
    fun load(source: RecapSource, startPositionMs: Long)

    fun play()

    fun pause()

    /** Unloads the song (keeps the player for the next card). */
    fun stop()

    /** Frees the player entirely; a later [load] builds a new one. */
    fun release()

    /** 0..1, the preview's own gain (not the phone's volume, which the user keeps). */
    fun setVolume(volume: Float)
}

/**
 * [RecapPreviewEngine] on its own ExoPlayer — NOT the MediaSession player. Being a separate
 * player is what keeps story audio out of every listener of the main session: listening history
 * (ListeningRecorder), play counts, Last.fm / ListenBrainz / YouTube Music history, autoplay radio,
 * Recently played, the notification, Discord and Link-on-the-web handoff all observe the session
 * player through PlayerRepository / MediaController, and never see this one.
 *
 * It resolves nothing: [RecapAudio] hands it a local file or an already-resolved URL
 * ([RecapStreamLookup]), read over plain HTTP (a DASH source for a cached Tidal manifest, a
 * no-redirect OkHttp client for a cached JioSaavn URL). So a preview can never start the main
 * player's source chain, its relays or its retries.
 *
 * Built like the search [com.stash.core.media.preview.PreviewPlayer]: the user's EQ, never the
 * hi-res output (`mainPlayback = false`, so a preview can't retune the DAC under the paused song),
 * `handleAudioFocus = true` (a call or another app pauses it) and becoming-noisy handling
 * (headphones out → silence, never the speaker).
 */
@OptIn(UnstableApi::class)
class ExoRecapPreviewEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eqController: EqController,
    private val loudnessController: LoudnessController,
    private val hiResOutputController: HiResOutputController,
    private val okHttpClient: okhttp3.OkHttpClient,
) : RecapPreviewEngine {

    private var player: ExoPlayer? = null
    private var listener: RecapPreviewEngine.Listener? = null

    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) return
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY ||
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
            ) {
                listener?.onInterrupted()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            listener?.onError("${error.errorCodeName}: ${error.message}")
        }
    }

    /** JioSaavn's rule (see StashMediaSourceFactory): never follow a redirect off the validated host. */
    private val noRedirectClient by lazy {
        okHttpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

    override fun setListener(listener: RecapPreviewEngine.Listener?) {
        this.listener = listener
    }

    override fun load(source: RecapSource, startPositionMs: Long) {
        val p = requirePlayer()
        p.stop()
        // Silent until RecapAudio fades it in. Set here, on the player that exists now, so even
        // the first card (whose player was only just built) can't blip at full gain.
        p.volume = 0f
        p.setMediaSource(mediaSourceFor(source), startPositionMs)
        p.playWhenReady = false
        p.prepare()
    }

    override fun play() {
        player?.play()
    }

    override fun pause() {
        player?.pause()
    }

    override fun stop() {
        player?.let {
            it.stop()
            it.clearMediaItems()
        }
    }

    override fun release() {
        player?.let {
            it.removeListener(playerListener)
            it.release()
        }
        player = null
    }

    override fun setVolume(volume: Float) {
        player?.volume = volume.coerceIn(0f, 1f)
    }

    private fun mediaSourceFor(source: RecapSource): MediaSource {
        // Fail fast, like the search preview: one retry, not ExoPlayer's 3+ with backoff.
        val errorPolicy = DefaultLoadErrorHandlingPolicy(/* minimumLoadableRetryCount = */ 1)
        return when (source) {
            is RecapSource.Local -> ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
                .setLoadErrorHandlingPolicy(errorPolicy)
                // Same rule as PlayerRepositoryImpl.toMediaItem: a bare path needs file://.
                .createMediaSource(
                    MediaItem.fromUri(
                        if (source.path.startsWith("/")) "file://${source.path}".toUri() else source.path.toUri(),
                    ),
                )
            is RecapSource.Url -> {
                val http: DataSource.Factory = if (source.noRedirects) {
                    OkHttpDataSource.Factory(noRedirectClient)
                } else {
                    DefaultHttpDataSource.Factory()
                        .setUserAgent(USER_AGENT)
                        .setConnectTimeoutMs(10_000)
                        .setReadTimeoutMs(15_000)
                }
                if (source.isDash) {
                    DashMediaSource.Factory(http)
                        .setLoadErrorHandlingPolicy(errorPolicy)
                        .createMediaSource(
                            MediaItem.Builder().setUri(source.url).setMimeType(MimeTypes.APPLICATION_MPD).build(),
                        )
                } else {
                    ProgressiveMediaSource.Factory(http)
                        .setLoadErrorHandlingPolicy(errorPolicy)
                        .createMediaSource(MediaItem.fromUri(source.url))
                }
            }
        }
    }

    private fun requirePlayer(): ExoPlayer = player ?: run {
        check(Looper.myLooper() == Looper.getMainLooper()) { "RecapPreviewEngine is main-thread only" }
        ExoPlayer.Builder(context)
            .setLooper(Looper.getMainLooper())
            .setRenderersFactory(
                StashRenderersFactory(
                    context, eqController, loudnessController, hiResOutputController, mainPlayback = false,
                ),
            )
            .setLoadControl(storyLoadControl())
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also {
                it.addListener(playerListener)
                player = it
            }
    }

    private companion object {
        /** Same hard-coded agent as StreamingMediaSourceFactory (core/media has no BuildConfig). */
        const val USER_AGENT = "Stash/0.9.26"

        /**
         * Audible fast (READY at 250 ms of audio), and a story card is ~6 s long, so buffer
         * at most 15 s ahead instead of the default 50 s: no downloading a third of a FLAC
         * nobody will hear.
         */
        fun storyLoadControl() = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 5_000,
                /* maxBufferMs = */ 15_000,
                /* bufferForPlaybackMs = */ 250,
                /* bufferForPlaybackAfterRebufferMs = */ 500,
            )
            .build()
    }
}
