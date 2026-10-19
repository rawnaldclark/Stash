package com.stash.core.media.recap

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.media.equalizer.EqController
import com.stash.core.media.equalizer.LoudnessController
import com.stash.core.media.equalizer.StashRenderersFactory
import com.stash.core.media.output.HiResOutputController
import com.stash.core.media.streaming.StashMediaSourceFactory
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.media.streaming.StreamingMediaSourceFactory
import com.stash.core.media.streaming.TidalStreamResolver
import com.stash.core.media.streaming.stashResolveUri
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

        /** The current song couldn't be played (resolve failed, file unreadable). Its card stays quiet. */
        fun onError(message: String)
    }

    fun setListener(listener: Listener?)

    /** Loads [source] at [startPositionMs], paused. Replaces whatever was loaded. */
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
 * Streams go through the main player's own routing: a [StashMediaSourceFactory] built from the
 * same singletons the playback service uses, fed `stash-resolve://track/<id>` placeholders, so a
 * story song resolves exactly like a tapped song (URL cache first, then the source chain, Tidal
 * DASH when a server is set) — and a URL it resolves warms the cache for a later real play.
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
    private val streamingMediaSourceFactory: StreamingMediaSourceFactory,
    private val okHttpClient: okhttp3.OkHttpClient,
    private val streamResolver: StreamSourceRegistry,
    private val streamUrlCache: StreamUrlCache,
    private val trackDao: TrackDao,
    private val tidalStreamResolver: TidalStreamResolver,
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

    override fun setListener(listener: RecapPreviewEngine.Listener?) {
        this.listener = listener
    }

    override fun load(source: RecapSource, startPositionMs: Long) {
        val p = requirePlayer()
        p.stop()
        p.setMediaItem(source.toMediaItem(), startPositionMs)
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

    private fun requirePlayer(): ExoPlayer = player ?: run {
        check(Looper.myLooper() == Looper.getMainLooper()) { "RecapPreviewEngine is main-thread only" }
        val sourceFactory = StashMediaSourceFactory(
            context = context,
            streamingFactory = streamingMediaSourceFactory,
            // Only stash-resolve placeholders and local files reach this player: the
            // resolved-URL predicates the service needs for its prefetched items never apply.
            streamingTrackId = { null },
            isJioSaavnOrigin = { false },
            httpClient = okHttpClient,
            resolver = streamResolver,
            urlCache = streamUrlCache,
            trackDao = trackDao,
            resolveBeforeSourcePick = tidalStreamResolver::enabledHint,
        )
        ExoPlayer.Builder(context)
            .setLooper(Looper.getMainLooper())
            .setRenderersFactory(
                StashRenderersFactory(
                    context, eqController, loudnessController, hiResOutputController, mainPlayback = false,
                ),
            )
            .setMediaSourceFactory(sourceFactory)
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

        fun RecapSource.toMediaItem(): MediaItem = when (this) {
            is RecapSource.Local -> MediaItem.Builder()
                .setMediaId("recap:local")
                // Same rule as PlayerRepositoryImpl.toMediaItem: a bare path needs file://.
                .setUri(if (path.startsWith("/")) "file://$path".toUri() else path.toUri())
                .build()
            is RecapSource.Stream -> MediaItem.Builder()
                .setMediaId("recap:$trackId")
                .setUri(
                    stashResolveUri(
                        trackId = trackId,
                        youtubeId = youtubeId,
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = durationMs,
                        isrc = isrc,
                    ),
                )
                .build()
        }
    }
}
