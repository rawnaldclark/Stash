package com.stash.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.media.cast.BrowserOutputs
import com.stash.core.media.cast.CastRemote
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrl
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [PlayerRepositoryImpl] while a linked browser plays the queue (Remote v2, spec 2026-11-03 §2.3): the browser finds and streams
 * every song itself, so nothing is resolved on this phone (a tap, the next-song look-ahead, a quality change's re-resolve), and a
 * song the browser couldn't play is a plain skip through the cascade guard, never a stream fallback here.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryBrowserTest {

    private val playbackStateStore: PlaybackStateStore = mockk(relaxed = true)
    private val musicRepository: MusicRepository = mockk { every { trackDeletions } returns MutableSharedFlow() }
    private val streamingPreference: StreamingPreference = mockk(relaxed = true) {
        coEvery { current() } returns true
        every { streamOnCellular } returns flowOf(true)
    }
    private val streamResolver: StreamSourceRegistry = mockk {
        coEvery { resolve(any(), any(), any(), any()) } returns StreamUrl(
            url = "https://cdn.example/song.flac", expiresAtMs = Long.MAX_VALUE, codec = "flac",
        )
    }
    private val cache: StreamUrlCache = mockk<StreamUrlCache>(relaxUnitFun = true).also { every { it.get(any()) } returns null }
    private val trackDao: TrackDao = mockk(relaxed = true) { coEvery { getById(any()) } returns null }
    private val controller: MediaController = mockk(relaxed = true)
    private val trackIdentityEvents: TrackIdentityEvents = mockk { every { changes } returns MutableSharedFlow() }
    private val connectivity: ConnectivityMonitor = mockk(relaxed = true) {
        every { isConnected() } returns true
        every { isCellular() } returns false
    }

    /** The receiver the playback service plays through: set while a browser plays the queue. */
    private val browserRemote = MutableStateFlow<CastRemote?>(null)
    private val browserOutputs: BrowserOutputs = mockk(relaxed = true) { every { remote } returns browserRemote }

    private fun repo() = PlayerRepositoryImpl(
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
        browserOutputs = browserOutputs,
    ).also {
        every { controller.isConnected } returns true
        it.controllerDeferred = controller
    }

    private val streamed = TrackEntity(id = 42L, title = "Pink + White", artist = "Frank Ocean", isStreamable = true)

    private fun onBrowser() {
        browserRemote.value = mockk(relaxed = true)
    }

    // ── Nothing resolved on this phone ─────────────────────────────────

    @Test
    fun `while a browser plays, a tapped song is a placeholder, not a resolve`() = runTest {
        val repo = repo()
        onBrowser()

        val result = repo.buildMediaItemForTrack(streamed)

        val item = (result as StreamRoutingResult.Item).mediaItem
        assertThat(item.mediaId).isEqualTo("42")
        assertThat(item.localConfiguration?.uri?.scheme).isEqualTo("stash-resolve")
        coVerify(exactly = 0) { streamResolver.resolve(any(), any(), any(), any()) }
    }

    @Test
    fun `on mobile data with streaming there turned off, a browser still gets the song`() = runTest {
        every { connectivity.isCellular() } returns true
        every { streamingPreference.streamOnCellular } returns flowOf(false)
        val repo = repo()
        onBrowser()

        assertThat(repo.buildMediaItemForTrack(streamed)).isInstanceOf(StreamRoutingResult.Item::class.java)
        coVerify(exactly = 0) { streamResolver.resolve(any(), any(), any(), any()) }
    }

    @Test
    fun `with no browser, a tapped song resolves here as before`() = runTest {
        val repo = repo()

        val item = (repo.buildMediaItemForTrack(streamed) as StreamRoutingResult.Item).mediaItem

        assertThat(item.localConfiguration?.uri?.toString()).isEqualTo("https://cdn.example/song.flac")
        coVerify(exactly = 1) { streamResolver.resolve(any(), any(), any(), any()) }
    }

    @Test
    fun `the next-song look-ahead and a quality change's re-resolve stand down while a browser plays`() = runTest {
        val repo = repo()
        every { controller.currentMediaItemIndex } returns 0
        every { controller.nextMediaItemIndex } returns 1
        every { controller.mediaItemCount } returns 2
        every { controller.getMediaItemAt(1) } returns placeholder(43L)
        repo.currentQueueTracks = listOf(Track(id = 42L, title = "Pink + White", artist = "Frank Ocean"), Track(id = 43L, title = "Nights", artist = "Frank Ocean", isStreamable = true))
        onBrowser()

        repo.prefetchNextTrack()
        repo.reresolveNextStream()

        coVerify(exactly = 0) { streamResolver.resolve(any(), any(), any(), any()) }
        verify(exactly = 0) { controller.replaceMediaItem(any(), any()) }
    }

    // ── A song the browser couldn't play ───────────────────────────────

    @Test
    fun `a song the browser couldn't play is skipped, even a downloaded one, with no stream tried here`() = runTest {
        val repo = repo()
        onBrowser()
        every { controller.currentMediaItem } returns downloaded(42L)
        every { controller.hasNextMediaItem() } returns true

        repo.playerListener.onPlayerError(remoteError)

        verify(exactly = 1) { controller.seekToNextMediaItem() }
        verify(exactly = 0) { controller.replaceMediaItem(any(), any()) }
        coVerify(exactly = 0) { streamResolver.resolve(any(), any(), any(), any()) }
        assertThat(repo.streamingHaltedEvents.replayCache).isEmpty()
    }

    @Test
    fun `a browser that fails song after song is stopped by the cascade guard, not drained`() = runTest {
        val repo = repo()
        onBrowser()
        every { controller.currentMediaItem } returns downloaded(42L)
        every { controller.hasNextMediaItem() } returns true

        repeat(3) { repo.playerListener.onPlayerError(remoteError) }

        verify(exactly = 2) { controller.seekToNextMediaItem() }
        verify { controller.pause() }
        assertThat(repo.streamingHaltedEvents.replayCache.single().consecutiveErrorCount).isEqualTo(3)
    }

    private val remoteError = PlaybackException("The browser couldn't play it", null, PlaybackException.ERROR_CODE_REMOTE_ERROR)

    private fun placeholder(id: Long) = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri("stash-resolve://track/$id")
        .setMediaMetadata(MediaMetadata.Builder().setExtras(android.os.Bundle().apply { putLong(EXTRA_TRACK_ID, id) }).build())
        .build()

    private fun downloaded(id: Long) = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri("file:///storage/music/$id.flac")
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle("Pink + White")
                .setExtras(android.os.Bundle().apply { putLong(EXTRA_TRACK_ID, id) }).build(),
        )
        .build()
}
