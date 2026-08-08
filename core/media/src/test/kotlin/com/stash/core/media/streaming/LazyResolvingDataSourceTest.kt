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
    private val amzSource: DataSource = mockk(relaxed = true)
    private val jioSaavnSource: DataSource = mockk(relaxed = true)

    private fun source(deadlineMs: Long = 45_000L) = LazyResolvingDataSource(
        resolver = resolver,
        urlCache = urlCache,
        trackDao = trackDao,
        httpDelegate = { httpSource },
        amzDelegate = { amzSource },
        jioSaavnDelegate = { jioSaavnSource },
        resolveDeadlineMs = deadlineMs,
    )

    private fun placeholderSpec(trackId: Long) =
        DataSpec.Builder().setUri(Uri.parse("$STASH_RESOLVE_SCHEME://track/$trackId")).build()

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
    fun `amz origin routes to the amz delegate not the http delegate`() {
        every { urlCache.get(7L) } returns stream("https://amz/z.flac", "amz")
        every { amzSource.open(any()) } returns 100L

        source().open(placeholderSpec(7L))

        verify(exactly = 1) { amzSource.open(any()) }
        verify(exactly = 0) { httpSource.open(any()) }
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
}
