package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.data.download.BuildConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * ⚠️ Two resolvers in [StreamSourceRegistry.resolve] are gated on COMPILE-TIME
 * build config, not on anything a test can inject:
 *
 *   qbdlx → `BuildConfig.QBDLX_CONFIGURED`   (local.properties qbdlx creds)
 *   arcod → `BuildConfig.ARCOD_CONFIGURED`   (local.properties arcod.streamBase)
 *
 * A maintainer machine has both, so both are in the chain. CI has neither, so
 * neither is. That makes any UNCONDITIONAL assertion about those two sources
 * environment-dependent — it will pass in one place and fail in the other, which
 * is exactly what happened here: `arcod never called` passed in CI and failed
 * locally, while `qbdlx.resolve was called` passed locally and failed in CI. It
 * went unnoticed for months only because the whole :core:media suite hung before
 * reaching this class (see LoudnessGainProcessorTest).
 *
 * So: guard every qbdlx/arcod expectation with its flag. Use `assumeTrue` when a
 * test's whole premise needs the source (skips), and an `if` when the test still
 * means something without it (narrows). Assertions about amz/youtube/kennyy/qobuz
 * are unconditional — those aren't build-gated.
 *
 * If this gets tiresome, the real fix is to have `resolve()` take the chain
 * composition as an injected value instead of reading BuildConfig inline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamSourceRegistryTest {

    private val kennyy: KennyyStreamResolver = mockk()
    private val qobuz: QobuzStreamResolver = mockk()
    private val arcod: ArcodStreamResolver = mockk()
    private val amz: AmzStreamResolver = mockk()
    private val qbdlx: QbdlxStreamResolver = mockk()
    private val jiosaavn: JioSaavnStreamResolver = mockk {
        coEvery { resolve(any()) } returns null
    }
    private val youtube: YouTubeStreamResolver = mockk()
    private val streamingPreference: StreamingPreference = mockk {
        // Default: no test toggle on. Individual tests override as needed.
        coEvery { isForceQbdlxOnly() } returns false
        coEvery { isForceArcodOnly() } returns false
        coEvery { isForceAmzOnly() } returns false
    }

    private fun registry() = StreamSourceRegistry(
        kennyy, qobuz, arcod, amz, qbdlx, jiosaavn, youtube, streamingPreference,
        LosslessSourceHealth(),
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
        coEvery { streamingPreference.isForceAmzOnly() } returns false
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { kennyy.resolve(any()) } returns null
        coEvery { qobuz.resolve(any()) } returns null
        coEvery { arcod.resolve(any()) } returns null
        coEvery { amz.resolve(any()) } returns null
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
        coEvery { streamingPreference.isForceAmzOnly() } returns false
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { kennyy.resolve(any()) } returns null
        coEvery { qobuz.resolve(any()) } returns null
        coEvery { arcod.resolve(any()) } returns null
        coEvery { amz.resolve(any()) } returns null
        coEvery { qbdlx.resolve(any()) } returns null
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true)

        coVerify { youtube.resolve(track, allowYtDlp = true) }
    }

    /**
     * The active lossless sources (qbdlx, amz) miss → the registry falls
     * through to youtube. The parked proxies (kennyy/squid/arcod) are never
     * consulted.
     */
    @Test
    fun resolve_falls_to_youtube_when_lossless_misses() = runTest {
        coEvery { streamingPreference.isForceAmzOnly() } returns false
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { amz.resolve(any()) } returns null
        coEvery { qbdlx.resolve(any()) } returns null
        coEvery { arcod.resolve(any()) } returns null
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true)

        // Chain is qbdlx -> arcod -> youtube; the two lossless legs are build-gated.
        if (BuildConfig.QBDLX_CONFIGURED) coVerify { qbdlx.resolve(track) }
        if (BuildConfig.ARCOD_CONFIGURED) coVerify { arcod.resolve(track) }
        coVerify { youtube.resolve(track, allowYtDlp = true) }
        // amz/kennyy/qobuz remain parked — a miss must not wait on sources that
        // cannot succeed (~4.8s of a 5.2s resolve, measured on device).
        coVerify(exactly = 0) { amz.resolve(any()) }
        coVerify(exactly = 0) { kennyy.resolve(any()) }
        coVerify(exactly = 0) { qobuz.resolve(any()) }
    }

    /**
     * qbdlx is the PRIMARY lossless source, tried ahead of amz: when qbdlx
     * produces a [StreamUrl], the registry returns it and never consults amz
     * or the YouTube fallback.
     */
    @Test
    fun resolve_uses_qbdlx_before_amz_and_youtube() = runTest {
        // This test's entire premise is "qbdlx serves it", which is impossible on a
        // build that doesn't bundle qbdlx creds — resolve() skips the source
        // outright. Assume rather than assert, so it SKIPS on CI instead of failing.
        assumeTrue("needs a qbdlx-configured build", BuildConfig.QBDLX_CONFIGURED)
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { qbdlx.resolve(any()) } returns stubStreamUrl("qbdlx")
        coEvery { amz.resolve(any()) } returns null
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        val result = registry().resolve(track, allowYouTube = true)

        assertThat(result?.origin).isEqualTo("qbdlx")
        coVerify { qbdlx.resolve(track) }
        coVerify(exactly = 0) { amz.resolve(any()) }
        coVerify(exactly = 0) { youtube.resolve(any(), any()) }
        coVerify(exactly = 0) { kennyy.resolve(any()) } // parked
        coVerify(exactly = 0) { qobuz.resolve(any()) } // parked
        coVerify(exactly = 0) { arcod.resolve(any()) } // parked
    }

    /**
     * amz and arcod are PARKED (2026-07-30). They are no longer working lossless
     * providers, and leaving them in the chain made every YouTube-fallback play
     * wait on sources that could not succeed — ~4.8s of a 5.2s resolve, measured
     * on device.
     *
     * amz is still parked and must never be consulted. arcod was UNPARKED
     * 2026-08-01 (operator rotated the key + moved us to /v2/stash; verified live),
     * so it IS expected in the chain on a keyed build — this test pins both halves
     * at once: amz absent, arcod present.
     */
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

    @Test
    fun resolve_skips_parked_amz_but_consults_arcod() = runTest {
        coEvery { streamingPreference.isForceAmzOnly() } returns false
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { qbdlx.resolve(any()) } returns null
        // arcod UNPARKED 2026-08-01 — it misses here so the chain still reaches
        // YouTube, which keeps the amz assertion below meaningful.
        coEvery { arcod.resolve(any()) } returns null
        // amz is stubbed to SUCCEED on purpose: if it were still in the chain it
        // would serve this resolve, so "youtube served instead" is proof it was
        // skipped rather than merely absent from the fixture.
        coEvery { amz.resolve(any()) } returns StreamUrl(
            url = "https://amz.squid.wtf/api/stream?asin=B00X",
            expiresAtMs = Long.MAX_VALUE,
            codec = "flac",
            origin = AmzStreamResolver.ORIGIN,
        )
        coEvery { youtube.resolve(any(), any()) } returns StreamUrl(
            url = "https://yt/x",
            expiresAtMs = Long.MAX_VALUE,
            codec = "aac",
            origin = YouTubeStreamResolver.ORIGIN,
        )
        val track = stubTrack()

        val result = registry().resolve(track, allowYouTube = true)

        assertThat(result!!.origin).isEqualTo(YouTubeStreamResolver.ORIGIN)
        if (BuildConfig.QBDLX_CONFIGURED) coVerify { qbdlx.resolve(track) }
        // arcod is build-gated like qbdlx: only a keyed build can reach it.
        if (BuildConfig.ARCOD_CONFIGURED) coVerify { arcod.resolve(track) }
        coVerify(exactly = 0) { amz.resolve(any()) }
        coVerify(exactly = 0) { kennyy.resolve(any()) }
        coVerify(exactly = 0) { qobuz.resolve(any()) }
    }

    /**
     * Both slow sources — ARCOD (job-based, quota-capped) AND amz (whole-file
     * decrypt, single-flight) — must NOT run on the speculative queue-wide
     * background fill (allowYtDlp = false); only on foreground/next-up resolves.
     * Otherwise the fill stalls on their latency and starves the fast YouTube
     * fallback, leaving the timeline too sparse to skip through or auto-advance.
     * The fast path (kennyy, squid, youtube) is what populates the timeline.
     */
    @Test
    fun resolve_background_fill_skips_arcod_and_amz() = runTest {
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { kennyy.resolve(any()) } returns null
        coEvery { qobuz.resolve(any()) } returns null
        // arcod + amz + qbdlx intentionally unstubbed — none must be consulted.
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true, allowYtDlp = false)

        coVerify(exactly = 0) { arcod.resolve(any()) }
        coVerify(exactly = 0) { amz.resolve(any()) }
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
     * The forceYouTubeFallback branch routes through youtube ONLY — amz
     * (like kennyy/squid/arcod) must be absent from that branch.
     */
    @Test
    fun resolve_forceYt_branch_does_not_consult_amz() = runTest {
        coEvery { streamingPreference.isForceAmzOnly() } returns false
        coEvery { streamingPreference.isForceYouTubeFallback() } returns true
        // kennyy/qobuz/amz are skipped in the forceYt branch — intentionally unstubbed.
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true, allowYtDlp = true)

        coVerify(exactly = 0) { amz.resolve(any()) }
        coVerify { youtube.resolve(track, allowYtDlp = true) }
    }

    /**
     * The forceYt test toggle skips kennyy/qobuz entirely and routes through
     * the YouTube resolver only. Verify that branch still forwards
     * `allowYtDlp` to [YouTubeStreamResolver.resolve].
     */
    @Test
    fun resolve_forceYt_branch_passes_allowYtDlp_to_youtube() = runTest {
        coEvery { streamingPreference.isForceAmzOnly() } returns false
        coEvery { streamingPreference.isForceYouTubeFallback() } returns true
        // kennyy/qobuz are skipped in the forceYt branch — intentionally unstubbed.
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true, allowYtDlp = false)

        coVerify { youtube.resolve(track, allowYtDlp = false) }
    }

    /**
     * The force-amz-only test toggle routes through amz ONLY — kennyy,
     * squid, and youtube are never consulted, and an amz hit is returned.
     */
    /**
     * A STALE `force_amz_only` preference must not strand the user.
     *
     * amz was parked on 2026-07-30 and its Settings toggle removed on 2026-07-31 —
     * but anyone who had switched it on still has `force_amz_only = true` persisted,
     * with no UI left to switch it off. If the registry still honoured that flag,
     * those users would route every track through a parked source and get permanent
     * silence with no way to recover.
     *
     * This is the same shape as the force-YouTube incident: a stale preference
     * quietly disabling lossless, costing a full debugging session. So the rule is
     * that parking a source parks its force branch in the same change, and this test
     * is the guard. These two tests previously asserted the opposite (that the flag
     * routed amz-only); that behaviour is intentionally gone.
     */
    @Test
    fun `a stale forceAmzOnly preference is ignored and playback still resolves`() = runTest {
        coEvery { streamingPreference.isForceAmzOnly() } returns true
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { qbdlx.resolve(any()) } returns null
        // Stubbed to succeed: if the parked branch were still live, amz would serve
        // this and the assertion below would catch it.
        coEvery { amz.resolve(any()) } returns StreamUrl(
            url = "https://amz.squid.wtf/api/stream?asin=B00X",
            expiresAtMs = Long.MAX_VALUE,
            codec = "flac",
            origin = AmzStreamResolver.ORIGIN,
        )
        coEvery { youtube.resolve(any(), any()) } returns StreamUrl(
            url = "https://yt/x",
            expiresAtMs = Long.MAX_VALUE,
            codec = "aac",
            origin = YouTubeStreamResolver.ORIGIN,
        )
        val track = stubTrack()

        val result = registry().resolve(track, allowYouTube = true, allowYtDlp = true)

        // Falls through to the normal chain instead of being trapped on amz.
        assertThat(result!!.origin).isEqualTo(YouTubeStreamResolver.ORIGIN)
        coVerify(exactly = 0) { amz.resolve(any()) }
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
        coVerify(exactly = 0) { youtube.resolve(any(), any()) }
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

    private fun stubTrack(): TrackEntity = TrackEntity(
        id = 1L,
        title = "Title",
        artist = "Artist",
        album = "Album",
        durationMs = 200_000L,
        youtubeId = "abc123",
    )
}
