package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.data.download.BuildConfig
import com.stash.data.download.lossless.LosslessSourcePreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Test

/**
 * ⚠️ One resolver in [StreamSourceRegistry.resolve] is still gated on
 * COMPILE-TIME build config, not on anything a test can inject:
 *
 *   arcod → `BuildConfig.ARCOD_CONFIGURED`   (local.properties arcod.stashKey)
 *
 * qbdlx is no longer build-gated; it self-gates on LosslessAvailability, so
 * every qbdlx expectation below is unconditional.
 *
 * A maintainer machine has the arcod key; CI does not. That makes any
 * UNCONDITIONAL assertion about arcod environment-dependent — it will pass in
 * one place and fail in the other, which is exactly what happened here: `arcod
 * never called` passed in CI and failed locally. It went unnoticed for months
 * only because the whole :core:media suite hung before reaching this class (see
 * LoudnessGainProcessorTest).
 *
 * So: guard every arcod expectation with its flag (an `if`, so the test still
 * means something without it). Assertions about the other sources are
 * unconditional — those aren't build-gated.
 *
 * If this gets tiresome, the real fix is to have `resolve()` take the chain
 * composition as an injected value instead of reading BuildConfig inline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamSourceRegistryTest {

    private val kennyy: KennyyStreamResolver = mockk()
    private val qobuz: QobuzStreamResolver = mockk()
    private val arcod: ArcodStreamResolver = mockk()
    private val qbdlx: QbdlxStreamResolver = mockk()
    private val jiosaavn: JioSaavnStreamResolver = mockk {
        coEvery { resolve(any()) } returns null
    }
    private val youtube: YouTubeStreamResolver = mockk()
    private val streamingPreference: StreamingPreference = mockk {
        // Default: no test toggle on. Individual tests override as needed.
        coEvery { isForceQbdlxOnly() } returns false
        coEvery { isForceArcodOnly() } returns false
    }

    private val losslessPrefs: LosslessSourcePreferences = mockk {
        coEvery { enabledNow() } returns true // the Lossless switch, on by default
    }

    private fun registry(dispatcher: CoroutineDispatcher = Dispatchers.IO) = StreamSourceRegistry(
        kennyy, qobuz, arcod, qbdlx, jiosaavn, youtube, streamingPreference,
        LosslessSourceHealth(), losslessPrefs, dispatcher,
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
        coEvery { arcod.resolve(any()) } returns null
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
        coEvery { arcod.resolve(any()) } returns null
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
        coEvery { arcod.resolve(any()) } returns null
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true)

        // Chain is qbdlx -> arcod -> youtube; only the arcod leg is build-gated.
        coVerify { qbdlx.resolve(track) }
        if (BuildConfig.ARCOD_CONFIGURED) coVerify { arcod.resolve(track) }
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
        coVerify(exactly = 0) { arcod.resolve(any()) } // qbdlx already served
    }

    /**
     * A force toggle must never be able to strand a user with no audio.
     *
     * The pref outlives the build that exposed the switch: arcod is parked here,
     * and its debug toggle isn't in this build at all, yet `force_arcod_only`
     * can still be true in DataStore with no UI to clear it. Before this, that
     * meant every track resolved through a parked source with no fallback —
     * silence, permanently. YouTube stays in the chain so the worst case is
     * lossy playback, never none.
     */
    @Test
    fun forceArcod_still_falls_back_to_youtube_when_arcod_misses() = runTest {
        coEvery { streamingPreference.isForceQbdlxOnly() } returns false
        coEvery { streamingPreference.isForceArcodOnly() } returns true
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { arcod.resolve(any()) } returns null   // parked / dead endpoint
        coEvery { youtube.resolve(any(), any()) } returns StreamUrl(
            url = "https://yt/x",
            expiresAtMs = Long.MAX_VALUE,
            codec = "aac",
            origin = YouTubeStreamResolver.ORIGIN,
        )
        val track = stubTrack()

        val result = registry().resolve(track, allowYouTube = true)

        assertThat(result).isNotNull()
        assertThat(result!!.origin).isEqualTo(YouTubeStreamResolver.ORIGIN)
    }

    /**
     * arcod was UNPARKED 2026-08-01 (operator rotated the key + moved us to
     * /v2/stash; verified live), so it IS expected in the chain on a keyed build.
     * When qbdlx and arcod both miss, the chain reaches the YouTube fallback.
     */
    @Test
    fun resolve_consults_arcod_then_falls_to_youtube() = runTest {
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { qbdlx.resolve(any()) } returns null
        coEvery { arcod.resolve(any()) } returns null
        coEvery { youtube.resolve(any(), any()) } returns StreamUrl(
            url = "https://yt/x",
            expiresAtMs = Long.MAX_VALUE,
            codec = "aac",
            origin = YouTubeStreamResolver.ORIGIN,
        )
        val track = stubTrack()

        val result = registry().resolve(track, allowYouTube = true)

        assertThat(result!!.origin).isEqualTo(YouTubeStreamResolver.ORIGIN)
        coVerify { qbdlx.resolve(track) }
        // arcod is build-gated: only a keyed build can reach it.
        if (BuildConfig.ARCOD_CONFIGURED) coVerify { arcod.resolve(track) }
        coVerify(exactly = 0) { kennyy.resolve(any()) }
        coVerify(exactly = 0) { qobuz.resolve(any()) }
    }

    /**
     * ARCOD (job-based, quota-capped) must NOT run on the speculative queue-wide
     * background fill (allowYtDlp = false); only on foreground/next-up resolves.
     * Otherwise the fill stalls on its latency and starves the fast YouTube
     * fallback, leaving the timeline too sparse to skip through or auto-advance.
     * The fast path (kennyy, squid, youtube) is what populates the timeline.
     */
    @Test
    fun resolve_background_fill_skips_arcod() = runTest {
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { kennyy.resolve(any()) } returns null
        coEvery { qobuz.resolve(any()) } returns null
        // arcod + qbdlx intentionally unstubbed — none must be consulted.
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true, allowYtDlp = false)

        coVerify(exactly = 0) { arcod.resolve(any()) }
        coVerify(exactly = 0) { qbdlx.resolve(any()) }
        coVerify { youtube.resolve(track, allowYtDlp = false) }
    }

    /**
     * arcod is a lossless source, so the forceYouTubeFallback test toggle must
     * skip it entirely — that branch routes through YouTube only.
     */
    @Test
    fun resolve_forceYt_branch_skips_arcod() = runTest {
        coEvery { streamingPreference.isForceYouTubeFallback() } returns true
        // kennyy/qobuz/arcod are skipped in the forceYt branch — intentionally unstubbed.
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true)

        coVerify(exactly = 0) { arcod.resolve(any()) }
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
        coEvery { arcod.resolve(any()) } returns null
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
        coEvery { arcod.resolve(any()) } returns null
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
     * #429: force-qbdlx must keep the lossy safety net, exactly like
     * force-arcod. The reporter had this toggle on while the qbdlx pool was
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
    fun lossless_off_skips_qbdlx_and_arcod_and_streams_youtube() = runTest {
        coEvery { losslessPrefs.enabledNow() } returns false
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { youtube.resolve(any(), any()) } returns stubStreamUrl("youtube")
        val track = stubTrack()

        val result = registry().resolve(track, allowYouTube = true)

        assertThat(result?.origin).isEqualTo("youtube")
        coVerify(exactly = 0) { qbdlx.resolve(any()) }
        coVerify(exactly = 0) { arcod.resolve(any()) }
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
        coEvery { arcod.resolve(any()) } returns null
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
        coEvery { arcod.resolve(any()) } returns null
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
        coEvery { arcod.resolve(any()) } returns null
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
}
