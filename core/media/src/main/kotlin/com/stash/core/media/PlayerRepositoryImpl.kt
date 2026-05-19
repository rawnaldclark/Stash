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
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.service.StashPlaybackService
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.model.PlayerState
import com.stash.core.model.RepeatMode
import com.stash.core.model.Track
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_BIT_DEPTH
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_BITRATE
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_CODEC
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_SAMPLE_RATE
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.model.TrackItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile

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
    private val streamingPreference: StreamingPreference,
    private val streamResolver: StreamSourceRegistry,
    private val streamUrlCache: StreamUrlCache,
    private val connectivity: ConnectivityMonitor,
    private val trackDao: TrackDao,
) : PlayerRepository {

    /**
     * Visible-for-testing indirection so unit tests can stub out the
     * on-disk existence check without touching the real filesystem.
     * Handles both plain filesystem paths (via [File.exists]) and
     * SAF-backed external storage URIs (via [DocumentFile.exists]).
     */
    internal var filePathExistsOnDisk: (String) -> Boolean = { path ->
        if (path.startsWith("content://")) {
            try {
                DocumentFile.fromSingleUri(context, path.toUri())?.exists() == true
            } catch (e: Exception) {
                false
            }
        } else {
            // Handle both plain paths and file:// URIs
            val plainPath = if (path.startsWith("file://")) {
                path.toUri().path ?: path.removePrefix("file://")
            } else {
                path
            }
            File(plainPath).exists()
        }
    }

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

    /**
     * Background coroutine that resolves the rest of the queue while
     * the user is already listening to the first track. Cancelled when
     * [setQueue] is invoked again so a stale fill can't pollute a new
     * playlist's queue.
     */
    @Volatile
    private var queueBuildJob: Job? = null

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
        // Any prior background fill belongs to a previous queue; kill it
        // so its addMediaItem calls can't pollute the new one.
        queueBuildJob?.cancel()
        queueBuildJob = null

        // Any explicit setQueue (playlist tap, single-song play, etc.) leaves
        // library-shuffle mode behind. Snapshot is cleared so a stale Track
        // list doesn't grow back into a different queue later.
        libraryShuffleActive = false
        librarySnapshot = emptyList()

        val controller = ensureController() ?: return
        if (tracks.isEmpty()) return

        val streamingOn = streamingPreference.current()
        val safeStart = startIndex.coerceIn(0, tracks.size - 1)
        val semaphore = Semaphore(STREAM_RESOLVE_PARALLELISM)

        // Resolve the tapped track first so playback can begin immediately,
        // regardless of queue size. For a 2671-track Liked Songs queue,
        // pre-resolving the whole list at the start used to take minutes;
        // resolving just the tapped track is one Kennyy round-trip (~1-2 s)
        // and the rest of the queue fills in below while the user listens.
        //
        // If the tapped track itself can't be resolved (niche track not in
        // Qobuz, transient 5xx, etc.), probe forward up to
        // [START_TRACK_PROBE_LIMIT] entries before giving up — most queues
        // have at least one playable track near the tap.
        var startTrackIndex = -1
        var startItem: MediaItem? = null
        val probeEnd = (safeStart + START_TRACK_PROBE_LIMIT).coerceAtMost(tracks.size)
        for (i in safeStart until probeEnd) {
            val item = resolveTrackToMediaItem(tracks[i], semaphore, streamingOn)
            if (item != null) {
                startTrackIndex = i
                startItem = item
                break
            }
        }

        if (startItem == null) {
            Log.w(
                TAG,
                "setQueue: no resolvable track in tracks[$safeStart .. ${probeEnd - 1}] " +
                    "(streamingOn=$streamingOn) — nothing to play",
            )
            return
        }

        Log.i(
            TAG,
            "setQueue: starting playback on track $startTrackIndex; " +
                "${tracks.size - 1} more to resolve in background",
        )

        controller.setMediaItems(listOf(startItem), /* startIndex = */ 0, /* startPositionMs = */ 0L)
        controller.prepare()
        controller.play()

        // Fill the rest of the queue in the background. Tracks after the
        // start anchor are appended first (skip-next is the common case);
        // tracks before are prepended afterwards so skip-back still works
        // once the fill catches up. Cancellable — see queueBuildJob KDoc.
        val forward = tracks.subList(startTrackIndex + 1, tracks.size)
        val backward = tracks.subList(0, startTrackIndex)
        queueBuildJob = scope.launch {
            try {
                fillQueueAppend(controller, forward, semaphore, streamingOn)
                fillQueuePrepend(controller, backward, semaphore, streamingOn)
                Log.i(TAG, "setQueue: background fill complete (${tracks.size} tracks)")
            } catch (e: CancellationException) {
                // Expected when the user starts a new queue. Don't log as failure.
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "setQueue: background fill failed", e)
            }
        }
    }

    /**
     * Resolves [tracks] in parallel batches of [BACKGROUND_FILL_BATCH] and
     * appends each batch to the controller's timeline as it completes.
     * Order within the input list is preserved. Failed resolutions are
     * silently dropped — they would only contribute URI-less MediaItems
     * that ExoPlayer can't play anyway.
     */
    private suspend fun fillQueueAppend(
        controller: MediaController,
        tracks: List<Track>,
        semaphore: Semaphore,
        streamingOn: Boolean,
    ) {
        tracks.chunked(BACKGROUND_FILL_BATCH).forEach { batch ->
            if (!currentCoroutineActive()) return
            val resolved = resolveBatchParallel(batch, semaphore, streamingOn)
            if (resolved.isNotEmpty()) controller.addMediaItems(resolved)
        }
    }

    /**
     * Like [fillQueueAppend] but prepends. The input is iterated in reverse
     * batch chunks so the *first* tracks of the original list end up at
     * index 0 of the controller's timeline once fill completes.
     */
    private suspend fun fillQueuePrepend(
        controller: MediaController,
        tracks: List<Track>,
        semaphore: Semaphore,
        streamingOn: Boolean,
    ) {
        // Process from the END of [tracks] backwards in chunks. The chunk
        // closest to the current playback head is processed last so the
        // skip-prev experience improves monotonically.
        val reversed = tracks.asReversed()
        reversed.chunked(BACKGROUND_FILL_BATCH).forEach { batchReversed ->
            if (!currentCoroutineActive()) return
            // Resolve the batch in original (forward) order so the
            // semaphore-bounded async fan-out doesn't reshuffle results.
            val batch = batchReversed.asReversed()
            val resolved = resolveBatchParallel(batch, semaphore, streamingOn)
            if (resolved.isNotEmpty()) controller.addMediaItems(/* index = */ 0, resolved)
        }
    }

    private suspend fun resolveBatchParallel(
        batch: List<Track>,
        semaphore: Semaphore,
        streamingOn: Boolean,
    ): List<MediaItem> = coroutineScope {
        batch.map { track ->
            async(Dispatchers.IO) {
                resolveTrackToMediaItem(track, semaphore, streamingOn)
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * Helper that bridges [isActive] (a CoroutineScope extension) into a
     * plain suspend function context. Returns false once the enclosing
     * job has been cancelled so background-fill loops can bail out.
     */
    private suspend fun currentCoroutineActive(): Boolean =
        kotlin.coroutines.coroutineContext[Job]?.isActive ?: true

    /**
     * Resolves a single [Track] to a Media3 [MediaItem] with a playable URI,
     * or `null` if the track is unplayable in the current mode.
     *
     * - Downloaded tracks: returns the local file:// MediaItem immediately
     *   (no network needed even when streaming is on — local is faster).
     * - Streaming-only tracks: when streaming is enabled, acquires a
     *   [semaphore] permit and resolves via [buildMediaItemForTrack] which
     *   consults the URL cache and falls through to [streamResolver].
     * - Streaming off + no file: returns `null` (dropped from the queue).
     */
    private suspend fun resolveTrackToMediaItem(
        track: Track,
        semaphore: Semaphore,
        streamingOn: Boolean,
    ): MediaItem? {
        val localPath = track.filePath
        if (track.isDownloaded && !localPath.isNullOrBlank() && filePathExistsOnDisk(localPath)) {
            return track.toMediaItem()
        }
        if (!streamingOn) return null

        return semaphore.withPermit {
            val entity = trackDao.getById(track.id) ?: track.toEntity()
            val result = buildMediaItemForTrack(entity)
            (result as? StreamRoutingResult.Item)?.mediaItem
        }
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

    // ---- Streaming routing ----

    override suspend fun playTrack(track: Track): StreamRoutingResult {
        val entity = trackDao.getById(track.id) ?: track.toEntity()
        val result = buildMediaItemForTrack(entity)
        if (result is StreamRoutingResult.Item) {
            playSingleMediaItem(result.mediaItem)
        }
        return result
    }

    override suspend fun playFromStream(item: TrackItem): StreamRoutingResult {
        // Idempotency guard #1 (already-playing): if the controller's
        // current MediaItem is THIS track and is in an active state,
        // skip. Mirrors the preview path's original guard.
        val targetMediaId = item.videoId.hashCode().toLong().toString()
        val controller = controllerDeferred
        if (controller != null) {
            val currentId = controller.currentMediaItem?.mediaId
            val state = controller.playbackState
            val activeStates = setOf(Player.STATE_BUFFERING, Player.STATE_READY)
            if (currentId == targetMediaId && state in activeStates) {
                return StreamRoutingResult.Item(controller.currentMediaItem!!)
            }
        }

        // Idempotency guard #2 (in-flight resolve): the user may tap N
        // times before the FIRST resolve has completed — at that point
        // the controller still shows the previous track, so guard #1
        // misses. Track in-flight videoIds in a synchronised set; rapid
        // duplicate taps short-circuit until the original resolve
        // finishes. Without this, 30 rapid taps = 30 separate resolves
        // and 30 setMediaItem calls.
        synchronized(inFlightStreamingTaps) {
            if (item.videoId in inFlightStreamingTaps) {
                return StreamRoutingResult.Deduped
            }
            inFlightStreamingTaps.add(item.videoId)
        }
        try {
            return playFromStreamInner(item)
        } finally {
            synchronized(inFlightStreamingTaps) {
                inFlightStreamingTaps.remove(item.videoId)
            }
        }
    }

    private val inFlightStreamingTaps = mutableSetOf<String>()

    private suspend fun playFromStreamInner(item: TrackItem): StreamRoutingResult {

        // Search-tab tap: no library row yet, so synthesize a transient
        // TrackEntity carrying only the fields buildMediaItemForTrack
        // reads. isDownloaded = false routes us straight into the
        // streaming branch.
        // Synthetic stable ID derived from videoId so the StreamUrlCache key
        // and the MediaItem.mediaId both differ between tracks. The previous
        // id=0L collapsed every search-tap stream onto a single cache key:
        // first tap cached, second tap returned the FIRST track's URL and
        // Media3 no-op'd setMediaItem on the matching mediaId. Repeat taps
        // of the same videoId still hit the cache (intended TTL behaviour).
        val transient = TrackEntity(
            id = item.videoId.hashCode().toLong(),
            title = item.title,
            artist = item.artist,
            album = item.album ?: "",
            durationMs = (item.durationSeconds * 1000).toLong(),
            isDownloaded = false,
            isStreamable = true,
            albumArtUrl = item.thumbnailUrl,
        )
        val result = buildMediaItemForTrack(transient)
        if (result is StreamRoutingResult.Item) {
            playSingleMediaItem(result.mediaItem)
        }
        return result
    }

    /**
     * Streaming-routing decision tree. The ordering matters:
     *
     * 1. Local file present + actually on disk → play it. Cheap, no
     *    network, works in airplane mode. Always preferred even when
     *    streaming is enabled — caching what you already have is free.
     * 2. Streaming pref off → [StreamRoutingResult.OfflineMode]. The
     *    track is theoretically streamable but the user has opted out.
     * 3. No validated internet → [StreamRoutingResult.NoConnectivity].
     *    Includes airplane mode, captive-portal-not-yet-accepted, and
     *    any other "associated but no real internet" state.
     * 4. Cellular + cellular pref off → [StreamRoutingResult.CellularRefused].
     *    The user has a data plan they want to protect.
     * 5. URL cache hit → use the cached signed URL.
     * 6. Cache miss → resolve via Kennyy and cache the result. Resolver
     *    null = no match in the proxy's catalog → [NotAvailable].
     *
     * Note: the `is_streamable` column is no longer consulted here.
     * AvailabilityCheckWorker (which set that flag) was removed; Kennyy
     * is now the sole source of truth on whether a track has a stream URL.
     * If `streamResolver.resolve()` returns null, we surface NotAvailable
     * at step 6 — no need to pre-gate on a stale flag.
     */
    internal suspend fun buildMediaItemForTrack(track: TrackEntity): StreamRoutingResult {
        val localPath = track.filePath
        if (track.isDownloaded && !localPath.isNullOrBlank() && filePathExistsOnDisk(localPath)) {
            val uri = if (localPath.startsWith("/")) Uri.parse("file://$localPath") else Uri.parse(localPath)
            return StreamRoutingResult.Item(
                MediaItem.Builder()
                    .setMediaId(track.id.toString())
                    .setUri(uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(track.title)
                            .setArtist(track.artist)
                            .setAlbumTitle(track.album)
                            .setArtworkUri(
                                (track.albumArtPath ?: track.albumArtUrl)?.let { Uri.parse(it) }
                            )
                            .setExtras(Bundle().apply { putLong(EXTRA_TRACK_ID, track.id) })
                            .build()
                    )
                    .build()
            )
        }
        if (!streamingPreference.current()) return StreamRoutingResult.OfflineMode
        if (!connectivity.isConnected()) return StreamRoutingResult.NoConnectivity
        if (connectivity.isCellular() && !streamingPreference.streamOnCellular.first()) {
            return StreamRoutingResult.CellularRefused
        }

        val cached = streamUrlCache.get(track.id)
        val stream = cached ?: streamResolver.resolve(track)?.also {
            streamUrlCache.put(track.id, it)
        } ?: return StreamRoutingResult.NotAvailable

        // YouTube *video* thumbnails (i.ytimg.com/vi/...) leak into
        // album_art_url for both YOUTUBE-sourced rows AND for Spotify
        // rows that the sync de-duped against a YT match. Source alone
        // can't tell us which rows have bad art — check the URL itself.
        // We also fill blank rows. Proper YT Music catalog art
        // (lh3.googleusercontent.com) is left alone; Spotify scdn URLs
        // are left alone. Fire-and-forget; never block playback on a
        // cosmetic DB write.
        val betterArt = stream.coverArtUrl
        val currentArt = track.albumArtUrl
        val needsUpgrade = currentArt.isNullOrBlank() ||
            com.stash.core.common.ArtUrlUpgrader.isYouTubeVideoThumbnail(currentArt)
        if (betterArt != null && needsUpgrade && betterArt != currentArt) {
            scope.launch { trackDao.updateAlbumArtUrl(track.id, betterArt) }
        }
        val displayArtUrl = if (betterArt != null && needsUpgrade) {
            betterArt
        } else {
            track.albumArtPath ?: currentArt
        }

        return StreamRoutingResult.Item(
            MediaItem.Builder()
                .setMediaId(track.id.toString())
                .setUri(Uri.parse(stream.url))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setAlbumTitle(track.album)
                        .setArtworkUri(
                            displayArtUrl?.let { Uri.parse(it) }
                        )
                        .setExtras(Bundle().apply {
                            putLong(EXTRA_TRACK_ID, track.id)
                            // Surface the actual format Qobuz served so Now Playing
                            // shows "FLAC · 24-bit/96 kHz" instead of the stale Room
                            // default ("opus") that streaming-only rows carry forever.
                            stream.codec?.let { putString(EXTRA_STREAM_CODEC, it) }
                            stream.bitsPerSample?.let { putInt(EXTRA_STREAM_BIT_DEPTH, it) }
                            stream.sampleRateHz?.let { putInt(EXTRA_STREAM_SAMPLE_RATE, it) }
                            stream.bitrateKbps?.let { putInt(EXTRA_STREAM_BITRATE, it) }
                        })
                        .build()
                )
                .build()
        )
    }

    /**
     * Helper used by [playTrack] and [playFromStream]: replace the queue
     * with a single MediaItem and start playback. Mirrors the prepare()
     * + play() pattern used by [setQueue].
     *
     * Also clears the library-shuffle armed state since the user has
     * navigated into a specific track — same invariant as [setQueue].
     */
    private suspend fun playSingleMediaItem(mediaItem: MediaItem) {
        libraryShuffleActive = false
        librarySnapshot = emptyList()
        val controller = ensureController() ?: return
        controller.setMediaItem(mediaItem)
        controller.prepare()
        controller.play()
    }

    /**
     * Lossy mapping from the domain [Track] back to a [TrackEntity] for
     * the routing decision tree. Used only when [trackDao.getById] returns
     * null — i.e. the track was resolved from a non-Room source. Carries
     * the routing-relevant fields ([isDownloaded], [filePath],
     * [isStreamable]) and the metadata fields the MediaItem builder reads.
     *
     * [Track] doesn't currently carry [isStreamable]; pessimistically
     * defaults to `false` here, which is safe — if a Track lookup misses
     * Room and isn't already downloaded, treating it as not-streamable
     * surfaces a [NotAvailable] rather than mysteriously falling through.
     */
    private fun Track.toEntity(): TrackEntity = TrackEntity(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        filePath = filePath,
        isDownloaded = isDownloaded,
        isStreamable = false,
        albumArtUrl = albumArtUrl,
        albumArtPath = albumArtPath,
        isrc = isrc,
    )

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

        // Streaming detection: derive purely from the active MediaItem's
        // URI scheme so the routing decision (Kennyy http(s) URL vs local
        // file://) is reflected automatically — no parallel flag to keep
        // in sync. localConfiguration is the resolved playback URI; falls
        // back to RequestMetadata for items built without a direct uri.
        val scheme = currentItem?.localConfiguration?.uri?.scheme
            ?: currentItem?.requestMetadata?.mediaUri?.scheme
        val isStreaming = scheme == "http" || scheme == "https"

        val newState = PlayerState(
            currentTrack = track,
            isPlaying = controller.isPlaying,
            positionMs = controller.currentPosition.coerceAtLeast(0),
            durationMs = controller.duration.coerceAtLeast(0),
            isShuffleEnabled = controller.shuffleModeEnabled,
            repeatMode = controller.repeatMode.toRepeatMode(),
            queue = queue,
            currentIndex = controller.currentMediaItemIndex,
            isStreaming = isStreaming,
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

        /**
         * Max in-flight Kennyy resolves while building a streaming queue.
         * Higher = faster queue-build but risks overwhelming the Qobuz
         * proxy (it's a community resource, not an SLA endpoint). 16 was
         * comfortably handled by a 2.6k-track Liked Songs queue in
         * dogfood testing.
         */
        private const val STREAM_RESOLVE_PARALLELISM = 16

        /**
         * If the tapped track itself fails to resolve, probe this many
         * adjacent tracks forward before giving up. Keeps playback alive
         * when the user lands on a single dead row (niche track not in
         * Qobuz, transient 5xx).
         */
        private const val START_TRACK_PROBE_LIMIT = 5

        /**
         * Tracks per background-fill batch. Each batch fires off a
         * parallel resolve fan-out up to [STREAM_RESOLVE_PARALLELISM] in
         * flight. Same value as the resolve cap so one batch saturates
         * the semaphore — keeps proxy pressure consistent.
         */
        private const val BACKGROUND_FILL_BATCH = 16
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
        val extras = meta.extras
        // v0.9.27: allow id=0 fallback for non-library tracks (e.g. search
        // previews with videoId strings). This makes the Like button and
        // other actions visible in Now Playing for non-library content.
        val trackId = extras?.getLong(EXTRA_TRACK_ID) ?: mediaId.toLongOrNull() ?: 0L

        // Streaming tracks carry the Qobuz-reported format in extras so the
        // Now Playing screen can show "FLAC · 24-bit/96 kHz" instead of the
        // Room row's default ("opus") that streaming-only library entries
        // inherit. Absent for downloaded tracks — those keep Room's truth.
        val streamCodec = extras?.getString(EXTRA_STREAM_CODEC)
        val streamBitDepth = extras?.getInt(EXTRA_STREAM_BIT_DEPTH, 0)?.takeIf { it > 0 }
        val streamSampleRate = extras?.getInt(EXTRA_STREAM_SAMPLE_RATE, 0)?.takeIf { it > 0 }
        val streamBitrate = extras?.getInt(EXTRA_STREAM_BITRATE, 0)?.takeIf { it > 0 }

        return Track(
            id = trackId,
            title = meta.title?.toString() ?: "",
            artist = meta.artist?.toString() ?: "",
            album = meta.albumTitle?.toString() ?: "",
            albumArtUrl = meta.artworkUri?.toString(),
            // For non-library tracks, the mediaId is the YouTube videoId.
            youtubeId = if (trackId == 0L) mediaId else null,
            source = if (trackId == 0L) com.stash.core.model.MusicSource.YOUTUBE else com.stash.core.model.MusicSource.SPOTIFY,
            fileFormat = streamCodec ?: "opus",
            bitsPerSample = streamBitDepth,
            sampleRateHz = streamSampleRate,
            qualityKbps = streamBitrate ?: 0,
        )
    }
}
