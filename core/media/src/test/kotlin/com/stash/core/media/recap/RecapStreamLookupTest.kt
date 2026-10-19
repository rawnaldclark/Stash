package com.stash.core.media.recap

import com.google.common.truth.Truth.assertThat
import com.stash.core.media.streaming.JioSaavnStreamResolver
import com.stash.core.media.streaming.StreamUrl
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.media.streaming.YouTubeStreamResolver
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Where a story card's stream comes from ([RecapStreamLookup]). Built through its production
 * (@Inject) constructor, whose only network dependency is the YouTube resolver: the registry,
 * qbdlx, the Tidal relay and JioSaavn can't be reached from here at all, so a preview never spends
 * the daily lossless allowance or feeds the "lossless offline" banner. These tests pin the rest:
 * the cache first, YouTube only through the InnerTube fast lane, and the cache never written.
 */
class RecapStreamLookupTest {

    private val cache = StreamUrlCache()
    private val youtube: YouTubeStreamResolver = mockk()
    private val lookup = RecapStreamLookup(cache, youtube)

    private val track = Track(id = 42, title = "Idioteque", artist = "Radiohead", youtubeId = "svwJTnZOaco")

    private fun url(u: String, origin: String? = null, isDash: Boolean = false) = StreamUrl(
        url = u,
        expiresAtMs = System.currentTimeMillis() + 3_600_000L,
        origin = origin,
        isDash = isDash,
    )

    @Test
    fun `the main player's cached URL wins and YouTube is never asked`() = runTest {
        cache.put(42, url("https://qobuz/flac", origin = "qbdlx"))

        assertThat(lookup.lookup(track)).isEqualTo(RecapSource.Url("https://qobuz/flac"))
        confirmVerified(youtube)
    }

    @Test
    fun `a cached Tidal manifest plays as DASH`() = runTest {
        cache.put(42, url("https://tidal/x.mpd", origin = "tidal", isDash = true))
        assertThat(lookup.lookup(track)).isEqualTo(RecapSource.Url("https://tidal/x.mpd", isDash = true))
    }

    @Test
    fun `a cached JioSaavn URL is read without following redirects`() = runTest {
        cache.put(42, url("https://aac.saavncdn.com/x.mp4", origin = JioSaavnStreamResolver.ORIGIN))
        assertThat(lookup.lookup(track))
            .isEqualTo(RecapSource.Url("https://aac.saavncdn.com/x.mp4", noRedirects = true))
    }

    @Test
    fun `no cached URL - YouTube's fast lane only, and the cache is never written`() = runTest {
        coEvery { youtube.resolve(any(), allowYtDlp = false) } returns url("https://yt/opus", origin = "youtube")

        assertThat(lookup.lookup(track)).isEqualTo(RecapSource.Url("https://yt/opus"))
        coVerify(exactly = 1) { youtube.resolve(match { it.id == 42L && it.youtubeId == "svwJTnZOaco" }, allowYtDlp = false) }
        confirmVerified(youtube)
        // A YouTube URL in the track-id cache would make the next real tap play YouTube, not FLAC.
        assertThat(cache.get(42)).isNull()
    }

    @Test
    fun `YouTube has nothing - no stream, the card stays quiet`() = runTest {
        coEvery { youtube.resolve(any(), allowYtDlp = false) } returns null
        assertThat(lookup.lookup(track)).isNull()
        assertThat(cache.get(42)).isNull()
    }
}
