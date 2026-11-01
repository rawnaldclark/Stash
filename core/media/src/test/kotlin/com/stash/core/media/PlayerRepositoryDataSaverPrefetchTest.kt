package com.stash.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.DataSaver
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_CODEC
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamQualityPolicy
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrl
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Data saver on mobile data in [PlayerRepositoryImpl]:
 *  - the next-song look-ahead ([PlayerRepositoryImpl.prefetchNextTrack]) is skipped: the slot stays a
 *    placeholder that the player's own buffering resolves before the transition. What costs no data
 *    is untouched: a next song whose download finished still swaps to its file.
 *  - a FLAC link saved on Wi-Fi is never played on mobile data: not by a tap, not by the look-ahead,
 *    and not from the next slot it was already swapped into ([PlayerRepositoryImpl.relineNextStream]).
 *    These run against a real, pre-populated [StreamUrlCache].
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryDataSaverPrefetchTest {

    private val playbackStateStore: PlaybackStateStore = mockk(relaxed = true)
    private val musicRepository: MusicRepository = mockk { every { trackDeletions } returns MutableSharedFlow() }
    private val streamingPreference: StreamingPreference = mockk(relaxed = true) {
        coEvery { current() } returns true
        every { streamOnCellular } returns flowOf(true)
    }
    private val streamResolver: StreamSourceRegistry = mockk {
        coEvery { resolve(any(), any(), any(), any()) } returns StreamUrl(
            url = "https://cdn.example/next.flac", expiresAtMs = Long.MAX_VALUE, codec = "flac",
        )
    }
    private val mockCache: StreamUrlCache = mockk<StreamUrlCache>(relaxUnitFun = true).also { cache ->
        every { cache.get(any()) } returns null
    }
    private val trackDao: TrackDao = mockk(relaxed = true) { coEvery { getById(any()) } returns null }
    private val controller: MediaController = mockk(relaxed = true)
    private val trackIdentityEvents: TrackIdentityEvents = mockk { every { changes } returns MutableSharedFlow() }
    /** On mobile data, connected. */
    private val connectivity: ConnectivityMonitor = mockk(relaxed = true) {
        every { isConnected() } returns true
        every { isCellular() } returns true
        every { isMetered() } returns true
    }

    private fun policy(dataSaver: Boolean): StreamQualityPolicy = mockk {
        coEvery { dataSaverApplies() } returns dataSaver
        every { dataSaverChanges() } returns emptyFlow()
    }

    /** A real cache with Data saver in effect (the switch on, mobile data), as on the phone. */
    private fun cacheUnderDataSaver() = StreamUrlCache(DataSaver(switchOn = { true }, metered = { true }))

    /** What a FLAC resolve on Wi-Fi left behind: about an hour of life left. */
    private val flacFromWifi = StreamUrl(
        url = "https://qobuz.example/42.flac?etsp=1",
        expiresAtMs = System.currentTimeMillis() + 3_600_000L,
        codec = "flac", bitsPerSample = 16, origin = "qbdlx",
    )

    private fun repo(
        dataSaver: Boolean,
        cache: StreamUrlCache = mockCache,
        qualityPolicy: StreamQualityPolicy = policy(dataSaver),
    ) = PlayerRepositoryImpl(
        context = ApplicationProvider.getApplicationContext(),
        playbackStateStore = playbackStateStore,
        musicRepository = musicRepository,
        streamingPreference = streamingPreference,
        streamResolver = streamResolver,
        streamUrlCache = cache,
        connectivity = connectivity,
        trackDao = trackDao,
        playbackResumer = PlaybackResumer(playbackStateStore, trackDao),
        radioGenerator = mockk(relaxed = true),
        trackIdentityEvents = trackIdentityEvents,
        playbackSessionBus = PlaybackSessionBus(),
        qualityPolicy = qualityPolicy,
    ).also {
        // The seam mock must read as CONNECTED or ensureController rebuilds a real one.
        every { controller.isConnected } returns true
        it.controllerDeferred = controller
    }

    /** Slot 1 holds [next] at [uri] (a stash-resolve:// placeholder by default); slot 0 is playing. */
    private fun queue(repo: PlayerRepositoryImpl, next: Track, uri: String = "stash-resolve://track/${next.id}") {
        val nextItem = MediaItem.Builder()
            .setMediaId(next.id.toString())
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder().setExtras(
                    android.os.Bundle().apply {
                        putLong(EXTRA_TRACK_ID, next.id)
                        if (uri.startsWith("https")) putString(EXTRA_STREAM_CODEC, "flac")
                    },
                ).build(),
            )
            .build()
        every { controller.currentMediaItemIndex } returns 0
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

    /** The look-ahead's fresh-cache upgrade used to swap a Wi-Fi FLAC link in before Data saver was asked. */
    @Test
    fun `the look-ahead never swaps a FLAC link saved on Wi-Fi into the next slot on mobile data`() = runTest {
        val cache = cacheUnderDataSaver().apply { put(42L, flacFromWifi) }
        val repo = repo(dataSaver = true, cache = cache)
        queue(repo, streamedNext)

        repo.prefetchNextTrack()

        verify(exactly = 0) { controller.replaceMediaItem(any(), any()) }
        coVerify(exactly = 0) { streamResolver.resolve(any(), any(), any(), any()) }
        assertThat(cache.get(42L)).isNull()
    }

    @Test
    fun `the look-ahead asks Data saver before it reads the cache`() = runTest {
        val qualityPolicy = policy(dataSaver = true)
        val repo = repo(dataSaver = true, qualityPolicy = qualityPolicy)
        queue(repo, streamedNext)

        repo.prefetchNextTrack()

        coVerifyOrder {
            qualityPolicy.dataSaverApplies()
            mockCache.get(42L)
        }
    }

    /** A tap on an album played on Wi-Fi earlier, now on mobile data: it resolves again, under Data saver. */
    @Test
    fun `a tap on mobile data resolves again instead of playing a FLAC link saved on Wi-Fi`() = runTest {
        val cache = cacheUnderDataSaver().apply { put(42L, flacFromWifi) }
        val lossy = StreamUrl(
            url = "https://jiosaavn.example/42_160.mp4", expiresAtMs = System.currentTimeMillis() + 3_600_000L,
            codec = "aac", bitrateKbps = 160, origin = "jiosaavn",
        )
        coEvery { streamResolver.resolve(any(), any(), any(), any()) } returns lossy
        val qualityPolicy = policy(dataSaver = true)
        val repo = repo(dataSaver = true, cache = cache, qualityPolicy = qualityPolicy)
        val track = TrackEntity(
            id = 42L, title = "Next", artist = "Artist", album = "Album", durationMs = 200_000L,
            isDownloaded = false, isStreamable = true,
        )

        val result = repo.buildMediaItemForTrack(track)

        assertThat(result).isInstanceOf(StreamRoutingResult.Item::class.java)
        val item = (result as StreamRoutingResult.Item).mediaItem
        assertThat(item.localConfiguration?.uri?.toString()).isEqualTo(lossy.url)
        coVerify(exactly = 1) { streamResolver.resolve(any(), any(), any(), any()) }
        coVerify { qualityPolicy.dataSaverApplies() }
        assertThat(cache.get(42L)).isEqualTo(lossy)
    }

    /**
     * Wi-Fi to mobile data mid-song: the look-ahead had already swapped a FLAC link into the next
     * slot. It goes back to a placeholder (resolved under Data saver when reached), and nothing is
     * looked up ahead on mobile data.
     */
    @Test
    fun `a FLAC link already lined up next goes back to a placeholder when Data saver comes into effect`() = runTest {
        val cache = cacheUnderDataSaver().apply { put(42L, flacFromWifi) }
        val repo = repo(dataSaver = true, cache = cache)
        queue(repo, streamedNext, uri = flacFromWifi.url)
        val replacement = slot<MediaItem>()
        every { controller.replaceMediaItem(1, capture(replacement)) } returns Unit

        repo.relineNextStream()

        val uri = replacement.captured.localConfiguration?.uri
        assertThat(uri?.scheme).isEqualTo("stash-resolve")
        assertThat(uri?.lastPathSegment).isEqualTo("42")
        // The stamps of the FLAC link go with it, so Now Playing doesn't call the placeholder FLAC.
        assertThat(replacement.captured.mediaMetadata.extras?.getString(EXTRA_STREAM_CODEC)).isNull()
        assertThat(replacement.captured.mediaMetadata.extras?.getLong(EXTRA_TRACK_ID)).isEqualTo(42L)
        coVerify(exactly = 0) { streamResolver.resolve(any(), any(), any(), any()) }
        assertThat(cache.get(42L)).isNull()
    }

    @Test
    fun `a placeholder or a file lined up next is left where it is`() = runTest {
        val repo = repo(dataSaver = true)
        queue(repo, streamedNext)

        repo.relineNextStream()

        verify(exactly = 0) { controller.replaceMediaItem(any(), any()) }
    }
}
