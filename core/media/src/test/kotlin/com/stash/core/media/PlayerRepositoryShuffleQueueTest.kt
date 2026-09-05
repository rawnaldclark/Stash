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
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Issue #468, the repository half: with shuffle on, the published queue follows
 * the controller's shuffle walk, and the sheet's index-based actions (tap, swipe
 * away) map through that walk to the right timeline slot. Drag-reorder is a
 * no-op under shuffle — a MediaController cannot rewrite Media3's shuffle order.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryShuffleQueueTest {

    private val playbackStateStore: PlaybackStateStore = mockk(relaxed = true)
    private val musicRepository: MusicRepository = mockk {
        every { trackDeletions } returns MutableSharedFlow()
    }
    private val streamingPreference: StreamingPreference = mockk(relaxed = true)
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

    /** Timeline slot i holds track id i+1; the shuffle walk is 2 → 0 → 3 → 1. */
    private val walk = listOf(2, 0, 3, 1)

    private fun item(id: Long): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri("file:///music/$id.flac")
        .setMediaMetadata(
            MediaMetadata.Builder().setExtras(Bundle().apply { putLong(EXTRA_TRACK_ID, id) }).build(),
        )
        .build()

    // Streamable rows: a "downloaded" row would be probed on disk, which the JVM cannot satisfy.
    private fun track(id: Long) = Track(id = id, title = "T$id", artist = "A$id", isDownloaded = false, isStreamable = true)

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
        every { streamUrlCache.get(any()) } returns null // updateState stamps quality from the cache
        every { controller.isConnected } returns true
        every { controller.mediaItemCount } returns 4
        for (i in 0 until 4) every { controller.getMediaItemAt(i) } returns item(i + 1L)
        every { controller.currentMediaItem } returns item(3L)   // slot 2 = track 3 is playing
        every { controller.currentMediaItemIndex } returns 2
        every { controller.currentTimeline } returns timeline
        every { controller.shuffleModeEnabled } returns true
        every { controller.playbackState } returns Player.STATE_READY
        repo.controllerDeferred = controller
        repo.currentQueueTracks = (1L..4L).map { track(it) }
        shadowOf(Looper.getMainLooper()).idle()
        repo.controllerDeferred = controller // the bus's initial "not alive" released the seam
    }

    @Test
    fun `under shuffle the published queue follows the shuffle walk`() {
        repo.updateState(controller)

        val state = repo.playerState.value
        assertThat(state.queue.map { it.id }).containsExactly(3L, 1L, 4L, 2L).inOrder()
        assertThat(state.currentIndex).isEqualTo(0)
    }

    @Test
    fun `swiping a row away under shuffle removes the timeline slot it maps to`() = runTest {
        repo.updateState(controller)

        repo.removeFromQueue(2)                 // third row in the walk = timeline slot 3 (track 4)

        verify(exactly = 1) { controller.removeMediaItem(3) }
    }

    @Test
    fun `tapping a row under shuffle seeks to the timeline slot it maps to`() = runTest {
        repo.updateState(controller)

        repo.skipToQueueIndex(3)                // fourth row in the walk = timeline slot 1 (track 2)

        verify(exactly = 1) { controller.seekToDefaultPosition(1) }
    }

    @Test
    fun `dragging under shuffle moves nothing`() = runTest {
        repo.updateState(controller)

        repo.moveInQueue(0, 2)

        verify(exactly = 0) { controller.moveMediaItem(any(), any()) }
    }

    @Test
    fun `with shuffle off the display is the logical queue as before`() {
        every { controller.shuffleModeEnabled } returns false

        repo.updateState(controller)

        assertThat(repo.playerState.value.queue.map { it.id }).containsExactly(1L, 2L, 3L, 4L).inOrder()
        assertThat(repo.playerState.value.currentIndex).isEqualTo(2)
    }
}
