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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A downloaded track's disk check is a ContentResolver query on a SAF tree, which a slow provider
 * (SD card, cloud-backed tree) stalls. Play, Play next and Add to queue run on the caller's thread,
 * Main from a viewModelScope, so the check itself must hop off it.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryDiskCheckThreadTest {

    private val musicRepository: MusicRepository = mockk {
        every { trackDeletions } returns MutableSharedFlow()
    }
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val controller: MediaController = mockk(relaxed = true)
    private val trackIdentityEvents: TrackIdentityEvents = mockk {
        every { changes } returns MutableSharedFlow()
    }

    private lateinit var repo: PlayerRepositoryImpl
    private val checkedOn = mutableListOf<Thread>()
    private val downloaded = Track(id = 7L, title = "T", artist = "A", filePath = "/music/7.flac", isDownloaded = true)

    @Before
    fun setUp() {
        repo = PlayerRepositoryImpl(
            context = ApplicationProvider.getApplicationContext(),
            playbackStateStore = mockk<PlaybackStateStore>(relaxed = true),
            musicRepository = musicRepository,
            streamingPreference = mockk<StreamingPreference>(relaxed = true),
            streamResolver = mockk<StreamSourceRegistry>(),
            streamUrlCache = mockk<StreamUrlCache>(relaxed = true),
            connectivity = mockk<ConnectivityMonitor>(relaxed = true),
            trackDao = trackDao,
            playbackResumer = mockk(relaxed = true),
            radioEngines = radioEngines(mockk(relaxed = true)),
            trackIdentityEvents = trackIdentityEvents,
            playbackSessionBus = PlaybackSessionBus(),
        )
        every { controller.isConnected } returns true
        every { controller.mediaItemCount } returns 1
        // One real song playing: Play next reads the queue's items to place the new one.
        every { controller.currentMediaItemIndex } returns 0
        every { controller.getMediaItemAt(0) } returns MediaItem.Builder().setMediaId("1").build()
        repo.controllerDeferred = controller
        shadowOf(Looper.getMainLooper()).idle()
        repo.controllerDeferred = controller // the bus's initial "not alive" released the seam
        repo.filePathExistsOnDisk = { checkedOn += Thread.currentThread(); true }
    }

    @Test
    fun `Play next checks the file off the calling thread`() = runTest {
        repo.addNext(downloaded)

        assertThat(checkedOn).isNotEmpty()
        assertThat(checkedOn).doesNotContain(Thread.currentThread())
    }

    @Test
    fun `Add to queue checks the file off the calling thread`() = runTest {
        repo.addToQueue(downloaded)

        assertThat(checkedOn).isNotEmpty()
        assertThat(checkedOn).doesNotContain(Thread.currentThread())
    }

    @Test
    fun `playing one track checks the file off the calling thread`() = runTest {
        coEvery { trackDao.getById(7L) } returns null

        repo.playTrack(downloaded)

        assertThat(checkedOn).isNotEmpty()
        assertThat(checkedOn).doesNotContain(Thread.currentThread())
    }
}
