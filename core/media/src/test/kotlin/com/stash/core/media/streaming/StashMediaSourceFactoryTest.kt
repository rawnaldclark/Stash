package com.stash.core.media.streaming

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Routing tests for [StashMediaSourceFactory] — the player-wide
 * [MediaSource.Factory] that fans items out to three sub-factories by predicate:
 *
 * 1. YouTube refresh chain ([streamingTrackId] returns an id),
 * 2. JioSaavn authed-HTTP OkHttpDataSource ([isJioSaavnOrigin] true), and
 * 3. local/default (both false).
 *
 * The factory is a plain class (not Hilt), so routing is exercised directly via
 * fake predicate lambdas. Uses [RobolectricTestRunner] because
 * `DefaultMediaSourceFactory` / `OkHttpDataSource.Factory` construction touches
 * Android framework stubs (`android.net.Uri`) that throw on bare JVM.
 */
@RunWith(RobolectricTestRunner::class)
class StashMediaSourceFactoryTest {

    @Test fun `cold and cached SoundCloud placeholders prepare an HLS child`() {
        for (cached in listOf("cold", "soundcloud", "qbdlx")) {
            val streaming = mockk<StreamingMediaSourceFactory>(relaxed = true)
            val resolver = mockk<StreamSourceRegistry>()
            val dao = mockk<com.stash.core.data.db.dao.TrackDao>()
            val stream = StreamUrl("https://cf-media.sndcdn.com/playlist", System.currentTimeMillis() + 300_000,
                origin = "soundcloud", isHls = true)
            val cache = StreamUrlCache()
            if (cached != "cold") cache.put(42, stream.copy(origin = cached))
            io.mockk.coEvery { dao.getById(42) } returns com.stash.core.data.db.entity.TrackEntity(
                id = 42, title = "Remix", artist = "Artist", soundCloudId = "123")
            io.mockk.coEvery { resolver.resolve(any(), any(), any(), any()) } returns stream
            val item = MediaItem.Builder().setMediaId("42").setUri("stash-resolve://track/42?sc=1").build()
            val kid = mockk<MediaSource>(relaxed = true) {
                every { mediaItem } returns item.buildUpon().setUri(stream.url).build()
            }
            every { streaming.createHlsMediaSource(any(), stream) } returns kid
            val factory = StashMediaSourceFactory(ApplicationProvider.getApplicationContext(), streaming,
                { null }, { false }, OkHttpClient(), resolver, cache, dao,
                resolveDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
            val source = factory.createMediaSource(item)
            val caller = MediaSource.MediaSourceCaller { _, _ -> }
            source.prepareSource(caller, null, androidx.media3.exoplayer.analytics.PlayerId.UNSET)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            verify(exactly = 1) { streaming.createHlsMediaSource(any(), stream) }
            source.releaseSource(caller)
        }
    }

    private val streamingFactory: StreamingMediaSourceFactory = mockk(relaxed = true)

    private fun item(uri: String): MediaItem = MediaItem.fromUri(uri)

    private fun newFactory(
        streamingTrackId: (MediaItem) -> Long?,
        isJioSaavnOrigin: (MediaItem) -> Boolean = { false },
    ): StashMediaSourceFactory = StashMediaSourceFactory(
        context = ApplicationProvider.getApplicationContext(),
        streamingFactory = streamingFactory,
        streamingTrackId = streamingTrackId,
        isJioSaavnOrigin = isJioSaavnOrigin,
        httpClient = OkHttpClient(),
        resolver = mockk(relaxed = true),
        urlCache = mockk(relaxed = true),
        trackDao = mockk(relaxed = true),
    )

    @Test
    fun placeholderItem_routesToLazyResolvingChain() {
        val lazy = item("stash-resolve://track/42")
        val factory = newFactory(streamingTrackId = { null })

        val source: MediaSource = factory.createMediaSource(lazy)

        // Placeholder must not enter the eager YouTube chain, and must produce a
        // progressive source backed by the lazy factory.
        verify(exactly = 0) { streamingFactory.create(any()) }
        assertThat(source).isInstanceOf(ProgressiveMediaSource::class.java)
    }

    @Test
    fun jioSaavnItem_routesToDedicatedProgressiveSource_notStreamingChain() {
        val jio = item("https://aac.saavncdn.com/song_320.mp4")
        val factory = newFactory(
            streamingTrackId = { null },
            isJioSaavnOrigin = { it === jio },
        )

        val source = factory.createMediaSource(jio)

        verify(exactly = 0) { streamingFactory.create(any()) }
        assertThat(source).isInstanceOf(ProgressiveMediaSource::class.java)
        assertThat(factory.jioSaavnHttpClient.followRedirects).isFalse()
        assertThat(factory.jioSaavnHttpClient.followSslRedirects).isFalse()
    }

    @Test
    fun youtubeTrackIdItem_routesToStreamingChain() {
        val yt = item("https://youtube.example/v.m4a")
        val innerFactory: MediaSource.Factory = mockk(relaxed = true)
        every { streamingFactory.create(99L) } returns innerFactory

        val factory = newFactory(
            streamingTrackId = { if (it === yt) 99L else null },
        )

        factory.createMediaSource(yt)

        verify(exactly = 1) { streamingFactory.create(99L) }
        verify(exactly = 1) { innerFactory.createMediaSource(yt) }
    }

    @Test
    fun youtubeTrackIdTakesPrecedence_overJioSaavn() {
        // trackId branch is checked first; if it matches, JioSaavn must not be consulted.
        val both = item("https://example/x")
        val innerFactory: MediaSource.Factory = mockk(relaxed = true)
        every { streamingFactory.create(7L) } returns innerFactory
        var jioConsulted = false

        val factory = newFactory(
            streamingTrackId = { 7L },
            isJioSaavnOrigin = { jioConsulted = true; true },
        )

        factory.createMediaSource(both)

        verify(exactly = 1) { streamingFactory.create(7L) }
        assertThat(jioConsulted).isFalse()
    }

    @Test
    fun localItem_routesToNeitherStreamingNorJioSaavn() {
        val local = item("file:///music/song.flac")
        val factory = newFactory(
            streamingTrackId = { null },
        )

        val source: MediaSource = factory.createMediaSource(local)

        verify(exactly = 0) { streamingFactory.create(any()) }
        // Local files also resolve to a progressive source via the default factory;
        // the meaningful assert is that it did not enter the streaming chain above.
        assertThat(source).isNotNull()
    }
}
