package com.stash.core.media

import android.os.Bundle
import android.os.Looper
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
import com.stash.core.data.prefs.AutoplayRadioPreference
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.radio.RadioSeed
import com.stash.core.data.radio.RadioSession
import com.stash.core.data.radio.RadioStationGenerator
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.media.service.StashPlaybackService
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Autoplay radio adds a station AFTER the last song and never takes a song away.
 *
 * Pixel 5, 2026-11-13 19:45: Next onto the last song (the station starts building), Previous one second later. The
 * station landed spliced around the song playing THEN: everything after it went, the last song (the seed) included.
 *
 * These run the real watcher: [PlayerRepositoryImpl.updateState] publishes the state, the autoplay collector sees the
 * last song playing and builds the station, and a stateful fake player stands behind the MediaController mock. The
 * generator's answer is where the user acts "while the station is being built".
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryAutoplayRadioTest {

    private val playbackStateStore: PlaybackStateStore = mockk(relaxed = true)
    private val musicRepository: MusicRepository = mockk(relaxed = true) {
        every { trackDeletions } returns MutableSharedFlow()
    }
    private val streamingPreference: StreamingPreference = mockk(relaxed = true) { coEvery { current() } returns true }
    private val streamResolver: StreamSourceRegistry = mockk(relaxed = true)
    private val streamUrlCache: StreamUrlCache = mockk<StreamUrlCache>(relaxUnitFun = true).also { every { it.get(any()) } returns null }
    private val connectivity: ConnectivityMonitor = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val controller: MediaController = mockk(relaxed = true)
    private val radioGenerator: RadioStationGenerator = mockk()
    private val trackIdentityEvents: TrackIdentityEvents = mockk { every { changes } returns MutableSharedFlow() }

    private val autoplayOn = object : AutoplayRadioPreference {
        override val enabled = flowOf(true)
        override suspend fun setEnabled(value: Boolean) = Unit
    }

    private lateinit var repo: PlayerRepositoryImpl
    private val fake = FakePlayer()

    private fun track(id: Long) = Track(id = id, title = "T$id", artist = "A", isDownloaded = false, isStreamable = true)

    /**
     * What the generator hands back for a song radio: the seed itself as a fresh search found it (another id, same
     * title and artist), then the songs it leads to.
     */
    private fun station(seed: Long, vararg ids: Long) = listOf(track(seed).copy(id = 9_000 + seed)) + ids.map(::track)

    /** The player behind the controller: its items, the playing slot, shuffle (with its play order) and repeat. */
    private inner class FakePlayer {
        val items = mutableListOf<MediaItem>()
        var cur = 0
        var shuffle = false
        var repeat = Player.REPEAT_MODE_OFF

        /**
         * Under shuffle, the timeline slots in play order. Media3's DefaultShuffleOrder gives an added item a random
         * spot in it; here added items go FIRST, before the playing song (the worst spot: they would never play),
         * unless the repository sends the order it wants (COMMAND_SET_SHUFFLE_ORDER).
         */
        var order = mutableListOf<Int>()

        fun ids() = items.map { it.mediaId.toLong() }
        fun playOrderIds() = (if (shuffle) order else items.indices.toList()).map { items[it].mediaId.toLong() }

        fun timeline(): Timeline = object : Timeline() {
            val n = items.size
            val seq = if (shuffle) order.toList() else (0 until n).toList()
            fun walk(on: Boolean) = if (on) seq else (0 until n).toList()
            override fun getWindowCount() = n
            override fun getPeriodCount() = n
            override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
                window.set(windowIndex, null, null, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, false, false, null, 0L, C.TIME_UNSET, windowIndex, windowIndex, 0L)
            override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
                period.set(periodIndex, periodIndex, periodIndex, C.TIME_UNSET, 0L)
            override fun getIndexOfPeriod(uid: Any): Int = (uid as? Int) ?: C.INDEX_UNSET
            override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex
            override fun getFirstWindowIndex(shuffleModeEnabled: Boolean) = walk(shuffleModeEnabled).firstOrNull() ?: C.INDEX_UNSET
            override fun getLastWindowIndex(shuffleModeEnabled: Boolean) = walk(shuffleModeEnabled).lastOrNull() ?: C.INDEX_UNSET
            override fun getNextWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int {
                val w = walk(shuffleModeEnabled)
                val at = w.indexOf(windowIndex)
                return when {
                    at in 0 until w.size - 1 -> w[at + 1]
                    repeatMode == Player.REPEAT_MODE_ALL && w.isNotEmpty() -> w.first()
                    else -> C.INDEX_UNSET
                }
            }
            override fun getPreviousWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int {
                val w = walk(shuffleModeEnabled)
                val at = w.indexOf(windowIndex)
                return when {
                    at > 0 -> w[at - 1]
                    repeatMode == Player.REPEAT_MODE_ALL && w.isNotEmpty() -> w.last()
                    else -> C.INDEX_UNSET
                }
            }
        }

        /** Takes timeline slots [from] until [to] out of the play order, the later slots moving down. */
        private fun dropFromOrder(from: Int, to: Int) {
            order = order.filterNot { it in from until to }.map { if (it >= to) it - (to - from) else it }.toMutableList()
        }

        fun install() {
            every { controller.isConnected } returns true
            every { controller.isPlaying } returns true
            every { controller.playWhenReady } returns true
            every { controller.playbackState } returns Player.STATE_READY
            every { controller.mediaItemCount } answers { items.size }
            every { controller.getMediaItemAt(any()) } answers { items[firstArg()] }
            every { controller.currentMediaItemIndex } answers { cur }
            every { controller.currentMediaItem } answers { items.getOrNull(cur) }
            every { controller.shuffleModeEnabled } answers { shuffle }
            every { controller.repeatMode } answers { repeat }
            every { controller.currentTimeline } answers { timeline() }
            every { controller.currentPosition } returns 1_000L
            // No look-ahead: these are about the queue, and the next song's prefetch would only add noise.
            every { controller.nextMediaItemIndex } returns C.INDEX_UNSET
            every { controller.setMediaItems(any<List<MediaItem>>(), any<Int>(), any<Long>()) } answers {
                items.clear(); items += firstArg<List<MediaItem>>(); cur = secondArg()
                order = items.indices.toMutableList()
            }
            every { controller.addMediaItems(any<List<MediaItem>>()) } answers {
                val at = items.size
                val list = firstArg<List<MediaItem>>()
                items += list
                order.addAll(0, (at until at + list.size).toList())
            }
            every { controller.addMediaItem(any<MediaItem>()) } answers {
                val at = items.size
                items += firstArg<MediaItem>()
                order.add(0, at)
            }
            every { controller.addMediaItems(any<Int>(), any<List<MediaItem>>()) } answers {
                val at = firstArg<Int>(); val list = secondArg<List<MediaItem>>()
                items.addAll(at, list)
                order = order.map { if (it >= at) it + list.size else it }.toMutableList()
                order.addAll(0, (at until at + list.size).toList())
                if (at <= cur) cur += list.size
            }
            every { controller.removeMediaItems(any(), any()) } answers {
                val from = firstArg<Int>(); val to = secondArg<Int>()
                repeat(to - from) { items.removeAt(from) }
                dropFromOrder(from, to)
                if (cur >= to) cur -= (to - from)
            }
            every { controller.removeMediaItem(any()) } answers {
                val at = firstArg<Int>()
                items.removeAt(at)
                dropFromOrder(at, at + 1)
                if (at < cur) cur--
            }
            every { controller.sendCustomCommand(any(), any()) } answers {
                if (firstArg<SessionCommand>().customAction == StashPlaybackService.COMMAND_SET_SHUFFLE_ORDER) {
                    order = secondArg<Bundle>().getIntArray(StashPlaybackService.EXTRA_SHUFFLE_ORDER)!!.toMutableList()
                }
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }
    }

    @Before
    fun setUp() {
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
            radioGenerator = radioGenerator,
            trackIdentityEvents = trackIdentityEvents,
            playbackSessionBus = PlaybackSessionBus().apply { onServiceCreated() },
            autoplayRadioPreference = autoplayOn,
        )
        fake.install()
        repo.controllerDeferred = controller
        idle()
        repo.controllerDeferred = controller
        // Once a station runs, its grower asks for more as the queue nears its end: none, unless a test says so.
        coEvery { radioGenerator.nextBatch(any()) } returns emptyList()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Stations the generator has handed back so far. The station lands in the same main-thread step. */
    @Volatile private var built = 0

    /**
     * A player event: the state is published and the watchers see it. The last song is playing, so autoplay builds a
     * station; this waits until it has landed (what the user does meanwhile may hop to IO and back).
     */
    private fun playerEvent() {
        val before = built
        repo.updateState(controller)
        awaitMain { built > before }
    }

    /** Runs the main looper until [done]: the repository's coroutines run there, and IO hops come back to it. */
    private fun awaitMain(done: () -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        repeat(500) {
            looper.idle()
            if (done()) return
            Thread.sleep(10)
        }
        assertThat(done()).isTrue()
    }

    /** The generator builds [songs] for the next song radio; [meanwhile] is what the user does while it does. */
    private fun stationBuilds(songs: List<Track>, meanwhile: suspend () -> Unit = {}) {
        coEvery { radioGenerator.start(any()) } coAnswers {
            meanwhile()
            built++
            mockk<RadioSession>(relaxed = true) to songs
        }
    }

    @Test
    fun `Previous while the station is built keeps the last song, and the station goes after it`() = runTest {
        // Nights, then Nights - Remastered 2016: Next onto the last song.
        repo.setQueue(listOf(track(1), track(2)), startIndex = 1)
        stationBuilds(station(2, 201, 202)) { fake.cur = 0 } // Previous, one second in

        playerEvent()

        assertThat(fake.ids()).containsExactly(1L, 2L, 201L, 202L).inOrder()
        assertThat(fake.cur).isEqualTo(0)
        assertThat(repo.currentQueueTracks.map { it.id }).containsExactly(1L, 2L, 201L, 202L).inOrder()
        assertThat(repo.radioSeedLabel.value).isEqualTo("T2")
    }

    @Test
    fun `the songs before the last one stay when the station is added`() = runTest {
        repo.setQueue(listOf(track(1), track(2), track(3)), startIndex = 2)
        stationBuilds(station(3, 201, 202))

        playerEvent()

        coVerify(exactly = 1) { radioGenerator.start(RadioSeed.Song("T3", "A", null)) }
        assertThat(fake.ids()).containsExactly(1L, 2L, 3L, 201L, 202L).inOrder()
        assertThat(fake.cur).isEqualTo(2)
        assertThat(repo.currentQueueTracks.map { it.id }).containsExactly(1L, 2L, 3L, 201L, 202L).inOrder()
    }

    @Test
    fun `a song queued while the station is built plays first, and the station follows it once it plays`() = runTest {
        repo.setQueue(listOf(track(1), track(2)), startIndex = 1)
        stationBuilds(station(2, 201, 202)) { repo.addToQueue(track(3)) }

        playerEvent()

        // The queue no longer ends with the seed: nothing is added, nothing goes.
        assertThat(fake.ids()).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(repo.radioSeedLabel.value).isNull()

        // The queued song starts: it is the last one now, and it gets its station.
        stationBuilds(station(3, 301, 302))
        fake.cur = 2
        playerEvent()

        assertThat(fake.ids()).containsExactly(1L, 2L, 3L, 301L, 302L).inOrder()
    }

    @Test
    fun `repeat switched on while the station is built adds none, and it comes once repeat is off again`() = runTest {
        repo.setQueue(listOf(track(1), track(2)), startIndex = 1)
        stationBuilds(station(2, 201, 202)) {
            fake.repeat = Player.REPEAT_MODE_ALL
            repo.updateState(controller) // the player reports the change before the station lands
        }

        playerEvent()

        assertThat(fake.ids()).containsExactly(1L, 2L).inOrder()
        assertThat(repo.radioSeedLabel.value).isNull()

        stationBuilds(station(2, 201, 202))
        fake.repeat = Player.REPEAT_MODE_OFF
        playerEvent()

        assertThat(fake.ids()).containsExactly(1L, 2L, 201L, 202L).inOrder()
    }

    @Test
    fun `Download mode switched on while the station is built adds none`() = runTest {
        repo.setQueue(listOf(track(1), track(2)), startIndex = 1)
        stationBuilds(station(2, 201, 202)) { coEvery { streamingPreference.current() } returns false }

        playerEvent()

        assertThat(fake.ids()).containsExactly(1L, 2L).inOrder()
        assertThat(repo.radioSeedLabel.value).isNull()
    }

    @Test
    fun `under shuffle the station plays after the last song of the play order`() = runTest {
        repo.setQueue(listOf(track(1), track(2)), startIndex = 0)
        fake.shuffle = true
        fake.order = mutableListOf(1, 0) // T2 played first; T1, playing now, is the last of the play order
        fake.cur = 0
        stationBuilds(station(1, 201, 202))

        playerEvent()

        assertThat(fake.playOrderIds()).containsExactly(2L, 1L, 201L, 202L).inOrder()
        assertThat(fake.ids()).containsExactly(1L, 2L, 201L, 202L).inOrder()
    }

    @Test
    fun `the station grows at the end of the play order under shuffle too`() = runTest {
        repo.setQueue(listOf(track(1), track(2)), startIndex = 0)
        fake.shuffle = true
        fake.order = mutableListOf(1, 0)
        fake.cur = 0
        stationBuilds(station(1, 201, 202))
        coEvery { radioGenerator.nextBatch(any()) } returns listOf(track(203), track(204))
        playerEvent()

        repo.growRadio()

        assertThat(fake.playOrderIds()).containsExactly(2L, 1L, 201L, 202L, 203L, 204L).inOrder()
    }

    @Test
    fun `at the end of a big queue the station joins its whole order`() = runTest {
        // More songs than the player holds: it plays a window of them, and the repository keeps the whole order.
        val library = (1L..(QUEUE_WINDOW_SIZE + 100).toLong()).map(::track)
        coEvery { trackDao.getByIds(any()) } answers {
            firstArg<List<Long>>().map { TrackEntity(id = it, title = "T$it", artist = "A", isStreamable = true) }
        }
        repo.setQueue(library, startIndex = library.lastIndex)
        assertThat(repo.queueWindow?.total).isEqualTo(QUEUE_WINDOW_SIZE + 100)
        val last = library.last().id
        stationBuilds(station(last, 7_001, 7_002))

        playerEvent()
        // The window is full, so the station's songs join the order first, and the player as the window slides.
        awaitMain { fake.ids().lastOrNull() == 7_002L }

        val window = repo.queueWindow!!
        assertThat(window.total).isEqualTo(QUEUE_WINDOW_SIZE + 102)
        assertThat(window.windowIds().toList()).isEqualTo(fake.ids())
        assertThat(fake.ids().takeLast(3)).containsExactly(last, 7_001L, 7_002L).inOrder()
        assertThat(fake.items[fake.cur].mediaId).isEqualTo(last.toString())
    }

    @Test
    fun `a song played again after its station gets a station again`() = runTest {
        repo.setQueue(listOf(track(1)), startIndex = 0)
        stationBuilds(station(1, 201, 202))
        playerEvent()
        assertThat(fake.ids()).containsExactly(1L, 201L, 202L).inOrder()

        // Later the same song, alone, from Search: a new queue, and its end is a new end.
        repo.setQueue(listOf(track(1)), startIndex = 0)
        stationBuilds(station(1, 211, 212))
        playerEvent()

        coVerify(exactly = 2) { radioGenerator.start(any()) }
        assertThat(fake.ids()).containsExactly(1L, 211L, 212L).inOrder()
    }
}
