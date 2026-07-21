package com.stash.core.media

import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
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
        )
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
    fun `offline addToQueue rejects consecutive stream-only tracks without starting playback`() = runTest {
        coEvery { streamingPreference.current() } returns false
        every { controller.mediaItemCount } returns 0
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

        repo.addToQueue(first)
        repo.addToQueue(second)

        verify(exactly = 0) { controller.addMediaItem(any()) }
        verify(exactly = 0) { controller.prepare() }
        verify(exactly = 0) { controller.play() }
    }

    @Test
    fun `offline addToQueue rejects a downloaded row whose local file is unusable`() = runTest {
        coEvery { streamingPreference.current() } returns false
        repo.filePathExistsOnDisk = { false }
        val staleDownload = Track(
            id = 303L,
            title = "Missing",
            artist = "Artist",
            filePath = "/storage/music/missing.flac",
            isDownloaded = true,
            isStreamable = true,
        )

        repo.addToQueue(staleDownload)

        verify(exactly = 0) { controller.addMediaItem(any()) }
    }

    @Test
    fun `addToQueue rejects a stream when Online mode turns off during persistence`() = runTest {
        coEvery { streamingPreference.current() } returnsMany listOf(true, false)
        coEvery { musicRepository.ensureTrackPersisted(any()) } returns 404L
        val transientStream = Track(
            id = 0L,
            title = "Transient",
            artist = "Artist",
            isStreamable = true,
        )

        val added = repo.addToQueue(transientStream)

        assertThat(added).isFalse()
        verify(exactly = 0) { controller.addMediaItem(any()) }
    }

    @Test
    fun `offline setQueue rejects a downloaded row whose local file is unusable`() = runTest {
        coEvery { streamingPreference.current() } returns false
        repo.filePathExistsOnDisk = { false }
        val staleDownload = Track(
            id = 505L,
            title = "Missing",
            artist = "Artist",
            filePath = "/storage/music/missing.flac",
            isDownloaded = true,
            isStreamable = true,
        )

        repo.setQueue(listOf(staleDownload))

        verify(exactly = 0) {
            controller.setMediaItems(any<List<MediaItem>>(), any<Int>(), any<Long>())
        }
    }

    @Test
    fun `setQueue drops streams when Online mode turns off while items are built`() = runTest {
        var online = true
        coEvery { streamingPreference.current() } answers { online }
        repo.filePathExistsOnDisk = {
            online = false
            true
        }
        val local = Track(
            id = 606L,
            title = "Local",
            artist = "Artist",
            filePath = "/storage/music/local.flac",
            isDownloaded = true,
        )
        val remote = Track(
            id = 707L,
            title = "Remote",
            artist = "Artist",
            isStreamable = true,
        )
        val items = slot<List<MediaItem>>()
        every { controller.setMediaItems(capture(items), any<Int>(), any<Long>()) } returns Unit

        repo.setQueue(listOf(local, remote))

        assertThat(items.captured.map { it.mediaId }).containsExactly("606")
    }

    @Test
    fun `batch addToQueue drops streams when Online mode turns off while items are built`() = runTest {
        var online = true
        coEvery { streamingPreference.current() } answers { online }
        repo.filePathExistsOnDisk = {
            online = false
            true
        }
        every { controller.mediaItemCount } returns 1
        val local = Track(
            id = 808L,
            title = "Local",
            artist = "Artist",
            filePath = "/storage/music/local.flac",
            isDownloaded = true,
        )
        val remote = Track(
            id = 909L,
            title = "Remote",
            artist = "Artist",
            isStreamable = true,
        )
        val items = slot<List<MediaItem>>()
        every { controller.addMediaItems(capture(items)) } returns Unit

        val added = repo.addToQueue(listOf(local, remote))

        assertThat(added).isTrue()
        assertThat(items.captured.map { it.mediaId }).containsExactly("808")
    }

    @Test
    fun `offline batch addToQueue accepts a usable SAF content download`() = runTest {
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
    fun `skipNext is always a native seek`() = runTest {
        every { controller.hasNextMediaItem() } returns true

        repo.skipNext()

        verify { controller.seekToNextMediaItem() }
    }

    @Test
    fun `skipPrevious is always a native seek`() = runTest {
        every { controller.hasPreviousMediaItem() } returns true

        repo.skipPrevious()

        verify { controller.seekToPreviousMediaItem() }
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
}
