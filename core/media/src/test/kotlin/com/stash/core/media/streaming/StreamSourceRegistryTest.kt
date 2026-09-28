package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.diagnostics.PlaybackDiagnosticsLog
import com.stash.data.download.lossless.LosslessSourcePreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamSourceRegistryTest {

    private val kennyy: KennyyStreamResolver = mockk()
    private val qobuz: QobuzStreamResolver = mockk()
    private val qbdlx: QbdlxStreamResolver = mockk()
    private val jiosaavn: JioSaavnStreamResolver = mockk {
        coEvery { resolve(any()) } returns null
    }
    private val youtube: YouTubeStreamResolver = mockk()
    private val streamingPreference: StreamingPreference = mockk {
        // Default: no test toggle on. Individual tests override as needed.
        coEvery { isForceQbdlxOnly() } returns false
    }

    private val losslessPrefs: LosslessSourcePreferences = mockk {
        coEvery { enabledNow() } returns true // the Lossless switch, on by default
    }

    private val log = PlaybackDiagnosticsLog()

    private fun registry(dispatcher: CoroutineDispatcher = Dispatchers.IO) = StreamSourceRegistry(
        kennyy, qobuz, qbdlx, jiosaavn, youtube, streamingPreference,
        LosslessSourceHealth(), losslessPrefs, dispatcher, log,
    )

    private fun stubStreamUrl(origin: String) = StreamUrl(
        url = "https://example.test/$origin.flac",
        expiresAtMs = Long.MAX_VALUE,
        codec = "flac",
        origin = origin,
    )

    /**
     * The background-fill path passes `allowYtDlp = false` so the YouTube
     * fallback resolves via the fast InnerTube engine only. Verify the flag
     * is forwarded to [YouTubeStreamResolver.resolve].
     */
    @Test
    fun resolve_passes_allowYtDlp_to_youtube_resolver() = runTest {
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { kennyy.resolve(any()) } returns null
        coEvery { qobuz.resolve(any()) } returns null
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true, allowYtDlp = false)

        coVerify { youtube.resolve(track, allowYtDlp = false) }
    }

    /**
     * Foreground (user-tap) callers leave `allowYtDlp` at its default of
     * `true`, so the slower yt-dlp path stays available.
     */
    @Test
    fun resolve_defaults_allowYtDlp_true() = runTest {
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { kennyy.resolve(any()) } returns null
        coEvery { qobuz.resolve(any()) } returns null
        coEvery { qbdlx.resolve(any()) } returns null
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true)

        coVerify { youtube.resolve(track, allowYtDlp = true) }
    }

    /**
     * The active lossless source (qbdlx) misses → the registry falls through
     * to youtube. The parked proxies (kennyy/squid) are never consulted.
     */
    @Test
    fun resolve_falls_to_youtube_when_lossless_misses() = runTest {
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { qbdlx.resolve(any()) } returns null
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true)

        coVerify { qbdlx.resolve(track) }
        coVerify { youtube.resolve(track, allowYtDlp = true) }
        // kennyy/qobuz remain parked — a miss must not wait on sources that
        // cannot succeed (~4.8s of a 5.2s resolve, measured on device).
        coVerify(exactly = 0) { kennyy.resolve(any()) }
        coVerify(exactly = 0) { qobuz.resolve(any()) }
    }

    /**
     * qbdlx is the PRIMARY lossless source, tried first: when qbdlx produces a
     * [StreamUrl], the registry returns it and never consults the YouTube
     * fallback.
     */
    @Test
    fun resolve_uses_qbdlx_before_youtube() = runTest {
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { qbdlx.resolve(any()) } returns stubStreamUrl("qbdlx")
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        val result = registry().resolve(track, allowYouTube = true)

        assertThat(result?.origin).isEqualTo("qbdlx")
        coVerify { qbdlx.resolve(track) }
        // The hedged fast lane may have started alongside qbdlx; the yt-dlp-capable rung must not.
        coVerify(exactly = 0) { youtube.resolve(any(), allowYtDlp = true) }
        coVerify(exactly = 0) { kennyy.resolve(any()) } // parked
        coVerify(exactly = 0) { qobuz.resolve(any()) } // parked
    }

    /**
     * qbdlx (quota-capped) must NOT run on the speculative queue-wide
     * background fill (allowYtDlp = false); only on foreground/next-up resolves.
     * Otherwise the fill stalls on its latency and starves the fast YouTube
     * fallback, leaving the timeline too sparse to skip through or auto-advance.
     * The fast path (kennyy, squid, youtube) is what populates the timeline.
     */
    @Test
    fun resolve_background_fill_skips_qbdlx() = runTest {
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { kennyy.resolve(any()) } returns null
        coEvery { qobuz.resolve(any()) } returns null
        // qbdlx intentionally unstubbed — it must not be consulted.
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true, allowYtDlp = false)

        coVerify(exactly = 0) { qbdlx.resolve(any()) }
        coVerify { youtube.resolve(track, allowYtDlp = false) }
    }

    /**
     * qbdlx is a lossless source, so the forceYouTubeFallback test toggle must
     * skip it entirely — that branch routes through YouTube only.
     */
    @Test
    fun resolve_forceYt_branch_skips_qbdlx() = runTest {
        coEvery { streamingPreference.isForceYouTubeFallback() } returns true
        // kennyy/qobuz/qbdlx are skipped in the forceYt branch — intentionally unstubbed.
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true)

        coVerify(exactly = 0) { qbdlx.resolve(any()) }
        coVerify { youtube.resolve(track, allowYtDlp = true) }
    }

    /**
     * The forceYt test toggle skips kennyy/qobuz entirely and routes through
     * the YouTube resolver only. Verify that branch still forwards
     * `allowYtDlp` to [YouTubeStreamResolver.resolve].
     */
    @Test
    fun resolve_forceYt_branch_passes_allowYtDlp_to_youtube() = runTest {
        coEvery { streamingPreference.isForceYouTubeFallback() } returns true
        // kennyy/qobuz are skipped in the forceYt branch — intentionally unstubbed.
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true, allowYtDlp = false)

        coVerify { youtube.resolve(track, allowYtDlp = false) }
    }

    @Test
    fun `normal foreground chain tries jiosaavn before youtube`() = runTest {
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { qbdlx.resolve(any()) } returns null
        coEvery { jiosaavn.resolve(any()) } returns null
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true, allowYtDlp = true)

        coVerifyOrder {
            jiosaavn.resolve(track)
            youtube.resolve(track, allowYtDlp = true)
        }
    }

    @Test
    fun `jiosaavn hit prevents youtube fallback`() = runTest {
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { qbdlx.resolve(any()) } returns null
        coEvery { jiosaavn.resolve(any()) } returns StreamUrl(
            url = "https://aac.saavncdn.com/song_320.mp4",
            expiresAtMs = Long.MAX_VALUE,
            codec = "aac",
            bitrateKbps = 320,
            origin = JioSaavnStreamResolver.ORIGIN,
        )
        val track = stubTrack()

        val result = registry().resolve(track, allowYouTube = true, allowYtDlp = true)

        assertThat(result!!.origin).isEqualTo(JioSaavnStreamResolver.ORIGIN)
        // The fast lane may have run alongside (P3 hedge); the yt-dlp-capable rung must not.
        coVerify(exactly = 0) { youtube.resolve(any(), allowYtDlp = true) }
    }

    @Test
    fun `speculative background fill skips jiosaavn`() = runTest {
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true, allowYtDlp = false)

        coVerify(exactly = 0) { jiosaavn.resolve(any()) }
        coVerify { youtube.resolve(track, allowYtDlp = false) }
    }

    @Test
    fun `force youtube diagnostic branch bypasses jiosaavn`() = runTest {
        coEvery { streamingPreference.isForceYouTubeFallback() } returns true
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true, allowYtDlp = true)

        coVerify(exactly = 0) { jiosaavn.resolve(any()) }
        coVerify { youtube.resolve(track, allowYtDlp = true) }
    }

    /**
     * #429: force-qbdlx must keep the lossy safety net. The reporter had this toggle on while the qbdlx pool was
     * dead — every track resolved through a dead source with no fallback,
     * an infinite spinner on all playback.
     */
    @Test
    fun `force qbdlx branch falls through to jiosaavn then youtube when qbdlx misses`() = runTest {
        coEvery { streamingPreference.isForceQbdlxOnly() } returns true
        coEvery { qbdlx.resolve(any()) } returns null
        coEvery { jiosaavn.resolve(any()) } returns null
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true, allowYtDlp = true)

        coVerify { jiosaavn.resolve(track) }
        coVerify { youtube.resolve(track, allowYtDlp = true) }
    }

    private fun stubTrack(): TrackEntity = TrackEntity(
        id = 1L,
        title = "Title",
        artist = "Artist",
        album = "Album",
        durationMs = 200_000L,
        youtubeId = "abc123",
    )

    /**
     * The Lossless switch governs streaming too (2026-09-05): off means no FLAC anywhere,
     * so the chain skips the lossless sources and streams YouTube. Downloads read the same
     * switch; a user who wants less than FLAC turns it off, and Save Data trims from there.
     */
    @Test
    fun lossless_off_skips_qbdlx_and_streams_youtube() = runTest {
        coEvery { losslessPrefs.enabledNow() } returns false
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { youtube.resolve(any(), any()) } returns stubStreamUrl("youtube")
        val track = stubTrack()

        val result = registry().resolve(track, allowYouTube = true)

        assertThat(result?.origin).isEqualTo("youtube")
        coVerify(exactly = 0) { qbdlx.resolve(any()) }
        coVerify { youtube.resolve(track, any()) }
    }

    /** A stale force-qbdlx test toggle must not override the user's Lossless switch. */
    @Test
    fun lossless_off_ignores_a_stale_force_qbdlx_toggle() = runTest {
        coEvery { losslessPrefs.enabledNow() } returns false
        coEvery { streamingPreference.isForceQbdlxOnly() } returns true
        coEvery { youtube.resolve(any(), any()) } returns stubStreamUrl("youtube")
        val track = stubTrack()

        val result = registry().resolve(track, allowYouTube = true)

        assertThat(result?.origin).isEqualTo("youtube")
        coVerify(exactly = 0) { qbdlx.resolve(any()) }
    }

    // ── P3 hedge: the rungs ahead of YouTube no longer serialize its latency ──
    //
    // Measured 2026-09-06 (Pixel 6, lossless on, a YouTube-only video): the
    // relay search missed in 2.0 s, JioSaavn missed in 1.3 s, and only THEN did
    // the YouTube resolve start. The fast lane (one InnerTube round trip and a
    // probe) is cheap enough to start at once and throw away on a lossless hit.

    @Test
    fun `a lossless miss uses the youtube fast lane that ran alongside it`() = runTest {
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { qbdlx.resolve(any()) } coAnswers { delay(2_000); null }
        coEvery { youtube.resolve(any(), allowYtDlp = false) } coAnswers { delay(500); stubStreamUrl("youtube") }
        val track = stubTrack()
        val started = currentTime

        val result = registry(StandardTestDispatcher(testScheduler)).resolve(track)

        assertThat(result?.origin).isEqualTo("youtube")
        // The fast lane's 500 ms hid inside the relay's 2 s; nothing was added after the miss.
        assertThat(currentTime - started).isAtMost(2_000)
        coVerify(exactly = 0) { youtube.resolve(any(), allowYtDlp = true) }
    }

    @Test
    fun `a lossless hit cancels the fast lane`() = runTest {
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        // 100 ms of relay work: enough for the fast lane to start and park in its 60 s wait.
        coEvery { qbdlx.resolve(any()) } coAnswers { delay(100); stubStreamUrl("qbdlx") }
        val cancelled = AtomicBoolean(false)
        coEvery { youtube.resolve(any(), allowYtDlp = false) } coAnswers {
            try {
                delay(60_000); null
            } catch (ce: CancellationException) {
                cancelled.set(true); throw ce
            }
        }
        val track = stubTrack()

        val started = currentTime
        val result = registry(StandardTestDispatcher(testScheduler)).resolve(track)
        advanceUntilIdle()

        assertThat(result?.origin).isEqualTo("qbdlx")
        assertThat(cancelled.get()).isTrue()
        // The hit never waited on the lane: 100 ms of relay time, not the lane's 60 s.
        assertThat(currentTime - started).isAtMost(100)
        coVerify(exactly = 0) { youtube.resolve(any(), allowYtDlp = true) }
    }

    @Test
    fun `a fast lane miss still falls back to the full youtube rung`() = runTest {
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { qbdlx.resolve(any()) } returns null
        coEvery { youtube.resolve(any(), allowYtDlp = false) } returns null
        coEvery { youtube.resolve(any(), allowYtDlp = true) } returns stubStreamUrl("youtube")
        val track = stubTrack()

        val result = registry(StandardTestDispatcher(testScheduler)).resolve(track)

        assertThat(result?.origin).isEqualTo("youtube")
        coVerifyOrder {
            youtube.resolve(track, allowYtDlp = false)
            youtube.resolve(track, allowYtDlp = true)
        }
    }

    @Test
    fun `jiosaavn keeps its rank over the fast lane when both answer`() = runTest {
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { qbdlx.resolve(any()) } returns null
        coEvery { jiosaavn.resolve(any()) } coAnswers {
            delay(300)
            StreamUrl(
                url = "https://aac.saavncdn.com/song_320.mp4", expiresAtMs = Long.MAX_VALUE,
                codec = "aac", bitrateKbps = 320, origin = JioSaavnStreamResolver.ORIGIN,
            )
        }
        coEvery { youtube.resolve(any(), allowYtDlp = false) } returns stubStreamUrl("youtube")
        val track = stubTrack()

        val result = registry(StandardTestDispatcher(testScheduler)).resolve(track)

        assertThat(result?.origin).isEqualTo(JioSaavnStreamResolver.ORIGIN)
        coVerify(exactly = 0) { youtube.resolve(any(), allowYtDlp = true) }
    }

    // ── The diagnostics bundle's "Recent resolves": one line per finished chain walk ──

    @Test
    fun `a served resolve is recorded by source and gates, without its title or url`() = runTest {
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { qbdlx.resolve(any()) } returns StreamUrl(
            url = "https://streaming-qobuz-std.akamaized.net/file?uid=7&eid=8&fmt=27&hmac=SECRET",
            expiresAtMs = Long.MAX_VALUE,
            codec = "flac",
            origin = "qbdlx",
        )
        coEvery { youtube.resolve(any(), any()) } returns null

        registry().resolve(stubTrack().copy(title = "Secret Song"))

        val r = log.recentResolves().single()
        assertThat(r.trackId).isEqualTo(1L)
        assertThat(r.servedBy).isEqualTo("qbdlx")
        assertThat(r.lossless).isTrue()
        assertThat(r.toString()).doesNotContain("Secret Song")
        assertThat(r.toString()).doesNotContain("akamaized")
    }

    @Test
    fun `a resolve every source missed is recorded as all missed`() = runTest {
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { youtube.resolve(any(), any()) } returns null

        val result = registry().resolve(stubTrack(), allowYouTube = true, allowYtDlp = false)

        assertThat(result).isNull()
        val r = log.recentResolves().single()
        assertThat(r.servedBy).isEqualTo("none (all missed)")
    }

    @Test
    fun `a cancelled resolve is not an outcome and is never recorded`() = runTest {
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { qbdlx.resolve(any()) } throws CancellationException("preempted")
        coEvery { youtube.resolve(any(), any()) } returns null

        val outcome = runCatching { registry().resolve(stubTrack()) }

        assertThat(outcome.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(log.recentResolves()).isEmpty()
    }

    /** The deadline is the one cancellation that IS an outcome: the player only sees a generic IO error for it. */
    @Test
    fun `a resolve that hits its deadline is recorded as timed out`() = runTest {
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { qbdlx.resolve(any()) } coAnswers { delay(LazyResolvingDataSource.RESOLVE_DEADLINE_MS * 2); null }
        coEvery { youtube.resolve(any(), any()) } returns null

        val outcome = runCatching { registry(StandardTestDispatcher(testScheduler)).resolve(stubTrack()) }

        assertThat(outcome.exceptionOrNull()).isInstanceOf(TimeoutCancellationException::class.java)
        assertThat(log.recentResolves().single().servedBy).isEqualTo("none (timed out)")
    }
}
