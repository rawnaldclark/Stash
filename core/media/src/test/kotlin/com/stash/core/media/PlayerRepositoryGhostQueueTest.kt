package com.stash.core.media

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.model.PlaybackSource
import com.stash.core.model.RepeatMode
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The queue sheet on the #462 ghost. After a cold start, or after the service idles out while
 * paused, the screen shows the last session but the player holds nothing. The sheet's tap, swipe
 * and drag reached only the controller's empty timeline, so a tap closed the sheet and nothing
 * played until the user pressed Play once.
 *
 * Pinned here: a tap rebuilds the saved queue the way play() does (shuffle, repeat and "Playing
 * from" included) but starts the tapped song from its beginning, and with shuffle on it is the song
 * on the tapped ROW: a ghost kept from a stopped service lists Media3's shuffle walk, while the saved
 * queue is in timeline order. A swipe or a drag changes what the ghost shows and what is saved, and
 * never starts playback. The resume plan is the real [PlaybackResumer] over an in-memory store, so
 * what an edit saves is exactly what the next rebuild reads.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryGhostQueueTest {

    private val source = PlaybackSource.Playlist(7L, "In Rainbows")
    private val titles = mapOf(1L to "15 Step", 2L to "Bodysnatchers", 3L to "Nude", 4L to "Weird Fishes")

    // Streamable rows: a "downloaded" row would be probed on disk, which the JVM cannot satisfy.
    private val library = titles.mapValues { (id, title) ->
        TrackEntity(
            id = id,
            title = title,
            artist = "Radiohead",
            album = "In Rainbows",
            durationMs = 240_000L,
            isDownloaded = false,
            isStreamable = true,
        )
    }

    /** PlaybackStateStore's DataStore, in memory: what the repository saves is what a resume plan reads back. */
    private var stored: SavedPlaybackState? = null
    private val blank = SavedPlaybackState(0L, 0L, 0, emptyList(), false, RepeatMode.OFF, PlaybackSource.Unknown)
    private val playbackStateStore: PlaybackStateStore = mockk {
        coEvery { getLastPlaybackState() } answers { stored }
        coEvery { savePosition(any(), any(), any()) } answers {
            stored = (stored ?: blank).copy(trackId = firstArg(), positionMs = secondArg(), queueIndex = thirdArg())
        }
        coEvery { saveQueue(any(), any(), any(), any()) } answers {
            stored = (stored ?: blank).copy(
                queueTrackIds = firstArg(),
                isShuffled = secondArg(),
                repeatMode = thirdArg(),
                source = arg(3),
            )
        }
    }
    private val trackDao: TrackDao = mockk(relaxed = true) {
        coEvery { getByIds(any()) } answers { firstArg<List<Long>>().mapNotNull { library[it] } }
    }
    private val trackDeletions = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    private val musicRepository: MusicRepository = mockk {
        every { this@mockk.trackDeletions } returns this@PlayerRepositoryGhostQueueTest.trackDeletions
    }
    private val streamUrlCache: StreamUrlCache =
        mockk<StreamUrlCache>(relaxUnitFun = true).also { every { it.get(any()) } returns null }
    private val controller: MediaController = mockk(relaxed = true) { every { isConnected } returns true }
    private val trackIdentityEvents: TrackIdentityEvents = mockk { every { changes } returns MutableSharedFlow() }

    /** Timeline slot i holds track i+1; with shuffle on, Media3's walk is slots 2 → 0 → 3 → 1 (Nude first). */
    private val walk = listOf(2, 0, 3, 1)

    /** A four-window timeline whose shuffle order is [walk]. Only the index methods matter here. */
    private val timeline = object : Timeline() {
        override fun getWindowCount() = 4
        override fun getPeriodCount() = 4
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
            window.set(windowIndex, null, null, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, false, false, null, 0L, C.TIME_UNSET, windowIndex, windowIndex, 0L)
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
            period.set(periodIndex, periodIndex, periodIndex, C.TIME_UNSET, 0L)
        override fun getIndexOfPeriod(uid: Any): Int = (uid as? Int) ?: C.INDEX_UNSET
        override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex
        override fun getFirstWindowIndex(shuffleModeEnabled: Boolean): Int = if (shuffleModeEnabled) walk.first() else 0
        override fun getLastWindowIndex(shuffleModeEnabled: Boolean): Int = if (shuffleModeEnabled) walk.last() else 3
        override fun getNextWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int {
            if (!shuffleModeEnabled) return if (windowIndex >= 3) C.INDEX_UNSET else windowIndex + 1
            val pos = walk.indexOf(windowIndex)
            return if (pos < 0 || pos == walk.lastIndex) C.INDEX_UNSET else walk[pos + 1]
        }
    }

    private fun item(id: Long): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri("https://example.test/$id")
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(titles.getValue(id)).setArtist("Radiohead")
                .setExtras(Bundle().apply { putLong(EXTRA_TRACK_ID, id) }).build(),
        )
        .build()

    private fun ids(items: List<MediaItem>): List<Long> = items.map { it.mediaId.toLong() }

    /** The controller holds tracks 1-4 in timeline order with [current] playing. */
    private fun holding(current: Long) {
        every { controller.mediaItemCount } returns 4
        for (i in 0 until 4) every { controller.getMediaItemAt(i) } returns item(i + 1L)
        every { controller.currentMediaItem } returns item(current)
        every { controller.currentMediaItemIndex } returns (current - 1).toInt()
    }

    private fun build(together: ListenTogetherController? = null): PlayerRepositoryImpl {
        val repo = PlayerRepositoryImpl(
            context = ApplicationProvider.getApplicationContext(),
            playbackStateStore = playbackStateStore,
            musicRepository = musicRepository,
            streamingPreference = mockk(relaxed = true),
            streamResolver = mockk(),
            streamUrlCache = streamUrlCache,
            connectivity = mockk(relaxed = true),
            trackDao = trackDao,
            playbackResumer = PlaybackResumer(playbackStateStore, trackDao),
            radioGenerator = mockk(relaxed = true),
            trackIdentityEvents = trackIdentityEvents,
            playbackSessionBus = PlaybackSessionBus(),
            listenTogether = together,
        )
        repo.controllerDeferred = controller
        shadowOf(Looper.getMainLooper()).idle() // init: connect, then seed the cold-start ghost if a session was saved
        // The session bus's initial "not alive" released the seam: re-seat it, or the next
        // ensureController() would build a real controller against Robolectric's binder.
        repo.controllerDeferred = controller
        return repo
    }

    /** Process death, then a cold start: the player is empty and the saved session shows as a paused ghost. */
    private fun coldStartGhost(shuffled: Boolean = false, together: ListenTogetherController? = null): PlayerRepositoryImpl {
        stored = SavedPlaybackState(
            trackId = 2L,
            positionMs = 44_000L,
            queueIndex = 1,
            queueTrackIds = listOf(1L, 2L, 3L, 4L),
            isShuffled = shuffled,
            repeatMode = RepeatMode.ALL,
            source = source,
        )
        every { controller.mediaItemCount } returns 0
        every { controller.currentMediaItem } returns null
        val repo = build(together)
        assertThat(repo.playerState.value.currentTrack?.id).isEqualTo(2L) // Bodysnatchers, paused at 0:44
        clearMocks(controller, playbackStateStore, answers = false)
        return repo
    }

    /**
     * Playing tracks 1-4 from the playlist, paused 44 s into Nude (track 3); then the service idles
     * out. The controller is released, the last state stays on screen as the ghost, and the app's
     * foreground reconnect finds the new service empty.
     */
    private suspend fun serviceStopGhost(shuffled: Boolean): PlayerRepositoryImpl {
        every { controller.mediaItemCount } returns 0
        val repo = build() // nothing saved yet: no cold-start ghost
        repo.setQueue((1L..4L).map { library.getValue(it).toDomain() }, startIndex = 2, source = source)
        holding(current = 3L)
        every { controller.currentTimeline } returns timeline
        every { controller.shuffleModeEnabled } returns shuffled
        every { controller.repeatMode } returns Player.REPEAT_MODE_ALL
        every { controller.currentPosition } returns 44_000L
        every { controller.nextMediaItemIndex } returns C.INDEX_UNSET // no next-up prefetch to resolve
        repo.updateState(controller)
        shadowOf(Looper.getMainLooper()).idle() // the queue and position saves land
        repo.onSessionAliveChanged(false) // the idle-stop
        every { controller.mediaItemCount } returns 0
        every { controller.currentMediaItem } returns null
        repo.controllerDeferred = controller // the foreground reconnect: a new, empty service
        clearMocks(controller, playbackStateStore, answers = false)
        return repo
    }

    private fun verifyNothingLoaded() {
        verify(exactly = 0) { controller.setMediaItems(any<List<MediaItem>>(), any<Int>(), any<Long>()) }
        verify(exactly = 0) { controller.prepare() }
        verify(exactly = 0) { controller.play() }
    }

    @Test
    fun `a tap on the cold-start ghost's queue plays that song from its start`() = runTest {
        val repo = coldStartGhost()

        repo.skipToQueueIndex(3) // Weird Fishes, while the ghost is paused 44 s into Bodysnatchers

        verifyOrder {
            controller.shuffleModeEnabled = false
            controller.repeatMode = Player.REPEAT_MODE_ALL
            controller.setMediaItems(match<List<MediaItem>> { ids(it) == listOf(1L, 2L, 3L, 4L) }, 3, 0L)
            controller.prepare()
            controller.play()
        }
        // The rebuilt queue still says where it is playing from.
        holding(current = 4L)
        repo.updateState(controller)
        assertThat(repo.playerState.value.currentTrack?.id).isEqualTo(4L)
        assertThat(repo.playerState.value.source).isEqualTo(source)
    }

    @Test
    fun `with shuffle on, a tap on the cold-start ghost plays the tapped song with shuffle kept`() = runTest {
        val repo = coldStartGhost(shuffled = true)
        // Nothing saves the shuffle walk, so a cold-start ghost lists the saved (timeline) order.
        assertThat(repo.playerState.value.queue.map { it.id }).containsExactly(1L, 2L, 3L, 4L).inOrder()

        repo.skipToQueueIndex(2) // Nude

        verifyOrder {
            controller.shuffleModeEnabled = true
            controller.setMediaItems(match<List<MediaItem>> { ids(it) == listOf(1L, 2L, 3L, 4L) }, 2, 0L)
            controller.play()
        }
    }

    @Test
    fun `a tap on a stopped service's ghost plays that song from its start`() = runTest {
        val repo = serviceStopGhost(shuffled = false)
        assertThat(repo.playerState.value.currentTrack?.id).isEqualTo(3L)

        repo.skipToQueueIndex(3) // Weird Fishes

        verifyOrder {
            controller.shuffleModeEnabled = false
            controller.repeatMode = Player.REPEAT_MODE_ALL
            controller.setMediaItems(match<List<MediaItem>> { ids(it) == listOf(1L, 2L, 3L, 4L) }, 3, 0L)
            controller.prepare()
            controller.play()
        }
    }

    @Test
    fun `with shuffle on, a tap on a stopped service's ghost plays the song on the tapped row`() = runTest {
        val repo = serviceStopGhost(shuffled = true)
        // The ghost keeps the shuffle walk the sheet showed (#468); the saved queue is in timeline order.
        assertThat(repo.playerState.value.queue.map { it.id }).containsExactly(3L, 1L, 4L, 2L).inOrder()
        assertThat(stored?.queueTrackIds).containsExactly(1L, 2L, 3L, 4L).inOrder()

        repo.skipToQueueIndex(3) // Bodysnatchers: timeline slot 1, not slot 3

        verifyOrder {
            controller.shuffleModeEnabled = true
            controller.setMediaItems(match<List<MediaItem>> { ids(it) == listOf(1L, 2L, 3L, 4L) }, 1, 0L)
            controller.play()
        }
    }

    @Test
    fun `a swipe on the ghost's queue drops the row from the screen and the saved queue, and plays nothing`() = runTest {
        val repo = coldStartGhost()

        repo.removeFromQueue(2) // Nude

        val state = repo.playerState.value
        assertThat(state.queue.map { it.id }).containsExactly(1L, 2L, 4L).inOrder()
        assertThat(state.currentIndex).isEqualTo(1)
        assertThat(state.currentTrack?.id).isEqualTo(2L)
        assertThat(state.positionMs).isEqualTo(44_000L)
        assertThat(stored?.queueTrackIds).containsExactly(1L, 2L, 4L).inOrder()
        assertThat(stored?.repeatMode).isEqualTo(RepeatMode.ALL)
        assertThat(stored?.source).isEqualTo(source)
        verifyNothingLoaded()
        verify(exactly = 0) { controller.removeMediaItem(any()) }
    }

    @Test
    fun `a song swiped off the ghost's queue stays off when a tap rebuilds it`() = runTest {
        val repo = coldStartGhost()

        repo.removeFromQueue(2) // Nude
        repo.skipToQueueIndex(2) // the row that is now third: Weird Fishes

        verify { controller.setMediaItems(match<List<MediaItem>> { ids(it) == listOf(1L, 2L, 4L) }, 2, 0L) }
    }

    @Test
    fun `with shuffle on, a swipe on a stopped service's ghost drops that song from the saved queue`() = runTest {
        val repo = serviceStopGhost(shuffled = true)

        repo.removeFromQueue(2) // Weird Fishes, third in the walk

        assertThat(repo.playerState.value.queue.map { it.id }).containsExactly(3L, 1L, 2L).inOrder()
        assertThat(repo.playerState.value.currentIndex).isEqualTo(0)
        assertThat(stored?.queueTrackIds).containsExactly(1L, 2L, 3L).inOrder() // still timeline order
        assertThat(stored?.isShuffled).isTrue()
        verifyNothingLoaded()
        verify(exactly = 0) { controller.removeMediaItem(any()) }
    }

    @Test
    fun `a drag on the ghost's queue reorders the rows and the saved queue, and plays nothing`() = runTest {
        val repo = coldStartGhost()

        repo.moveInQueue(3, 2) // Weird Fishes up above Nude

        assertThat(repo.playerState.value.queue.map { it.id }).containsExactly(1L, 2L, 4L, 3L).inOrder()
        assertThat(repo.playerState.value.currentIndex).isEqualTo(1)
        assertThat(stored?.queueTrackIds).containsExactly(1L, 2L, 4L, 3L).inOrder()
        verifyNothingLoaded()
        verify(exactly = 0) { controller.moveMediaItem(any(), any()) }
    }

    @Test
    fun `under shuffle a drag on the ghost's queue moves nothing, like the live queue's`() = runTest {
        val repo = coldStartGhost(shuffled = true)
        val before = repo.playerState.value

        repo.moveInQueue(3, 2)

        assertThat(repo.playerState.value).isEqualTo(before)
        assertThat(stored?.queueTrackIds).containsExactly(1L, 2L, 3L, 4L).inOrder()
    }

    @Test
    fun `a song deleted from the library leaves the ghost's queue`() = runTest {
        val repo = coldStartGhost()

        assertThat(trackDeletions.tryEmit(1L)).isTrue() // 15 Step, the row above the paused song
        shadowOf(Looper.getMainLooper()).idle()

        val state = repo.playerState.value
        assertThat(state.queue.map { it.id }).containsExactly(2L, 3L, 4L).inOrder()
        assertThat(state.currentIndex).isEqualTo(0)
        assertThat(state.currentTrack?.id).isEqualTo(2L)
    }

    @Test
    fun `during a Listen Together session a tap on the ghost leaves the session's player alone`() = runTest {
        val together = ListenTogetherController(ApplicationProvider.getApplicationContext())
        val repo = coldStartGhost(together = together)
        together.setActive(true)

        repo.skipToQueueIndex(3)

        verifyNothingLoaded()
    }

    @Test
    fun `once the player holds the queue, a tap seeks in it and rebuilds nothing`() = runTest {
        val repo = coldStartGhost()
        holding(current = 2L)
        repo.updateState(controller) // the first refresh with real items retires the ghost

        repo.skipToQueueIndex(3)

        verify(exactly = 1) { controller.seekToDefaultPosition(3) }
        verify(exactly = 0) { controller.setMediaItems(any<List<MediaItem>>(), any<Int>(), any<Long>()) }
    }
}
