package com.stash.core.media.widget

import androidx.media3.common.Player
import kotlinx.serialization.Serializable

/**
 * What the home screen widget shows: the session's current song and whether it is meant to play.
 *
 * Written by [StashPlaybackService][com.stash.core.media.service.StashPlaybackService] through a
 * [NowPlayingWidgetSink] (pushed on player events, never polled) and persisted by the app, so a
 * widget redrawn in a fresh process (launcher restart, process death) still shows the last song.
 * There is no position: a widget that ticks costs battery.
 */
@Serializable
data class NowPlayingWidgetState(
    /** The song's title, never blank. */
    val title: String,
    /** The artist line; empty when the song has none. */
    val artist: String,
    /** The cover the session shows (http(s), file or content URI, or a bare path); null for none. */
    val artUrl: String?,
    /**
     * True while the player intends to play (Media3's playWhenReady outside IDLE/ENDED), so the
     * button shows Pause through a buffering stall, as the in-app toggle does. Only trusted while
     * the service runs: see [WidgetControls.of].
     */
    val isPlaying: Boolean,
)

/**
 * The few fields of a Media3 [Player] the widget reads, as plain values so the mapping is tested on
 * the JVM. Built from the session's player by [widgetSnapshot].
 */
data class WidgetPlayerSnapshot(
    /** Whether the player has a current item at all (an empty timeline has none). */
    val hasCurrentItem: Boolean,
    val title: CharSequence?,
    val artist: CharSequence?,
    val artworkUri: String?,
    val playWhenReady: Boolean,
    /** One of Player.STATE_IDLE, STATE_BUFFERING, STATE_READY, STATE_ENDED. */
    val playbackState: Int,
)

/** Playback state → widget state. Pure; see [NowPlayingWidgetMapperTest]. */
object NowPlayingWidgetMapper {

    /**
     * The widget state for [snapshot], or null when the player has nothing to show (an empty
     * timeline, or an item without a title).
     *
     * Null means "leave the widget as it is", never "nothing playing": a service that starts with
     * an empty player (the app binding it after an idle stop, Listen Together handing back an
     * emptied player) must not wipe the last song, because Play on the widget resumes exactly that
     * song through playback resumption.
     */
    fun map(snapshot: WidgetPlayerSnapshot): NowPlayingWidgetState? {
        if (!snapshot.hasCurrentItem) return null
        val title = snapshot.title?.toString()?.trim().orEmpty()
        if (title.isEmpty()) return null
        return NowPlayingWidgetState(
            title = title,
            artist = snapshot.artist?.toString()?.trim().orEmpty(),
            artUrl = snapshot.artworkUri?.trim()?.takeIf { it.isNotEmpty() },
            isPlaying = snapshot.playWhenReady &&
                snapshot.playbackState != Player.STATE_IDLE &&
                snapshot.playbackState != Player.STATE_ENDED,
        )
    }
}

/**
 * Reads [WidgetPlayerSnapshot] off a player. [Player.getMediaMetadata] is the combined metadata
 * (the item's own, plus what a stream's tracks add), so a cover that lands after the item does
 * (a refreshed stream) is picked up on its EVENT_MEDIA_METADATA_CHANGED.
 */
fun Player.widgetSnapshot(): WidgetPlayerSnapshot {
    val item = currentMediaItem
    val metadata = mediaMetadata
    return WidgetPlayerSnapshot(
        hasCurrentItem = item != null,
        title = metadata.title ?: metadata.displayTitle ?: item?.mediaMetadata?.title,
        artist = metadata.artist ?: metadata.albumArtist ?: item?.mediaMetadata?.artist,
        artworkUri = (metadata.artworkUri ?: item?.mediaMetadata?.artworkUri)?.toString(),
        playWhenReady = playWhenReady,
        playbackState = playbackState,
    )
}
