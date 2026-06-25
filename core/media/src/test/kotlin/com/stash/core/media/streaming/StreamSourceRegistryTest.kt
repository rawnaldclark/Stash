package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamSourceRegistryTest {

    private val kennyy: KennyyStreamResolver = mockk()
    private val qobuz: QobuzStreamResolver = mockk()
    private val arcod: ArcodStreamResolver = mockk()
    private val amz: AmzStreamResolver = mockk()
    private val qbdlx: QbdlxStreamResolver = mockk()
    private val youtube: YouTubeStreamResolver = mockk()
    private val streamingPreference: StreamingPreference = mockk {
        // Default: no test toggle on. Individual tests override as needed.
        coEvery { isForceQbdlxOnly() } returns false
        coEvery { isForceArcodOnly() } returns false
        coEvery { isForceAmzOnly() } returns false
    }

    private fun registry() = StreamSourceRegistry(kennyy, qobuz, arcod, amz, qbdlx, youtube, streamingPreference)

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
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true)

        coVerify { qbdlx.resolve(track) }
        coVerify { amz.resolve(track) }
        coVerify { youtube.resolve(track, allowYtDlp = true) }
        coVerify(exactly = 0) { kennyy.resolve(any()) } // parked
        coVerify(exactly = 0) { qobuz.resolve(any()) } // parked
        coVerify(exactly = 0) { arcod.resolve(any()) } // parked
    }

    /**
     * qbdlx is the PRIMARY lossless source, tried ahead of amz: when qbdlx
     * produces a [StreamUrl], the registry returns it and never consults amz
     * or the YouTube fallback.
     */
    @Test
    fun resolve_uses_qbdlx_before_amz_and_youtube() = runTest {
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
     * amz sits AFTER qbdlx and BEFORE youtube: when qbdlx misses but amz has a
     * match, amz serves it and youtube is never consulted. The parked proxies
     * are skipped.
     */
    @Test
    fun resolve_amz_consulted_after_qbdlx_before_youtube() = runTest {
        coEvery { streamingPreference.isForceAmzOnly() } returns false
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { qbdlx.resolve(any()) } returns null
        coEvery { amz.resolve(any()) } returns StreamUrl(
            url = "https://amz.squid.wtf/api/stream?asin=B00X",
            expiresAtMs = Long.MAX_VALUE,
            codec = "flac",
            origin = AmzStreamResolver.ORIGIN,
        )
        val track = stubTrack()

        val result = registry().resolve(track, allowYouTube = true)

        assertThat(result).isNotNull()
        assertThat(result!!.origin).isEqualTo("amz")
        coVerify { qbdlx.resolve(track) }
        coVerify { amz.resolve(track) }
        coVerify(exactly = 0) { youtube.resolve(any(), any()) }
        coVerify(exactly = 0) { kennyy.resolve(any()) } // parked
        coVerify(exactly = 0) { qobuz.resolve(any()) } // parked
        coVerify(exactly = 0) { arcod.resolve(any()) } // parked
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
     * Cellular fast-start keeps the quick Qobuz proxies but skips slow
     * job/full-file lossless fallbacks, then reaches YouTube directly.
     */
    @Test
    fun resolve_fastStart_skips_arcod_and_amz_before_youtube() = runTest {
        coEvery { streamingPreference.isForceAmzOnly() } returns false
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { kennyy.resolve(any()) } returns null
        coEvery { qobuz.resolve(any()) } returns null
        // arcod + amz intentionally unstubbed; fast-start must skip both.
        coEvery { youtube.resolve(any(), any()) } returns stubStreamUrl("youtube")
        val track = stubTrack()

        val result = registry().resolve(
            track,
            allowYouTube = true,
            allowYtDlp = true,
            preferFastStartup = true,
        )

        assertThat(result?.origin).isEqualTo("youtube")
        coVerify { kennyy.resolve(track) }
        coVerify { qobuz.resolve(track) }
        coVerify(exactly = 0) { arcod.resolve(any()) }
        coVerify(exactly = 0) { amz.resolve(any()) }
        coVerify { youtube.resolve(track, allowYtDlp = true) }
    }

    /** Force toggles are diagnostic overrides and must ignore fast-start. */
    @Test
    fun resolve_fastStart_still_honours_forceArcodOnly() = runTest {
        coEvery { streamingPreference.isForceArcodOnly() } returns true
        coEvery { arcod.resolve(any()) } returns stubStreamUrl("arcod")
        val track = stubTrack()

        val result = registry().resolve(
            track,
            allowYouTube = true,
            allowYtDlp = true,
            preferFastStartup = true,
        )

        assertThat(result?.origin).isEqualTo("arcod")
        coVerify { arcod.resolve(track) }
        coVerify(exactly = 0) { kennyy.resolve(any()) }
        coVerify(exactly = 0) { qobuz.resolve(any()) }
        coVerify(exactly = 0) { youtube.resolve(any(), any()) }
    }

    /** Force-amz remains exact even when cellular fast-start is requested. */
    @Test
    fun resolve_fastStart_still_honours_forceAmzOnly() = runTest {
        coEvery { streamingPreference.isForceArcodOnly() } returns false
        coEvery { streamingPreference.isForceAmzOnly() } returns true
        coEvery { amz.resolve(any()) } returns stubStreamUrl("amz")
        val track = stubTrack()

        val result = registry().resolve(
            track,
            allowYouTube = true,
            allowYtDlp = true,
            preferFastStartup = true,
        )

        assertThat(result?.origin).isEqualTo("amz")
        coVerify { amz.resolve(track) }
        coVerify(exactly = 0) { kennyy.resolve(any()) }
        coVerify(exactly = 0) { qobuz.resolve(any()) }
        coVerify(exactly = 0) { youtube.resolve(any(), any()) }
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
     * The force-qbdlx-only test toggle routes through qbdlx ONLY — every other
     * source is skipped, and a qbdlx hit is returned. Takes precedence over the
     * other force toggles.
     */
    @Test
    fun `forceQbdlxOnly routes through qbdlx only`() = runTest {
        coEvery { streamingPreference.isForceQbdlxOnly() } returns true
        coEvery { qbdlx.resolve(any()) } returns StreamUrl(
            url = "https://www.qobuz.com/file?fmt=27&etsp=1782867891",
            expiresAtMs = Long.MAX_VALUE,
            codec = "flac",
            origin = "qbdlx",
        )
        val track = stubTrack()

        val result = registry().resolve(track, allowYouTube = true, allowYtDlp = true)

        assertThat(result).isNotNull()
        assertThat(result!!.origin).isEqualTo("qbdlx")
        coVerify { qbdlx.resolve(track) }
        coVerify(exactly = 0) { kennyy.resolve(any()) }
        coVerify(exactly = 0) { qobuz.resolve(any()) }
        coVerify(exactly = 0) { amz.resolve(any()) }
        coVerify(exactly = 0) { youtube.resolve(any(), any()) }
    }

    @Test
    fun `forceAmzOnly routes through amz only`() = runTest {
        coEvery { streamingPreference.isForceAmzOnly() } returns true
        // kennyy/qobuz/youtube are skipped in the amz-only branch — unstubbed.
        coEvery { amz.resolve(any()) } returns StreamUrl(
            url = "https://amz.squid.wtf/api/stream?asin=B00X",
            expiresAtMs = Long.MAX_VALUE,
            codec = "flac",
            origin = AmzStreamResolver.ORIGIN,
        )
        val track = stubTrack()

        val result = registry().resolve(track, allowYouTube = true, allowYtDlp = true)

        assertThat(result).isNotNull()
        assertThat(result!!.origin).isEqualTo("amz")
        coVerify { amz.resolve(track) }
        coVerify(exactly = 0) { kennyy.resolve(any()) }
        coVerify(exactly = 0) { qobuz.resolve(any()) }
        coVerify(exactly = 0) { youtube.resolve(any(), any()) }
    }

    /**
     * Under force-amz-only an amz miss returns null and youtube is NOT
     * consulted (even though allowYouTube is true) — it's amz or nothing.
     */
    @Test
    fun `forceAmzOnly amz miss returns null and does not consult youtube`() = runTest {
        coEvery { streamingPreference.isForceAmzOnly() } returns true
        coEvery { amz.resolve(any()) } returns null
        val track = stubTrack()

        val result = registry().resolve(track, allowYouTube = true, allowYtDlp = true)

        assertThat(result).isNull()
        coVerify { amz.resolve(track) }
        coVerify(exactly = 0) { kennyy.resolve(any()) }
        coVerify(exactly = 0) { qobuz.resolve(any()) }
        coVerify(exactly = 0) { youtube.resolve(any(), any()) }
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
