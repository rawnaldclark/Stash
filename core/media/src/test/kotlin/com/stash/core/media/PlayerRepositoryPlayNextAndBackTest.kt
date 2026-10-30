package com.stash.core.media

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.media.service.StashPlaybackService
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * PR #588: "Play next" queues first in, first out (shuffle on or off), Back under shuffle follows
 * what actually played, and shuffle off keeps Back exactly as before.
 *
 * A stateful fake player (timeline, shuffle walk, current index, position) stands behind the
 * MediaController mock and drives the real [PlayerRepositoryImpl]; its transitions reach
 * [PlayerRepositoryImpl.playerListener] the way the controller would report them.
 * COMMAND_PLAY_NEXT_SHUFFLED runs the service's own [PlayNext.insert] against that player, with
 * the service's guard (refused once its shuffle is off) and its result extras.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryPlayNextAndBackTest {

    private val playbackStateStore: PlaybackStateStore = mockk(relaxed = true)
    private val musicRepository: MusicRepository = mockk {
        every { trackDeletions } returns MutableSharedFlow()
    }
    private val streamingPreference: StreamingPreference = mockk(relaxed = true) { coEvery { current() } returns true }
    private val streamResolver: StreamSourceRegistry = mockk()
    private val streamUrlCache: StreamUrlCache = mockk(relaxUnitFun = true)
    private val connectivity: ConnectivityMonitor = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val controller: MediaController = mockk(relaxed = true)
    private val playbackResumer: PlaybackResumer = mockk(relaxed = true)
    private val trackIdentityEvents: TrackIdentityEvents = mockk {
        every { changes } returns MutableSharedFlow()
    }

    private lateinit var repo: PlayerRepositoryImpl
    private val fake = FakePlayer()

    private fun item(id: Long): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri("file:///music/$id.flac")
        .setMediaMetadata(
            MediaMetadata.Builder().setExtras(Bundle().apply { putLong(EXTRA_TRACK_ID, id) }).build(),
        )
        .build()

    // Streamable rows: a "downloaded" row would be probed on disk, which the JVM cannot satisfy.
    private fun track(id: Long) = Track(id = id, title = "T$id", artist = "A$id", isDownloaded = false, isStreamable = true)

    /** An [n]-window timeline whose shuffle order is [order]. Only the index methods matter here. */
    private fun snapshot(n: Int, order: List<Int>): Timeline = object : Timeline() {
        override fun getWindowCount() = n
        override fun getPeriodCount() = n
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
            window.set(windowIndex, null, null, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, false, false, null, 0L, C.TIME_UNSET, windowIndex, windowIndex, 0L)
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
            period.set(periodIndex, periodIndex, periodIndex, C.TIME_UNSET, 0L)
        override fun getIndexOfPeriod(uid: Any): Int = (uid as? Int) ?: C.INDEX_UNSET
        override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex
        private fun seq(shuffle: Boolean) = if (shuffle) order else (0 until n).toList()
        override fun getFirstWindowIndex(shuffleModeEnabled: Boolean): Int =
            if (n == 0) C.INDEX_UNSET else seq(shuffleModeEnabled).first()
        override fun getLastWindowIndex(shuffleModeEnabled: Boolean): Int =
            if (n == 0) C.INDEX_UNSET else seq(shuffleModeEnabled).last()
        override fun getNextWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int {
            val s = seq(shuffleModeEnabled)
            val p = s.indexOf(windowIndex)
            return if (p < 0 || p == s.lastIndex) C.INDEX_UNSET else s[p + 1]
        }
        override fun getPreviousWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int {
            val s = seq(shuffleModeEnabled)
            val p = s.indexOf(windowIndex)
            return if (p <= 0) C.INDEX_UNSET else s[p - 1]
        }
    }

    /** The session's player as the controller sees it. */
    private inner class FakePlayer {
        val items = mutableListOf<MediaItem>()
        var order = mutableListOf<Int>() // shuffle walk (timeline indexes in play order)
        var shuffle = false
        var cur = 0
        var pos = 0L

        /** The service can't take a Play next (its shuffle went off, a disconnect): it answers an error. */
        var refusePlayNext = false

        fun tl() = snapshot(items.size, order.toList())
        fun ids() = items.map { it.mediaId.toLong() }
        fun walkIds() = order.map { items[it].mediaId.toLong() }

        fun load(ids: List<Long>, start: Int = 0, walk: List<Int>? = null) {
            items.clear(); items += ids.map { item(it) }
            order = (walk ?: ids.indices.toList()).toMutableList()
            cur = start
        }

        private fun fireTimeline() =
            repo.playerListener.onTimelineChanged(tl(), Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)

        private fun fireTransition(reason: Int) =
            repo.playerListener.onMediaItemTransition(items.getOrNull(cur), reason)

        /** ExoPlayer-like insert; the new index goes to the walk's end (stand-in for cloneAndInsert's random slot). */
        fun insert(at: Int, item: MediaItem) {
            val wasEmpty = items.isEmpty()
            items.add(at, item)
            order = order.map { if (it >= at) it + 1 else it }.toMutableList().apply { add(at) }
            if (!wasEmpty && at <= cur) cur++
            fireTimeline()
            if (wasEmpty) { cur = 0; fireTransition(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) }
        }

        fun remove(i: Int) {
            items.removeAt(i)
            order = order.filter { it != i }.map { if (it > i) it - 1 else it }.toMutableList()
            if (i < cur) cur--
            fireTimeline()
        }

        fun seek(i: Int, reason: Int = Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
            val changed = i != cur
            cur = i
            pos = 0L
            if (changed) fireTransition(reason)
        }

        /** A song ends on its own. */
        fun autoAdvance() {
            val next = tl().getNextWindowIndex(cur, Player.REPEAT_MODE_OFF, shuffle)
            check(next != C.INDEX_UNSET)
            seek(next, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        }

        /** The notification's, a headset's or Android Auto's Previous: the session's native seekToPrevious. */
        fun nativePrevious() {
            val previous = tl().getPreviousWindowIndex(cur, Player.REPEAT_MODE_OFF, shuffle)
            check(previous != C.INDEX_UNSET)
            seek(previous)
        }

        /** Another controller (Android Auto onSetMediaItems, a resumption) replaces the queue. */
        fun replaceQueueExternally(ids: List<Long>, start: Int) {
            load(ids, start)
            fireTimeline()
            fireTransition(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        }

        fun custom(command: SessionCommand, args: Bundle): SessionResult {
            when (command.customAction) {
                StashPlaybackService.COMMAND_SET_SHUFFLE_ORDER -> {
                    val o = args.getIntArray(StashPlaybackService.EXTRA_SHUFFLE_ORDER)
                    if (o != null && o.size == items.size) order = o.toMutableList()
                    fireTimeline()
                }
                StashPlaybackService.COMMAND_PLAY_NEXT_SHUFFLED -> {
                    // The service's playNextShuffled: refused once its shuffle is off, else PlayNext.insert.
                    if (refusePlayNext || !shuffle) return SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE)
                    val bundle = args.getBundle(StashPlaybackService.EXTRA_PLAY_NEXT_ITEM)
                        ?: return SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)
                    val plan = PlayNext.insert(controller, MediaItem.fromBundle(bundle)) { newOrder ->
                        order = newOrder.toMutableList() // master.setShuffleOrder(...)
                        fireTimeline()
                    } ?: return SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE)
                    return SessionResult(SessionResult.RESULT_SUCCESS, PlayNext.toResultExtras(plan))
                }
            }
            return SessionResult(SessionResult.RESULT_SUCCESS)
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
            playbackResumer = playbackResumer,
            radioGenerator = mockk(relaxed = true),
            trackIdentityEvents = trackIdentityEvents,
            playbackSessionBus = PlaybackSessionBus(),
        )
        every { streamUrlCache.get(any()) } returns null
        every { connectivity.isConnected() } returns true
        every { controller.isConnected } returns true
        every { controller.mediaItemCount } answers { fake.items.size }
        every { controller.getMediaItemAt(any()) } answers { fake.items[firstArg()] }
        every { controller.currentMediaItem } answers { fake.items.getOrNull(fake.cur) }
        every { controller.currentMediaItemIndex } answers { fake.cur }
        every { controller.currentTimeline } answers { fake.tl() }
        every { controller.shuffleModeEnabled } answers { fake.shuffle }
        every { controller.currentPosition } answers { fake.pos }
        every { controller.repeatMode } returns Player.REPEAT_MODE_OFF
        every { controller.playbackState } returns Player.STATE_READY
        every { controller.playbackParameters } returns PlaybackParameters.DEFAULT
        every { controller.previousMediaItemIndex } answers {
            fake.tl().getPreviousWindowIndex(fake.cur, Player.REPEAT_MODE_OFF, fake.shuffle)
        }
        every { controller.hasPreviousMediaItem() } answers {
            fake.tl().getPreviousWindowIndex(fake.cur, Player.REPEAT_MODE_OFF, fake.shuffle) != C.INDEX_UNSET
        }
        every { controller.nextMediaItemIndex } answers {
            fake.tl().getNextWindowIndex(fake.cur, Player.REPEAT_MODE_OFF, fake.shuffle)
        }
        every { controller.hasNextMediaItem() } answers {
            fake.tl().getNextWindowIndex(fake.cur, Player.REPEAT_MODE_OFF, fake.shuffle) != C.INDEX_UNSET
        }
        every { controller.addMediaItem(any<Int>(), any<MediaItem>()) } answers { fake.insert(firstArg(), secondArg()) }
        every { controller.addMediaItem(any<MediaItem>()) } answers { fake.insert(fake.items.size, firstArg()) }
        every { controller.removeMediaItem(any()) } answers { fake.remove(firstArg()) }
        every { controller.seekToDefaultPosition(any<Int>()) } answers { fake.seek(firstArg()) }
        every { controller.seekTo(any<Long>()) } answers { fake.pos = firstArg() }
        every { controller.sendCustomCommand(any(), any()) } answers {
            Futures.immediateFuture(fake.custom(firstArg(), secondArg()))
        }
        repo.controllerDeferred = controller
        shadowOf(Looper.getMainLooper()).idle()
        repo.controllerDeferred = controller // the bus's initial "not alive" released the seam
    }

    private fun start(ids: List<Long>, at: Int = 0, shuffle: Boolean = false, walk: List<Int>? = null) {
        fake.load(ids, at, walk)
        fake.shuffle = shuffle
        repo.currentQueueTracks = ids.map { track(it) }
        repo.updateState(controller) // the repository learns the current song
    }

    private fun currentId() = fake.items[fake.cur].mediaId.toLong()
    private fun nextInTimeline() = fake.items[fake.cur + 1].mediaId.toLong()

    // ---------------------------------------------------------------- Play next: order

    @Test
    fun `shuffle on, two Play nexts play right after the current song in the order added`() = runTest {
        start(listOf(1, 2, 3, 4), at = 2, shuffle = true, walk = listOf(2, 0, 3, 1))
        repo.addNext(track(10))
        repo.addNext(track(11))
        assertThat(fake.walkIds().take(3)).containsExactly(3L, 10L, 11L).inOrder()
    }

    /**
     * The timeline must stay an in-order subsequence of the logical queue: two Play nexts under
     * shuffle, then shuffle off, and the sheet's next song is the one that plays.
     */
    @Test
    fun `shuffle on, two Play nexts, shuffle off - the sheet's next song is the one that plays`() = runTest {
        start(listOf(1, 2, 3, 4), at = 2, shuffle = true, walk = listOf(2, 0, 3, 1))
        repo.addNext(track(10))
        repo.addNext(track(11))

        fake.shuffle = false
        repo.updateState(controller)

        val state = repo.playerState.value
        assertThat(state.queue[state.currentIndex + 1].id).isEqualTo(nextInTimeline())
        assertThat(fake.ids()).containsExactly(1L, 2L, 3L, 10L, 11L, 4L).inOrder()
        assertThat(repo.currentQueueTracks.map { it.id }).containsExactly(1L, 2L, 3L, 10L, 11L, 4L).inOrder()
    }

    @Test
    fun `Play selected next under shuffle keeps the selection's order in the walk and the queue`() = runTest {
        start(listOf(1, 2, 3, 4, 5), at = 1, shuffle = true, walk = listOf(4, 1, 3, 0, 2))
        listOf(20L, 21L, 22L).forEach { repo.addNext(track(it)) }

        assertThat(fake.walkIds().take(5)).containsExactly(5L, 2L, 20L, 21L, 22L).inOrder()
        assertThat(fake.ids()).containsExactly(1L, 2L, 20L, 21L, 22L, 3L, 4L, 5L).inOrder()
        assertThat(repo.currentQueueTracks.map { it.id }).containsExactly(1L, 2L, 20L, 21L, 22L, 3L, 4L, 5L).inOrder()
    }

    @Test
    fun `shuffle off, two Play nexts, a jump far ahead, then Play next plays next`() = runTest {
        start((1L..30L).toList())
        repo.addNext(track(101))
        repo.addNext(track(102))
        repo.skipToQueueIndex(20)
        repo.addNext(track(103))
        assertThat(nextInTimeline()).isEqualTo(103L)
    }

    @Test
    fun `empty player, Play next, Add to queue, Play next - the second Play next plays next`() = runTest {
        repo.addNext(track(1))     // nothing playing: becomes the current song
        repo.addToQueue(track(2))  // appended
        repo.addNext(track(3))     // must play right after song 1
        assertThat(fake.ids()).containsExactly(1L, 3L, 2L).inOrder()
    }

    @Test
    fun `Play next of the playing song, it repeats, then Play next plays next`() = runTest {
        start(listOf(1, 2, 3))
        repo.addNext(track(1))   // [1, 1', 2, 3]
        fake.autoAdvance()        // 1 -> 1' (same id)
        repo.addNext(track(50))
        assertThat(nextInTimeline()).isEqualTo(50L)
    }

    @Test
    fun `after a Play next has played, the next Play next goes right after the current song`() = runTest {
        start(listOf(1, 2, 3))
        repo.addNext(track(10))   // [1, 10, 2, 3]
        repo.addNext(track(11))   // [1, 10, 11, 2, 3]
        fake.autoAdvance()        // 10
        repo.addNext(track(12))   // after 11, which is still queued
        assertThat(fake.ids()).containsExactly(1L, 10L, 11L, 12L, 2L, 3L).inOrder()
    }

    /** Fix 4: a Play next the service can't take (casting used to be one) is inserted anyway, never dropped. */
    @Test
    fun `Play next the service refuses under shuffle is inserted through the controller`() = runTest {
        start(listOf(1, 2, 3, 4), at = 2, shuffle = true, walk = listOf(2, 0, 3, 1))
        fake.refusePlayNext = true
        assertThat(repo.addNext(track(10))).isTrue()
        assertThat(fake.ids()).containsExactly(1L, 2L, 3L, 10L, 4L).inOrder()
        assertThat(repo.currentQueueTracks.map { it.id }).containsExactly(1L, 2L, 3L, 10L, 4L).inOrder()
    }

    @Test
    fun `a Play next song carries the tag through the session round trip`() = runTest {
        start(listOf(1, 2, 3), shuffle = true, walk = listOf(0, 2, 1))
        repo.addNext(track(10))
        assertThat(PlayNext.isTagged(fake.items[1])).isTrue()
        assertThat(PlayNext.isTagged(fake.items[0])).isFalse()
    }

    // ---------------------------------------------------------------- Back: shuffle off is unchanged

    @Test
    fun `shuffle off, jump to row 20, Back goes to row 19 as before`() = runTest {
        start((1L..30L).toList())
        fake.autoAdvance()
        repo.skipToQueueIndex(20)
        repo.skipPrevious()
        assertThat(fake.cur).isEqualTo(19)
    }

    @Test
    fun `shuffle off, song queued twice, Back lands on the slot before the playing copy`() = runTest {
        start(listOf(1, 2, 3, 1, 4))
        repeat(4) { fake.autoAdvance() } // 1 -> 2 -> 3 -> 1 (second copy, slot 3) -> 4
        repo.skipPrevious()
        assertThat(fake.cur).isEqualTo(3)
    }

    @Test
    fun `shuffle off, headset Previous then in-app Back keeps going back`() = runTest {
        start(listOf(1, 2, 3, 4))
        fake.autoAdvance(); fake.autoAdvance() // 1 -> 2 -> 3
        fake.nativePrevious()                   // 3 -> 2
        repo.skipPrevious()
        assertThat(currentId()).isEqualTo(1L)
    }

    @Test
    fun `shuffle off, Back on a queue another controller replaced stays at its first song`() = runTest {
        start(listOf(1, 2, 3))
        fake.autoAdvance()
        fake.replaceQueueExternally(listOf(5, 1, 6), start = 0)
        repo.currentQueueTracks = listOf(5L, 1L, 6L).map { track(it) }
        repo.skipPrevious()
        assertThat(fake.cur).isEqualTo(0)
    }

    // ---------------------------------------------------------------- Back: shuffle on follows history

    @Test
    fun `shuffle on, Back goes to the song that played, not the walk's previous slot`() = runTest {
        // Walk 2 -> 0 -> 3 -> 1; tap slot 1 (track 2) while on slot 2 (track 3): the walk's previous of slot 1 is slot 3.
        start(listOf(1, 2, 3, 4), at = 2, shuffle = true, walk = listOf(2, 0, 3, 1))
        repo.updateState(controller)
        repo.skipToQueueIndex(3) // shuffle rows: [3, 1, 4, 2] -> row 3 = slot 1 (track 2)
        repo.skipPrevious()
        assertThat(currentId()).isEqualTo(3L)
    }

    @Test
    fun `shuffle on, song queued twice, Back lands on the copy that just played`() = runTest {
        start(listOf(1, 2, 3, 1, 4), shuffle = true, walk = listOf(0, 1, 2, 3, 4))
        repeat(4) { fake.autoAdvance() } // 1 -> 2 -> 3 -> 1 (second copy, slot 3) -> 4
        repo.skipPrevious()
        assertThat(fake.cur).isEqualTo(3)
    }

    @Test
    fun `shuffle on, Back onto a same-song copy does not swallow the next song change`() = runTest {
        start(listOf(1, 2, 1, 3), shuffle = true, walk = listOf(0, 1, 2, 3))
        fake.autoAdvance(); fake.autoAdvance() // 1 -> 2 -> 1 (slot 2)
        repo.updateState(controller)
        repo.removeFromQueue(1)                // swipe song 2 away: [1, 1, 3], playing slot 1
        repo.skipPrevious()                     // 2 is gone; back to the first 1 (slot 0)
        assertThat(fake.cur).isEqualTo(0)
        repo.addNext(track(50))                 // [1, 50, 1, 3]
        fake.autoAdvance()                      // 1 -> 50
        repo.addNext(track(60))                 // must go right after 50
        assertThat(nextInTimeline()).isEqualTo(60L)
        repo.skipPrevious()                     // and Back from 50 is the 1 it followed
        assertThat(fake.cur).isEqualTo(0)
    }

    @Test
    fun `shuffle on, headset Previous then in-app Back keeps going back`() = runTest {
        start(listOf(1, 2, 3, 4), at = 2, shuffle = true, walk = listOf(2, 0, 3, 1))
        fake.autoAdvance(); fake.autoAdvance() // 3 -> 1 -> 4
        fake.nativePrevious()                   // the session's Previous: 4 -> 1
        repo.skipPrevious()                     // in-app Back from 1
        assertThat(currentId()).isEqualTo(3L)
    }

    @Test
    fun `shuffle on, after another controller replaced the queue, Back does not jump into it`() = runTest {
        start(listOf(1, 2, 3), shuffle = true, walk = listOf(0, 1, 2))
        fake.autoAdvance()                                       // 1 -> 2; history [1]
        fake.replaceQueueExternally(listOf(5, 1, 6), start = 0)  // the car plays a playlist from its top
        repo.currentQueueTracks = listOf(5L, 1L, 6L).map { track(it) }
        repo.skipPrevious()                                      // nothing played in this queue yet
        assertThat(fake.cur).isEqualTo(0)
    }

    @Test
    fun `shuffle on, Back with nothing played before restarts the song`() = runTest {
        start(listOf(1, 2, 3), at = 1, shuffle = true, walk = listOf(2, 1, 0))
        fake.pos = 1_000L
        repo.skipPrevious()
        assertThat(fake.cur).isEqualTo(1)
        assertThat(fake.pos).isEqualTo(0L)
    }

    // ---------------------------------------------------------------- Back button vs swipe

    @Test
    fun `10 s into a song, the Back button restarts it`() = runTest {
        start(listOf(1, 2, 3))
        fake.autoAdvance()
        fake.pos = 10_000L
        repo.skipPrevious()
        assertThat(fake.cur).isEqualTo(1)
        assertThat(fake.pos).isEqualTo(0L)
    }

    @Test
    fun `10 s into a song, a swipe goes to the previous song`() = runTest {
        start(listOf(1, 2, 3))
        fake.autoAdvance()
        fake.pos = 10_000L
        repo.skipToPreviousSong()
        assertThat(fake.cur).isEqualTo(0)
    }

    @Test
    fun `shuffle on, 10 s in, a swipe goes back through history`() = runTest {
        start(listOf(1, 2, 3, 4), at = 2, shuffle = true, walk = listOf(2, 0, 3, 1))
        fake.autoAdvance()        // 3 -> 1
        fake.pos = 10_000L
        repo.skipToPreviousSong()
        assertThat(currentId()).isEqualTo(3L)
    }

    @Test
    fun `shuffle on, nothing played before, a swipe goes to the walk's previous song`() = runTest {
        start(listOf(1, 2, 3), at = 1, shuffle = true, walk = listOf(2, 1, 0))
        repo.skipToPreviousSong()
        assertThat(currentId()).isEqualTo(3L)
    }
}
