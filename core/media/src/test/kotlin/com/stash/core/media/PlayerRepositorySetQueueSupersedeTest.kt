package com.stash.core.media

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Two quick taps are two independent setQueue calls. Whichever started LAST must win, even when
 * the first one's build (its selected download's disk probe, slow on a SAF tree) finishes last.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositorySetQueueSupersedeTest {

    private val playbackStateStore: PlaybackStateStore = mockk(relaxed = true)
    private val musicRepository: MusicRepository = mockk {
        every { trackDeletions } returns MutableSharedFlow()
    }
    private val controller: MediaController = mockk(relaxed = true)
    private val trackIdentityEvents: TrackIdentityEvents = mockk {
        every { changes } returns MutableSharedFlow()
    }

    private lateinit var repo: PlayerRepositoryImpl

    @Before
    fun setUp() {
        repo = PlayerRepositoryImpl(
            context = ApplicationProvider.getApplicationContext(),
            playbackStateStore = playbackStateStore,
            musicRepository = musicRepository,
            streamingPreference = mockk<StreamingPreference>(relaxed = true) { coEvery { current() } returns true },
            streamResolver = mockk<StreamSourceRegistry>(),
            streamUrlCache = mockk<StreamUrlCache>(relaxed = true),
            connectivity = mockk<ConnectivityMonitor>(relaxed = true),
            trackDao = mockk<TrackDao>(relaxed = true),
            playbackResumer = mockk(relaxed = true),
            radioGenerator = mockk(relaxed = true),
            trackIdentityEvents = trackIdentityEvents,
            playbackSessionBus = PlaybackSessionBus(),
        )
        every { controller.isConnected } returns true
        repo.controllerDeferred = controller
        shadowOf(Looper.getMainLooper()).idle()
        repo.controllerDeferred = controller // the bus's initial "not alive" released the seam
    }

    @Test
    fun `an earlier tap whose build finishes last does not replace the later one`() = runTest {
        val probeStarted = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)
        repo.filePathExistsOnDisk = { path ->
            if (path == "/sd/slow.flac") {
                probeStarted.countDown()
                releaseProbe.await(5, TimeUnit.SECONDS)
            }
            true
        }
        val slow = Track(id = 1L, title = "Slow", artist = "A", filePath = "/sd/slow.flac", isDownloaded = true)
        val fast = Track(id = 2L, title = "Fast", artist = "B", isStreamable = true)

        val firstTap = launch { repo.setQueue(listOf(slow)) }
        runCurrent()
        assertThat(probeStarted.await(5, TimeUnit.SECONDS)).isTrue()
        repo.setQueue(listOf(fast)) // the second tap builds and plays first
        releaseProbe.countDown()
        firstTap.join()

        verify(exactly = 1) { controller.setMediaItems(any<List<MediaItem>>(), any<Int>(), any<Long>()) }
        verify { controller.setMediaItems(match<List<MediaItem>> { it.single().mediaId == "2" }, any<Int>(), any<Long>()) }
    }
}
