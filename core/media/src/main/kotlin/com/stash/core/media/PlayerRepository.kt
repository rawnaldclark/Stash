package com.stash.core.media

import androidx.media3.common.MediaItem
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import com.stash.core.model.TrackItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Outcome of a streaming-routing decision when the player is asked to play
 * a track. Surfaced as a return value rather than emitting through an
 * internal SharedFlow so the caller (a ViewModel / screen) can decide how
 * to render each reason — typically a snackbar for the refusal variants
 * and silent no-op for [NotAvailable] rows (which should already be
 * greyed out in the UI).
 *
 * Defined here rather than in `core/model` because the [Item] variant
 * carries a Media3 [MediaItem], a `core/media`-private type.
 */
sealed class StreamRoutingResult {
    /** The track is playable — hand [mediaItem] to the controller. */
    data class Item(val mediaItem: MediaItem) : StreamRoutingResult()

    /**
     * No local file on disk, not marked streamable in the library, OR
     * the resolver couldn't find a stream URL. Library row should be
     * rendered greyed-out; tap is silently ignored.
     */
    data object NotAvailable : StreamRoutingResult()

    /**
     * Streaming is disabled in preferences and the track has no local
     * file. Caller surfaces "Turn on Online mode to stream this track".
     */
    data object OfflineMode : StreamRoutingResult()

    /**
     * Streaming is enabled but the device is on cellular and the user
     * has not opted-in to streaming on metered networks. Caller surfaces
     * "Streaming on cellular is off in Settings".
     */
    data object CellularRefused : StreamRoutingResult()

    /**
     * Streaming is enabled but the device has no validated internet
     * connection (airplane mode, captive portal not yet accepted, etc.).
     * Caller surfaces "You're offline — can't stream this track".
     */
    data object NoConnectivity : StreamRoutingResult()

    /**
     * Rapid duplicate tap while a prior resolve for the same videoId is
     * still in flight. Caller should NOT emit any user-visible feedback:
     * the original tap is being handled and will produce its own outcome
     * (Item/NotAvailable/etc.) — duplicating the snackbar would just
     * confuse the user with a "Couldn't find this track" message while
     * the track is actually about to start playing.
     */
    data object Deduped : StreamRoutingResult()
}

/**
 * Abstraction over the playback engine. Consumers observe [playerState] for UI updates
 * and call the suspend functions to control playback.
 */
interface PlayerRepository {

    /** Current snapshot of playback state, updated in real time. */
    val playerState: StateFlow<PlayerState>

    /**
     * Emits the current playback position in milliseconds at a regular interval
     * (typically ~250 ms) while playback is active.
     */
    val currentPosition: Flow<Long>

    /**
     * Hot SharedFlow of cascade-halt events. Emitted at most once per
     * outage (resets after successful playback or user transport).
     * UI surface: in-app Snackbar in NowPlaying.
     */
    val streamingHaltedEvents: kotlinx.coroutines.flow.SharedFlow<StreamingHaltedEvent>

    /**
     * Snackbar-targeted one-shot messages from playback flow. Used for
     * conditions the player layer surfaces directly to the user:
     *
     *  - "Couldn't play this track right now." — `setQueue` couldn't
     *    resolve the tapped track via any source.
     *  - "End of offline Mix" — auto-advance silent-skip exhausted the
     *    queue trying to find a playable item while offline (v0.9.37).
     *
     * Collected by NowPlayingViewModel and forwarded into its own
     * `userMessages` SharedFlow so the Snackbar/Toast surfaces while the
     * user is on Now Playing or anywhere else the global player UI is
     * visible.
     */
    val userMessages: kotlinx.coroutines.flow.SharedFlow<String>

    /** Start or resume playback. */
    suspend fun play()

    /** Pause playback. */
    suspend fun pause()

    /** Skip to the next track in the queue. */
    suspend fun skipNext()

    /** Skip to the previous track (or restart current track based on position). */
    suspend fun skipPrevious()

    /** Seek to the given [positionMs] within the current track. */
    suspend fun seekTo(positionMs: Long)

    /**
     * Replace the current queue with [tracks] and begin playback at [startIndex].
     */
    suspend fun setQueue(tracks: List<Track>, startIndex: Int = 0)

    /**
     * Restore the last-played queue — full queue, saved current track,
     * saved position, and shuffle state — and start playing. Reuses the
     * normal [setQueue] resolution path, so it works in both offline and
     * online modes (downloaded files and streamed tracks) with background
     * queue fill, exactly like a normal playlist tap.
     *
     * Fire-and-forget: the work runs on the repository's own scope so the
     * caller (a no-UI trampoline activity launched from an app shortcut /
     * Bluetooth routine) can finish immediately. Falls back to the most
     * recently played / added track when no queue has been persisted yet.
     */
    fun resumeLastQueue()

    /**
     * v0.9.14: Replace the queue with a freshly-shuffled snapshot of the
     * user's entire downloaded library, begin playback, and arm the
     * auto-grow watcher so the queue refills from the unused remainder
     * once the user nears the tail. No-op if no tracks are downloaded.
     */
    /** Shuffle-play every downloaded track. Returns false when there is
     * nothing downloaded to shuffle (caller surfaces the why). */
    suspend fun shuffleLibrary(): Boolean

    /**
     * Insert [track] immediately after the currently-playing track in the queue.
     * Playback continues uninterrupted; the inserted track will play next.
     */
    suspend fun addNext(track: Track)

    /**
     * Append [track] to the end of the current queue.
     * Playback continues uninterrupted.
     */
    suspend fun addToQueue(track: Track)

    /**
     * Append [tracks] (in order) to the end of the current queue.
     * Single MediaController.addMediaItems round-trip — preferred
     * over looping the single-track variant for known-size batches
     * like an album's full tracklist or an artist's catalog. Empty
     * list is a no-op.
     *
     * Playback continues uninterrupted.
     */
    suspend fun addToQueue(tracks: List<Track>)

    /**
     * Start a radio station seeded from an artist or track. Builds a balanced
     * queue and arms self-extension; replaces any current queue/station. Returns
     * false (no-op) if streaming is off/offline or the seed yields nothing.
     *
     * [keepCurrent] = true (the Now Playing "Start radio from this song" case):
     * the seed IS the currently-playing track, so DON'T restart it — keep the
     * current item playing and splice the discoveries around it.
     */
    suspend fun startRadio(
        seed: com.stash.core.data.radio.RadioSeed,
        keepCurrent: Boolean = false,
    ): Boolean

    /** Stop the active station (queued tracks remain; no more auto-grow). */
    fun stopRadio()

    /** Live label of the active station's seed (null when no station). */
    val radioSeedLabel: StateFlow<String?>

    /** Toggle shuffle mode on/off. */
    suspend fun toggleShuffle()

    /** Cycle repeat mode: OFF -> ALL -> ONE -> OFF. */
    suspend fun cycleRepeatMode()

    /**
     * Remove the track at [index] from the playback queue.
     * Silently ignored if the index is out of bounds.
     */
    suspend fun removeFromQueue(index: Int)

    /**
     * Move the track at position [from] to position [to] within the playback queue.
     * Both indices refer to the current MediaController item list.
     */
    suspend fun moveInQueue(from: Int, to: Int)

    /**
     * Jump playback to the track at [index] in the queue.
     * Starts from the beginning of that track.
     */
    suspend fun skipToQueueIndex(index: Int)

    /**
     * v0.9.27: Streaming-aware single-track playback entry point. Runs
     * the routing decision tree for [track] (downloaded → local file;
     * streamable + streaming-on → resolve + cache + stream; etc.). On
     * [StreamRoutingResult.Item], the controller is asked to play the
     * resolved [MediaItem] immediately, replacing the current queue with
     * a single-item queue. On any refusal variant, the controller is
     * **not** touched and the variant is returned so the caller can
     * render a user-visible reason (snackbar).
     *
     * Distinct from [setQueue] because the latter pre-assumes local-only
     * playback and doesn't run the streaming routing. Once Task 18's row
     * treatment lands the Library tap path will migrate to this method.
     */
    suspend fun playTrack(track: Track): StreamRoutingResult

    /**
     * v0.9.27: Streaming-only playback entry point for the Search tab
     * (Task 20). Search results carry [TrackItem], a lightweight model
     * that doesn't yet have a [Track] / [com.stash.core.data.db.entity.TrackEntity]
     * because the song hasn't been imported into the user's library. The
     * routing layer inline-constructs a transient [com.stash.core.data
     * .db.entity.TrackEntity] and runs the streaming branch directly —
     * never a local-file branch, because by definition a search-result
     * track isn't on disk.
     *
     * Returns the same [StreamRoutingResult] variants as [playTrack] so
     * the Search ViewModel can render the same set of snackbars.
     */
    suspend fun playFromStream(item: TrackItem): StreamRoutingResult
}
