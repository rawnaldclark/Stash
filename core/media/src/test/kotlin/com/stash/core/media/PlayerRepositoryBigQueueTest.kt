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
    private val musicRepository: MusicRepository = mockk(relaxed = true) {
        every { trackDeletions } returns MutableSharedFlow()
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
                customCommands += firstArg<SessionCommand>().customAction to secondArg<Bundle>()
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
            playbackSessionBus = PlaybackSessionBus(),
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
        val keep = fake.customCommands.last { it.first == StashPlaybackService.COMMAND_KEEP_TIMELINE_ORDER }
        assertThat(keep.second.getBoolean(StashPlaybackService.EXTRA_KEEP_TIMELINE_ORDER)).isTrue()
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
