package com.stash.core.media

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.radio.RadioSeed
import com.stash.core.data.radio.RadioSession
import com.stash.core.data.radio.RadioStationGenerator
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.model.PlaybackSource
import com.stash.core.model.RepeatMode
import com.stash.core.model.Track
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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
 * from" included) but starts the tapped song, the tapped copy of it, from its beginning; with
 * shuffle on it is the song on the tapped ROW (a ghost kept from a stopped service lists Media3's
 * shuffle walk, while the saved queue is in timeline order); and a radio song no saved queue can
 * rebuild still plays from the ghost's own rows. A swipe or a drag changes what the ghost shows and
 * what is saved, and never starts playback nor grows a radio station into the empty player. Play
 * next and Add to queue join the saved session instead of replacing it. Taps, swipes and Play are
 * serialised, and leaving the screen cancels none of them.
 *
 * The resume plan is the real [PlaybackResumer] over an in-memory store, so what an edit saves is
 * exactly what the next rebuild reads, and the controller is a small fake player whose timeline
 * follows the loads and inserts the code makes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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

    private val videotape = Track(id = 9L, title = "Videotape", artist = "Radiohead", durationMs = 280_000L, isStreamable = true)

    /** A station's first batch as RadioStationGenerator makes it: discoveries with synthetic ids and no library row. */
    private val station = (1L..6L).map { n ->
        Track(id = 100L + n, title = "R$n", artist = "Thom Yorke", youtubeId = "yt$n", durationMs = 200_000L, isStreamable = true)
    }

    private val streamError = PlaybackException("boom", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

    /** PlaybackStateStore's DataStore, in memory: what the repository saves is what a resume plan reads back. */
    private var stored: SavedPlaybackState? = null

    /**
     * While set, a store read returns what was stored when it was made, but only once this completes:
     * the window in which two read-modify-writes could both start from the same saved queue.
     */
    private var readGate: CompletableDeferred<Unit>? = null
    private val blank = SavedPlaybackState(0L, 0L, 0, emptyList(), false, RepeatMode.OFF, PlaybackSource.Unknown)
    private val playbackStateStore: PlaybackStateStore = mockk {
        coEvery { getLastPlaybackState() } coAnswers {
            val snapshot = stored
            readGate?.await()
            snapshot
        }
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
    private val radioGenerator: RadioStationGenerator = mockk {
        coEvery { start(any()) } returns (mockk<RadioSession>() to station)
        coEvery { nextBatch(any()) } returns
            listOf(Track(id = 200L, title = "R7", artist = "Thom Yorke", youtubeId = "yt7", isStreamable = true))
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

    /** The controller's timeline as a tiny player: loads, inserts and the current item follow the calls made to it. */
    private val timelineItems = mutableListOf<MediaItem>()
    private var timelineCurrent = 0

    /**
     * When set, the fake player reports each timeline change to this listener during the call that
     * made it, the way a MediaController applies its own changes locally and notifies at once.
     */
    private var timelineListener: Player.Listener? = null

    private fun notifyTimeline() {
        timelineListener?.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
    }

    private fun livePlayer() {
        every { controller.mediaItemCount } answers { timelineItems.size }
        every { controller.getMediaItemAt(any()) } answers { timelineItems[firstArg()] }
        every { controller.currentMediaItemIndex } answers { timelineCurrent }
        every { controller.currentMediaItem } answers { timelineItems.getOrNull(timelineCurrent) }
        every { controller.setMediaItems(any<List<MediaItem>>(), any<Int>(), any<Long>()) } answers {
            timelineItems.clear()
            timelineItems.addAll(firstArg<List<MediaItem>>())
            timelineCurrent = secondArg()
            notifyTimeline()
        }
        every { controller.addMediaItem(any<Int>(), any()) } answers {
            timelineItems.add(minOf(firstArg<Int>(), timelineItems.size), secondArg())
            notifyTimeline()
        }
        every { controller.addMediaItem(any<MediaItem>()) } answers {
            timelineItems.add(firstArg())
            notifyTimeline()
        }
        every { controller.addMediaItems(any<List<MediaItem>>()) } answers {
            timelineItems.addAll(firstArg<List<MediaItem>>())
            notifyTimeline()
        }
        every { controller.nextMediaItemIndex } returns C.INDEX_UNSET // no next-up prefetch to resolve
    }

    /** The controller holds tracks 1-4 in timeline order with [current] playing (a fixed picture, not [livePlayer]). */
    private fun holding(current: Long) {
        every { controller.mediaItemCount } returns 4
        for (i in 0 until 4) every { controller.getMediaItemAt(i) } returns item(i + 1L)
        every { controller.currentMediaItem } returns item(current)
        every { controller.currentMediaItemIndex } returns (current - 1).toInt()
    }

    /** Runs the main looper (the repository's own scope) until its work, Dispatchers.IO hops included, settles. */
    private fun idleMain(ms: Long = 300) {
        val deadline = System.currentTimeMillis() + ms
        do {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        } while (System.currentTimeMillis() < deadline)
        shadowOf(Looper.getMainLooper()).idle()
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
            radioGenerator = radioGenerator,
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
    private fun coldStartGhost(
        ids: List<Long> = listOf(1L, 2L, 3L, 4L),
        current: Long = 2L,
        shuffled: Boolean = false,
        together: ListenTogetherController? = null,
    ): PlayerRepositoryImpl {
        stored = SavedPlaybackState(
            trackId = current,
            positionMs = 44_000L,
            queueIndex = ids.indexOf(current),
            queueTrackIds = ids,
            isShuffled = shuffled,
            repeatMode = RepeatMode.ALL,
            source = source,
        )
        livePlayer() // the new service's player: empty
        val repo = build(together)
        assertThat(repo.playerState.value.currentTrack?.id).isEqualTo(current) // the ghost, paused at 0:44
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
        every { controller.nextMediaItemIndex } returns C.INDEX_UNSET
        repo.updateState(controller)
        shadowOf(Looper.getMainLooper()).idle() // the queue and position saves land
        repo.onSessionAliveChanged(false) // the idle-stop
        timelineItems.clear()
        livePlayer()
        repo.controllerDeferred = controller // the foreground reconnect: a new, empty service
        clearMocks(controller, playbackStateStore, answers = false)
        return repo
    }

    /**
     * A radio station playing R1 with five discoveries after it, one more than the grow threshold,
     * then the idle-stop: the ghost keeps the station's queue, and the station stays armed.
     */
    private suspend fun radioGhost(withLibrarySong: Boolean = false): PlayerRepositoryImpl {
        livePlayer()
        val repo = build() // nothing saved yet: no cold-start ghost
        repo.startRadio(RadioSeed.Artist("Radiohead"), keepCurrent = false)
        if (withLibrarySong) assertThat(repo.addToQueue(library.getValue(1L).toDomain())).isTrue()
        repo.updateState(controller)
        shadowOf(Looper.getMainLooper()).idle() // the queue and position saves land
        repo.onSessionAliveChanged(false) // the idle-stop
        timelineItems.clear()
        repo.controllerDeferred = controller // the foreground reconnect: a new, empty service
        clearMocks(controller, playbackStateStore, radioGenerator, answers = false)
        return repo
    }

    private fun verifyNothingLoaded() {
        verify(exactly = 0) { controller.setMediaItems(any<List<MediaItem>>(), any<Int>(), any<Long>()) }
        verify(exactly = 0) { controller.prepare() }
        verify(exactly = 0) { controller.play() }
    }

    // ---- Taps ----

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
        // The player now holds what was loaded: its first refresh shows the tapped song, from the playlist.
        repo.updateState(controller)
        assertThat(repo.playerState.value.currentTrack?.id).isEqualTo(4L)
        assertThat(repo.playerState.value.source).isEqualTo(source)
    }

    @Test
    fun `with shuffle on, a tap on the cold-start ghost plays the tapped song with shuffle kept`() = runTest {
        val repo = coldStartGhost(shuffled = true)

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
    fun `a tap on the second copy of a song in the ghost starts at that copy`() = runTest {
        val repo = coldStartGhost(ids = listOf(1L, 2L, 3L, 1L, 4L), current = 2L)

        repo.skipToQueueIndex(3) // 15 Step again: the copy just before Weird Fishes

        verify { controller.setMediaItems(match<List<MediaItem>> { ids(it) == listOf(1L, 2L, 3L, 1L, 4L) }, 3, 0L) }
    }

    @Test
    fun `a tap on a radio song in the ghost plays it, though no saved queue can rebuild it`() = runTest {
        val repo = radioGhost()

        repo.skipToQueueIndex(3) // R4: a discovery with no library row

        verify { controller.setMediaItems(match<List<MediaItem>> { ids(it) == (101L..106L).toList() }, 3, 0L) }
        verify { controller.play() }
        repo.updateState(controller)
        assertThat(repo.playerState.value.currentTrack?.id).isEqualTo(104L)
        assertThat(repo.playerState.value.source).isEqualTo(PlaybackSource.Radio("Radiohead"))
    }

    @Test
    fun `a tap on a library song in a ghost with radio songs keeps the radio songs`() = runTest {
        val repo = radioGhost(withLibrarySong = true)

        repo.skipToQueueIndex(6) // 15 Step, queued after the station's songs: the only one the saved queue can rebuild

        verify {
            controller.setMediaItems(match<List<MediaItem>> { ids(it) == (101L..106L).toList() + 1L }, 6, 0L)
        }
    }

    @Test
    fun `a tap on the ghost re-arms the streaming error guard, like Play`() = runTest {
        val repo = serviceStopGhost(shuffled = false)
        // Before the pause Weird Fishes failed three times: a retry in place, then two skips. One more
        // failure in a row would halt streaming.
        every { controller.currentMediaItem } returns item(4L)
        repeat(3) { repo.playerListener.onPlayerError(streamError) }
        every { controller.currentMediaItem } answers { timelineItems.getOrNull(timelineCurrent) }

        repo.skipToQueueIndex(3) // Weird Fishes, picked on purpose
        repo.playerListener.onPlayerError(streamError) // and it fails once more

        assertThat(repo.streamingHaltedEvents.replayCache).isEmpty() // retried in place, not halted
    }

    @Test
    fun `Play pressed while a tap's rebuild is loading keeps the tapped song`() = runTest {
        val repo = coldStartGhost()
        val gate = CompletableDeferred<Unit>().also { readGate = it }
        val tap = launch { repo.skipToQueueIndex(3) } // Weird Fishes
        runCurrent() // the tap is reading the saved queue

        repo.play() // nothing has loaded yet, so Play takes the rebuild path too
        idleMain()
        gate.complete(Unit)
        tap.join()
        idleMain() // Play's rebuild gets its turn

        verify(exactly = 1) { controller.setMediaItems(any<List<MediaItem>>(), any<Int>(), any<Long>()) }
        verify { controller.setMediaItems(any<List<MediaItem>>(), 3, 0L) }
    }

    @Test
    fun `a tap whose ghost retires while it reads the saved queue seeks in the live queue instead`() = runTest {
        val repo = coldStartGhost()
        val gate = CompletableDeferred<Unit>().also { readGate = it }
        val tap = launch { repo.skipToQueueIndex(3) } // Weird Fishes
        runCurrent() // the tap is reading the saved queue

        // Meanwhile a media button brings the session back on the service, and its first refresh lands.
        timelineItems.addAll((1L..4L).map { item(it) })
        timelineCurrent = 1
        repo.updateState(controller)
        gate.complete(Unit)
        tap.join()

        verify(exactly = 0) { controller.setMediaItems(any<List<MediaItem>>(), any<Int>(), any<Long>()) }
        verify(exactly = 1) { controller.seekToDefaultPosition(3) }
    }

    @Test
    fun `leaving Now Playing while a tap's rebuild waits does not cancel it`() = runTest {
        val repo = coldStartGhost()
        val gate = CompletableDeferred<Unit>().also { readGate = it }
        val tap = launch { repo.skipToQueueIndex(3) } // Weird Fishes
        runCurrent() // the tap is reading the saved queue

        tap.cancel() // the ViewModel's scope goes with the screen
        gate.complete(Unit)
        tap.join()

        verify { controller.setMediaItems(match<List<MediaItem>> { ids(it) == listOf(1L, 2L, 3L, 4L) }, 3, 0L) }
        verify { controller.play() }
    }

    @Test
    fun `a ghost over a player that already holds items lets a tap seek the live queue`() = runTest {
        val repo = coldStartGhost()
        // A media button loaded the queue on the service, and no refresh has reached the ghost yet.
        timelineItems.addAll((1L..4L).map { item(it) })
        timelineCurrent = 1

        repo.skipToQueueIndex(3)

        verify(exactly = 1) { controller.seekToDefaultPosition(3) }
        verify(exactly = 0) { controller.setMediaItems(any<List<MediaItem>>(), any<Int>(), any<Long>()) }
    }

    // ---- Swipes and drags ----

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
    fun `a swipe on the second copy of a song drops that copy from the saved queue`() = runTest {
        val repo = coldStartGhost(ids = listOf(1L, 2L, 3L, 2L, 4L), current = 1L)

        repo.removeFromQueue(3) // Bodysnatchers again: the copy just before Weird Fishes

        assertThat(repo.playerState.value.queue.map { it.id }).containsExactly(1L, 2L, 3L, 4L).inOrder()
        assertThat(stored?.queueTrackIds).containsExactly(1L, 2L, 3L, 4L).inOrder()
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
    fun `two quick swipes on the ghost both stick`() = runTest {
        val repo = coldStartGhost()
        val gate = CompletableDeferred<Unit>().also { readGate = it }
        val first = launch { repo.removeFromQueue(2) } // Nude
        runCurrent() // its save is reading the stored queue
        val second = launch { repo.removeFromQueue(2) } // then Weird Fishes, third by now
        runCurrent()

        gate.complete(Unit)
        joinAll(first, second)

        assertThat(repo.playerState.value.queue.map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(stored?.queueTrackIds).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `leaving Now Playing while a swipe is being saved still saves it`() = runTest {
        val repo = coldStartGhost()
        val gate = CompletableDeferred<Unit>().also { readGate = it }
        val swipe = launch { repo.removeFromQueue(2) } // Nude
        runCurrent() // its save is reading the stored queue

        swipe.cancel() // the ViewModel's scope goes with the screen
        gate.complete(Unit)
        swipe.join()

        assertThat(stored?.queueTrackIds).containsExactly(1L, 2L, 4L).inOrder()
    }

    @Test
    fun `a swipe on a radio ghost's queue never grows the station into the empty player`() = runTest {
        val repo = radioGhost()

        repo.removeFromQueue(1) // R2: four songs left after the paused one, under the grow threshold
        shadowOf(Looper.getMainLooper()).idle() // the station's grow watcher sees the new queue

        coVerify(exactly = 0) { radioGenerator.nextBatch(any()) }
        verify(exactly = 0) { controller.addMediaItems(any<List<MediaItem>>()) }
        assertThat(timelineItems).isEmpty()
        assertThat(repo.playerState.value.currentTrack?.id).isEqualTo(101L)
        assertThat(stored?.queueTrackIds).containsExactly(101L, 103L, 104L, 105L, 106L).inOrder()
    }

    @Test
    fun `a station's next batch shows in the queue as soon as it is added`() = runTest {
        livePlayer()
        val repo = build() // nothing saved: no ghost
        timelineListener = repo.playerListener
        repo.startRadio(RadioSeed.Artist("Radiohead"), keepCurrent = false)

        repo.growRadio()

        assertThat(repo.playerState.value.queue.map { it.id })
            .containsExactly(101L, 102L, 103L, 104L, 105L, 106L, 200L).inOrder()
    }

    @Test
    fun `the station never grows an empty player`() = runTest {
        val repo = radioGhost()

        repo.growRadio() // whatever asks: an empty player has no queue to extend

        coVerify(exactly = 0) { radioGenerator.nextBatch(any()) }
        verify(exactly = 0) { controller.addMediaItems(any<List<MediaItem>>()) }
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

    // ---- Play next and Add to queue ----

    @Test
    fun `Play next on the ghost queues the song after the paused one, in the saved session`() = runTest {
        val repo = coldStartGhost()
        timelineListener = repo.playerListener

        assertThat(repo.addNext(videotape)).isTrue()

        // The saved session comes back paused at 0:44 of Bodysnatchers, and Videotape goes in after it.
        verifyOrder {
            controller.playWhenReady = false
            controller.setMediaItems(match<List<MediaItem>> { ids(it) == listOf(1L, 2L, 3L, 4L) }, 1, 44_000L)
            controller.addMediaItem(2, match { it.mediaId == "9" })
        }
        verify(exactly = 0) { controller.prepare() }
        verify(exactly = 0) { controller.play() }
        // Shown at once, from the insert's own refresh: a paused, unprepared player may send nothing more.
        val state = repo.playerState.value
        assertThat(state.currentTrack?.id).isEqualTo(2L)
        assertThat(state.isPlaying).isFalse()
        assertThat(state.queue.map { it.id }).containsExactly(1L, 2L, 9L, 3L, 4L).inOrder()
        assertThat(state.currentIndex).isEqualTo(1)
        shadowOf(Looper.getMainLooper()).idle() // that refresh's saves land
        assertThat(stored?.queueTrackIds).containsExactly(1L, 2L, 9L, 3L, 4L).inOrder()
    }

    @Test
    fun `Add to queue on the ghost appends the song to the saved session`() = runTest {
        val repo = coldStartGhost()
        timelineListener = repo.playerListener

        assertThat(repo.addToQueue(videotape)).isTrue()

        verify { controller.setMediaItems(match<List<MediaItem>> { ids(it) == listOf(1L, 2L, 3L, 4L) }, 1, 44_000L) }
        verify(exactly = 0) { controller.play() }
        // Shown at once, from the insert's own refresh.
        assertThat(repo.playerState.value.queue.map { it.id }).containsExactly(1L, 2L, 3L, 4L, 9L).inOrder()
        shadowOf(Looper.getMainLooper()).idle() // that refresh's saves land
        assertThat(stored?.queueTrackIds).containsExactly(1L, 2L, 3L, 4L, 9L).inOrder()
    }

    @Test
    fun `in a normal paused session too, Play next and Add to queue show at once`() = runTest {
        livePlayer()
        val repo = build() // nothing saved: no ghost
        timelineListener = repo.playerListener
        repo.setQueue((1L..4L).map { library.getValue(it).toDomain() }, startIndex = 1, source = source)
        val jigsaw = Track(id = 8L, title = "Jigsaw Falling Into Place", artist = "Radiohead", isStreamable = true)

        repo.addNext(videotape)
        assertThat(repo.playerState.value.queue.map { it.id }).containsExactly(1L, 2L, 9L, 3L, 4L).inOrder()

        repo.addToQueue(jigsaw)
        assertThat(repo.playerState.value.queue.map { it.id }).containsExactly(1L, 2L, 9L, 3L, 4L, 8L).inOrder()
    }

    // ---- Deletions and Listen Together ----

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
    fun `while a Listen Together restore is pending the ghost's queue stays untouched`() = runTest {
        val together = ListenTogetherController(ApplicationProvider.getApplicationContext())
        val repo = coldStartGhost(together = together)
        together.restorePending = true // a session just ended: its restore owns the player until it lands
        val before = repo.playerState.value

        repo.skipToQueueIndex(3)
        repo.removeFromQueue(2)

        verifyNothingLoaded()
        assertThat(repo.playerState.value).isEqualTo(before)
        assertThat(stored?.queueTrackIds).containsExactly(1L, 2L, 3L, 4L).inOrder()
    }
}
