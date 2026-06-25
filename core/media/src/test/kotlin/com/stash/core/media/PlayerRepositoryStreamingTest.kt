package com.stash.core.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.common.constants.StashConstants
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrl
import com.stash.core.media.streaming.StreamUrlCache
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Tests for [PlayerRepositoryImpl.buildMediaItemForTrack] and
 * [PlayerRepositoryImpl.playFromStream] — the streaming-routing decision
 * tree that decides between local-file playback, cached/resolved
 * streaming, and various "refused" outcomes.
 *
 * Uses Robolectric because the routing builds Media3 [androidx.media3
 * .common.MediaItem]s, which touch `android.net.Uri` and
 * `android.os.Bundle` internally. We only assert the result variant
 * type (and, for [StreamRoutingResult.Item], the mediaId and uri) —
 * downstream MediaController playback is exercised on-device.
 *
 * Test cases follow the 7-case spec from Task 11 plus a no-active-
 * network edge case to cover the early-exit on connectivity loss.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryStreamingTest {

    private val playbackStateStore: PlaybackStateStore = mockk(relaxed = true)
    private val musicRepository: MusicRepository = mockk {
        every { trackDeletions } returns MutableSharedFlow()
    }
    private val streamingPreference: StreamingPreference = mockk()
    private val streamResolver: StreamSourceRegistry = mockk()
    private val streamUrlCache: StreamUrlCache = mockk(relaxUnitFun = true)
    private val connectivity: ConnectivityMonitor = mockk()
    private val trackDao: TrackDao = mockk()

    private lateinit var repo: PlayerRepositoryImpl

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        repo = PlayerRepositoryImpl(
            context = context,
            playbackStateStore = playbackStateStore,
            musicRepository = musicRepository,
            streamingPreference = streamingPreference,
            streamResolver = streamResolver,
            streamUrlCache = streamUrlCache,
            connectivity = connectivity,
            trackDao = trackDao,
            playbackResumer = PlaybackResumer(playbackStateStore, trackDao),
            radioGenerator = mockk(relaxed = true),
        )
        // Tests that don't care about disk existence get a "file is there"
        // default; the not-downloaded tests can override per-test.
        repo.filePathExistsOnDisk = { true }
    }

    @Test
    fun `filePathExistsOnDisk rejects too-small junk downloads, accepts real audio`() {
        // Proven on-device root cause: failed YouTube downloads left ~274-byte
        // .webm error bodies marked isDownloaded=true. exists()/length() are
        // both truthy, so the old check played them as local sources -> ExoPlayer
        // read a few hundred bytes of non-audio -> ERROR_CODE_PARSING_CONTAINER_MALFORMED
        // -> skip-storm. Requiring MIN_PLAYABLE_LOCAL_BYTES makes those rows fall
        // through to streaming instead.
        //
        // Use a fresh repo so we exercise the REAL default lambda (setUp above
        // overrides it to { true }).
        val context: Context = ApplicationProvider.getApplicationContext()
        val fresh = PlayerRepositoryImpl(
            context = context,
            playbackStateStore = playbackStateStore,
            musicRepository = musicRepository,
            streamingPreference = streamingPreference,
            streamResolver = streamResolver,
            streamUrlCache = streamUrlCache,
            connectivity = connectivity,
            trackDao = trackDao,
            playbackResumer = PlaybackResumer(playbackStateStore, trackDao),
            radioGenerator = mockk(relaxed = true),
        )

        val empty = File.createTempFile("stash-empty", ".flac").apply { deleteOnExit() }
        val junk = File.createTempFile("stash-junk", ".webm").apply {
            writeBytes(ByteArray(274)) // the exact failure shape from the device
            deleteOnExit()
        }
        val real = File.createTempFile("stash-real", ".flac").apply {
            writeBytes(ByteArray((StashConstants.MIN_PLAYABLE_LOCAL_BYTES + 1).toInt()))
            deleteOnExit()
        }
        val missing = File(empty.parentFile, "stash-missing-${empty.name}")

        // Plain paths (java.io.File handles them on any OS; a file:// URI over a
        // Windows drive-letter path is an env artifact, not a real-app case).
        assertThat(fresh.filePathExistsOnDisk(empty.absolutePath)).isFalse()
        assertThat(fresh.filePathExistsOnDisk(junk.absolutePath)).isFalse()
        assertThat(fresh.filePathExistsOnDisk(real.absolutePath)).isTrue()
        assertThat(fresh.filePathExistsOnDisk(missing.absolutePath)).isFalse()
    }

    @Test
    fun buildMediaItem_downloadedTrack_returnsLocalFileMediaItem() = runTest {
        val track = downloaded(id = 1L, path = "/storage/music/song.flac")

        val result = repo.buildMediaItemForTrack(track)

        assertThat(result).isInstanceOf(StreamRoutingResult.Item::class.java)
        val item = (result as StreamRoutingResult.Item).mediaItem
        assertThat(item.mediaId).isEqualTo("1")
        assertThat(item.localConfiguration?.uri?.toString())
            .isEqualTo("file:///storage/music/song.flac")
    }

    @Test
    fun buildMediaItem_downloadedTrackButFileMissing_withStreamingOff_returnsOfflineMode() = runTest {
        // Row says downloaded=true but the file is gone, isStreamable=false,
        // streaming-pref off. The post-Stash-v0.9.30 routing exits at the
        // streaming-pref check (PlayerRepositoryImpl.kt:725) before reaching
        // the isStreamable fall-through, so the result is OfflineMode.
        //
        // Coverage gap (TODO): a separate test with streamingPreference=true
        // is needed to actually exercise the file-missing → stream-resolution
        // path. This test only covers the streaming-off short-circuit.
        coEvery { streamingPreference.current() } returns false
        repo.filePathExistsOnDisk = { false }
        val track = downloaded(id = 1L, path = "/storage/music/missing.flac")
            .copy(isStreamable = false)

        val result = repo.buildMediaItemForTrack(track)

        assertThat(result).isEqualTo(StreamRoutingResult.OfflineMode)
    }

    @Test
    fun buildMediaItem_streamableTrackWithCacheHit_usesCachedUrl() = runTest {
        val track = streamable(id = 2L)
        every { streamingPreference.streamOnCellular } returns flowOf(true)
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns true
        every { connectivity.isCellular() } returns false
        every { streamUrlCache.get(2L) } returns StreamUrl(
            url = "https://cdn.example/cached?etsp=999",
            expiresAtMs = 999_999L,
        )

        val result = repo.buildMediaItemForTrack(track)

        assertThat(result).isInstanceOf(StreamRoutingResult.Item::class.java)
        val item = (result as StreamRoutingResult.Item).mediaItem
        assertThat(item.localConfiguration?.uri?.toString())
            .isEqualTo("https://cdn.example/cached?etsp=999")
    }

    @Test
    fun buildMediaItem_streamableTrackWithCacheMiss_resolvesAndCaches() = runTest {
        val track = streamable(id = 3L)
        every { streamingPreference.streamOnCellular } returns flowOf(true)
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns true
        every { connectivity.isCellular() } returns false
        every { streamUrlCache.get(3L) } returns null
        coEvery { streamResolver.resolve(track) } returns StreamUrl(
            url = "https://cdn.example/fresh?etsp=1234",
            expiresAtMs = 1_234_000L,
        )

        val result = repo.buildMediaItemForTrack(track)

        assertThat(result).isInstanceOf(StreamRoutingResult.Item::class.java)
        val item = (result as StreamRoutingResult.Item).mediaItem
        assertThat(item.localConfiguration?.uri?.toString())
            .isEqualTo("https://cdn.example/fresh?etsp=1234")
        // And the resolved URL must have been persisted into the cache
        // so a subsequent tap doesn't re-resolve.
        verify {
            streamUrlCache.put(3L, match { it.url.contains("fresh?etsp=1234") })
        }
    }

    /**
     * Full-timeline queue: buffering is purely the controller's own state —
     * the tapped track "resolves" inside LazyResolvingDataSource while
     * ExoPlayer sits in STATE_BUFFERING, so no custom spinner flag exists.
     */
    @Test
    fun computeIsBuffering_passesThroughControllerBuffering() {
        assertThat(repo.computeIsBuffering(controllerBuffering = true)).isTrue()
        assertThat(repo.computeIsBuffering(controllerBuffering = false)).isFalse()
    }

    // ── isStreaming: drives the Now Playing wifi/streaming indicator ──
    // A track counts as "streaming" when it came from a stream resolver,
    // NOT purely when its URI is http(s). antra plays its lossless FLAC
    // from a LOCAL cache file (file://) but is every bit a stream — without
    // this it rendered like a downloaded track (no wifi glyph).

    @Test
    fun computeIsStreaming_true_for_http_url() {
        assertThat(repo.computeIsStreaming(scheme = "https", streamOrigin = null)).isTrue()
        assertThat(repo.computeIsStreaming(scheme = "http", streamOrigin = null)).isTrue()
    }

    @Test
    fun computeIsStreaming_false_for_downloaded_local_file() {
        // Downloaded track: file:// and NO stream origin → not streaming.
        assertThat(repo.computeIsStreaming(scheme = "file", streamOrigin = null)).isFalse()
        assertThat(repo.computeIsStreaming(scheme = null, streamOrigin = null)).isFalse()
    }

    @Test
    fun buildMediaItem_forwards_allowYtDlp_false_to_resolver() = runTest {
        // The queue-wide background fill passes allowYtDlp = false so a
        // speculative resolve uses the fast InnerTube engine only (no slow
        // yt-dlp on the critical path). Verify the flag reaches the registry.
        val track = streamable(id = 4L)
        every { streamingPreference.streamOnCellular } returns flowOf(true)
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns true
        every { connectivity.isCellular() } returns false
        every { streamUrlCache.get(4L) } returns null
        coEvery {
            streamResolver.resolve(track, allowYouTube = true, allowYtDlp = false)
        } returns null

        val result = repo.buildMediaItemForTrack(
            track,
            allowYouTube = true,
            allowYtDlp = false,
        )

        assertThat(result).isEqualTo(StreamRoutingResult.NotAvailable)
        coVerify {
            streamResolver.resolve(track, allowYouTube = true, allowYtDlp = false)
        }
    }

    @Test
    fun buildMediaItem_speculativeYouTubeFallback_isNotCached() = runTest {
        // ROOT CAUSE (cache poisoning): during a Qobuz outage the queue-wide
        // background fill resolves with allowYtDlp = false (speculative) and
        // can fall through to a LOSSY youtube URL. Persisting that into the
        // shared StreamUrlCache poisons the track: the next-up prefetch and a
        // later foreground tap (both allowYtDlp = TRUE) defer to the fresh
        // cache entry and never re-attempt the Qobuz proxies (which may have
        // recovered) — so the user hears YouTube AAC even though FLAC could
        // serve. A youtube result from a speculative call must therefore stay
        // provisional (not cached).
        val track = streamable(id = 40L)
        every { streamingPreference.streamOnCellular } returns flowOf(true)
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns true
        every { connectivity.isCellular() } returns false
        every { streamUrlCache.get(40L) } returns null
        coEvery {
            streamResolver.resolve(track, allowYouTube = true, allowYtDlp = false)
        } returns StreamUrl(
            url = "https://yt.example/fallback",
            expiresAtMs = 9_999_000L,
            origin = "youtube",
        )

        val result = repo.buildMediaItemForTrack(
            track,
            allowYouTube = true,
            allowYtDlp = false,
        )

        // The track still plays (timeline floor), but the lossy URL is NOT
        // cached, so a full-fat path can re-resolve to lossless.
        assertThat(result).isInstanceOf(StreamRoutingResult.Item::class.java)
        verify(exactly = 0) { streamUrlCache.put(eq(40L), any()) }
    }

    @Test
    fun buildMediaItem_genuineYouTubeOnly_isCachedFromFullPermissionCall() = runTest {
        // No regression: when the full-fat path (foreground tap / next-up
        // prefetch, allowYtDlp = TRUE) still finds only youtube, the track is
        // genuinely lossless-less — cache it so we don't re-attempt the Qobuz
        // proxies on every single play.
        val track = streamable(id = 41L)
        every { streamingPreference.streamOnCellular } returns flowOf(true)
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns true
        every { connectivity.isCellular() } returns false
        every { streamUrlCache.get(41L) } returns null
        coEvery {
            streamResolver.resolve(track, allowYouTube = true, allowYtDlp = true)
        } returns StreamUrl(
            url = "https://yt.example/only",
            expiresAtMs = 9_999_000L,
            origin = "youtube",
        )

        repo.buildMediaItemForTrack(track) // defaults: all allowed

        verify { streamUrlCache.put(eq(41L), match { it.url.contains("yt.example/only") }) }
    }

    @Test
    fun buildMediaItem_speculativeLosslessHit_isStillCached() = runTest {
        // Precision guard: the suppression is for LOSSY fallbacks only. A
        // speculative background-fill call (allowYtDlp = false) that resolves
        // kennyy/squid lossless SHOULD warm the cache — that result is the
        // best available and re-resolving it later is wasteful.
        val track = streamable(id = 42L)
        every { streamingPreference.streamOnCellular } returns flowOf(true)
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns true
        every { connectivity.isCellular() } returns false
        every { streamUrlCache.get(42L) } returns null
        coEvery {
            streamResolver.resolve(track, allowYouTube = true, allowYtDlp = false)
        } returns StreamUrl(
            url = "https://cdn.example/lossless?etsp=5",
            expiresAtMs = 5_000L,
            origin = "kennyy",
        )

        repo.buildMediaItemForTrack(
            track,
            allowYouTube = true,
            allowYtDlp = false,
        )

        verify { streamUrlCache.put(eq(42L), match { it.origin == "kennyy" }) }
    }


    @Test
    fun buildMediaItem_cellularCacheMiss_prefersFastStartup() = runTest {
        val track = streamable(id = 43L)
        every { streamingPreference.streamOnCellular } returns flowOf(true)
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns true
        every { connectivity.isCellular() } returns true
        every { streamUrlCache.get(43L) } returns null
        coEvery {
            streamResolver.resolve(
                track,
                allowYouTube = true,
                allowYtDlp = true,
                preferFastStartup = true,
            )
        } returns StreamUrl(
            url = "https://yt.example/mobile-fast",
            expiresAtMs = 9_999_000L,
            origin = "youtube",
        )

        val result = repo.buildMediaItemForTrack(track)

        assertThat(result).isInstanceOf(StreamRoutingResult.Item::class.java)
        coVerify(exactly = 1) {
            streamResolver.resolve(
                track,
                allowYouTube = true,
                allowYtDlp = true,
                preferFastStartup = true,
            )
        }
    }

    @Test
    fun buildMediaItem_unavailableTrack_returnsOfflineMode() = runTest {
        // Not downloaded AND not streamable, with streaming off — routing
        // exits early on the streaming-pref check before any isStreamable
        // logic. AvailabilityCheckWorker that drove isStreamable was
        // removed in Stash v0.9.30; Kennyy is now the sole source of truth.
        coEvery { streamingPreference.current() } returns false
        val track = TrackEntity(
            id = 4L,
            title = "x",
            artist = "y",
            isDownloaded = false,
            isStreamable = false,
        )

        val result = repo.buildMediaItemForTrack(track)

        assertThat(result).isEqualTo(StreamRoutingResult.OfflineMode)
    }

    @Test
    fun buildMediaItem_streamableTrackWithStreamingOff_returnsOfflineMode() = runTest {
        val track = streamable(id = 5L)
        coEvery { streamingPreference.current() } returns false

        val result = repo.buildMediaItemForTrack(track)

        assertThat(result).isEqualTo(StreamRoutingResult.OfflineMode)
    }

    @Test
    fun buildMediaItem_streamableButCellularWithoutCellularPref_returnsCellularRefused() = runTest {
        val track = streamable(id = 6L)
        every { streamingPreference.streamOnCellular } returns flowOf(false)
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns true
        every { connectivity.isCellular() } returns true

        val result = repo.buildMediaItemForTrack(track)

        assertThat(result).isEqualTo(StreamRoutingResult.CellularRefused)
    }

    @Test
    fun buildMediaItem_streamableButNoConnectivity_returnsNoConnectivity() = runTest {
        val track = streamable(id = 7L)
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns false

        val result = repo.buildMediaItemForTrack(track)

        assertThat(result).isEqualTo(StreamRoutingResult.NoConnectivity)
    }

    @Test
    fun buildMediaItem_streamableButResolverFails_returnsNotAvailable() = runTest {
        // Cache miss AND resolver returns null (no match in Kennyy's
        // catalog). Caller should treat as not-available rather than
        // surfacing a snackbar.
        val track = streamable(id = 8L)
        every { streamingPreference.streamOnCellular } returns flowOf(true)
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns true
        every { connectivity.isCellular() } returns false
        every { streamUrlCache.get(8L) } returns null
        coEvery { streamResolver.resolve(track) } returns null

        val result = repo.buildMediaItemForTrack(track)

        assertThat(result).isEqualTo(StreamRoutingResult.NotAvailable)
    }

    @Test
    fun playFromStream_searchItem_resolvesIntoStreamingMediaItem() = runTest {
        // Verifies the search-tab routing branch: TrackItem inputs are
        // converted into a transient streamable TrackEntity that runs
        // through buildMediaItemForTrack's streaming arm. We invoke
        // buildMediaItemForTrack directly with the synthesized entity
        // rather than playFromStream() — playFromStream additionally
        // connects to StashPlaybackService, which has no equivalent
        // in this Robolectric environment.
        every { streamingPreference.streamOnCellular } returns flowOf(true)
        coEvery { streamingPreference.current() } returns true
        every { connectivity.isConnected() } returns true
        every { connectivity.isCellular() } returns false
        every { streamUrlCache.get(0L) } returns null
        coEvery { streamResolver.resolve(any()) } returns StreamUrl(
            url = "https://cdn.example/searchhit?etsp=42",
            expiresAtMs = 42_000L,
        )

        val transient = TrackEntity(
            id = 0L,
            title = "Hit Song",
            artist = "Hit Artist",
            isDownloaded = false,
            isStreamable = true,
        )

        val result = repo.buildMediaItemForTrack(transient)

        assertThat(result).isInstanceOf(StreamRoutingResult.Item::class.java)
        val mediaItem = (result as StreamRoutingResult.Item).mediaItem
        assertThat(mediaItem.localConfiguration?.uri?.toString())
            .isEqualTo("https://cdn.example/searchhit?etsp=42")
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun downloaded(id: Long, path: String): TrackEntity = TrackEntity(
        id = id,
        title = "Local Song",
        artist = "Local Artist",
        album = "Local Album",
        filePath = path,
        isDownloaded = true,
        isStreamable = false,
    )

    private fun streamable(id: Long): TrackEntity = TrackEntity(
        id = id,
        title = "Streamable Song",
        artist = "Streamable Artist",
        album = "Streamable Album",
        durationMs = 200_000L,
        isDownloaded = false,
        isStreamable = true,
    )
}
