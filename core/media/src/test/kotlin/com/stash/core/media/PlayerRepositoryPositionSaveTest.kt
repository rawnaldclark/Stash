package com.stash.core.media

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.model.Track
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * updateState saves the position only on player events, and steady playback raises none after a
 * track starts. The save-while-playing tick has to catch the position up, or a process death
 * mid-track resumes near the track's beginning.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryPositionSaveTest {

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
    private val trackIdentityEvents: TrackIdentityEvents = mockk {
        every { changes } returns MutableSharedFlow()
    }

    private lateinit var repo: PlayerRepositoryImpl

    private val item: MediaItem = MediaItem.Builder()
        .setMediaId("1")
        .setUri("file:///music/1.flac")
        .setMediaMetadata(
            MediaMetadata.Builder().setExtras(Bundle().apply { putLong(EXTRA_TRACK_ID, 1L) }).build(),
        )
        .build()

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
            radioEngines = radioEngines(mockk(relaxed = true)),
            trackIdentityEvents = trackIdentityEvents,
            playbackSessionBus = PlaybackSessionBus(),
        )
        every { streamUrlCache.get(any()) } returns null // updateState stamps quality from the cache
        every { controller.isConnected } returns true
        every { controller.mediaItemCount } returns 1
        every { controller.getMediaItemAt(0) } returns item
        every { controller.currentMediaItem } returns item
        every { controller.currentMediaItemIndex } returns 0
        every { controller.shuffleModeEnabled } returns false
        every { controller.playbackState } returns Player.STATE_READY
        every { controller.isPlaying } returns true
        repo.controllerDeferred = controller
        repo.currentQueueTracks = listOf(Track(id = 1L, title = "T1", artist = "A1", isStreamable = true))
        shadowOf(Looper.getMainLooper()).idle()
        repo.controllerDeferred = controller // the bus's initial "not alive" released the seam
    }

    @Test
    fun `a tick while playing saves the live position`() {
        every { controller.currentPosition } returns 3_000L
        repo.updateState(controller) // the track starts: saved at 0:03
        every { controller.currentPosition } returns 2_700_000L // 45 minutes in, no event since

        repo.persistPositionWhilePlaying()
        shadowOf(Looper.getMainLooper()).idle()

        coVerify(exactly = 1) { playbackStateStore.savePosition(1L, 2_700_000L, 0) }
    }

    @Test
    fun `a tick with the position barely moved writes nothing new`() {
        every { controller.currentPosition } returns 3_000L
        repo.updateState(controller)
        every { controller.currentPosition } returns 4_000L

        repo.persistPositionWhilePlaying()
        shadowOf(Looper.getMainLooper()).idle()

        coVerify(exactly = 0) { playbackStateStore.savePosition(1L, 4_000L, any()) }
    }
}
