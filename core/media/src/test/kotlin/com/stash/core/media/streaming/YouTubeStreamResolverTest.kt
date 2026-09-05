package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.preview.PreviewUrlExtractor
import com.stash.data.ytmusic.YTMusicApiClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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
     * `StreamRoutingResult.NotAvailable` → "Couldn't find this track"
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
        // routes through the raced extractStreamUrl path.
        coEvery { extractor.extractStreamUrl(any(), any()) } throws
            CancellationException("outer cancel")
        val resolver = YouTubeStreamResolver(extractor, ytMusic, policy())
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
        val resolver = YouTubeStreamResolver(extractor, ytMusic, policy())
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
        val resolver = YouTubeStreamResolver(extractor, ytMusic, policy())
        val track = trackWithYoutubeId("abc123")

        val result = resolver.resolve(track)
        assertThat(result).isNull()
    }

    /**
     * Playback resolution (allowYtDlp=true) races both lanes rather than going
     * straight to yt-dlp.
     *
     * This test previously pinned the opposite, as "the core of the 2026-06-08
     * fix": the InnerTube URLs of that day were PO-token-gated, 403'd past ~1MB
     * and could not stream a full track, so playback was pinned to yt-dlp direct.
     * That pinning outlived its cause — yt-dlp now extracts by pinning
     * `player_client=android_vr`, a client InnerTubeClient can query itself, so
     * the slow lane was spawning Python to make one HTTPS request. Racing is safe
     * again because AudioUrlTailProbe rejects a URL that can't serve its final
     * byte, so a gated URL falls through to yt-dlp instead of reaching ExoPlayer.
     */
    @Test
    fun resolve_allowYtDlpTrue_racesBothLanes_notYtDlpDirect() = runTest {
        val extractor: PreviewUrlExtractor = mockk()
        val ytMusic: YTMusicApiClient = mockk()
        coEvery { extractor.extractStreamUrl("abc123", true, false) } returns "https://raced/abc123"
        every { extractor.observedCodec("abc123") } returns "opus"
        val resolver = YouTubeStreamResolver(extractor, ytMusic, policy())

        val result = resolver.resolve(trackWithYoutubeId("abc123"), allowYtDlp = true)

        assertThat(result?.url).isEqualTo("https://raced/abc123")
        assertThat(result?.codec).isEqualTo("opus")
        coVerify(exactly = 1) { extractor.extractStreamUrl("abc123", true, false) }
        coVerify(exactly = 0) { extractor.extractStreamUrlViaYtDlp(any()) }
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
        coEvery { extractor.extractStreamUrl("abc123", false, false) } returns "https://innertube/abc123"
        // Deliberately NOT "opus": the resolver used to hardcode a codec, so a
        // test that only ever expects the default would pass against the bug it
        // is meant to catch. Asserting a non-default value proves the observed
        // codec is actually threaded through.
        every { extractor.observedCodec("abc123") } returns "aac"
        val resolver = YouTubeStreamResolver(extractor, ytMusic, policy())

        val result = resolver.resolve(trackWithYoutubeId("abc123"), allowYtDlp = false)

        assertThat(result?.url).isEqualTo("https://innertube/abc123")
        assertThat(result?.codec).isEqualTo("aac")
        coVerify(exactly = 1) { extractor.extractStreamUrl("abc123", false, false) }
        coVerify(exactly = 0) { extractor.extractStreamUrlViaYtDlp(any()) }
    }

    /** Save Data on the lossy path: the resolver asks the extractor for the lowest audio quality. */
    @Test
    fun resolve_saveDataOn_asksForTheLowestQuality() = runTest {
        val extractor: PreviewUrlExtractor = mockk()
        val ytMusic: YTMusicApiClient = mockk()
        coEvery { extractor.extractStreamUrl("abc123", true, true) } returns "https://low/abc123"
        every { extractor.observedCodec("abc123") } returns "opus"
        val resolver = YouTubeStreamResolver(extractor, ytMusic, policy(saveData = true))

        val result = resolver.resolve(trackWithYoutubeId("abc123"), allowYtDlp = true)

        assertThat(result?.url).isEqualTo("https://low/abc123")
        coVerify(exactly = 1) { extractor.extractStreamUrl("abc123", true, true) }
    }

    private fun policy(saveData: Boolean = false): StreamQualityPolicy =
        mockk { coEvery { saveData() } returns saveData }

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
