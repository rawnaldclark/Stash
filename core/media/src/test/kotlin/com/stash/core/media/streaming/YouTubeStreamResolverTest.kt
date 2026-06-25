package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.preview.PreviewUrlExtractor
import com.stash.data.ytmusic.YTMusicApiClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeStreamResolverTest {

    /**
     * Regression: structural-concurrency bug — `runCatching` inside
     * `resolve()` used to catch `CancellationException` (a Throwable)
     * and convert it to a null result, which then surfaces upstream as
     * `StreamRoutingResult.NotAvailable` -> "Couldn't stream this track"
     * snackbar — even when the resolve was simply preempted by a newer
     * tap. The fix rethrows CE inside the `runCatching.onFailure`.
     */
    @Test
    fun resolve_propagatesCancellationException_notSwallowAsNull() = runTest {
        val extractor: PreviewUrlExtractor = mockk()
        val ytMusic: YTMusicApiClient = mockk()
        // Mock throws CE synchronously — simulates an in-flight
        // extraction call hitting a suspension point that observes
        // parent cancellation. resolve() defaults allowYtDlp=true, which
        // now routes through the yt-dlp-direct path.
        coEvery { extractor.extractStreamUrlViaYtDlp(any()) } throws
            CancellationException("outer cancel")
        val resolver = YouTubeStreamResolver(extractor, ytMusic)
        val track = trackWithYoutubeId("abc123")

        try {
            resolver.resolve(track)
            fail("Expected CancellationException to propagate")
        } catch (expected: CancellationException) {
            // pass — cancellation reached us, not converted to null
        }
    }

    /**
     * Same regression as above, but for the metadata-search path that
     * runs when the track has no stored `youtubeId`.
     */
    @Test
    fun searchYouTubeForVideoId_propagatesCancellationException() = runTest {
        val extractor: PreviewUrlExtractor = mockk()
        val ytMusic: YTMusicApiClient = mockk()
        coEvery { ytMusic.searchAll(any()) } throws
            CancellationException("outer cancel")
        val resolver = YouTubeStreamResolver(extractor, ytMusic)
        // Track without youtubeId — forces the search path.
        val track = trackWithoutYoutubeId(artist = "X", title = "Y")

        try {
            resolver.resolve(track)
            fail("Expected CancellationException to propagate")
        } catch (expected: CancellationException) {
            // pass
        }
    }

    /**
     * Regression lock for the existing timeout behaviour: a genuine
     * extraction stall past `YT_RESOLVE_TIMEOUT_MS` (35s) still
     * surfaces as null (and upstream as `NotAvailable`), separate
     * from a cancellation. Catches the case where the CE-rethrow
     * fix accidentally turns `withTimeoutOrNull` into `withTimeout`.
     * `runTest`'s virtual time skips the real 35-60s wait.
     */
    @Test
    fun resolve_returnsNull_onGenuineExtractionTimeout() = runTest {
        val extractor: PreviewUrlExtractor = mockk()
        val ytMusic: YTMusicApiClient = mockk()
        coEvery { extractor.extractStreamUrlViaYtDlp(any()) } coAnswers {
            delay(60_000)
            "unreachable"
        }
        val resolver = YouTubeStreamResolver(extractor, ytMusic)
        val track = trackWithYoutubeId("abc123")

        val result = resolver.resolve(track)
        assertThat(result).isNull()
    }

    /**
     * Core of the 2026-06-08 fix: playback resolution (allowYtDlp=true)
     * must go straight to yt-dlp — the InnerTube/iOS fast lane returns
     * PO-token-gated URLs that 403 past ~1MB and can't stream a full track.
     */
    @Test
    fun resolve_allowYtDlpTrue_routesToYtDlpDirect_notInnerTubeRace() = runTest {
        val extractor: PreviewUrlExtractor = mockk()
        val ytMusic: YTMusicApiClient = mockk()
        coEvery { extractor.extractStreamUrlViaYtDlp("abc123") } returns "https://ytdlp/abc123"
        val resolver = YouTubeStreamResolver(extractor, ytMusic)

        val result = resolver.resolve(trackWithYoutubeId("abc123"), allowYtDlp = true)

        assertThat(result?.url).isEqualTo("https://ytdlp/abc123")
        coVerify(exactly = 1) { extractor.extractStreamUrlViaYtDlp("abc123") }
        coVerify(exactly = 0) { extractor.extractStreamUrl(any(), any()) }
    }

    /**
     * Background queue-fill (allowYtDlp=false) keeps using the cheap
     * InnerTube fast lane to seed the deep in-order timeline — these
     * placeholder URLs never actually stream audio (prefetch / 403-refresh
     * swap them to yt-dlp before playback).
     */
    @Test
    fun resolve_allowYtDlpFalse_routesToInnerTubeFastLaneOnly() = runTest {
        val extractor: PreviewUrlExtractor = mockk()
        val ytMusic: YTMusicApiClient = mockk()
        coEvery { extractor.extractStreamUrl("abc123", false) } returns "https://innertube/abc123"
        val resolver = YouTubeStreamResolver(extractor, ytMusic)

        val result = resolver.resolve(trackWithYoutubeId("abc123"), allowYtDlp = false)

        assertThat(result?.url).isEqualTo("https://innertube/abc123")
        coVerify(exactly = 1) { extractor.extractStreamUrl("abc123", false) }
        coVerify(exactly = 0) { extractor.extractStreamUrlViaYtDlp(any()) }
    }

    private fun trackWithYoutubeId(id: String): TrackEntity = TrackEntity(
        id = 1L,
        title = "Title",
        artist = "Artist",
        album = "Album",
        durationMs = 200_000L,
        youtubeId = id,
    )

    private fun trackWithoutYoutubeId(artist: String, title: String): TrackEntity = TrackEntity(
        id = 2L,
        title = title,
        artist = artist,
        album = "Album",
        durationMs = 200_000L,
        youtubeId = null,
    )
}
