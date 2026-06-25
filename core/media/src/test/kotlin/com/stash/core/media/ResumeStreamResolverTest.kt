package com.stash.core.media

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrl
import com.stash.core.media.streaming.StreamUrlCache
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Unit tests for [ResumeStreamResolver] — the current-track stream-URL
 * resolution used on the native Bluetooth/Auto resumption path so online-mode
 * resume actually plays. Pure JVM (no Robolectric): the resolver returns a
 * URL string, not a Media3 MediaItem.
 */
class ResumeStreamResolverTest {

    private val streamingPreference: StreamingPreference = mockk()
    private val connectivity: ConnectivityMonitor = mockk()
    private val streamUrlCache: StreamUrlCache = mockk(relaxUnitFun = true)
    private val streamResolver: StreamSourceRegistry = mockk()

    private val resolver = ResumeStreamResolver(
        streamingPreference, connectivity, streamUrlCache, streamResolver,
    )

    private fun streamable(id: Long) = TrackEntity(
        id = id, title = "t$id", artist = "a$id", isDownloaded = false, isStreamable = true,
    )

    @Test
    fun downloadedTrack_returnsNull_soCallerUsesLocalFile() = runTest {
        val track = TrackEntity(
            id = 1L, title = "t", artist = "a",
            filePath = "/music/t.flac", isDownloaded = true,
        )
        assertThat(resolver.resolveStreamUrl(track)).isNull()
    }

    @Test
    fun streamingOff_returnsNull() = runTest {
        coEvery { streamingPreference.current() } returns false
        assertThat(resolver.resolveStreamUrl(streamable(2L))).isNull()
    }

    @Test
    fun noConnectivity_returnsNull() = runTest {
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns false
        assertThat(resolver.resolveStreamUrl(streamable(3L))).isNull()
    }

    @Test
    fun cellularWithoutCellularPref_returnsNull() = runTest {
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns true
        every { connectivity.isCellular() } returns true
        every { streamingPreference.streamOnCellular } returns flowOf(false)
        assertThat(resolver.resolveStreamUrl(streamable(4L))).isNull()
    }

    @Test
    fun cacheHit_returnsCachedUrl_withoutResolving() = runTest {
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns true
        every { connectivity.isCellular() } returns false
        every { streamUrlCache.get(5L) } returns StreamUrl("https://cdn/cached", 999L)

        assertThat(resolver.resolveStreamUrl(streamable(5L))).isEqualTo("https://cdn/cached")
        // Resolver is never consulted on a cache hit.
        coVerify(exactly = 0) { streamResolver.resolve(any(), any()) }
    }

    @Test
    fun cacheMiss_resolvesAndCachesUrl() = runTest {
        val track = streamable(6L)
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns true
        every { connectivity.isCellular() } returns false
        every { streamUrlCache.get(6L) } returns null
        coEvery { streamResolver.resolve(track, allowYouTube = true) } returns
            StreamUrl("https://cdn/fresh", 1234L)

        assertThat(resolver.resolveStreamUrl(track)).isEqualTo("https://cdn/fresh")
        verify { streamUrlCache.put(6L, match { it.url == "https://cdn/fresh" }) }
    }


    @Test
    fun cellularCacheMiss_resolvesWithFastStartupPolicy() = runTest {
        val track = streamable(8L)
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns true
        every { connectivity.isCellular() } returns true
        every { streamingPreference.streamOnCellular } returns flowOf(true)
        every { streamUrlCache.get(8L) } returns null
        coEvery {
            streamResolver.resolve(track, allowYouTube = true, preferFastStartup = true)
        } returns StreamUrl("https://cdn/mobile", 1234L)

        assertThat(resolver.resolveStreamUrl(track)).isEqualTo("https://cdn/mobile")
        coVerify(exactly = 1) {
            streamResolver.resolve(track, allowYouTube = true, preferFastStartup = true)
        }
    }

    @Test
    fun resolverReturnsNull_returnsNull() = runTest {
        val track = streamable(7L)
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns true
        every { connectivity.isCellular() } returns false
        every { streamUrlCache.get(7L) } returns null
        coEvery { streamResolver.resolve(track, allowYouTube = true) } returns null

        assertThat(resolver.resolveStreamUrl(track)).isNull()
    }
}
