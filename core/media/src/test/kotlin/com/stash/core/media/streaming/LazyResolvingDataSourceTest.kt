package com.stash.core.media.streaming

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.delay
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * The full-timeline queue puts EVERY stream track into ExoPlayer as a
 * `stash-resolve://track/<id>` placeholder; this source turns the placeholder
 * into a playable stream at open() time on the loader thread — cache first,
 * then a deadline-bounded registry resolve — and dispatches the read to the
 * origin-appropriate delegate. Failure/timeout must THROW (IOException) so
 * the onPlayerError cascade guard owns the policy; it must never hang.
 */
@RunWith(RobolectricTestRunner::class)
class LazyResolvingDataSourceTest {

    private val resolver: StreamSourceRegistry = mockk()
    private val urlCache: StreamUrlCache = mockk(relaxUnitFun = true)
    private val trackDao: TrackDao = mockk()
    private val httpSource: DataSource = mockk(relaxed = true)
    private val jioSaavnSource: DataSource = mockk(relaxed = true)

    private fun source(deadlineMs: Long = 45_000L) = LazyResolvingDataSource(
        resolver = resolver,
        urlCache = urlCache,
        trackDao = trackDao,
        httpDelegate = { httpSource },
        jioSaavnDelegate = { jioSaavnSource },
        resolveDeadlineMs = deadlineMs,
    )

    private fun placeholderSpec(trackId: Long, position: Long = 0L) =
        DataSpec.Builder().setUri(Uri.parse("$STASH_RESOLVE_SCHEME://track/$trackId")).setPosition(position).build()

    private fun stream(url: String, origin: String) = StreamUrl(
        url = url,
        expiresAtMs = Long.MAX_VALUE,
        origin = origin,
    )

    @Test
    fun `cache hit opens delegate with the cached url and skips the resolver`() {
        every { urlCache.get(42L) } returns stream("https://cdn/x.flac", "squid")
        val spec = slot<DataSpec>()
        every { httpSource.open(capture(spec)) } returns 100L

        source().open(placeholderSpec(42L))

        assertThat(spec.captured.uri.toString()).isEqualTo("https://cdn/x.flac")
        coVerify(exactly = 0) { trackDao.getById(any()) }
    }

    @Test
    fun `cache miss resolves via registry and caches the result`() {
        every { urlCache.get(42L) } returns null
        coEvery { trackDao.getById(42L) } returns TrackEntity(id = 42L, title = "t", artist = "a")
        coEvery { resolver.resolve(any(), any(), any()) } returns stream("https://cdn/y.flac", "qbdlx")
        every { httpSource.open(any()) } returns 100L

        source().open(placeholderSpec(42L))

        verify { urlCache.put(42L, any()) }
        verify { httpSource.open(any()) }
    }

    @Test
    fun `jiosaavn origin routes to its no-redirect delegate`() {
        every { urlCache.get(8L) } returns
            stream("https://aac.saavncdn.com/song_320.mp4", JioSaavnStreamResolver.ORIGIN)
        every { jioSaavnSource.open(any()) } returns 100L

        source().open(placeholderSpec(8L))

        verify(exactly = 1) { jioSaavnSource.open(any()) }
        verify(exactly = 0) { httpSource.open(any()) }
    }

    @Test
    fun `dao miss falls back to resolver inputs carried in the uri query params`() {
        // Search-surface tracks have synthetic ids with NO Room row — the
        // placeholder URI must carry enough for the resolver on its own.
        every { urlCache.get(99L) } returns null
        coEvery { trackDao.getById(99L) } returns null
        val entity = slot<TrackEntity>()
        coEvery { resolver.resolve(capture(entity), any(), any()) } returns
            stream("https://cdn/z.m4a", "youtube")
        every { httpSource.open(any()) } returns 100L

        val uri = stashResolveUri(
            trackId = 99L, youtubeId = "vid123",
            title = "Song", artist = "Artist", album = "Album",
            durationMs = 123_000L, isrc = "USX123",
        )
        source().open(DataSpec.Builder().setUri(uri).build())

        assertThat(entity.captured.youtubeId).isEqualTo("vid123")
        assertThat(entity.captured.title).isEqualTo("Song")
        assertThat(entity.captured.artist).isEqualTo("Artist")
        assertThat(entity.captured.isrc).isEqualTo("USX123")
        verify { httpSource.open(any()) }
    }

    @Test
    fun `an override item resolves the ids it carries, not its row's, and stays out of the url cache`() {
        // Listen Together: the listener's row 7 is another edit of the host's song.
        coEvery { trackDao.getById(7L) } returns TrackEntity(
            id = 7L, title = "Song", artist = "Artist", youtubeId = "mine", isrc = "MYISRC", durationMs = 200_000L,
        )
        val entity = slot<TrackEntity>()
        coEvery { resolver.resolve(capture(entity), any(), any()) } returns stream("https://yt/host.webm", "youtube")
        every { httpSource.open(any()) } returns 100L

        val uri = stashResolveUri(
            trackId = 7L, youtubeId = "host", title = "Song", artist = "Artist",
            durationMs = 260_000L, isrc = "HOSTISRC",
        ).withRowOverride()
        source().open(DataSpec.Builder().setUri(uri).build())

        assertThat(entity.captured.youtubeId).isEqualTo("host")
        assertThat(entity.captured.isrc).isEqualTo("HOSTISRC")
        assertThat(entity.captured.durationMs).isEqualTo(260_000L)
        // The row's own cached URL is the wrong edit; the host's must not become it.
        verify(exactly = 0) { urlCache.get(any()) }
        verify(exactly = 0) { urlCache.put(any(), any()) }
    }

    @Test
    fun `failed resolve throws IOException so the cascade guard owns the failure`() {
        every { urlCache.get(42L) } returns null
        coEvery { trackDao.getById(42L) } returns null

        assertThrows(IOException::class.java) { source().open(placeholderSpec(42L)) }
    }

    @Test
    fun `hung resolve hits the deadline and throws instead of blocking forever`() {
        every { urlCache.get(42L) } returns null
        coEvery { trackDao.getById(42L) } returns TrackEntity(id = 42L, title = "t", artist = "a")
        coEvery { resolver.resolve(any(), any(), any()) } coAnswers { delay(60_000); null }

        assertThrows(IOException::class.java) { source(deadlineMs = 200L).open(placeholderSpec(42L)) }
    }

    @Test
    fun `non-placeholder specs pass through to the http delegate untouched`() {
        val spec = DataSpec.Builder().setUri(Uri.parse("https://direct/a.flac")).build()
        every { httpSource.open(spec) } returns 5L

        source().open(spec)

        verify { httpSource.open(spec) }
        verify(exactly = 0) { urlCache.get(any()) }
    }

    @Test
    fun `a re-open at an offset reuses the pinned stream after the url cache was cleared`() {
        // A tier change clears StreamUrlCache; the seek that follows must not
        // re-resolve to the new tier's file and splice it into the old extractor.
        every { urlCache.get(42L) } returns stream("https://cdn/hires.flac", "qbdlx") andThen null
        val specs = mutableListOf<DataSpec>()
        // The whole 10 000-byte file, then its last 5 000 bytes.
        every { httpSource.open(capture(specs)) } returnsMany listOf(10_000L, 5_000L)

        val s = source()
        s.open(placeholderSpec(42L))
        s.close()
        s.open(placeholderSpec(42L, position = 5_000L))

        assertThat(specs.map { it.uri.toString() })
            .containsExactly("https://cdn/hires.flac", "https://cdn/hires.flac")
        coVerify(exactly = 0) { resolver.resolve(any(), any(), any()) }
    }

    @Test
    fun `a re-resolve that lands on another source or tier fails instead of splicing`() {
        // The pinned URL lapsed during a long pause; the re-resolve now serves the
        // YouTube rung. Its bytes from offset N must never reach the FLAC extractor.
        val expired = stream("https://cdn/hires.flac", "qbdlx").copy(expiresAtMs = System.currentTimeMillis() - 1)
        every { urlCache.get(42L) } returns expired andThen null
        coEvery { trackDao.getById(42L) } returns TrackEntity(id = 42L, title = "t", artist = "a")
        coEvery { resolver.resolve(any(), any(), any()) } returns stream("https://yt/v.webm", "youtube")
        every { httpSource.open(any()) } returns 100L

        val s = source()
        s.open(placeholderSpec(42L))
        s.close()

        assertThrows(IOException::class.java) { s.open(placeholderSpec(42L, position = 5_000L)) }
        verify(exactly = 0) { httpSource.open(match { it.uri.toString() == "https://yt/v.webm" }) }
    }

    @Test
    fun `a re-resolve to the same file is accepted, and a file of another length is not`() {
        val expired = stream("https://cdn/a.flac?v=1", "qbdlx").copy(expiresAtMs = System.currentTimeMillis() - 1)
        every { urlCache.get(42L) } returns expired andThen
            expired.copy(url = "https://cdn/a.flac?v=2") andThen
            expired.copy(url = "https://cdn/a.flac?v=3")
        // 1000 bytes from 0; the same 1000 from 400; then a 2000-byte file.
        every { httpSource.open(any()) } returnsMany listOf(1_000L, 600L, 1_600L)

        val s = source()
        s.open(placeholderSpec(42L))
        s.close()
        assertThat(s.open(placeholderSpec(42L, position = 400L))).isEqualTo(600L)
        s.close()
        assertThrows(IOException::class.java) { s.open(placeholderSpec(42L, position = 400L)) }
    }

    @Test
    fun `read and close forward to whichever delegate was opened`() {
        every { urlCache.get(42L) } returns stream("https://cdn/x.flac", "squid")
        every { httpSource.open(any()) } returns 10L
        every { httpSource.read(any(), any(), any()) } returns 4

        val s = source()
        s.open(placeholderSpec(42L))
        s.read(ByteArray(4), 0, 4)
        s.close()

        verify { httpSource.read(any(), 0, 4) }
        verify { httpSource.close() }
    }

    @Test
    fun `a cloud copy opens through the cloud delegate`() {
        every { urlCache.get(5L) } returns stream("stash-cloud://dropbox/1/abc?v=1", CloudCopyFinder.ORIGIN)
        val cloud: DataSource = mockk(relaxed = true)
        every { cloud.open(any()) } returns 100L

        LazyResolvingDataSource(
            resolver, urlCache, trackDao, { httpSource }, { jioSaavnSource }, cloudDelegate = { cloud },
        ).open(placeholderSpec(5L))

        verify(exactly = 1) { cloud.open(any()) }
        verify(exactly = 0) { httpSource.open(any()) }
    }

    @Test
    fun `a cloud copy that won't open falls back to the stream chain in the same open`() {
        every { urlCache.get(5L) } returns stream("stash-cloud://dropbox/1/abc?v=1", CloudCopyFinder.ORIGIN)
        val cloud: DataSource = mockk(relaxed = true)
        every { cloud.open(any()) } throws java.io.IOException("404")
        val finder: CloudCopyFinder = mockk(relaxUnitFun = true)
        coEvery { trackDao.getById(5L) } returns TrackEntity(id = 5L, title = "t", artist = "a")
        coEvery { resolver.resolve(any(), any(), any()) } returns stream("https://cdn/q.flac", "qbdlx")
        val spec = slot<DataSpec>()
        every { httpSource.open(capture(spec)) } returns 100L

        val length = LazyResolvingDataSource(
            resolver, urlCache, trackDao, { httpSource }, { jioSaavnSource },
            cloudDelegate = { cloud }, cloudCopies = finder,
        ).open(placeholderSpec(5L))

        assertThat(length).isEqualTo(100L)
        assertThat(spec.captured.uri.toString()).isEqualTo("https://cdn/q.flac")
        verify { finder.markFailed("stash-cloud://dropbox/1/abc?v=1") }
        verify { urlCache.invalidate(5L) }
    }
}
