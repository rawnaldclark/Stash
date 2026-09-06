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
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrl
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.model.TrackItem
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A track played from the Search tab used to be queued under a hash of its
 * video id — an identity that lived only in memory. The persisted queue then
 * held an id no library row answers to, so no resume path (the Resume
 * shortcut, the idle-resume play, the cold-start ghost of #462) could rebuild
 * a Search-started queue, and none of the per-row bookkeeping (art upgrades,
 * quality stamps, "now playing" markers) could find it either.
 *
 * Queue additions already solve this with [MusicRepository.ensureTrackPersisted]
 * (dedup by video id / Spotify URI / canonical identity, else insert). A Search
 * play must take the same road.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositorySearchPlayIdentityTest {

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
        every { controller.isConnected } returns true
        every { controller.mediaItemCount } returns 0
        every { streamUrlCache.get(any()) } returns null
        repo.controllerDeferred = controller
        shadowOf(Looper.getMainLooper()).idle()
        repo.controllerDeferred = controller // the bus's initial "not alive" released the seam
    }

    @Test
    fun `a search-tab play is queued under its persisted library id`() = runTest {
        coEvery { musicRepository.ensureTrackPersisted(any()) } returns 531L
        coEvery { streamingPreference.current() } returns true
        every { streamingPreference.streamOnCellular } returns flowOf(true)
        every { connectivity.isConnected() } returns true
        every { connectivity.isCellular() } returns false
        coEvery { streamResolver.resolve(any(), any(), any()) } returns StreamUrl(
            url = "https://cdn.example/reckoner?etsp=42",
            expiresAtMs = 42_000L,
        )
        val queued = slot<MediaItem>()
        every { controller.setMediaItem(capture(queued)) } just Runs
        val item = TrackItem(
            videoId = "_uofQD-N6UI", title = "Reckoner", artist = "Radiohead",
            durationSeconds = 290.0, thumbnailUrl = null, album = "In Rainbows",
        )

        val result = repo.playFromStream(item)

        assertThat(result).isInstanceOf(StreamRoutingResult.Item::class.java)
        assertThat(queued.captured.mediaId).isEqualTo("531")
        assertThat(queued.captured.mediaMetadata.extras?.getLong(EXTRA_TRACK_ID)).isEqualTo(531L)
        coVerify(exactly = 1) {
            musicRepository.ensureTrackPersisted(match { it.youtubeId == "_uofQD-N6UI" && it.title == "Reckoner" && it.id == 0L })
        }
    }
}
