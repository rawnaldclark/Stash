package com.stash.core.media

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.media.service.StashPlaybackService
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.model.PlayerState
import com.stash.core.model.RepeatMode
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * Big queues (a whole library: 72,593 songs in the crash reports): the player holds a window of
 * at most [QUEUE_WINDOW_SIZE] songs and the repository keeps the whole order as ids
 * ([QueueWindow]). A stateful fake player stands behind the MediaController mock, so the real
 * [PlayerRepositoryImpl] drives a timeline that behaves like Media3's.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryBigQueueTest {

    private val playbackStateStore: PlaybackStateStore = mockk(relaxed = true)
    private val deletions = MutableSharedFlow<Long>(extraBufferCapacity = 256)
    private val musicRepository: MusicRepository = mockk(relaxed = true) {
        every { trackDeletions } returns deletions
    }
    private val streamingPreference: StreamingPreference = mockk(relaxed = true) { coEvery { current() } returns true }
    private val streamResolver: StreamSourceRegistry = mockk(relaxed = true)
    private val streamUrlCache: StreamUrlCache = mockk(relaxUnitFun = true)
    private val connectivity: ConnectivityMonitor = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val controller: MediaController = mockk(relaxed = true)
    private val trackIdentityEvents: TrackIdentityEvents = mockk {
        every { changes } returns MutableSharedFlow()
    }

    private lateinit var repo: PlayerRepositoryImpl
    private val fake = FakePlayer()

    /** Every song the library has: the DAO answers by id, like Room. */
    private fun track(id: Long) = Track(id = id, title = "T$id", artist = "A", isDownloaded = false, isStreamable = true)
    private fun library(n: Int) = (1L..n.toLong()).map(::track)

    /** The player as the controller sees it: its items, the playing slot, shuffle and repeat. */
    private inner class FakePlayer {
        val items = mutableListOf<MediaItem>()
        var cur = 0
        var shuffle = false
        var repeat = Player.REPEAT_MODE_OFF
        var setMediaItemsCalls = 0
        val customCommands = mutableListOf<Pair<String, Bundle>>()

        /** The service's COMMAND_KEEP_TIMELINE_ORDER flag: a new service instance starts without it. */
        var keepsTimelineOrder = false

        /**
         * Whether the player walks its timeline as it is. Under shuffle only while the service keeps
         * the timeline order: otherwise Media3 walks its own random order over the window.
         */
        fun walksTimelineOrder() = !shuffle || keepsTimelineOrder

        /** The service idle-stopped and a new one came back: an empty player, no flag. */
        fun newService() {
            items.clear()
            cur = 0
            keepsTimelineOrder = false
        }

        fun ids() = items.map { it.mediaId.toLong() }
        fun currentId() = items[cur].mediaId.toLong()

        fun timeline(): Timeline = object : Timeline() {
            val n = items.size
            override fun getWindowCount() = n
            override fun getPeriodCount() = n
            override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
                window.set(windowIndex, null, null, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, false, false, null, 0L, C.TIME_UNSET, windowIndex, windowIndex, 0L)
            override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
                period.set(periodIndex, periodIndex, periodIndex, C.TIME_UNSET, 0L)
            override fun getIndexOfPeriod(uid: Any): Int = (uid as? Int) ?: C.INDEX_UNSET
            override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex
            override fun getNextWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int = when {
                windowIndex < n - 1 -> windowIndex + 1
                repeatMode == Player.REPEAT_MODE_ALL -> 0
                else -> C.INDEX_UNSET
            }
            override fun getPreviousWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int = when {
                windowIndex > 0 -> windowIndex - 1
                repeatMode == Player.REPEAT_MODE_ALL -> n - 1
                else -> C.INDEX_UNSET
            }
        }

        fun install() {
            every { controller.isConnected } returns true
            every { controller.mediaItemCount } answers { items.size }
            every { controller.getMediaItemAt(any()) } answers { items[firstArg()] }
            every { controller.currentMediaItemIndex } answers { cur }
            every { controller.currentMediaItem } answers { items.getOrNull(cur) }
            every { controller.shuffleModeEnabled } answers { shuffle }
            every { controller.shuffleModeEnabled = any() } answers { shuffle = firstArg() }
            every { controller.repeatMode } answers { repeat }
            every { controller.repeatMode = any() } answers { repeat = firstArg() }
            every { controller.currentTimeline } answers { timeline() }
            every { controller.currentPosition } returns 0L
            every { controller.hasNextMediaItem() } answers { cur < items.size - 1 || (repeat == Player.REPEAT_MODE_ALL && items.isNotEmpty()) }
            every { controller.hasPreviousMediaItem() } answers { cur > 0 || (repeat == Player.REPEAT_MODE_ALL && items.isNotEmpty()) }
            every { controller.nextMediaItemIndex } answers { timeline().getNextWindowIndex(cur, repeat, shuffle) }
            every { controller.previousMediaItemIndex } answers { timeline().getPreviousWindowIndex(cur, repeat, shuffle) }
            every { controller.seekToNextMediaItem() } answers { cur = timeline().getNextWindowIndex(cur, repeat, shuffle) }
            every { controller.seekToDefaultPosition(any<Int>()) } answers { cur = firstArg() }
            every { controller.setMediaItems(any<List<MediaItem>>(), any<Int>(), any<Long>()) } answers {
                setMediaItemsCalls++
                items.clear(); items += firstArg<List<MediaItem>>(); cur = secondArg()
            }
            every { controller.addMediaItems(any<List<MediaItem>>()) } answers { items += firstArg<List<MediaItem>>() }
            every { controller.addMediaItems(any<Int>(), any<List<MediaItem>>()) } answers {
                val at = firstArg<Int>(); val list = secondArg<List<MediaItem>>()
                items.addAll(at, list); if (at <= cur) cur += list.size
            }
            every { controller.addMediaItem(any<MediaItem>()) } answers { items += firstArg<MediaItem>() }
            every { controller.addMediaItem(any<Int>(), any<MediaItem>()) } answers {
                val at = firstArg<Int>(); items.add(at, secondArg()); if (at <= cur) cur++
            }
            every { controller.removeMediaItem(any()) } answers {
                val at = firstArg<Int>(); items.removeAt(at); if (at < cur) cur--
            }
            every { controller.removeMediaItems(any(), any()) } answers {
                val from = firstArg<Int>(); val to = secondArg<Int>()
                repeat(to - from) { items.removeAt(from) }
                if (cur >= to) cur -= (to - from)
            }
            every { controller.replaceMediaItems(any(), any(), any()) } answers {
                val from = firstArg<Int>(); val to = secondArg<Int>(); val list = thirdArg<List<MediaItem>>()
                repeat(to - from) { items.removeAt(from) }
                items.addAll(from, list)
                if (cur >= to) cur += list.size - (to - from)
            }
            every { controller.moveMediaItem(any(), any()) } answers {
                val from = firstArg<Int>(); val to = secondArg<Int>()
                val moved = items.removeAt(from); items.add(to, moved)
                cur = when {
                    cur == from -> to
                    from < cur && to >= cur -> cur - 1
                    from > cur && to <= cur -> cur + 1
                    else -> cur
                }
            }
            every { controller.sendCustomCommand(any(), any()) } answers {
                val action = firstArg<SessionCommand>().customAction
                customCommands += action to secondArg<Bundle>()
                if (action == StashPlaybackService.COMMAND_KEEP_TIMELINE_ORDER) {
                    keepsTimelineOrder = secondArg<Bundle>().getBoolean(StashPlaybackService.EXTRA_KEEP_TIMELINE_ORDER)
                }
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }
    }

    @Before
    fun setUp() {
        every { streamUrlCache.get(any()) } returns null
        repo = PlayerRepositoryImpl(
            context = ApplicationProvider.getApplicationContext(),
            playbackStateStore = playbackStateStore,
            musicRepository = musicRepository,
            streamingPreference = streamingPreference,
            streamResolver = streamResolver,
            streamUrlCache = streamUrlCache,
            connectivity = connectivity,
            trackDao = trackDao,
            playbackResumer = PlaybackResumer(playbackStateStore, trackDao),
            radioGenerator = mockk(relaxed = true),
            trackIdentityEvents = trackIdentityEvents,
            // The service is up, as it always is while a window exists (the bus starts at false: no service).
            playbackSessionBus = PlaybackSessionBus().apply { onServiceCreated() },
        )
        fake.install()
        repo.controllerDeferred = controller
        repo.windowRandom = Random(2026)
        // Room by id, in any order (as SQLite's IN).
        coEvery { trackDao.getByIds(any()) } answers {
            firstArg<List<Long>>().reversed().map { TrackEntity(id = it, title = "T$it", artist = "A", isStreamable = true) }
        }
    }

    @Test
    fun `a 100k-song library builds only the window, never the whole list`() = runTest {
        repo.setQueue(library(100_000), startIndex = 50_000)

        assertThat(fake.setMediaItemsCalls).isEqualTo(1)
        assertThat(fake.items).hasSize(QUEUE_WINDOW_SIZE)
        assertThat(fake.currentId()).isEqualTo(50_001L)
        assertThat(fake.cur).isEqualTo(QUEUE_WINDOW_BEHIND)
        assertThat(repo.queueWindow?.total).isEqualTo(100_000)
        assertThat(repo.currentQueueTracks).hasSize(QUEUE_WINDOW_SIZE)
        // The window keeps the order: 49,951 .. 50,450.
        assertThat(fake.ids()).isEqualTo((49_951L..50_450L).toList())
    }

    @Test
    fun `a queue the player can hold is built whole, as before`() = runTest {
        repo.setQueue(library(QUEUE_WINDOW_SIZE), startIndex = 499)

        assertThat(fake.items).hasSize(QUEUE_WINDOW_SIZE)
        assertThat(fake.cur).isEqualTo(499)
        assertThat(repo.queueWindow).isNull()
        assertThat(fake.customCommands.map { it.first }).doesNotContain(StashPlaybackService.COMMAND_KEEP_TIMELINE_ORDER)
    }

    @Test
    fun `the published state counts the whole queue and the saved queue is only the window`() = runTest {
        repo.setQueue(library(72_593), startIndex = 10_000)
        repo.updateState(controller)
        // The saves are launched on the main looper.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val state: PlayerState = repo.playerState.value
        assertThat(state.queue).hasSize(QUEUE_WINDOW_SIZE)
        assertThat(state.queueSongsBefore + state.currentIndex).isEqualTo(10_000)
        assertThat(state.queueSongsBefore + state.queue.size + state.queueSongsAfter).isEqualTo(72_593)
        coVerify(timeout = 2_000) { playbackStateStore.saveQueue(match { it.size == QUEUE_WINDOW_SIZE }, any(), any(), any()) }
        coVerify(exactly = 0) { playbackStateStore.saveQueue(match { it.size > QUEUE_WINDOW_SIZE }, any(), any(), any()) }
    }

    @Test
    fun `Next at the window's end brings the next songs in and goes on`() = runTest {
        repo.setQueue(library(2_000), startIndex = 0)
        fake.cur = QUEUE_WINDOW_SIZE - 1 // a tap on the last row of Up next: song 500

        repo.skipNext()

        assertThat(fake.currentId()).isEqualTo(501L)
        assertThat(fake.items.size).isAtMost(QUEUE_WINDOW_SIZE)
        assertThat(fake.ids()).isEqualTo(repo.queueWindow!!.windowIds().toList())
        assertThat(fake.ids().first()).isEqualTo(500L - QUEUE_WINDOW_BEHIND)
    }

    @Test
    fun `Previous at the window's start brings the songs before it in`() = runTest {
        repo.setQueue(library(2_000), startIndex = 1_000)
        fake.cur = 0 // song 951, the window's first

        repo.skipToPreviousSong()

        assertThat(fake.currentId()).isEqualTo(950L)
        assertThat(fake.items.size).isAtMost(QUEUE_WINDOW_SIZE)
        assertThat(fake.ids()).isEqualTo(repo.queueWindow!!.windowIds().toList())
    }

    @Test
    fun `playing on slides the window and lets the far back go`() = runTest {
        repo.setQueue(library(5_000), startIndex = 0)
        fake.cur = 460 // song 461: 39 left ahead

        repo.slideWindow()

        assertThat(fake.currentId()).isEqualTo(461L)
        assertThat(fake.cur).isEqualTo(QUEUE_WINDOW_BEHIND)
        assertThat(fake.items).hasSize(QUEUE_WINDOW_SIZE)
        assertThat(fake.ids()).isEqualTo((411L..910L).toList())
        assertThat(repo.currentQueueTracks.map { it.id }).isEqualTo(fake.ids())
    }

    @Test
    fun `repeat-all goes from the whole list's last song to its first`() = runTest {
        fake.repeat = Player.REPEAT_MODE_ALL
        repo.setQueue(library(1_000), startIndex = 999)
        assertThat(fake.currentId()).isEqualTo(1_000L)

        repo.slideWindow()
        repo.skipNext()

        assertThat(fake.currentId()).isEqualTo(1L)
        assertThat(fake.items.size).isAtMost(QUEUE_WINDOW_SIZE)
    }

    @Test
    fun `shuffle draws from the whole list and keeps the player's order as the play order`() = runTest {
        fake.shuffle = true
        repo.setQueue(library(10_000), startIndex = 4_999)

        assertThat(fake.cur).isEqualTo(0)
        assertThat(fake.currentId()).isEqualTo(5_000L)
        assertThat(fake.ids().toSet()).hasSize(QUEUE_WINDOW_SIZE)
        assertThat(fake.ids().count { it > 9_000 }).isGreaterThan(10)
        assertThat(fake.ids().count { it < 1_000 }).isGreaterThan(10)
        assertThat(fake.walksTimelineOrder()).isTrue()
    }

    /** Runs the main looper until [done] (the repository's own coroutines run there, IO hops come back to it). */
    private fun awaitMain(done: () -> Boolean) {
        val looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        repeat(500) {
            looper.idle()
            if (done()) return
            Thread.sleep(10)
        }
        assertThat(done()).isTrue()
    }

    @Test
    fun `after the service idle-stops, a new big queue asks the new service to keep the timeline order`() = runTest {
        repo.setQueue(library(2_000), startIndex = 0)
        repo.updateState(controller)
        assertThat(fake.keepsTimelineOrder).isTrue()
        assertThat(repo.playerState.value.queueSongsAfter).isGreaterThan(0)

        // Paused past the idle stop: the service stops and a new one, empty, comes back.
        repo.onSessionAliveChanged(false)
        fake.newService()
        repo.controllerDeferred = controller
        repo.onSessionAliveChanged(true)
        repo.updateState(controller) // a fresh connection's first sync

        // Another tap in Songs, then shuffle on: the new service must keep the window's order too.
        repo.setQueue(library(2_000), startIndex = 700)
        assertThat(fake.keepsTimelineOrder).isTrue()
        fake.shuffle = true
        repo.rewindowForShuffle()
        assertThat(fake.walksTimelineOrder()).isTrue()
        assertThat(fake.ids()).isEqualTo(repo.queueWindow!!.windowIds().toList())
    }

    @Test
    fun `a client still bound keeps the service through the idle stop, and the window plays on in order`() = runTest {
        repo.setQueue(library(2_000), startIndex = 0)
        repo.updateState(controller)
        val window = repo.queueWindow!!
        assertThat(fake.keepsTimelineOrder).isTrue()

        // performIdleStop: the service drops the request and stops itself, but Android Auto's
        // binding keeps it, and its player with the window.
        fake.keepsTimelineOrder = false
        repo.onSessionAliveChanged(false)
        assertThat(repo.queueWindow).isSameInstanceAs(window)
        assertThat(repo.playerState.value.queueSongsAfter).isGreaterThan(0) // the paused ghost still counts the rest

        // In the car: shuffle on, then play. The service announces itself again; the app reconnects.
        fake.shuffle = true
        repo.controllerDeferred = controller
        repo.onSessionAliveChanged(true)
        repo.updateState(controller) // a fresh connection's first sync

        assertThat(fake.keepsTimelineOrder).isTrue()
        awaitMain { repo.queueWindow?.isShuffled == true } // shuffled over all 2,000, not 500
        assertThat(repo.queueWindow!!.total).isEqualTo(2_000)
        assertThat(fake.walksTimelineOrder()).isTrue()
        assertThat(fake.ids()).isEqualTo(repo.queueWindow!!.windowIds().toList())
        repo.updateState(controller)
        assertThat(repo.playerState.value.queueSongsAfter).isGreaterThan(0)
    }

    @Test
    fun `a new service that another controller filled ends window mode, and the next queue shuffles as Media3 does`() = runTest {
        repo.setQueue(library(2_000), startIndex = 0)
        repo.updateState(controller)
        repo.onSessionAliveChanged(false)
        fake.newService()
        repo.controllerDeferred = controller
        repo.onSessionAliveChanged(true)
        repo.updateState(controller)
        assertThat(repo.queueWindow).isNotNull() // an empty new player: the ghost's restore may still take it back

        // A media button's resumption, or the car, fills the new player with another queue.
        fake.items += (1L..300L).map { MediaItem.Builder().setMediaId("${90_000 + it}").build() }
        repo.updateState(controller)

        assertThat(repo.queueWindow).isNull()
        val keep = fake.customCommands.last { it.first == StashPlaybackService.COMMAND_KEEP_TIMELINE_ORDER }
        assertThat(keep.second.getBoolean(StashPlaybackService.EXTRA_KEEP_TIMELINE_ORDER)).isFalse()
        assertThat(repo.playerState.value.queueSongsAfter).isEqualTo(0)

        // An ordinary queue afterwards: Media3 shuffles it its own way.
        fake.shuffle = true
        repo.setQueue(library(300), startIndex = 0)
        assertThat(fake.keepsTimelineOrder).isFalse()
    }

    @Test
    fun `after the service idle-stops, playing the ghost takes the whole order back`() = runTest {
        repo.setQueue(library(2_000), startIndex = 700)
        repo.updateState(controller)
        val window = repo.queueWindow!!
        // What the position saves left: the window, slot for slot.
        coEvery { playbackStateStore.getLastPlaybackState() } returns SavedPlaybackState(
            trackId = fake.currentId(), positionMs = 0L, queueIndex = fake.cur, queueTrackIds = fake.ids(),
            isShuffled = false, repeatMode = RepeatMode.OFF, source = com.stash.core.model.PlaybackSource.Library,
        )

        repo.onSessionAliveChanged(false)
        fake.newService()
        repo.controllerDeferred = controller
        repo.onSessionAliveChanged(true)
        repo.updateState(controller)
        assertThat(repo.playerState.value.queueSongsAfter).isGreaterThan(0) // the ghost still counts the rest

        assertThat(repo.loadRestoredQueue()).isTrue()

        assertThat(repo.queueWindow).isSameInstanceAs(window)
        assertThat(window.total).isEqualTo(2_000)
        assertThat(fake.ids()).isEqualTo(window.windowIds().toList())
        assertThat(fake.currentId()).isEqualTo(701L)
        assertThat(fake.keepsTimelineOrder).isTrue() // asked of the new service
        repo.updateState(controller)
        assertThat(repo.playerState.value.queuePosition).isEqualTo(700)
        assertThat(repo.playerState.value.queueSongsAfter).isEqualTo(2_000 - 1 - 700 - (fake.items.size - 1 - fake.cur))
    }

    @Test
    fun `a song deleted while a slide reads its songs leaves the order, and the slide still lines up`() = runTest {
        repo.setQueue(library(5_000), startIndex = 0)
        val window = repo.queueWindow!!
        var deletedDuringRead = false
        coEvery { trackDao.getByIds(any()) } answers {
            val ids = firstArg<List<Long>>()
            // The orphan sweep's eviction lands while the slide is on IO: in place, on the same window.
            if (!deletedDuringRead && 600L in ids) {
                deletedDuringRead = true
                window.removeIds(setOf(600L))
            }
            ids.reversed().map { TrackEntity(id = it, title = "T$it", artist = "A", isStreamable = true) }
        }
        fake.cur = 460 // near the window's end

        repo.slideWindow()

        assertThat(deletedDuringRead).isTrue()
        assertThat(repo.queueWindow).isSameInstanceAs(window)
        assertThat(window.total).isEqualTo(4_999)
        assertThat(fake.ids()).doesNotContain(600L)
        assertThat(fake.ids()).isEqualTo(window.windowIds().toList())
        assertThat(fake.ids().last()).isGreaterThan(600L)
    }

    /** What the position saves left: the window, slot for slot, as the restore will read it. */
    private fun saveTheWindow() {
        coEvery { playbackStateStore.getLastPlaybackState() } returns SavedPlaybackState(
            trackId = fake.currentId(), positionMs = 0L, queueIndex = fake.cur, queueTrackIds = fake.ids(),
            isShuffled = fake.shuffle, repeatMode = RepeatMode.OFF, source = com.stash.core.model.PlaybackSource.Library,
        )
    }

    /** The service idle-stopped and a new, empty one is connected: the window waits, unconfirmed. */
    private suspend fun stopAndReconnectToANewService() {
        repo.onSessionAliveChanged(false)
        fake.newService()
        repo.controllerDeferred = controller
        repo.onSessionAliveChanged(true)
        repo.updateState(controller)
    }

    private fun lastKeepRequest(): Boolean =
        fake.customCommands.last { it.first == StashPlaybackService.COMMAND_KEEP_TIMELINE_ORDER }
            .second.getBoolean(StashPlaybackService.EXTRA_KEEP_TIMELINE_ORDER)

    @Test
    fun `a queue the user starts while the window waits wins, even the same 500 songs`() = runTest {
        repo.setQueue(library(2_000), startIndex = 700)
        repo.updateState(controller)
        val same = repo.queueWindow!!.windowIds().map(::track)
        stopAndReconnectToANewService()

        // A playlist of exactly the window's songs: not a restore, so not the old window.
        repo.setQueue(same, startIndex = 50)

        assertThat(repo.queueWindow).isNull()
        assertThat(lastKeepRequest()).isFalse()
        assertThat(fake.ids()).isEqualTo(same.map { it.id })
        repo.updateState(controller)
        assertThat(repo.playerState.value.queueSongsAfter).isEqualTo(0)
    }

    @Test
    fun `a big queue the user starts while the window waits replaces it`() = runTest {
        repo.setQueue(library(2_000), startIndex = 700)
        repo.updateState(controller)
        val frozen = repo.queueWindow!!
        stopAndReconnectToANewService()

        repo.setQueue(library(3_000), startIndex = 5)

        assertThat(repo.queueWindow).isNotSameInstanceAs(frozen)
        assertThat(repo.queueWindow!!.total).isEqualTo(3_000)
        assertThat(fake.currentId()).isEqualTo(6L)
        assertThat(lastKeepRequest()).isTrue()
    }

    @Test
    fun `a restore that isn't the window ends window mode and tells the service`() = runTest {
        repo.setQueue(library(2_000), startIndex = 700)
        repo.updateState(controller)
        // The last save lagged: one song short of the window.
        coEvery { playbackStateStore.getLastPlaybackState() } returns SavedPlaybackState(
            trackId = fake.currentId(), positionMs = 0L, queueIndex = fake.cur, queueTrackIds = fake.ids().dropLast(1),
            isShuffled = false, repeatMode = RepeatMode.OFF, source = com.stash.core.model.PlaybackSource.Library,
        )
        stopAndReconnectToANewService()

        assertThat(repo.loadRestoredQueue()).isTrue()

        assertThat(repo.queueWindow).isNull()
        assertThat(lastKeepRequest()).isFalse()
        assertThat(fake.items).hasSize(QUEUE_WINDOW_SIZE - 1)
    }

    @Test
    fun `Add to queue on the waiting window's paused song takes the whole order back and goes after it`() = runTest {
        repo.setQueue(library(2_000), startIndex = 700)
        repo.updateState(controller)
        val window = repo.queueWindow!!
        saveTheWindow()
        stopAndReconnectToANewService()

        assertThat(repo.addToQueue(track(9_999L))).isTrue()

        assertThat(repo.queueWindow).isSameInstanceAs(window)
        assertThat(window.total).isEqualTo(2_001)
        assertThat(window.idAtPosition(2_000)).isEqualTo(9_999L) // after all 2,000, not after the 500
        assertThat(fake.ids()).doesNotContain(9_999L)
        assertThat(fake.ids()).isEqualTo(window.windowIds().toList())
        assertThat(fake.keepsTimelineOrder).isTrue()
    }

    @Test
    fun `Play next on the waiting window's paused song takes the whole order back and goes right after it`() = runTest {
        repo.setQueue(library(2_000), startIndex = 700)
        repo.updateState(controller)
        val window = repo.queueWindow!!
        saveTheWindow()
        stopAndReconnectToANewService()

        assertThat(repo.addNext(track(8_888L))).isTrue()

        assertThat(repo.queueWindow).isSameInstanceAs(window)
        assertThat(window.total).isEqualTo(2_001)
        assertThat(fake.currentId()).isEqualTo(701L)
        assertThat(fake.ids()[fake.cur + 1]).isEqualTo(8_888L)
        assertThat(fake.ids()).isEqualTo(window.windowIds().toList())
    }

    @Test
    fun `a song deleted while the window waits with no controller leaves it, and the restore still takes it back`() = runTest {
        repo.setQueue(library(2_000), startIndex = 700)
        repo.updateState(controller)
        val window = repo.queueWindow!!
        saveTheWindow() // with song 720 in it
        repo.onSessionAliveChanged(false) // no controller from here

        repo.evictFromQueue(setOf(720L))
        assertThat(window.total).isEqualTo(1_999)

        // Song 720 is gone from the library: the restore reads back the other 499.
        coEvery { trackDao.getByIds(any()) } answers {
            firstArg<List<Long>>().filter { it != 720L }.map { TrackEntity(id = it, title = "T$it", artist = "A", isStreamable = true) }
        }
        fake.newService()
        repo.controllerDeferred = controller
        repo.onSessionAliveChanged(true)
        repo.updateState(controller)
        assertThat(repo.loadRestoredQueue()).isTrue()

        assertThat(repo.queueWindow).isSameInstanceAs(window)
        assertThat(fake.ids()).doesNotContain(720L)
        assertThat(fake.ids()).isEqualTo(window.windowIds().toList())
        assertThat(fake.keepsTimelineOrder).isTrue()
    }

    @Test
    fun `a song deleted while the reshuffle reads its songs leaves the order, and the reshuffle redoes it`() = runTest {
        repo.setQueue(library(3_000), startIndex = 10)
        val window = repo.queueWindow!!
        var deletedDuringRead = false
        coEvery { trackDao.getByIds(any()) } answers {
            // The eviction lands while the reshuffle is on IO: in place, on the window it started from.
            if (!deletedDuringRead) {
                deletedDuringRead = true
                window.removeIds(setOf(2_500L))
            }
            firstArg<List<Long>>().map { TrackEntity(id = it, title = "T$it", artist = "A", isStreamable = true) }
        }
        fake.shuffle = true

        repo.rewindowForShuffle()

        assertThat(deletedDuringRead).isTrue()
        val shuffled = repo.queueWindow!!
        assertThat(shuffled.isShuffled).isTrue()
        assertThat(shuffled.total).isEqualTo(2_999)
        assertThat((0 until shuffled.total).none { shuffled.idAtPosition(it) == 2_500L }).isTrue()
        assertThat(fake.ids()).isEqualTo(shuffled.windowIds().toList())
    }

    @Test
    fun `a reshuffle that gave up is tried again with the next state`() = runTest {
        repo.setQueue(library(3_000), startIndex = 10)
        var playbackMoves = true
        coEvery { trackDao.getByIds(any()) } answers {
            if (playbackMoves) fake.cur += 1 // every read sees playback move on: each pass starts again
            firstArg<List<Long>>().map { TrackEntity(id = it, title = "T$it", artist = "A", isStreamable = true) }
        }
        fake.shuffle = true
        repo.rewindowForShuffle()
        assertThat(repo.queueWindow!!.isShuffled).isFalse() // it gave up

        playbackMoves = false
        repo.updateState(controller)

        awaitMain { repo.queueWindow?.isShuffled == true }
        assertThat(repo.queueWindow!!.total).isEqualTo(3_000)
        assertThat(fake.ids()).isEqualTo(repo.queueWindow!!.windowIds().toList())
        assertThat(fake.walksTimelineOrder()).isTrue()
    }

    @Test
    fun `a reshuffle asks again to keep the timeline order`() = runTest {
        repo.setQueue(library(3_000), startIndex = 10)
        fake.keepsTimelineOrder = false // the service lost it (another controller set a queue meanwhile)

        fake.shuffle = true
        repo.rewindowForShuffle()

        assertThat(fake.walksTimelineOrder()).isTrue()
    }

    @Test
    fun `Android Auto handing over exactly 500 other songs ends window mode`() = runTest {
        repo.setQueue(library(2_000), startIndex = 0)
        fake.items.clear()
        fake.items += (1L..QUEUE_WINDOW_SIZE.toLong()).map { MediaItem.Builder().setMediaId("${90_000 + it}").build() }
        fake.cur = 0

        repo.updateState(controller)
        // Not "and 1,500 more" for the car's list.
        assertThat(repo.playerState.value.queueSongsAfter).isEqualTo(0)
        assertThat(repo.playerState.value.queuePosition).isEqualTo(-1)
        awaitMain { repo.queueWindow == null }
    }

    @Test
    fun `shuffle turned on while the window was being built is applied once it is built`() = runTest {
        val tracks = library(5_000).toMutableList()
        tracks[100] = tracks[100].copy(isDownloaded = true, filePath = "/music/101.opus")
        // The tapped download's disk check runs during the build: the user turns shuffle on then.
        repo.filePathExistsOnDisk = { fake.shuffle = true; true }

        repo.setQueue(tracks, startIndex = 100)
        assertThat(repo.queueWindow!!.isShuffled).isFalse() // built before the toggle was seen

        awaitMain { repo.queueWindow?.isShuffled == true }
        assertThat(fake.currentId()).isEqualTo(101L)
        assertThat(fake.walksTimelineOrder()).isTrue()
    }

    @Test
    fun `a burst of library deletions leaves the whole order in one batch`() = runTest {
        repo.setQueue(library(5_000), startIndex = 0)
        val gone = (3_001L..3_200L).toList() + listOf(10L)
        awaitMain { deletions.subscriptionCount.value > 0 } // the repository is listening
        val w = repo.queueWindow!!
        val version = w.version
        val looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())

        // One id per row, a few ms apart, as the orphan sweep emits them between file deletes.
        gone.forEach {
            assertThat(deletions.tryEmit(it)).isTrue()
            looper.idleFor(java.time.Duration.ofMillis(4))
        }
        looper.idleFor(java.time.Duration.ofSeconds(3))

        assertThat(w.total).isEqualTo(5_000 - gone.size)
        assertThat(w.version - version).isEqualTo(1) // one pass over the whole order for the whole sweep
        assertThat((0 until w.total).map { w.idAtPosition(it) }).containsNoneIn(gone)
        assertThat(fake.ids()).doesNotContain(10L)
        assertThat(fake.ids()).isEqualTo(w.windowIds().toList())
    }

    @Test
    fun `after a repeat-all wrap the count is the playing song's place in the whole order`() = runTest {
        fake.repeat = Player.REPEAT_MODE_ALL
        repo.setQueue(library(1_000), startIndex = 999)
        repo.slideWindow() // the next lap's first songs join after song 1,000
        assertThat(repo.queueWindow!!.wrapped).isTrue()

        repo.updateState(controller)
        assertThat(repo.playerState.value.queuePosition).isEqualTo(999)
        repo.skipNext() // song 1, the whole list's first
        repo.updateState(controller)
        assertThat(fake.currentId()).isEqualTo(1L)
        assertThat(repo.playerState.value.queuePosition).isEqualTo(0)
    }

    @Test
    fun `shuffle on and off mid-queue keeps the playing song and reorders the whole list around it`() = runTest {
        repo.setQueue(library(3_000), startIndex = 1_500)
        assertThat(fake.currentId()).isEqualTo(1_501L)

        fake.shuffle = true
        repo.rewindowForShuffle()
        assertThat(fake.currentId()).isEqualTo(1_501L)
        assertThat(fake.cur).isEqualTo(0)
        assertThat(fake.items).hasSize(QUEUE_WINDOW_SIZE)
        assertThat(repo.queueWindow!!.isShuffled).isTrue()
        assertThat(fake.ids().any { it < 1_000 } && fake.ids().any { it > 2_100 }).isTrue()

        fake.shuffle = false
        repo.rewindowForShuffle()
        assertThat(fake.currentId()).isEqualTo(1_501L)
        assertThat(fake.ids()).isEqualTo((1_451L..1_950L).toList())
        assertThat(repo.queueWindow!!.isShuffled).isFalse()
    }

    @Test
    fun `Add to queue goes after the whole list, Play next right after the playing song`() = runTest {
        coEvery { musicRepository.ensureTrackPersisted(any()) } answers { firstArg<Track>().id }
        repo.setQueue(library(2_000), startIndex = 100)

        assertThat(repo.addToQueue(track(9_001L))).isTrue()
        val w = repo.queueWindow!!
        assertThat(w.total).isEqualTo(2_001)
        assertThat(w.idAtPosition(2_000)).isEqualTo(9_001L)
        assertThat(fake.ids()).doesNotContain(9_001L) // the window doesn't reach the end yet

        assertThat(repo.addNext(track(9_002L))).isTrue()
        assertThat(fake.items[fake.cur + 1].mediaId).isEqualTo("9002")
        assertThat(w.idAtSlot(fake.cur + 1)).isEqualTo(9_002L)
        assertThat(fake.ids()).isEqualTo(w.windowIds().toList())
    }

    @Test
    fun `removing and moving rows change the whole order too`() = runTest {
        repo.setQueue(library(2_000), startIndex = 0)

        repo.removeFromQueue(3) // song 4
        repo.moveInQueue(1, 5) // song 2 after song 7
        val w = repo.queueWindow!!
        assertThat(fake.ids()).isEqualTo(w.windowIds().toList())
        assertThat(fake.ids().take(7)).containsExactly(1L, 3L, 5L, 6L, 7L, 2L, 8L).inOrder()
        assertThat(w.total).isEqualTo(1_999)
        assertThat(repo.currentQueueTracks.map { it.id }).isEqualTo(fake.ids())
    }

    @Test
    fun `a queue replaced by another controller ends window mode`() = runTest {
        repo.setQueue(library(2_000), startIndex = 0)
        // Android Auto puts its own list in.
        fake.items.clear(); fake.items += (1L..20L).map { MediaItem.Builder().setMediaId("car$it").build() }; fake.cur = 0

        repo.slideWindow()

        assertThat(repo.queueWindow).isNull()
    }

    @Test
    fun `a song deleted from the library leaves the whole order`() = runTest {
        repo.setQueue(library(2_000), startIndex = 0)
        repo.evictFromQueue(setOf(10L, 1_500L))
        val w = repo.queueWindow!!
        assertThat(w.total).isEqualTo(1_998)
        assertThat((0 until w.total).map { w.idAtPosition(it) }).containsNoneOf(10L, 1_500L)
        assertThat(fake.ids()).doesNotContain(10L)
    }

    @Test
    fun `autoplay radio waits for the end of the whole queue, not the window`() {
        val track = track(1L)
        val atWindowEnd = PlayerState(
            currentTrack = track, isPlaying = true, queue = listOf(track), currentIndex = 0,
            repeatMode = RepeatMode.OFF, queueSongsAfter = 70_000,
        )
        assertThat(shouldAutoplayRadio(true, false, false, atWindowEnd, null)).isFalse()
        assertThat(shouldAutoplayRadio(true, false, false, atWindowEnd.copy(queueSongsAfter = 0), null)).isTrue()
    }

    @Test
    fun `a saved library-sized queue comes back as the window around its song`() {
        val saved = SavedPlaybackState(
            trackId = 60_001L, positionMs = 1_000L, queueIndex = 60_000,
            queueTrackIds = (1L..72_593L).toList(), isShuffled = false,
            repeatMode = RepeatMode.OFF, source = com.stash.core.model.PlaybackSource.Library,
        )
        val bounded = saved.boundedToWindow()
        assertThat(bounded.queueTrackIds).hasSize(QUEUE_WINDOW_SIZE)
        assertThat(bounded.queueTrackIds[bounded.queueIndex]).isEqualTo(60_001L)
        assertThat(bounded.queueIndex).isEqualTo(QUEUE_WINDOW_BEHIND)
        val small = saved.copy(queueTrackIds = (1L..300L).toList(), queueIndex = 10, trackId = 11L)
        assertThat(small.boundedToWindow()).isSameInstanceAs(small)
    }
}
