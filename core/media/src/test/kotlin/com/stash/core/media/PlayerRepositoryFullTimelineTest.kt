package com.stash.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.mapper.toEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Full-timeline queue: setQueue hands ExoPlayer EVERY playable track as a
 * MediaItem immediately (stream tracks as stash-resolve:// placeholders), so
 * native next/prev/repeat/shuffle operate on the whole queue and the old
 * rolling-window machinery (fill window, pending-nav skip chain, end-of-
 * timeline recovery, repeat-all wrap patches) is gone.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryFullTimelineTest {

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
            playbackResumer = PlaybackResumer(playbackStateStore, trackDao),
            radioGenerator = mockk(relaxed = true),
            trackIdentityEvents = trackIdentityEvents,
            playbackSessionBus = PlaybackSessionBus(),
        )
        // The seam mock must read as CONNECTED or ensureController treats it
        // as a zombie from a stopped service and rebuilds a real one.
        every { controller.isConnected } returns true
        repo.controllerDeferred = controller
    }

    @Test
    fun `setQueue materializes the whole queue as media items immediately`() = runTest {
        coEvery { streamingPreference.current() } returns true
        val tracks = (1L..57L).map { Track(id = it, title = "t$it", artist = "a") }
        val items = slot<List<MediaItem>>()
        every { controller.setMediaItems(capture(items), any<Int>(), any<Long>()) } returns Unit

        repo.setQueue(tracks, startIndex = 0)

        assertThat(items.captured).hasSize(57)
        assertThat(items.captured[5].localConfiguration?.uri?.scheme).isEqualTo("stash-resolve")
        assertThat(items.captured[5].mediaMetadata.extras?.getLong(EXTRA_TRACK_ID)).isEqualTo(6L)
    }

    @Test
    fun `setQueue validates only the selected download before starting full library playback`() = runTest {
        coEvery { streamingPreference.current() } returns false
        val tracks = (1L..2_000L).map { id ->
            Track(
                id = id,
                title = "t$id",
                artist = "a",
                filePath = "/library/$id.opus",
                fileSizeBytes = 1_000_000L,
                isDownloaded = true,
            )
        }
        val checkedPaths = mutableListOf<String>()
        var checkedPathsWhenPlaybackStarted = emptyList<String>()
        var checksWhenPlaybackStarted = -1
        repo.filePathExistsOnDisk = { path ->
            checkedPaths += path
            true
        }
        val items = slot<List<MediaItem>>()
        every { controller.setMediaItems(capture(items), any<Int>(), any<Long>()) } returns Unit
        every { controller.play() } answers {
            checksWhenPlaybackStarted = checkedPaths.size
            checkedPathsWhenPlaybackStarted = checkedPaths.toList()
        }

        repo.setQueue(tracks, startIndex = 999)

        assertThat(items.captured.map { it.mediaId })
            .containsExactlyElementsIn((1L..2_000L).map(Long::toString))
            .inOrder()
        assertThat(checksWhenPlaybackStarted).isEqualTo(1)
        assertThat(checkedPathsWhenPlaybackStarted).containsExactly("/library/1000.opus")
        verify { controller.setMediaItems(any(), 999, 0L) }
    }

    @Test
    fun `setQueue falls back to a stream placeholder when the selected download is stale online`() = runTest {
        coEvery { streamingPreference.current() } returns true
        val checkedPaths = mutableListOf<String>()
        repo.filePathExistsOnDisk = { path ->
            checkedPaths += path
            path != "/storage/music/missing.flac"
        }
        val localBefore = Track(
            id = 41L,
            title = "Before",
            artist = "Artist",
            filePath = "/storage/music/before.flac",
            fileSizeBytes = 1_000_000L,
            isDownloaded = true,
        )
        val staleDownload = Track(
            id = 42L,
            title = "Missing locally",
            artist = "Artist",
            filePath = "/storage/music/missing.flac",
            fileSizeBytes = 1_000_000L,
            isDownloaded = true,
            isStreamable = true,
        )
        val localAfter = localBefore.copy(
            id = 43L,
            title = "After",
            filePath = "/storage/music/after.flac",
        )
        val items = slot<List<MediaItem>>()
        every { controller.setMediaItems(capture(items), any<Int>(), any<Long>()) } returns Unit

        repo.setQueue(listOf(localBefore, staleDownload, localAfter), startIndex = 1)

        assertThat(items.captured.map { it.localConfiguration?.uri?.scheme })
            .containsExactly("file", "stash-resolve", "file").inOrder()
        assertThat(checkedPaths).containsExactly("/storage/music/missing.flac")
        verify { controller.setMediaItems(any(), 1, 0L) }
        verify { controller.prepare() }
        verify { controller.play() }
    }

    @Test
    fun `prefetch replaces the exact duplicate slot chosen as Media3 next`() = runTest {
        coEvery { streamingPreference.current() } returns true
        every { streamUrlCache.get(3L) } returns null
        coEvery { trackDao.getById(3L) } returns null
        val checkedPaths = mutableListOf<String>()
        repo.filePathExistsOnDisk = { path ->
            checkedPaths += path
            path != "/storage/music/stale-next.flac"
        }
        val current = Track(id = 1L, title = "Current", artist = "Artist")
        val logicalNext = Track(
            id = 2L,
            title = "Logical next",
            artist = "Artist",
            filePath = "/storage/music/logical-next.flac",
            fileSizeBytes = 1_000_000L,
            isDownloaded = true,
        )
        val shuffledNext = Track(
            id = 3L,
            title = "Next",
            artist = "Artist",
            filePath = "/storage/music/stale-next.flac",
            fileSizeBytes = 1_000_000L,
            isDownloaded = true,
            isStreamable = true,
        )
        val currentItem = MediaItem.Builder()
            .setMediaId("1")
            .setUri("stash-resolve://track/1")
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setExtras(
                    android.os.Bundle().apply { putLong(EXTRA_TRACK_ID, 1L) },
                ).build(),
            )
            .build()
        val logicalNextItem = MediaItem.Builder()
            .setMediaId("2")
            .setUri("file:///storage/music/logical-next.flac")
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setExtras(
                    android.os.Bundle().apply { putLong(EXTRA_TRACK_ID, 2L) },
                ).build(),
            )
            .build()
        val shuffledNextItem = MediaItem.Builder()
            .setMediaId("3")
            .setUri("file:///storage/music/stale-next.flac")
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setExtras(
                    android.os.Bundle().apply { putLong(EXTRA_TRACK_ID, 3L) },
                ).build(),
            )
            .build()
        every { controller.currentMediaItem } returns currentItem
        every { controller.nextMediaItemIndex } returns 3
        every { controller.mediaItemCount } returns 4
        every { controller.getMediaItemAt(0) } returns shuffledNextItem
        every { controller.getMediaItemAt(1) } returns currentItem
        every { controller.getMediaItemAt(2) } returns logicalNextItem
        every { controller.getMediaItemAt(3) } returns shuffledNextItem
        val resolved = com.stash.core.media.streaming.StreamUrl(
            url = "https://cdn.example/next.flac",
            expiresAtMs = Long.MAX_VALUE,
            codec = "flac",
        )
        coEvery {
            streamResolver.resolve(any(), allowYouTube = true, allowYtDlp = true)
        } returns resolved
        val replacement = slot<MediaItem>()
        every { controller.replaceMediaItem(3, capture(replacement)) } returns Unit
        repo.currentQueueTracks = listOf(shuffledNext, current, logicalNext, shuffledNext)

        repo.prefetchNextTrack()

        assertThat(checkedPaths).containsExactly("/storage/music/stale-next.flac")
        assertThat(replacement.captured.mediaId).isEqualTo("3")
        assertThat(replacement.captured.localConfiguration?.uri?.toString())
            .isEqualTo("https://cdn.example/next.flac")
        coVerify {
            streamResolver.resolve(
                match { it.id == 3L },
                allowYouTube = true,
                allowYtDlp = true,
            )
        }
        verify(exactly = 0) { controller.replaceMediaItem(0, any()) }
    }

    @Test
    fun `failed local item retries as a stream for external controller navigation`() = runTest {
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns true
        val failedTrack = Track(
            id = 77L,
            title = "Missing local",
            artist = "Artist",
            filePath = "/storage/music/missing.flac",
            fileSizeBytes = 1_000_000L,
            isDownloaded = true,
            isStreamable = true,
        )
        val failedItem = MediaItem.Builder()
            .setMediaId("77")
            .setUri("file:///storage/music/missing.flac")
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setExtras(
                    android.os.Bundle().apply { putLong(EXTRA_TRACK_ID, 77L) },
                ).build(),
            )
            .build()
        every { controller.currentMediaItemIndex } returns 1
        every { controller.currentMediaItem } returns failedItem
        every { controller.mediaItemCount } returns 2
        every { controller.getMediaItemAt(1) } returns failedItem
        val replacement = slot<MediaItem>()
        every { controller.replaceMediaItem(1, capture(replacement)) } returns Unit
        repo.currentQueueTracks = listOf(
            Track(id = 1L, title = "Before", artist = "Artist"),
            failedTrack,
        )

        val recovered = repo.recoverLocalFailureAsStream(controller, failedItem, failedIndex = 1)

        assertThat(recovered).isTrue()
        assertThat(replacement.captured.mediaId).isEqualTo("77")
        assertThat(replacement.captured.localConfiguration?.uri?.scheme)
            .isEqualTo("stash-resolve")
        verify { controller.prepare() }
        verify { controller.play() }
    }

    @Test
    fun `failed local item from a service-created queue retries from its database row`() = runTest {
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns true
        val failedTrack = Track(
            id = 78L,
            title = "Restored local",
            artist = "Artist",
            filePath = "/storage/music/restored-missing.flac",
            fileSizeBytes = 1_000_000L,
            isDownloaded = true,
            isStreamable = true,
        )
        val failedItem = MediaItem.Builder()
            .setMediaId("78")
            .setUri("file:///storage/music/restored-missing.flac")
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setExtras(
                    android.os.Bundle().apply { putLong(EXTRA_TRACK_ID, 78L) },
                ).build(),
            )
            .build()
        every { controller.currentMediaItemIndex } returns 1
        every { controller.currentMediaItem } returns failedItem
        every { controller.mediaItemCount } returns 2
        every { controller.getMediaItemAt(1) } returns failedItem
        coEvery { trackDao.getById(78L) } returns failedTrack.toEntity()
        val replacement = slot<MediaItem>()
        every { controller.replaceMediaItem(1, capture(replacement)) } returns Unit
        repo.currentQueueTracks = emptyList()

        val recovered = repo.recoverLocalFailureAsStream(controller, failedItem, failedIndex = 1)

        assertThat(recovered).isTrue()
        assertThat(replacement.captured.mediaId).isEqualTo("78")
        assertThat(replacement.captured.localConfiguration?.uri?.scheme)
            .isEqualTo("stash-resolve")
        verify { controller.prepare() }
        verify { controller.play() }
    }

    @Test
    fun `failed local recovery stops when the user navigates during preference lookup`() = runTest {
        var activeIndex = 1
        val failedTrack = Track(
            id = 79L,
            title = "Failed local",
            artist = "Artist",
            filePath = "/storage/music/failed.flac",
            fileSizeBytes = 1_000_000L,
            isDownloaded = true,
            isStreamable = true,
        )
        val failedItem = MediaItem.Builder()
            .setMediaId("79")
            .setUri("file:///storage/music/failed.flac")
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setExtras(
                    android.os.Bundle().apply { putLong(EXTRA_TRACK_ID, 79L) },
                ).build(),
            )
            .build()
        val newCurrentItem = MediaItem.Builder()
            .setMediaId("80")
            .setUri("file:///storage/music/current.flac")
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setExtras(
                    android.os.Bundle().apply { putLong(EXTRA_TRACK_ID, 80L) },
                ).build(),
            )
            .build()
        every { connectivity.isConnected() } returns true
        every { connectivity.isCellular() } returns true
        // The cellular-preference read is the suspension point that outlived
        // the Download switch's removal from playback; navigate away inside it.
        every { streamingPreference.streamOnCellular } returns kotlinx.coroutines.flow.flow {
            activeIndex = 0
            emit(true)
        }
        every { controller.currentMediaItemIndex } answers { activeIndex }
        every { controller.currentMediaItem } answers {
            if (activeIndex == 1) failedItem else newCurrentItem
        }
        every { controller.mediaItemCount } returns 2
        every { controller.getMediaItemAt(1) } returns failedItem
        repo.currentQueueTracks = listOf(
            Track(id = 80L, title = "Current", artist = "Artist"),
            failedTrack,
        )

        val recovered = repo.recoverLocalFailureAsStream(controller, failedItem, failedIndex = 1)

        assertThat(recovered).isFalse()
        verify(exactly = 0) { controller.replaceMediaItem(any(), any()) }
        verify(exactly = 0) { controller.prepare() }
        verify(exactly = 0) { controller.play() }
    }

    @Test
    fun `failed local recovery honors disabled cellular streaming`() = runTest {
        val failedTrack = Track(
            id = 81L,
            title = "Metered local",
            artist = "Artist",
            filePath = "/storage/music/metered.flac",
            fileSizeBytes = 1_000_000L,
            isDownloaded = true,
            isStreamable = true,
        )
        val failedItem = MediaItem.Builder()
            .setMediaId("81")
            .setUri("file:///storage/music/metered.flac")
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setExtras(
                    android.os.Bundle().apply { putLong(EXTRA_TRACK_ID, 81L) },
                ).build(),
            )
            .build()
        every { connectivity.isConnected() } returns true
        every { connectivity.isCellular() } returns true
        coEvery { streamingPreference.current() } returns true
        every { streamingPreference.streamOnCellular } returns flowOf(false)
        every { controller.currentMediaItemIndex } returns 0
        every { controller.currentMediaItem } returns failedItem
        every { controller.mediaItemCount } returns 1
        every { controller.getMediaItemAt(0) } returns failedItem
        repo.currentQueueTracks = listOf(failedTrack)

        val recovered = repo.recoverLocalFailureAsStream(controller, failedItem, failedIndex = 0)

        assertThat(recovered).isFalse()
        verify(exactly = 0) { controller.replaceMediaItem(any(), any()) }
        verify(exactly = 0) { controller.prepare() }
        verify(exactly = 0) { controller.play() }
    }

    @Test
    fun `failed local recovery rejects mismatched media and metadata identities`() = runTest {
        val failedTrack = Track(
            id = 82L,
            title = "Identity local",
            artist = "Artist",
            filePath = "/storage/music/identity.flac",
            fileSizeBytes = 1_000_000L,
            isDownloaded = true,
            isStreamable = true,
        )
        val malformedItem = MediaItem.Builder()
            .setMediaId("999")
            .setUri("file:///storage/music/identity.flac")
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setExtras(
                    android.os.Bundle().apply { putLong(EXTRA_TRACK_ID, 82L) },
                ).build(),
            )
            .build()
        every { connectivity.isConnected() } returns true
        every { connectivity.isCellular() } returns false
        coEvery { streamingPreference.current() } returns true
        every { controller.currentMediaItemIndex } returns 0
        every { controller.currentMediaItem } returns malformedItem
        every { controller.mediaItemCount } returns 1
        every { controller.getMediaItemAt(0) } returns malformedItem
        repo.currentQueueTracks = listOf(failedTrack)

        val recovered = repo.recoverLocalFailureAsStream(controller, malformedItem, failedIndex = 0)

        assertThat(recovered).isFalse()
        verify(exactly = 0) { controller.replaceMediaItem(any(), any()) }
        verify(exactly = 0) { controller.prepare() }
        verify(exactly = 0) { controller.play() }
    }

    @Test
    fun `prefetch leaves an existing downloaded next track local`() = runTest {
        coEvery { streamingPreference.current() } returns true
        val checkedPaths = mutableListOf<String>()
        repo.filePathExistsOnDisk = { path ->
            checkedPaths += path
            true
        }
        val current = Track(id = 31L, title = "Current", artist = "Artist")
        val localNext = Track(
            id = 32L,
            title = "Local next",
            artist = "Artist",
            filePath = "/storage/music/local-next.flac",
            fileSizeBytes = 1_000_000L,
            isDownloaded = true,
        )
        fun item(track: Track, uri: String) = MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(uri)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setExtras(
                    android.os.Bundle().apply { putLong(EXTRA_TRACK_ID, track.id) },
                ).build(),
            )
            .build()
        val currentItem = item(current, "stash-resolve://track/31")
        val nextItem = item(localNext, "file:///storage/music/local-next.flac")
        every { controller.nextMediaItemIndex } returns 1
        every { controller.mediaItemCount } returns 2
        every { controller.getMediaItemAt(1) } returns nextItem
        repo.currentQueueTracks = listOf(current, localNext)

        repo.prefetchNextTrack()

        assertThat(checkedPaths).containsExactly("/storage/music/local-next.flac")
        coVerify(exactly = 0) {
            streamResolver.resolve(any(), allowYouTube = true, allowYtDlp = true)
        }
        verify(exactly = 0) { controller.replaceMediaItem(any(), any()) }
    }

    @Test
    fun `direct queue jump replaces a stale downloaded target with a stream placeholder`() = runTest {
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns true
        repo.filePathExistsOnDisk = { false }
        val current = Track(id = 10L, title = "Current", artist = "Artist")
        val staleTarget = Track(
            id = 20L,
            title = "Target",
            artist = "Artist",
            filePath = "/storage/music/stale-target.flac",
            fileSizeBytes = 1_000_000L,
            isDownloaded = true,
            isStreamable = true,
        )
        val currentItem = MediaItem.Builder()
            .setMediaId("10")
            .setUri("stash-resolve://track/10")
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setExtras(
                    android.os.Bundle().apply { putLong(EXTRA_TRACK_ID, 10L) },
                ).build(),
            )
            .build()
        val staleTargetItem = MediaItem.Builder()
            .setMediaId("20")
            .setUri("file:///storage/music/stale-target.flac")
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setExtras(
                    android.os.Bundle().apply { putLong(EXTRA_TRACK_ID, 20L) },
                ).build(),
            )
            .build()
        every { controller.currentMediaItem } returns currentItem
        every { controller.mediaItemCount } returns 2
        every { controller.getMediaItemAt(0) } returns currentItem
        every { controller.getMediaItemAt(1) } returns staleTargetItem
        val replacement = slot<MediaItem>()
        every { controller.replaceMediaItem(1, capture(replacement)) } returns Unit
        repo.currentQueueTracks = listOf(current, staleTarget)

        repo.skipToQueueIndex(1)

        assertThat(replacement.captured.mediaId).isEqualTo("20")
        assertThat(replacement.captured.localConfiguration?.uri?.scheme)
            .isEqualTo("stash-resolve")
        verify { controller.seekToDefaultPosition(1) }
    }

    @Test
    fun `addToQueue in Download mode appends stream-only tracks as placeholders`() = runTest {
        // The Download switch decides what sync writes to disk, never what
        // plays (until 2026-09-17 Offline mode rejected both without touching
        // the player).
        coEvery { streamingPreference.current() } returns false
        every { controller.mediaItemCount } returns 1
        val first = Track(
            id = 101L,
            title = "First",
            artist = "Artist",
            youtubeId = "first-video",
            isStreamable = true,
        )
        val second = first.copy(
            id = 202L,
            title = "Second",
            youtubeId = "second-video",
        )
        val items = mutableListOf<MediaItem>()
        every { controller.addMediaItem(capture(items)) } returns Unit

        repo.addToQueue(first)
        repo.addToQueue(second)

        assertThat(items.map { it.mediaId }).containsExactly("101", "202").inOrder()
        assertThat(items.map { it.localConfiguration?.uri?.scheme })
            .containsExactly("stash-resolve", "stash-resolve")
    }

    @Test
    fun `addToQueue turns a downloaded row whose local file is unusable into a stream placeholder`() = runTest {
        coEvery { streamingPreference.current() } returns false
        repo.filePathExistsOnDisk = { false }
        every { controller.mediaItemCount } returns 1
        val staleDownload = Track(
            id = 303L,
            title = "Missing",
            artist = "Artist",
            filePath = "/storage/music/missing.flac",
            isDownloaded = true,
            isStreamable = true,
        )
        val item = slot<MediaItem>()
        every { controller.addMediaItem(capture(item)) } returns Unit

        val added = repo.addToQueue(staleDownload)

        assertThat(added).isTrue()
        assertThat(item.captured.mediaId).isEqualTo("303")
        assertThat(item.captured.localConfiguration?.uri?.scheme).isEqualTo("stash-resolve")
    }

    @Test
    fun `setQueue turns a start item whose local file is unusable into a stream placeholder`() = runTest {
        // Only the selected item is validated on disk (the rest lazily, at
        // open time); a stale one streams instead of refusing the whole queue.
        coEvery { streamingPreference.current() } returns false
        val checkedPaths = mutableListOf<String>()
        repo.filePathExistsOnDisk = { path ->
            checkedPaths += path
            path != "/storage/music/missing.flac"
        }
        val localBefore = Track(
            id = 504L,
            title = "Before",
            artist = "Artist",
            filePath = "/storage/music/before.flac",
            fileSizeBytes = 1_000_000L,
            isDownloaded = true,
        )
        val staleDownload = Track(
            id = 505L,
            title = "Missing",
            artist = "Artist",
            filePath = "/storage/music/missing.flac",
            fileSizeBytes = 1_000_000L,
            isDownloaded = true,
            isStreamable = true,
        )
        val localAfter = localBefore.copy(
            id = 506L,
            title = "After",
            filePath = "/storage/music/after.flac",
        )

        val items = slot<List<MediaItem>>()
        every { controller.setMediaItems(capture(items), any<Int>(), any<Long>()) } returns Unit

        repo.setQueue(listOf(localBefore, staleDownload, localAfter), startIndex = 1)

        assertThat(checkedPaths).containsExactly("/storage/music/missing.flac")
        assertThat(items.captured.map { it.mediaId }).containsExactly("504", "505", "506").inOrder()
        assertThat(items.captured.map { it.localConfiguration?.uri?.scheme })
            .containsExactly("file", "stash-resolve", "file")
            .inOrder()
        verify { controller.setMediaItems(any<List<MediaItem>>(), 1, any<Long>()) }
        verify { controller.play() }
    }

    @Test
    fun `batch addToQueue accepts a usable SAF content download`() = runTest {
        coEvery { streamingPreference.current() } returns false
        repo.filePathExistsOnDisk = { true }
        every { controller.mediaItemCount } returns 1
        val safDownload = Track(
            id = 1001L,
            title = "SAF Local",
            artist = "Artist",
            filePath = "content://com.android.externalstorage.documents/document/music%3Asong.flac",
            isDownloaded = true,
        )
        val items = slot<List<MediaItem>>()
        every { controller.addMediaItems(capture(items)) } returns Unit

        val added = repo.addToQueue(listOf(safDownload))

        assertThat(added).isTrue()
        assertThat(items.captured.single().localConfiguration?.uri?.scheme)
            .isEqualTo("content")
    }

    @Test
    fun `addToQueue persists zero-id stream tracks before building media items`() = runTest {
        coEvery { streamingPreference.current() } returns true
        every { controller.mediaItemCount } returns 1
        coEvery {
            musicRepository.ensureTrackPersisted(match { it.title == "First" })
        } returns 101L
        coEvery {
            musicRepository.ensureTrackPersisted(match { it.title == "Second" })
        } returns 202L
        val first = Track(
            id = 0L,
            title = "First",
            artist = "Artist",
            isStreamable = true,
        )
        val second = first.copy(title = "Second")
        val items = mutableListOf<MediaItem>()
        every { controller.addMediaItem(capture(items)) } returns Unit

        repo.addToQueue(first)
        repo.addToQueue(second)

        assertThat(items.map { it.mediaId }).containsExactly("101", "202").inOrder()
        assertThat(items.map { it.localConfiguration?.uri?.toString() })
            .containsExactly(
                "stash-resolve://track/101?t=First&a=Artist",
                "stash-resolve://track/202?t=Second&a=Artist",
            )
            .inOrder()
    }

    @Test
    fun `skipNext replaces a stale downloaded target online before native seek`() = runTest {
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns true
        repo.filePathExistsOnDisk = { false }
        val current = Track(id = 901L, title = "Current", artist = "Artist")
        val staleNext = Track(
            id = 902L,
            title = "Next",
            artist = "Artist",
            filePath = "/storage/music/stale-next.flac",
            fileSizeBytes = 1_000_000L,
            isDownloaded = true,
            isStreamable = true,
        )
        fun item(track: Track, uri: String) = MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(uri)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setExtras(
                    android.os.Bundle().apply { putLong(EXTRA_TRACK_ID, track.id) },
                ).build(),
            )
            .build()
        val nextItem = item(staleNext, "file:///storage/music/stale-next.flac")
        every { controller.hasNextMediaItem() } returns true
        every { controller.nextMediaItemIndex } returns 1
        every { controller.mediaItemCount } returns 2
        every { controller.getMediaItemAt(1) } returns nextItem
        val replacement = slot<MediaItem>()
        every { controller.replaceMediaItem(1, capture(replacement)) } returns Unit
        repo.currentQueueTracks = listOf(current, staleNext)

        repo.skipNext()

        assertThat(replacement.captured.mediaId).isEqualTo("902")
        assertThat(replacement.captured.localConfiguration?.uri?.scheme)
            .isEqualTo("stash-resolve")
        verify { controller.seekToNextMediaItem() }
    }

    @Test
    fun `skipPrevious traverses past a stale downloaded target offline`() = runTest {
        coEvery { streamingPreference.current() } returns false
        val checkedPaths = mutableListOf<String>()
        repo.filePathExistsOnDisk = { path ->
            checkedPaths += path
            path == "/storage/music/valid-previous.flac"
        }
        val validPrevious = Track(
            id = 902L,
            title = "Valid previous",
            artist = "Artist",
            filePath = "/storage/music/valid-previous.flac",
            fileSizeBytes = 1_000_000L,
            isDownloaded = true,
        )
        val stalePrevious = Track(
            id = 903L,
            title = "Previous",
            artist = "Artist",
            filePath = "/storage/music/stale-previous.flac",
            fileSizeBytes = 1_000_000L,
            isDownloaded = true,
            isStreamable = true,
        )
        val current = Track(id = 904L, title = "Current", artist = "Artist")
        fun item(track: Track, uri: String) = MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(uri)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setExtras(
                    android.os.Bundle().apply { putLong(EXTRA_TRACK_ID, track.id) },
                ).build(),
            )
            .build()
        val validPreviousItem = item(validPrevious, "file:///storage/music/valid-previous.flac")
        val previousItem = MediaItem.Builder()
            .setMediaId("903")
            .setUri("file:///storage/music/stale-previous.flac")
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setExtras(
                    android.os.Bundle().apply { putLong(EXTRA_TRACK_ID, 903L) },
                ).build(),
            )
            .build()
        val timeline: Timeline = mockk()
        every { controller.hasPreviousMediaItem() } returns true
        every { controller.previousMediaItemIndex } returns 1
        every { controller.currentMediaItemIndex } returns 2
        every { controller.currentTimeline } returns timeline
        every { controller.repeatMode } returns Player.REPEAT_MODE_OFF
        every { controller.shuffleModeEnabled } returns false
        every {
            timeline.getPreviousWindowIndex(1, Player.REPEAT_MODE_OFF, false)
        } returns 0
        every { controller.mediaItemCount } returns 3
        every { controller.getMediaItemAt(0) } returns validPreviousItem
        every { controller.getMediaItemAt(1) } returns previousItem
        repo.currentQueueTracks = listOf(validPrevious, stalePrevious, current)

        repo.skipPrevious()

        verify(exactly = 0) { controller.replaceMediaItem(any(), any()) }
        verify { controller.seekToDefaultPosition(0) }
        verify(exactly = 0) { controller.seekToPreviousMediaItem() }
        assertThat(checkedPaths).containsExactly(
            "/storage/music/stale-previous.flac",
            "/storage/music/valid-previous.flac",
        ).inOrder()
    }

    @Test
    fun `skipPrevious does not wrap to the current item when every predecessor is stale`() = runTest {
        coEvery { streamingPreference.current() } returns false
        repo.filePathExistsOnDisk = { false }
        val firstStale = Track(
            id = 905L,
            title = "First stale",
            artist = "Artist",
            filePath = "/storage/music/first-stale.flac",
            fileSizeBytes = 1_000_000L,
            isDownloaded = true,
        )
        val secondStale = Track(
            id = 906L,
            title = "Second stale",
            artist = "Artist",
            filePath = "/storage/music/second-stale.flac",
            fileSizeBytes = 1_000_000L,
            isDownloaded = true,
        )
        val current = Track(id = 907L, title = "Current", artist = "Artist")
        fun item(track: Track, uri: String) = MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(uri)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setExtras(
                    android.os.Bundle().apply { putLong(EXTRA_TRACK_ID, track.id) },
                ).build(),
            )
            .build()
        val timeline: Timeline = mockk()
        every { controller.hasPreviousMediaItem() } returns true
        every { controller.previousMediaItemIndex } returns 1
        every { controller.currentMediaItemIndex } returns 2
        every { controller.currentTimeline } returns timeline
        every { controller.repeatMode } returns Player.REPEAT_MODE_ALL
        every { controller.shuffleModeEnabled } returns false
        every {
            timeline.getPreviousWindowIndex(1, Player.REPEAT_MODE_ALL, false)
        } returns 0
        every {
            timeline.getPreviousWindowIndex(0, Player.REPEAT_MODE_ALL, false)
        } returns 2
        every { controller.mediaItemCount } returns 3
        every { controller.getMediaItemAt(0) } returns
            item(firstStale, "file:///storage/music/first-stale.flac")
        every { controller.getMediaItemAt(1) } returns
            item(secondStale, "file:///storage/music/second-stale.flac")
        every { controller.getMediaItemAt(2) } returns item(current, "stash-resolve://track/907")
        repo.currentQueueTracks = listOf(firstStale, secondStale, current)

        repo.skipPrevious()

        verify(exactly = 0) { controller.seekToDefaultPosition(any()) }
        verify(exactly = 0) { controller.seekToPreviousMediaItem() }
    }

    @Test
    fun `playing placeholder gets quality extras stamped from the url cache`() {
        // Full-timeline placeholders carry no codec/origin extras; once the
        // just-in-time resolve caches the StreamUrl, the current item's
        // metadata must be stamped so Now Playing doesn't show the "opus"
        // fallback (metadata-only replace — URI untouched).
        val placeholder = MediaItem.Builder()
            .setMediaId("5")
            .setUri("stash-resolve://track/5")
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setExtras(
                    android.os.Bundle().apply { putLong(EXTRA_TRACK_ID, 5L) },
                ).build(),
            )
            .build()
        every { controller.currentMediaItem } returns placeholder
        every { controller.currentMediaItemIndex } returns 3
        every { streamUrlCache.get(5L) } returns
            com.stash.core.media.streaming.StreamUrl(
                url = "https://cdn/x.flac",
                expiresAtMs = Long.MAX_VALUE,
                codec = "flac",
                bitsPerSample = 24,
                sampleRateHz = 96_000,
                origin = "qbdlx",
            )
        val stamped = slot<MediaItem>()
        every { controller.replaceMediaItem(3, capture(stamped)) } returns Unit

        repo.maybeStampCurrentItemQuality(controller)

        val extras = stamped.captured.mediaMetadata.extras!!
        assertThat(extras.getString("stash_stream_codec")).isEqualTo("flac")
        assertThat(extras.getInt("stash_stream_bit_depth")).isEqualTo(24)
        assertThat(extras.getString("stash_stream_origin")).isEqualTo("qbdlx")
        // URI untouched — a metadata-only replace never interrupts playback.
        assertThat(stamped.captured.localConfiguration?.uri?.toString())
            .isEqualTo("stash-resolve://track/5")
    }

    @Test
    fun `negative synthetic id gets stamped and its youtube-thumbnail art upgraded to the cover`() {
        // Radio/search tracks use videoId.hashCode() ids, which are frequently
        // NEGATIVE — the stamp must run for them (the old `<= 0L` guard skipped
        // them, leaving "opus" + low-res art). It must also upgrade a low-res
        // i.ytimg thumbnail to the resolved square cover.
        val negId = -600172367L
        val placeholder = MediaItem.Builder()
            .setMediaId(negId.toString())
            .setUri("stash-resolve://track/$negId")
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setArtworkUri(android.net.Uri.parse("https://i.ytimg.com/vi/abc/mqdefault.jpg"))
                    .setExtras(android.os.Bundle().apply { putLong(EXTRA_TRACK_ID, negId) })
                    .build(),
            )
            .build()
        every { controller.currentMediaItem } returns placeholder
        every { controller.currentMediaItemIndex } returns 2
        every { streamUrlCache.get(negId) } returns
            com.stash.core.media.streaming.StreamUrl(
                url = "https://cdn/x.flac",
                expiresAtMs = Long.MAX_VALUE,
                codec = "flac",
                coverArtUrl = "https://qobuz/cover-large.jpg",
                origin = "qbdlx",
            )
        val stamped = slot<MediaItem>()
        every { controller.replaceMediaItem(2, capture(stamped)) } returns Unit

        repo.maybeStampCurrentItemQuality(controller)

        assertThat(stamped.captured.mediaMetadata.extras!!.getString("stash_stream_codec"))
            .isEqualTo("flac")
        assertThat(stamped.captured.mediaMetadata.artworkUri?.toString())
            .isEqualTo("https://qobuz/cover-large.jpg")
    }

    @Test
    fun `stamped or uncached items are left alone`() {
        every { controller.currentMediaItem } returns MediaItem.Builder()
            .setMediaId("7").setUri("stash-resolve://track/7")
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setExtras(
                    android.os.Bundle().apply { putLong(EXTRA_TRACK_ID, 7L) },
                ).build(),
            ).build()
        every { streamUrlCache.get(7L) } returns null // resolve hasn't run yet

        repo.maybeStampCurrentItemQuality(controller)

        verify(exactly = 0) { controller.replaceMediaItem(any(), any()) }
    }

    @Test
    fun `setQueue starts playback at the tapped index`() = runTest {
        coEvery { streamingPreference.current() } returns true
        val tracks = (1L..20L).map { Track(id = it, title = "t$it", artist = "a") }

        repo.setQueue(tracks, startIndex = 7)

        verify { controller.setMediaItems(any(), 7, 0L) }
        verify { controller.prepare() }
        verify { controller.play() }
    }

    @Test
    fun `setQueue starts at the tapped copy of a song queued twice`() = runTest {
        coEvery { streamingPreference.current() } returns true
        val a = Track(id = 1L, title = "A", artist = "a")
        // A confirmed-unavailable row first, so the start also has to map through the playable filter.
        val gone = Track(id = 5L, title = "Gone", artist = "a", isStreamableCheckedAt = 1L)
        val tracks = listOf(gone, a, Track(id = 2L, title = "B", artist = "a"), a, Track(id = 3L, title = "C", artist = "a"))

        repo.setQueue(tracks, startIndex = 3) // the second A

        verify {
            controller.setMediaItems(match<List<MediaItem>> { items -> items.map { it.mediaId } == listOf("1", "2", "1", "3") }, 2, 0L)
        }
    }
}
