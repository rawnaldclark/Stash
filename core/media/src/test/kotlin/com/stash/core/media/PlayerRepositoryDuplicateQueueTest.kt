package com.stash.core.media

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
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
 * The same song queued twice (`[A, B, C, A]`, common in Spotify imports, or the playing song
 * added to the queue): the queue sheet's actions must reach the copy on the row the user touched,
 * and the sheet must highlight the copy that is playing.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryDuplicateQueueTest {

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
    private val trackIdentityEvents: TrackIdentityEvents = mockk {
        every { changes } returns MutableSharedFlow()
    }

    private lateinit var repo: PlayerRepositoryImpl

    /** Timeline and logical queue alike: A(1), B(2), C(3), A(1). */
    private val ids = listOf(1L, 2L, 3L, 1L)

    private fun item(id: Long): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri("file:///music/$id.flac")
        .setMediaMetadata(
            MediaMetadata.Builder().setExtras(Bundle().apply { putLong(EXTRA_TRACK_ID, id) }).build(),
        )
        .build()

    // Streamable rows: a "downloaded" row would be probed on disk, which the JVM cannot satisfy.
    private fun track(id: Long) = Track(id = id, title = "T$id", artist = "A$id", isDownloaded = false, isStreamable = true)

    private fun playing(slot: Int) {
        every { controller.currentMediaItem } returns item(ids[slot])
        every { controller.currentMediaItemIndex } returns slot
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
            playbackResumer = mockk(relaxed = true),
            radioGenerator = mockk(relaxed = true),
            trackIdentityEvents = trackIdentityEvents,
            playbackSessionBus = PlaybackSessionBus(),
        )
        every { streamUrlCache.get(any()) } returns null // updateState stamps quality from the cache
        every { controller.isConnected } returns true
        every { controller.mediaItemCount } returns ids.size
        ids.forEachIndexed { i, id -> every { controller.getMediaItemAt(i) } returns item(id) }
        every { controller.shuffleModeEnabled } returns false
        every { controller.playbackState } returns Player.STATE_READY
        playing(0)
        repo.controllerDeferred = controller
        repo.currentQueueTracks = ids.map { track(it) }
        shadowOf(Looper.getMainLooper()).idle()
        repo.controllerDeferred = controller // the bus's initial "not alive" released the seam
    }

    @Test
    fun `while the second copy plays the sheet highlights that copy`() {
        playing(3)

        repo.updateState(controller)

        assertThat(repo.playerState.value.currentIndex).isEqualTo(3)
    }

    @Test
    fun `swiping away the later copy removes that copy, not the playing one`() = runTest {
        repo.removeFromQueue(3)

        verify(exactly = 1) { controller.removeMediaItem(3) }
        verify(exactly = 0) { controller.removeMediaItem(0) }
    }

    @Test
    fun `tapping the later copy seeks to it`() = runTest {
        repo.skipToQueueIndex(3)

        verify(exactly = 1) { controller.seekToDefaultPosition(3) }
    }

    @Test
    fun `dragging the later copy moves that copy`() = runTest {
        repo.moveInQueue(3, 1) // [A, A, B, C]

        verify(exactly = 1) { controller.moveMediaItem(3, 1) }
    }

    /**
     * The queue sheet's Undo puts a swiped song back on its row of the logical queue, and into the
     * timeline after exactly the items that come before that row (one copy of A, then B; the second
     * A comes after it).
     */
    @Test
    fun `Undo puts the song back on its row and in the matching timeline slot`() = runTest {
        assertThat(repo.insertInQueue(2, track(9))).isTrue() // [A, B, 9, C, A]

        assertThat(repo.currentQueueTracks.map { it.id }).containsExactly(1L, 2L, 9L, 3L, 1L).inOrder()
        verify(exactly = 1) { controller.addMediaItem(2, any()) }
    }
}
