package com.stash.core.media

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.service.StashPlaybackService
import com.stash.core.model.PlayerState
import com.stash.core.model.RepeatMode
import com.stash.core.model.Track
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.net.toUri

/**
 * [PlayerRepository] implementation backed by a [MediaController] that connects
 * to [StashPlaybackService].
 *
 * The controller is lazily initialised on first use and re-used for the lifetime
 * of the application process.
 */
@Singleton
class PlayerRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playbackStateStore: PlaybackStateStore,
    private val musicRepository: MusicRepository,
) : PlayerRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        // v0.9.27: Connect the controller immediately on init so we can
        // provide live state even if the app was cold-started while
        // music was already playing (e.g. via Android Auto).
        scope.launch {
            ensureController()
        }

        // Evict deleted tracks from the live queue. Without this, ExoPlayer's
        // open file handle keeps audio playing after the user deletes the
        // song (correct Unix semantics, wrong UX) — see Reddit report from
        // user Superb_Agency_796. Subscribing here means every repo delete
        // entry-point automatically informs the player; future delete methods
        // don't have to remember to call a helper in the ViewModel layer.
        scope.launch {
            musicRepository.trackDeletions.collect { trackId ->
                evictTrackFromQueue(trackId)
            }
        }

        // v0.9.14: Library-shuffle auto-grow watcher. Subscribes to player
        // state and refills the queue with more shuffled library tracks
        // once the user nears the tail. Inactive unless shuffleLibrary()
        // armed it; setQueue() disarms it so per-playlist queues stay
        // finite and predictable.
        scope.launch {
            playerState.collect { state ->
                if (!libraryShuffleActive) return@collect
                val remaining = state.queue.size - state.currentIndex - 1
                if (remaining in 0 until LIBRARY_SHUFFLE_GROW_THRESHOLD) {
                    growLibraryShuffle()
                }
            }
        }
    }

    private val _playerState = MutableStateFlow(PlayerState())
    override val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    /**
     * Emits the playback position every 250 ms while the player is active.
     * Collectors receive 0 when nothing is playing.
     */
    override val currentPosition: Flow<Long> = flow {
        while (true) {
            val controller = controllerDeferred
            emit(controller?.currentPosition ?: 0L)
            delay(POSITION_UPDATE_INTERVAL_MS)
        }
    }

    /** Cached [MediaController] instance; null until [ensureController] succeeds. */
    @Volatile
    private var controllerDeferred: MediaController? = null

    /**
     * v0.9.14: True while a "Shuffle Library" queue is active. Set by
     * [shuffleLibrary], cleared by [setQueue]. Drives the auto-grow watcher.
     */
    @Volatile
    private var libraryShuffleActive: Boolean = false

    /**
     * v0.9.14: Cached snapshot of the user's downloaded library at the moment
     * [shuffleLibrary] was called. Auto-grow appends from this list (minus
     * tracks already queued). Survives app process for as long as library-
     * shuffle stays armed; cleared when leaving via [setQueue].
     */
    @Volatile
    private var librarySnapshot: List<Track> = emptyList()

    /** Serializes auto-grow operations so multiple state updates can't fan out. */
    private val growMutex = Mutex()

    // ---- Public API ----

    override suspend fun play() {
        ensureController()?.play()
    }

    override suspend fun pause() {
        ensureController()?.pause()
    }

    override suspend fun skipNext() {
        ensureController()?.seekToNextMediaItem()
    }

    override suspend fun skipPrevious() {
        ensureController()?.seekToPreviousMediaItem()
    }

    override suspend fun seekTo(positionMs: Long) {
        ensureController()?.seekTo(positionMs)
    }

    override suspend fun setQueue(tracks: List<Track>, startIndex: Int) {
        // Any explicit setQueue (playlist tap, single-song play, etc.) leaves
        // library-shuffle mode behind. Snapshot is cleared so a stale Track
        // list doesn't grow back into a different queue later.
        libraryShuffleActive = false
        librarySnapshot = emptyList()

        val controller = ensureController() ?: return
        val mediaItems = tracks.map { it.toMediaItem() }
        controller.setMediaItems(mediaItems, startIndex, /* startPositionMs = */ 0L)
        controller.prepare()
        controller.play()
    }

    override suspend fun shuffleLibrary() {
        val controller = ensureController() ?: return
        val all = musicRepository.getAllDownloadedTracks()
        if (all.isEmpty()) return

        val shuffled = all.shuffled()
        librarySnapshot = shuffled
        libraryShuffleActive = true

        val mediaItems = shuffled.map { it.toMediaItem() }
        controller.setMediaItems(mediaItems, /* startIndex = */ 0, /* startPositionMs = */ 0L)
        // Match user expectation: pressing "Shuffle Library" implies shuffle
        // is on, regardless of the previous toggle state. The Media3 shuffle
        // mode toggles randomized advance order; we already pre-shuffled the
        // queue ourselves, so we leave shuffleModeEnabled alone — the queue
        // we hand to the controller IS the playback order.
        controller.prepare()
        controller.play()
    }

    /**
     * Append the next slice of unused library tracks to the controller's
     * timeline. Mutex-guarded so a flurry of state updates (each track
     * change emits two or three) can't fan out into concurrent grows.
     *
     * Strategy: rebuild the "currently queued" set by reading the controller
     * timeline, take everything from [librarySnapshot] not in that set,
     * shuffle those, append [LIBRARY_SHUFFLE_GROW_BATCH]. If the snapshot is
     * exhausted (whole library is in the queue already), reshuffle the
     * snapshot for a fresh slice — looping is preferable to silence for the
     * "just keep music playing" intent of this entry point.
     */
    private suspend fun growLibraryShuffle() {
        growMutex.withLock {
            val controller = controllerDeferred ?: return
            val snapshot = librarySnapshot
            if (snapshot.isEmpty()) return

            val queuedIds = buildSet {
                for (i in 0 until controller.mediaItemCount) {
                    val id = controller.getMediaItemAt(i).mediaMetadata.extras
                        ?.getLong(EXTRA_TRACK_ID)
                        ?: controller.getMediaItemAt(i).mediaId.toLongOrNull()
                    if (id != null) add(id)
                }
            }

            val unused = snapshot.filterNot { it.id in queuedIds }
            val pool = if (unused.isEmpty()) snapshot else unused
            val toAppend = pool.shuffled().take(LIBRARY_SHUFFLE_GROW_BATCH)
            if (toAppend.isEmpty()) return

            controller.addMediaItems(toAppend.map { it.toMediaItem() })
        }
    }

    override suspend fun addNext(track: Track) {
        val controller = ensureController() ?: return
        val wasEmpty = controller.mediaItemCount == 0
        val insertIndex = controller.currentMediaItemIndex + 1
        controller.addMediaItem(insertIndex, track.toMediaItem())
        // If the queue was empty, the user tapped "Play next" with nothing
        // playing — they expect the song to actually start, not just sit
        // silently in a queue they can't see. Prepare and play.
        if (wasEmpty) {
            controller.prepare()
            controller.play()
        }
    }

    override suspend fun addToQueue(track: Track) {
        val controller = ensureController() ?: return
        val wasEmpty = controller.mediaItemCount == 0
        controller.addMediaItem(track.toMediaItem())
        if (wasEmpty) {
            controller.prepare()
            controller.play()
        }
    }

    override suspend fun toggleShuffle() {
        val controller = ensureController() ?: return
        controller.sendCustomCommand(
            SessionCommand(StashPlaybackService.COMMAND_TOGGLE_SHUFFLE, Bundle.EMPTY),
            Bundle.EMPTY,
        )
    }

    override suspend fun cycleRepeatMode() {
        val controller = ensureController() ?: return
        controller.sendCustomCommand(
            SessionCommand(StashPlaybackService.COMMAND_CYCLE_REPEAT, Bundle.EMPTY),
            Bundle.EMPTY,
        )
    }

    override suspend fun removeFromQueue(index: Int) {
        val controller = ensureController() ?: return
        if (index in 0 until controller.mediaItemCount) {
            controller.removeMediaItem(index)
        }
    }

    override suspend fun moveInQueue(from: Int, to: Int) {
        val controller = ensureController() ?: return
        val count = controller.mediaItemCount
        if (from in 0 until count && to in 0 until count && from != to) {
            controller.moveMediaItem(from, to)
        }
    }

    override suspend fun skipToQueueIndex(index: Int) {
        val controller = ensureController() ?: return
        if (index in 0 until controller.mediaItemCount) {
            controller.seekToDefaultPosition(index)
        }
    }

    /**
     * Called by the MusicRepository.trackDeletions collector. Removes every
     * queue entry whose Media3 extras carry [deletedTrackId]. Operates
     * high-to-low so earlier indices stay valid while the loop runs.
     *
     * If the currently-playing item is removed, Media3 auto-advances to the
     * next queue entry (or stops the player if we've emptied the queue) —
     * no manual `stop()` or `seekToNextMediaItem()` needed.
     *
     * No-op when the controller hasn't been initialised yet (user deleted
     * a track before ever hitting play this session).
     */
    private fun evictTrackFromQueue(deletedTrackId: Long) {
        val controller = controllerDeferred ?: return
        for (i in controller.mediaItemCount - 1 downTo 0) {
            val item = controller.getMediaItemAt(i)
            val queuedId = item.mediaMetadata.extras?.getLong(EXTRA_TRACK_ID)
                ?: item.mediaId.toLongOrNull()
            if (queuedId == deletedTrackId) {
                controller.removeMediaItem(i)
            }
        }
    }

    // ---- Internals ----

    /**
     * Lazily builds and connects a [MediaController] to [StashPlaybackService].
     * Returns the connected controller or null on failure.
     */
    private suspend fun ensureController(): MediaController? {
        controllerDeferred?.let { return it }

        return try {
            val sessionToken = SessionToken(
                context,
                ComponentName(context, StashPlaybackService::class.java),
            )
            val controller = MediaController.Builder(context, sessionToken)
                .buildAsync()
                .await()

            controller.addListener(playerListener)
            controllerDeferred = controller
            // Sync initial state
            updateState(controller)
            controller
        } catch (e: Exception) {
            null
        }
    }

    /** Listener that forwards Media3 player events into [_playerState]. */
    private val playerListener = object : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val controller = controllerDeferred ?: return
            // Defense in depth: the existing onPlayerError recovery catches
            // PlaybackException-driven failures, but some failure modes (audio
            // offload sink stalls before we removed offload, plus any future
            // codec/format edge case) can leave the player in STATE_IDLE on the
            // next track WITHOUT firing onPlayerError. The user-visible symptom
            // is "next song appears, play button does nothing." A single
            // prepare() call is a no-op when the player is already READY and
            // rescues the IDLE case automatically.
            if (controller.playbackState == Player.STATE_IDLE && controller.currentMediaItem != null) {
                Log.w(TAG, "onMediaItemTransition landed in STATE_IDLE — defensive prepare()")
                controller.prepare()
            }
            updateState(controller)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            controllerDeferred?.let { updateState(it) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            controllerDeferred?.let { updateState(it) }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            controllerDeferred?.let { updateState(it) }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            controllerDeferred?.let { updateState(it) }
        }

        /**
         * Fires whenever the queue itself changes — adds, removes, moves.
         * Without this, addMediaItem / removeMediaItem / moveMediaItem
         * mutate the underlying timeline but the UI's queue view (built
         * from _playerState) never sees the change. Symptom: "I tapped
         * Play Next but the song doesn't appear in the queue."
         */
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            controllerDeferred?.let { updateState(it) }
        }

        /**
         * Auto-recover from playback failures (issue #15).
         *
         * Without this override, ExoPlayer's default behaviour on
         * [PlaybackException] is to drop to `STATE_IDLE` and stay there —
         * the UI sees the auto-advance fire (`onMediaItemTransition`
         * delivers the next track) but playback never actually begins
         * because the player needs `prepare()` to re-enter `STATE_READY`.
         * Symptom: next song appears in Now Playing, play button does
         * nothing, until the user manually skips twice.
         *
         * The recovery pattern below mirrors what a manual "skip next"
         * does under the hood. We log the failing track + reason for
         * triage (often a missing file_path after a backfill swap, a
         * transient streaming hiccup, or a codec edge case), then seek
         * past the broken item and re-prepare. If we're at the end of
         * the queue we stop gracefully rather than loop on errors.
         */
        override fun onPlayerError(error: PlaybackException) {
            val controller = controllerDeferred
            val failingTitle = controller?.currentMediaItem?.mediaMetadata?.title
            Log.w(
                TAG,
                "onPlayerError: '$failingTitle' code=${error.errorCode} " +
                    "(${error.errorCodeName}) — attempting skip-next recovery",
                error,
            )
            if (controller == null) return

            if (controller.hasNextMediaItem()) {
                controller.seekToNextMediaItem()
                controller.prepare()
                controller.play()
            } else {
                // End of queue — let the player stop cleanly rather than
                // looping on the same broken item.
                controller.stop()
            }
        }
    }

    /**
     * Reads the current state from the [MediaController] and publishes it to
     * [_playerState]. Also persists the current position via [PlaybackStateStore].
     */
    private fun updateState(controller: MediaController) {
        val currentItem = controller.currentMediaItem
        val track = currentItem?.toTrack()
        val queue = buildList {
            for (i in 0 until controller.mediaItemCount) {
                add(controller.getMediaItemAt(i).toTrack())
            }
        }

        val newState = PlayerState(
            currentTrack = track,
            isPlaying = controller.isPlaying,
            positionMs = controller.currentPosition.coerceAtLeast(0),
            durationMs = controller.duration.coerceAtLeast(0),
            isShuffleEnabled = controller.shuffleModeEnabled,
            repeatMode = controller.repeatMode.toRepeatMode(),
            queue = queue,
            currentIndex = controller.currentMediaItemIndex,
        )
        _playerState.value = newState

        // Persist position for resume-on-restart (fire and forget)
        if (track != null) {
            scope.launch {
                playbackStateStore.savePosition(
                    trackId = track.id,
                    positionMs = newState.positionMs,
                    queueIndex = newState.currentIndex,
                )
            }
        }
    }

    // ---- Mappers ----

    companion object {
        private const val TAG = "StashPlayer"
        private const val POSITION_UPDATE_INTERVAL_MS = 250L

        /** Auto-grow fires once the remaining queue tail drops below this many tracks. */
        private const val LIBRARY_SHUFFLE_GROW_THRESHOLD = 5

        /** How many tracks each grow appends. Big enough to outpace a fast-skipping user. */
        private const val LIBRARY_SHUFFLE_GROW_BATCH = 50
    }

    /**
     * Converts a domain [Track] into a Media3 [MediaItem] suitable for ExoPlayer.
     * The local file path (if present) is set as the playback URI; album art is
     * carried as [MediaMetadata.artworkUri].
     */
    private fun Track.toMediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(
                (albumArtPath ?: albumArtUrl)?.toUri()
            )
            .setExtras(Bundle().apply { putLong(EXTRA_TRACK_ID, id) })
            .build()

        // Ensure file:// scheme so StashPlaybackService's URI validation passes.
        val fileUri = filePath?.let { path ->
            if (path.startsWith("/")) "file://$path".toUri() else path.toUri()
        }

        val requestMetadata = MediaItem.RequestMetadata.Builder()
            .setMediaUri(fileUri)
            .build()

        return MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(fileUri)
            .setMediaMetadata(metadata)
            .setRequestMetadata(requestMetadata)
            .build()
    }

    /**
     * Best-effort reconstruction of a [Track] from a [MediaItem]'s metadata.
     * Only the fields carried through Media3 metadata are populated.
     */
    private fun MediaItem.toTrack(): Track {
        val meta = mediaMetadata
        // v0.9.27: allow id=0 fallback for non-library tracks (e.g. search
        // previews with videoId strings). This makes the Like button and
        // other actions visible in Now Playing for non-library content.
        val trackId = meta.extras?.getLong(EXTRA_TRACK_ID) ?: mediaId.toLongOrNull() ?: 0L
        return Track(
            id = trackId,
            title = meta.title?.toString() ?: "",
            artist = meta.artist?.toString() ?: "",
            album = meta.albumTitle?.toString() ?: "",
            albumArtUrl = meta.artworkUri?.toString(),
            // For non-library tracks, the mediaId is the YouTube videoId.
            youtubeId = if (trackId == 0L) mediaId else null,
            source = if (trackId == 0L) com.stash.core.model.MusicSource.YOUTUBE else com.stash.core.model.MusicSource.SPOTIFY
        )
    }
}
