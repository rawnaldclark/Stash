package com.stash.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamQualityPolicy
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrl
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Data saver on mobile data skips the next-song look-ahead ([PlayerRepositoryImpl.prefetchNextTrack]):
 * the slot stays a placeholder that the player's own buffering resolves before the transition. What
 * costs no data is untouched: a next song whose download finished still swaps to its file.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryDataSaverPrefetchTest {

    private val playbackStateStore: PlaybackStateStore = mockk(relaxed = true)
    private val musicRepository: MusicRepository = mockk { every { trackDeletions } returns MutableSharedFlow() }
    private val streamingPreference: StreamingPreference = mockk(relaxed = true) { coEvery { current() } returns true }
    private val streamResolver: StreamSourceRegistry = mockk {
        coEvery { resolve(any(), any(), any(), any()) } returns StreamUrl(
            url = "https://cdn.example/next.flac", expiresAtMs = Long.MAX_VALUE, codec = "flac",
        )
    }
    private val streamUrlCache: StreamUrlCache = mockk(relaxUnitFun = true) { every { get(any()) } returns null }
    private val trackDao: TrackDao = mockk(relaxed = true) { coEvery { getById(any()) } returns null }
    private val controller: MediaController = mockk(relaxed = true)
    private val trackIdentityEvents: TrackIdentityEvents = mockk { every { changes } returns MutableSharedFlow() }

    private fun repo(dataSaver: Boolean) = PlayerRepositoryImpl(
        context = ApplicationProvider.getApplicationContext(),
        playbackStateStore = playbackStateStore,
        musicRepository = musicRepository,
        streamingPreference = streamingPreference,
        streamResolver = streamResolver,
        streamUrlCache = streamUrlCache,
        connectivity = mockk<ConnectivityMonitor>(relaxed = true),
        trackDao = trackDao,
        playbackResumer = PlaybackResumer(playbackStateStore, trackDao),
        radioGenerator = mockk(relaxed = true),
        trackIdentityEvents = trackIdentityEvents,
        playbackSessionBus = PlaybackSessionBus(),
        qualityPolicy = mockk<StreamQualityPolicy> { coEvery { dataSaverApplies() } returns dataSaver },
    ).also {
        // The seam mock must read as CONNECTED or ensureController rebuilds a real one.
        every { controller.isConnected } returns true
        it.controllerDeferred = controller
    }

    /** Slot 1 holds [next] as a stash-resolve:// placeholder; slot 0 is playing. */
    private fun queue(repo: PlayerRepositoryImpl, next: Track) {
        val nextItem = MediaItem.Builder()
            .setMediaId(next.id.toString())
            .setUri("stash-resolve://track/${next.id}")
            .setMediaMetadata(
                MediaMetadata.Builder().setExtras(android.os.Bundle().apply { putLong(EXTRA_TRACK_ID, next.id) }).build(),
            )
            .build()
        every { controller.nextMediaItemIndex } returns 1
        every { controller.mediaItemCount } returns 2
        every { controller.getMediaItemAt(1) } returns nextItem
        repo.currentQueueTracks = listOf(Track(id = 41L, title = "Current", artist = "Artist"), next)
    }

    private val streamedNext = Track(id = 42L, title = "Next", artist = "Artist", isStreamable = true)

    @Test
    fun `on mobile data under Data saver the next song is not looked up ahead`() = runTest {
        val repo = repo(dataSaver = true)
        queue(repo, streamedNext)

        repo.prefetchNextTrack()

        coVerify(exactly = 0) { streamResolver.resolve(any(), any(), any(), any()) }
        verify(exactly = 0) { controller.replaceMediaItem(any(), any()) }
    }

    @Test
    fun `where Data saver does not apply the next song is looked up and swapped in as before`() = runTest {
        val repo = repo(dataSaver = false)
        queue(repo, streamedNext)
        val replacement = slot<MediaItem>()
        every { controller.replaceMediaItem(1, capture(replacement)) } returns Unit

        repo.prefetchNextTrack()

        coVerify(exactly = 1) { streamResolver.resolve(any(), allowYouTube = true, allowYtDlp = true, speculative = true) }
        assertThat(replacement.captured.localConfiguration?.uri?.toString()).isEqualTo("https://cdn.example/next.flac")
    }

    /** Downloads are never Data saver's business: a file that finished since queueing still plays next. */
    @Test
    fun `under Data saver a next song downloaded since it was queued still swaps to its file`() = runTest {
        val repo = repo(dataSaver = true)
        repo.filePathExistsOnDisk = { it == "/storage/music/new.flac" }
        coEvery { trackDao.getById(42L) } returns TrackEntity(
            id = 42L, title = "Next", artist = "Artist", filePath = "/storage/music/new.flac",
            fileSizeBytes = 1_000_000L, fileFormat = "flac", isDownloaded = true,
        )
        queue(repo, streamedNext)
        val replacement = slot<MediaItem>()
        every { controller.replaceMediaItem(1, capture(replacement)) } returns Unit

        repo.prefetchNextTrack()

        assertThat(replacement.captured.localConfiguration?.uri?.toString()).isEqualTo("file:///storage/music/new.flac")
        coVerify(exactly = 0) { streamResolver.resolve(any(), any(), any(), any()) }
    }
}
