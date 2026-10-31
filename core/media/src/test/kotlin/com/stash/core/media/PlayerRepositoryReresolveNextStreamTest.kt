package com.stash.core.media

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_CODEC
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.STASH_RESOLVE_SCHEME
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrl
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A streaming quality change mid-song (Now Playing's Streaming quality sheet, #716): the song
 * playing keeps its stream, and the next one, resolved when this one started under the old
 * setting, is resolved again so the change is heard from the next song.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryReresolveNextStreamTest {

    private val playbackStateStore: PlaybackStateStore = mockk(relaxed = true)
    private val musicRepository: MusicRepository = mockk {
        every { trackDeletions } returns MutableSharedFlow()
    }
    private val streamingPreference: StreamingPreference = mockk(relaxed = true) { coEvery { current() } returns true }
    private val streamResolver: StreamSourceRegistry = mockk()
    private val streamUrlCache: StreamUrlCache = mockk(relaxUnitFun = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val controller: MediaController = mockk(relaxed = true)
    private val trackIdentityEvents: TrackIdentityEvents = mockk {
        every { changes } returns MutableSharedFlow()
    }

    private lateinit var repo: PlayerRepositoryImpl

    private val playing = Track(id = 1L, title = "Playing", artist = "Artist")
    private val next = Track(id = 2L, title = "Next", artist = "Artist", isStreamable = true)
    private val oldUrl = "https://cdn.example/next-hires.flac"
    private val newUrl = "https://cdn.example/next.m4a"

    /** Every item put in the next song's slot, in order. */
    private val swaps = mutableListOf<MediaItem>()

    private fun slot(id: Long, uri: String, codec: String? = null): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder().setExtras(
                Bundle().apply {
                    putLong(EXTRA_TRACK_ID, id)
                    codec?.let { putString(EXTRA_STREAM_CODEC, it) }
                },
            ).build(),
        )
        .build()

    private fun stream(url: String, codec: String) = StreamUrl(url = url, expiresAtMs = Long.MAX_VALUE, codec = codec)

    private fun resolvesNextTo(result: StreamUrl?) = coEvery {
        streamResolver.resolve(any(), allowYouTube = true, allowYtDlp = true, speculative = true)
    } returns result

    @Before
    fun setUp() {
        repo = PlayerRepositoryImpl(
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
        )
        // The seam mock must read as CONNECTED or ensureController rebuilds a real one.
        every { controller.isConnected } returns true
        repo.controllerDeferred = controller
        every { controller.currentMediaItemIndex } returns 0
        every { controller.nextMediaItemIndex } returns 1
        every { controller.mediaItemCount } returns 2
        every { controller.getMediaItemAt(0) } returns slot(1L, "https://cdn.example/playing.flac", "flac")
        // The prefetch at this song's start put the next song's old-quality URL in its slot.
        every { controller.getMediaItemAt(1) } returns slot(2L, oldUrl, "flac")
        every { controller.replaceMediaItem(1, capture(swaps)) } returns Unit
        every { streamUrlCache.get(any()) } returns null
        coEvery { trackDao.getById(any()) } returns null
        repo.currentQueueTracks = listOf(playing, next)
    }

    @Test
    fun `the next song is resolved again and its slot gets the new stream`() = runTest {
        resolvesNextTo(stream(newUrl, "aac"))

        repo.reresolveNextStream()

        verify { streamUrlCache.invalidate(2L) }
        coVerify(exactly = 1) {
            streamResolver.resolve(match { it.id == 2L }, allowYouTube = true, allowYtDlp = true, speculative = true)
        }
        // First a placeholder without the old quality stamps, then the new stream with its own.
        assertThat(swaps).hasSize(2)
        assertThat(swaps[0].localConfiguration?.uri?.scheme).isEqualTo(STASH_RESOLVE_SCHEME)
        assertThat(swaps[0].mediaMetadata.extras?.getString(EXTRA_STREAM_CODEC)).isNull()
        assertThat(swaps[1].localConfiguration?.uri?.toString()).isEqualTo(newUrl)
        assertThat(swaps[1].mediaMetadata.extras?.getString(EXTRA_STREAM_CODEC)).isEqualTo("aac")
        verify { streamUrlCache.put(2L, match { it.url == newUrl }) }
        // The song playing keeps its stream.
        verify(exactly = 0) { controller.replaceMediaItem(0, any()) }
    }

    @Test
    fun `when the look-ahead stops short the next song resolves as it plays, not on the old URL`() = runTest {
        // A speculative resolve never asks the Tidal relay (Prefer Tidal AAC just turned on, say).
        resolvesNextTo(null)

        repo.reresolveNextStream()

        assertThat(swaps).hasSize(1)
        assertThat(swaps.single().localConfiguration?.uri?.scheme).isEqualTo(STASH_RESOLVE_SCHEME)
    }

    @Test
    fun `a look-ahead already resolving when the setting changes asks again instead of using its answer`() = runTest {
        // This song just started: its look-ahead for the next one is under way, from the placeholder.
        every { controller.getMediaItemAt(1) } returns slot(2L, "$STASH_RESOLVE_SCHEME://track/2")
        val started = CompletableDeferred<Unit>()
        val oldAnswer = CompletableDeferred<StreamUrl>()
        var asked = 0
        coEvery {
            streamResolver.resolve(any(), allowYouTube = true, allowYtDlp = true, speculative = true)
        } coAnswers {
            asked++
            if (asked == 1) {
                started.complete(Unit)
                oldAnswer.await()
            } else {
                stream(newUrl, "aac")
            }
        }
        val lookAhead = launch { repo.prefetchNextTrack() }
        started.await()

        repo.reresolveNextStream() // finds it in flight and leaves it to finish
        oldAnswer.complete(stream(oldUrl, "flac"))
        lookAhead.join()

        assertThat(asked).isEqualTo(2)
        assertThat(swaps.map { it.localConfiguration?.uri?.toString() }).containsExactly(newUrl)
        verify(exactly = 0) { streamUrlCache.put(2L, match { it.url == oldUrl }) }
    }

    @Test
    fun `a one-song queue on repeat keeps its stream - the next song is the one playing`() = runTest {
        every { controller.nextMediaItemIndex } returns 0
        every { controller.mediaItemCount } returns 1

        repo.reresolveNextStream()

        verify(exactly = 0) { streamUrlCache.invalidate(any()) }
        verify(exactly = 0) { controller.replaceMediaItem(any(), any()) }
        coVerify(exactly = 0) { streamResolver.resolve(any(), any(), any(), any()) }
    }

    @Test
    fun `a look-ahead that finishes after its song started leaves the playing slot alone`() = runTest {
        every { controller.getMediaItemAt(1) } returns slot(2L, "$STASH_RESOLVE_SCHEME://track/2")
        var advanced = false
        every { controller.currentMediaItemIndex } answers { if (advanced) 1 else 0 }
        every { controller.nextMediaItemIndex } answers { if (advanced) C.INDEX_UNSET else 1 }
        coEvery {
            streamResolver.resolve(any(), allowYouTube = true, allowYtDlp = true, speculative = true)
        } coAnswers {
            advanced = true // the song before ended while this resolved
            stream(newUrl, "aac")
        }

        repo.prefetchNextTrack()

        // A new source for the song playing would restart it. The URL is cached for the next open.
        verify(exactly = 0) { controller.replaceMediaItem(any(), any()) }
        verify { streamUrlCache.put(2L, match { it.url == newUrl }) }
    }
}
