package com.stash.core.media.service

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.common.MediaMetadata
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.social.stash.StashLikedPlaylistRepository
import com.stash.core.media.R
import com.stash.core.media.equalizer.EqController
import com.stash.core.media.equalizer.LoudnessController
import com.stash.core.media.equalizer.StashRenderersFactory
import com.stash.core.media.equalizer.computeGain
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.guava.future
import javax.inject.Inject
import androidx.core.net.toUri
import com.stash.core.data.db.entity.TrackEntity

/**
 * Background playback service that hosts an [ExoPlayer] and exposes a [MediaSession]
 * for media-controller clients (e.g. system notification, Bluetooth, Android Auto).
 *
 * Custom session commands:
 * - [COMMAND_TOGGLE_SHUFFLE] -- toggles shuffle mode on/off
 * - [COMMAND_CYCLE_REPEAT]   -- cycles repeat mode: OFF -> ALL -> ONE -> OFF
 * - [COMMAND_TOGGLE_LIKE]    -- toggles Stash Liked Songs membership for the
 *   currently playing track. Surfaced as a heart icon in the system
 *   notification (expanded view) so the user can like/unlike from the
 *   lockscreen without opening Now Playing.
 */
@AndroidEntryPoint
class StashPlaybackService : MediaLibraryService() {

    @Inject lateinit var eqController: EqController
    @Inject lateinit var loudnessController: LoudnessController
    @Inject lateinit var trackDao: TrackDao
    @Inject lateinit var playlistDao: PlaylistDao
    @Inject lateinit var stashLikedRepository: StashLikedPlaylistRepository

    companion object {
        /** Custom command action for toggling shuffle mode. */
        const val COMMAND_TOGGLE_SHUFFLE = "com.stash.TOGGLE_SHUFFLE"

        /** Custom command action for cycling repeat mode. */
        const val COMMAND_CYCLE_REPEAT = "com.stash.CYCLE_REPEAT"

        /** Custom command action for toggling Stash Liked on the current track. */
        const val COMMAND_TOGGLE_LIKE = "com.stash.TOGGLE_LIKE"

        /** Extra key for the track ID in MediaMetadata extras. */
        const val EXTRA_TRACK_ID = "stash_track_id"

        private const val ROOT_ID = "ROOT"
        private const val PLAYLISTS_ID = "PLAYLISTS"
        private const val RECENTLY_ADDED_ID = "RECENTLY_ADDED"
        private const val PLAYLIST_PREFIX = "PLAYLIST_"
        private const val SHUFFLE_PLAY_PREFIX = "SHUFFLE_PLAY_"
    }

    private var mediaSession: MediaLibrarySession? = null

    // Service-scoped CoroutineScope for the like-state observer + toggle
    // suspending calls. Cancelled in onDestroy so the observer doesn't leak
    // when the service stops.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var likeObserverJob: Job? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // Generate an explicit audio session ID BEFORE building the player.
        // ExoPlayer.audioSessionId returns 0 (global mix) by default until playback starts,
        // which causes audio effect creation to fail with Error -3.
        // By generating our own ID and passing it to the builder, the effects can attach immediately.
        // Generate a dedicated audio session ID so audio effects can attach immediately.
        val audioManager = getSystemService(android.media.AudioManager::class.java)
        val audioSessionId = audioManager.generateAudioSessionId()
        android.util.Log.i("StashPlayback", "Generated audio session ID: $audioSessionId")

        // Optimised buffer for local music playback: larger buffers eliminate
        // micro-stutters from storage I/O; lower playback thresholds keep
        // start-up snappy.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000,
            )
            .build()

        val player = ExoPlayer.Builder(this)
            .setRenderersFactory(StashRenderersFactory(this, eqController, loudnessController))
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        // Set the pre-generated session ID on the player
        player.audioSessionId = audioSessionId

        // Set session activity so tapping the media notification opens the app.
        // The intent targets the app's launcher activity via the package's launch intent.
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val sessionActivity = if (launchIntent != null) {
            android.app.PendingIntent.getActivity(
                this, 0, launchIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        } else null

        val sessionBuilder = MediaLibrarySession.Builder(this, player, StashSessionCallback())
        if (sessionActivity != null) {
            sessionBuilder.setSessionActivity(sessionActivity)
        }
        val session = sessionBuilder.build()

        mediaSession = session

        // Per-track wiring on every transition:
        //   1. Heart-button notification icon (filled vs. outlined) so the
        //      lockscreen reflects the new track's Stash-Liked state. The
        //      per-track observe loop inside [refreshLikeButton] keeps the
        //      icon in sync if the user toggles like from elsewhere
        //      (Now Playing, Library, etc.) while audio is playing.
        //   2. Loudness normalisation: pull the new track's measured LUFS /
        //      true-peak from the DB and push the computed per-track gain
        //      to [LoudnessController]. The DSP layer reads the controller
        //      state and ramps to the new target via LoudnessGainProcessor.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateCustomLayout()
                onTrackTransitionForLoudness(mediaItem)
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                updateCustomLayout()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                updateCustomLayout()
            }
        })
        updateCustomLayout()
    }

    /**
     * Test-visible per-transition hook for loudness gain. Resolves the
     * media item to a track row (matching the heart-button's
     * `mediaId.toLongOrNull()` convention) and pushes the computed gain
     * to [LoudnessController]. No-ops when the id can't be parsed, the
     * row is missing, or the track has no measured loudness yet — in
     * those cases [computeGain] returns 0 dB which is the safe bypass.
     *
     * Visibility is `internal` so unit tests can invoke the hook directly
     * without booting a full [MediaLibraryService] / [ExoPlayer].
     */
    internal fun onTrackTransitionForLoudness(mediaItem: MediaItem?) {
        val trackId = mediaItem?.mediaId?.toLongOrNull() ?: return
        serviceScope.launch {
            val track = trackDao.getById(trackId) ?: return@launch
            val gainDb = computeGain(track.loudnessLufs, track.truePeakDbfs)
            loudnessController.setCurrentTrackGain(gainDb)
        }
    }

    private var lastTrackId: Long? = null
    private var lastIsLiked: Boolean = false

    /**
     * Updates the MediaSession custom layout with the heart, shuffle, and repeat icons.
     * Starts a database observer for the current track's like state. For player-state
     * changes (repeat/shuffle) on the same track, it refreshes the layout using the
     * last known like state to avoid redundant DB observer restarts.
     */
    @OptIn(UnstableApi::class)
    private fun updateCustomLayout() {
        val session = mediaSession ?: return
        val player = session.player
        val trackId = player.currentMediaItem?.mediaId?.toLongOrNull()

        if (trackId == null) {
            likeObserverJob?.cancel()
            lastTrackId = null
            lastIsLiked = false
            session.setCustomLayout(ImmutableList.of())
            return
        }

        if (trackId != lastTrackId) {
            likeObserverJob?.cancel()
            lastTrackId = trackId
            // Reset liked state and push an initial layout immediately for the new track.
            // This prevents the previous track's heart state from lingering until the
            // DB observer emits for the first time.
            lastIsLiked = false
            pushLayout(session, player, false)

            likeObserverJob = serviceScope.launch {
                trackDao.observeLikeState(trackId).collect { state ->
                    val isLiked = state?.stashLikedAt != null
                    // Update if the state actually changed, or if we haven't pushed for this track yet.
                    if (isLiked != lastIsLiked) {
                        lastIsLiked = isLiked
                        pushLayout(session, player, lastIsLiked)
                    }
                }
            }
        } else {
            pushLayout(session, player, lastIsLiked)
        }
    }

    @OptIn(UnstableApi::class)
    private fun pushLayout(session: MediaSession, player: Player, isLiked: Boolean) {
        val layout = ImmutableList.of(
            buildLikeButton(isLiked),
            buildRepeatButton(player.repeatMode)
        )
        session.setCustomLayout(layout)
    }

    @OptIn(UnstableApi::class)
    private fun buildRepeatButton(repeatMode: Int): CommandButton {
        val iconRes = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> R.drawable.ic_repeat_off
            Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one
            else -> R.drawable.ic_repeat
        }
        val displayNameRes = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> R.string.notification_action_repeat_off
            Player.REPEAT_MODE_ALL -> R.string.notification_action_repeat_all
            Player.REPEAT_MODE_ONE -> R.string.notification_action_repeat_one
            else -> R.string.notification_action_repeat_off
        }
        return CommandButton.Builder()
            .setDisplayName(getString(displayNameRes))
            .setIconResId(iconRes)
            .setSessionCommand(
                SessionCommand(COMMAND_CYCLE_REPEAT, android.os.Bundle.EMPTY),
            )
            .build()
    }

    @OptIn(UnstableApi::class)
    private fun buildLikeButton(isLiked: Boolean): CommandButton {
        val iconRes = if (isLiked) {
            R.drawable.ic_notification_heart_filled
        } else {
            R.drawable.ic_notification_heart_outlined
        }
        val displayNameRes = if (isLiked) {
            R.string.notification_action_unlike
        } else {
            R.string.notification_action_like
        }
        return CommandButton.Builder()
            .setDisplayName(getString(displayNameRes))
            .setIconResId(iconRes)
            .setSessionCommand(
                SessionCommand(COMMAND_TOGGLE_LIKE, android.os.Bundle.EMPTY),
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    @OptIn(UnstableApi::class)
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        likeObserverJob?.cancel()
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    // ---- MediaLibrarySession.Callback ----

    private inner class StashSessionCallback : MediaLibrarySession.Callback {

        fun TrackEntity.toMediaMetadata(): MediaMetadata {
            return MediaMetadata.Builder()
                .setTitle(this.title)
                .setArtist(this.artist)
                .setAlbumTitle(this.album)
                .setArtworkUri(this.albumArtUrl?.toUri() ?: this.albumArtPath?.toUri())
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setExtras(android.os.Bundle().apply {
                    putLong(EXTRA_TRACK_ID, id)
                })
                .build()
        }

        private suspend fun resolveMediaItem(item: MediaItem): MediaItem {
            // 1. If it's already a fully resolved item (has URI), use it
            if (item.localConfiguration?.uri != null) {
                return item
            }

            // 2. If it's a library item (has mediaId), resolve it from DB
            val trackId = item.mediaId.toLongOrNull()
            if (trackId != null) {
                val track = trackDao.getById(trackId)
                if (track != null) {
                    return item.buildUpon()
                        .setUri(track.filePath ?: "")
                        .setMediaMetadata(track.toMediaMetadata())
                        .build()
                }
            }

            // 3. Fallback to request metadata URI (with security check)
            val uri = item.requestMetadata.mediaUri
            if (uri != null) {
                val scheme = uri.scheme
                if (scheme == "file" || scheme == "android.resource" || scheme == "content") {
                    return item.buildUpon().setUri(uri).build()
                }
            }

            return item
        }

        @OptIn(UnstableApi::class)
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            return serviceScope.future {
                if (mediaItems.size == 1 && mediaItems[0].mediaId.startsWith(SHUFFLE_PLAY_PREFIX)) {
                    val playlistId = mediaItems[0].mediaId.removePrefix(SHUFFLE_PLAY_PREFIX).toLongOrNull()
                    if (playlistId != null) {
                        val tracks = playlistDao.getTracksForPlaylist(playlistId)
                        val items = tracks.filter{track -> track.isDownloaded}.map { track ->
                            MediaItem.Builder()
                                .setMediaId(track.id.toString())
                                .setUri(track.filePath ?: "")
                                .setMediaMetadata(track.toMediaMetadata())
                                .build()
                        }.shuffled()

                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            mediaSession.player.shuffleModeEnabled = true
                        }

                        return@future MediaSession.MediaItemsWithStartPosition(
                            items,
                            0,
                            C.TIME_UNSET
                        )
                    }
                }
                val resolvedItems = mediaItems.map { resolveMediaItem(it) }
                MediaSession.MediaItemsWithStartPosition(resolvedItems, startIndex, startPositionMs)
            }
        }

        @OptIn(UnstableApi::class)
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            return serviceScope.future {
                if (mediaItems.size == 1 && mediaItems[0].mediaId.startsWith(SHUFFLE_PLAY_PREFIX)) {
                    val playlistId = mediaItems[0].mediaId.removePrefix(SHUFFLE_PLAY_PREFIX).toLongOrNull()
                    if (playlistId != null) {
                        return@future playlistDao.getTracksForPlaylist(playlistId).map { track ->
                            MediaItem.Builder()
                                .setMediaId(track.id.toString())
                                .setUri(track.filePath ?: "")
                                .setMediaMetadata(track.toMediaMetadata())
                                .build()
                        }.shuffled()
                    }
                }
                mediaItems.map { resolveMediaItem(it) }
            }
        }

        @OptIn(UnstableApi::class)
        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            android.util.Log.d("StashPlayback", "onGetItem: id=$mediaId client=${browser.packageName}")
            return when (mediaId) {
                ROOT_ID -> {
                    val rootItem = MediaItem.Builder()
                        .setMediaId(ROOT_ID)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("Stash Root")
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .build(),
                        )
                        .build()
                    Futures.immediateFuture(LibraryResult.ofItem(rootItem, null))
                }
                PLAYLISTS_ID -> {
                    val playlistsItem = MediaItem.Builder()
                        .setMediaId(PLAYLISTS_ID)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("Playlists")
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .build(),
                        )
                        .build()
                    Futures.immediateFuture(LibraryResult.ofItem(playlistsItem, null))
                }
                else -> {
                    // Try to resolve track or playlist
                    serviceScope.future {
                        val trackId = mediaId.toLongOrNull()
                        if (trackId != null) {
                            val track = trackDao.getById(trackId)
                            if (track != null) {
                                return@future LibraryResult.ofItem(
                                    MediaItem.Builder()
                                        .setMediaId(track.id.toString())
                                        .setUri(track.filePath ?: "")
                                        .setMediaMetadata(
                                            track.toMediaMetadata(),
                                        )
                                        .build(),
                                    null,
                                )
                            }
                        }
                        if (mediaId.startsWith(PLAYLIST_PREFIX)) {
                            val playlistId = mediaId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
                            if (playlistId != null) {
                                val playlist = playlistDao.getById(playlistId)
                                if (playlist != null) {
                                    return@future LibraryResult.ofItem(
                                        MediaItem.Builder()
                                            .setMediaId(mediaId)
                                            .setMediaMetadata(
                                                MediaMetadata.Builder()
                                                    .setTitle(playlist.name)
                                                    .setIsBrowsable(true)
                                                    .setIsPlayable(false)
                                                    .build(),
                                            )
                                            .build(),
                                        null,
                                    )
                                }
                            }
                        }
                        if (mediaId.startsWith(SHUFFLE_PLAY_PREFIX)) {
                            val playlistId = mediaId.removePrefix(SHUFFLE_PLAY_PREFIX).toLongOrNull()
                            if (playlistId != null) {
                                val playlist = playlistDao.getById(playlistId)
                                if (playlist != null) {
                                    return@future LibraryResult.ofItem(
                                        MediaItem.Builder()
                                            .setMediaId(mediaId)
                                            .setMediaMetadata(
                                                MediaMetadata.Builder()
                                                    .setTitle(getString(R.string.shuffle_play))
                                                    .setArtworkUri("android.resource://$packageName/drawable/ic_shuffle".toUri())
                                                    .setIsBrowsable(false)
                                                    .setIsPlayable(true)
                                                    .build(),
                                            )
                                            .build(),
                                        null,
                                    )
                                }
                            }
                        }
                        LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    }
                }
            }
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Stash Root")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build(),
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        @OptIn(UnstableApi::class)
        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return serviceScope.future {
                val items = when (parentId) {
                    ROOT_ID -> {
                        listOf(
                            MediaItem.Builder()
                                .setMediaId(PLAYLISTS_ID)
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle("Playlists")
                                        .setIsBrowsable(true)
                                        .setIsPlayable(false)
                                        .build(),
                                )
                                .build(),
                            MediaItem.Builder()
                                .setMediaId(RECENTLY_ADDED_ID)
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle("Recently Added")
                                        .setIsBrowsable(true)
                                        .setIsPlayable(false)
                                        .build(),
                                )
                                .build(),
                        )
                    }
                    PLAYLISTS_ID -> {
                        playlistDao.getAllVisible().first().map { playlist ->
                            MediaItem.Builder()
                                .setMediaId("$PLAYLIST_PREFIX${playlist.id}")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(playlist.name)
                                        .setSubtitle("${playlist.trackCount} tracks")
                                        .setArtworkUri(playlist.artUrl?.toUri())
                                        .setIsBrowsable(true)
                                        .setIsPlayable(false)
                                        .build(),
                                )
                                .build()
                        }
                    }
                    RECENTLY_ADDED_ID -> {
                        trackDao.getRecentlyAdded(20).first().map { track ->
                            MediaItem.Builder()
                                .setMediaId(track.id.toString())
                                .setUri(track.filePath ?: "")
                                .setMediaMetadata(
                                    track.toMediaMetadata(),
                                )
                                .build()
                        }
                    }
                    else -> {
                        if (parentId.startsWith(PLAYLIST_PREFIX)) {
                            val playlistId = parentId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
                            if (playlistId != null) {
                                val shuffleItem = MediaItem.Builder()
                                    .setMediaId("$SHUFFLE_PLAY_PREFIX$playlistId")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(getString(R.string.shuffle_play))
                                            .setArtworkUri("android.resource://$packageName/drawable/ic_shuffle".toUri())
                                            .setIsBrowsable(false)
                                            .setIsPlayable(true)
                                            .build(),
                                    )
                                    .build()

                                val tracks = playlistDao.getTracksForPlaylist(playlistId).filter{track -> track.isDownloaded}.map { track ->
                                    MediaItem.Builder()
                                        .setMediaId(track.id.toString())
                                        .setUri(track.filePath ?: "")
                                        .setMediaMetadata(
                                            track.toMediaMetadata(),
                                        )
                                        .build()
                                }
                                listOf(shuffleItem) + tracks
                            } else emptyList()
                        } else emptyList()
                    }
                }
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            session.notifySearchResultChanged(browser, query, 0, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        @OptIn(UnstableApi::class)
        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return serviceScope.future {
                // Sanitize for FTS (append * to each term for prefix matching)
                val sanitized = query.split(" ")
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { "$it*" }

                val tracks = trackDao.searchDownloaded(sanitized).first()
                val items = tracks.map { track ->
                    MediaItem.Builder()
                        .setMediaId(track.id.toString())
                        .setUri(track.filePath ?: "")
                        .setMediaMetadata(
                            track.toMediaMetadata(),
                        )
                        .build()
                }
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            }
        }

        @OptIn(UnstableApi::class)
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val customCommands = listOf(
                SessionCommand(COMMAND_TOGGLE_SHUFFLE, /* extras = */ android.os.Bundle.EMPTY),
                SessionCommand(COMMAND_CYCLE_REPEAT, /* extras = */ android.os.Bundle.EMPTY),
                SessionCommand(COMMAND_TOGGLE_LIKE, /* extras = */ android.os.Bundle.EMPTY),
            )
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            customCommands.forEach { sessionCommands.add(it) }

            //Android auto commands
            sessionCommands.add(SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT)
            sessionCommands.add(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN)
            sessionCommands.add(SessionCommand.COMMAND_CODE_LIBRARY_GET_ITEM)
            sessionCommands.add(SessionCommand.COMMAND_CODE_LIBRARY_SUBSCRIBE)
            sessionCommands.add(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH)

            // Default availablePlayerCommands omits COMMAND_CHANGE_MEDIA_ITEMS,
            // which is what addMediaItem / removeMediaItem / moveMediaItem
            // require. Without explicitly granting full player commands here,
            // controller.addMediaItem(...) silently no-ops — the item never
            // reaches the underlying ExoPlayer's timeline. This is what made
            // "Play Next" and "Add to Queue" appear broken when a queue
            // already existed.
            //
            // Granting all commands is safe: this MediaSession is internal-
            // only (no third-party controllers connect to it).
            val playerCommands = Player.Commands.Builder().addAllCommands().build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands.build())
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                COMMAND_TOGGLE_SHUFFLE -> {
                    val player = session.player
                    player.shuffleModeEnabled = !player.shuffleModeEnabled
                }
                COMMAND_CYCLE_REPEAT -> {
                    val player = session.player
                    player.repeatMode = when (player.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }
                COMMAND_TOGGLE_LIKE -> {
                    val trackId = session.player.currentMediaItem?.mediaId?.toLongOrNull()
                    if (trackId != null) {
                        // Optimistic update: toggle the local state and push the layout
                        // immediately so the UI feels snappy and avoids race conditions
                        // where multiple clicks see the same stale DB state.
                        lastIsLiked = !lastIsLiked
                        pushLayout(session, session.player, lastIsLiked)

                        serviceScope.launch {
                            if (lastIsLiked) {
                                stashLikedRepository.add(trackId)
                            } else {
                                stashLikedRepository.remove(trackId)
                            }
                        }
                    }
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        @OptIn(UnstableApi::class)
        override fun onPlaybackResumption(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            return serviceScope.future {
                val track = trackDao.getLastPlayedTrack()
                val item = if (track != null) {
                    MediaItem.Builder()
                        .setMediaId(track.id.toString())
                        .setUri(track.filePath ?: "")
                        .setMediaMetadata(track.toMediaMetadata())
                        .build()
                } else {
                    // Fallback to most recently added if no last-played record exists
                    trackDao.getRecentlyAdded(1).first().firstOrNull()?.let { recentlyAdded ->
                        MediaItem.Builder()
                            .setMediaId(recentlyAdded.id.toString())
                            .setUri(recentlyAdded.filePath ?: "")
                            .setMediaMetadata(recentlyAdded.toMediaMetadata())
                            .build()
                    }
                }

                if (item != null) {
                    MediaSession.MediaItemsWithStartPosition(
                        ImmutableList.of(item),
                        /* startIndex= */ 0,
                        /* startPositionMs= */ C.TIME_UNSET,
                    )
                } else {
                    throw UnsupportedOperationException()
                }
            }
        }
    }
}
