package com.stash.core.media

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_DURATION_MS
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import com.stash.core.model.scannedTrackId
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
 * An All Songs file plays from its own file or not at all (review C2). Every path that turns a
 * local item into a `stash-resolve://` placeholder — queue build, prefetch, an explicit jump, the
 * local-error fallback — would otherwise resolve an ONLINE match by the file's title and artist and
 * play that instead, silently, in place of the user's own file.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryScannedSongTest {

    private val playbackStateStore: PlaybackStateStore = mockk(relaxed = true)
    private val musicRepository: MusicRepository = mockk(relaxed = true) {
        every { trackDeletions } returns MutableSharedFlow()
    }
    private val streamingPreference: StreamingPreference = mockk(relaxed = true)
    private val streamResolver: StreamSourceRegistry = mockk(relaxed = true)
    private val streamUrlCache: StreamUrlCache = mockk(relaxed = true)
    private val connectivity: ConnectivityMonitor = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val controller: MediaController = mockk(relaxed = true)
    private val trackIdentityEvents: TrackIdentityEvents = mockk {
        every { changes } returns MutableSharedFlow()
    }

    private lateinit var repo: PlayerRepositoryImpl

    private fun scanned(row: Long, name: String = "Song $row.flac") = Track(
        id = scannedTrackId(row),
        title = "Song $row",
        artist = "Artist",
        filePath = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2F" +
            name.replace(" ", "%20"),
        fileFormat = name.substringAfterLast('.'),
        fileSizeBytes = 5_000_000L,
        source = MusicSource.LOCAL,
        isDownloaded = true,
    )

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
        every { controller.isConnected } returns true
        repo.controllerDeferred = controller
        // Online and streaming allowed: exactly when the old paths swapped in a stream.
        every { connectivity.isConnected() } returns true
        every { connectivity.isCellular() } returns false
        every { streamingPreference.streamOnCellular } returns flowOf(true)
        coEvery { streamingPreference.current() } returns true
    }

    @Test fun `setQueue never turns a scanned song into a stream placeholder`() = runTest {
        // The selected file exists; a tail file is gone (not checked at build: the error policy skips it).
        repo.filePathExistsOnDisk = { true }
        val items = slot<List<MediaItem>>()
        every { controller.setMediaItems(capture(items), any<Int>(), any<Long>()) } returns Unit
        val stream = Track(id = 9L, title = "Online", artist = "Artist")

        repo.setQueue(listOf(scanned(1), scanned(2), stream), startIndex = 0)

        assertThat(items.captured.map { it.localConfiguration?.uri?.scheme })
            .containsExactly("content", "content", "stash-resolve").inOrder()
    }

    @Test fun `a missing selected scanned file is refused, not streamed`() = runTest {
        repo.filePathExistsOnDisk = { false }

        repo.setQueue(listOf(scanned(1), scanned(2)), startIndex = 0)

        verify(exactly = 0) { controller.setMediaItems(any<List<MediaItem>>(), any<Int>(), any<Long>()) }
    }

    @Test fun `the local-error fallback skips a scanned song instead of streaming it`() = runTest {
        val item = MediaItem.Builder()
            .setMediaId(scannedTrackId(1).toString())
            .setUri(scanned(1).filePath)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setExtras(Bundle().apply { putLong(EXTRA_TRACK_ID, scannedTrackId(1)) })
                    .build(),
            )
            .build()
        every { controller.mediaItemCount } returns 1
        every { controller.getMediaItemAt(0) } returns item
        every { controller.currentMediaItem } returns item
        every { controller.currentMediaItemIndex } returns 0

        val recovered = repo.recoverLocalFailureAsStream(controller, item, 0)

        assertThat(recovered).isFalse()
        verify(exactly = 0) { controller.replaceMediaItem(any(), any()) }
    }

    @Test fun `prefetch never resolves a scanned next-up`() = runTest {
        val next = MediaItem.Builder()
            .setMediaId(scannedTrackId(2).toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setExtras(Bundle().apply { putLong(EXTRA_TRACK_ID, scannedTrackId(2)) })
                    .build(),
            )
            .build()
        every { controller.nextMediaItemIndex } returns 1
        every { controller.mediaItemCount } returns 2
        every { controller.getMediaItemAt(1) } returns next
        repo.filePathExistsOnDisk = { false } // even a gone file is not "fixed" by a stream

        repo.prefetchNextTrack()

        coVerify(exactly = 0) { streamResolver.resolve(any(), any(), any()) }
        verify(exactly = 0) { controller.replaceMediaItem(any(), any()) }
    }

    @Test fun `a scanned media item maps to a local downloaded track with its real format`() {
        val uri = scanned(4).filePath!!
        val item = MediaItem.Builder()
            .setMediaId(scannedTrackId(4).toString())
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(android.net.Uri.parse(uri)).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Song 4")
                    .setArtist("Artist")
                    .setExtras(Bundle().apply {
                        putLong(EXTRA_TRACK_ID, scannedTrackId(4))
                        putLong(EXTRA_TRACK_DURATION_MS, 1234L)
                    })
                    .build(),
            )
            .build()

        val track = item.toScannedTrack(scannedTrackId(4))

        assertThat(track.source).isEqualTo(MusicSource.LOCAL)
        assertThat(track.isDownloaded).isTrue()
        assertThat(track.filePath).isEqualTo(uri)
        assertThat(track.fileFormat).isEqualTo("flac") // not the "opus" stream default
        assertThat(track.durationMs).isEqualTo(1234L)
        assertThat(track.youtubeId).isNull()
    }

    @Test fun `the carried format wins over an opaque uri`() {
        val item = MediaItem.Builder()
            .setMediaId(scannedTrackId(5).toString())
            .setUri("content://com.android.providers.downloads.documents/tree/downloads/document/msf%3A12")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setExtras(Bundle().apply { putString(EXTRA_SCANNED_FILE_FORMAT, "m4a") })
                    .build(),
            )
            .build()

        assertThat(item.toScannedTrack(scannedTrackId(5)).fileFormat).isEqualTo("m4a")
    }

    @Test fun `the saved queue leaves scanned songs out and keeps the library songs`() {
        assertThat(savableQueueIds(listOf(1L, scannedTrackId(1), 2L, scannedTrackId(2)))).containsExactly(1L, 2L).inOrder()
        assertThat(savableQueueIds(listOf(scannedTrackId(1)))).isEmpty()
        val plain = listOf(3L, 4L)
        assertThat(savableQueueIds(plain)).isSameInstanceAs(plain)
    }
}
