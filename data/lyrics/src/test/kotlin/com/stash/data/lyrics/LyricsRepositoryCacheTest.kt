package com.stash.data.lyrics

import com.stash.core.common.Clock
import com.stash.core.data.db.dao.LyricsDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.LyricsPreference
import com.stash.core.data.prefs.LyricsSourcePreference
import com.stash.data.lyrics.sidecar.LyricsSidecarWriter
import com.stash.data.lyrics.source.LyricsQuery
import com.stash.data.lyrics.source.LyricsResult
import com.stash.data.lyrics.source.LyricsSource
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * One network walk per song: the memory and the join behind Now Playing's prefetch, a second
 * screen, and a streamed track being persisted under its library id.
 */
class LyricsRepositoryCacheTest {

    private val clock = object : Clock { override fun now() = 1_700_000_000_000L }
    private val hit = LyricsResult("lrclib", "plain", "[00:01.00]plain", false, null, "42")

    private fun preference(): LyricsPreference = mockk(relaxed = true) {
        every { sourcePreference } returns flowOf(LyricsSourcePreference.APPLE_MUSIC)
    }

    /** A source that counts its calls, answers [result], and can be held open with [gate]. */
    private class CountingSource(
        private val result: LyricsResult?,
        private val gate: CompletableDeferred<Unit>? = null,
        private val failFirst: Boolean = false,
    ) : LyricsSource {
        val calls = AtomicInteger()
        override val id = "lrclib"
        override val displayName = "LRCLIB"
        override suspend fun resolve(query: LyricsQuery): LyricsResult? {
            val n = calls.incrementAndGet()
            gate?.await()
            if (failFirst && n == 1) throw IOException("network down")
            return result
        }
    }

    private fun repo(source: LyricsSource, lyricsDao: LyricsDao = mockk(relaxed = true)) =
        LyricsRepository(listOf(source), lyricsDao, mockk<TrackDao>(relaxed = true), mockk<LyricsSidecarWriter>(relaxed = true), clock, preference())

    private fun query(trackId: Long, yt: String? = "vid1") = LyricsQuery(
        trackId = trackId, title = "POP", artist = "Lil Uzi Vert", album = null, albumArtist = null,
        durationMs = 200_000, youtubeVideoId = yt, interactive = true,
    )

    @Test fun `a song asked twice is walked once, and the answer is in memory`() = runTest {
        val source = CountingSource(hit)
        val repo = repo(source)
        assertNull(repo.cached(query(0L)))
        assertEquals(hit, repo.resolveTransient(query(0L)))
        assertEquals(hit, repo.resolveTransient(query(0L)))
        assertEquals(1, source.calls.get())
        assertEquals(hit, repo.cached(query(0L))!!.result)
    }

    @Test fun `concurrent asks for one song join the walk in flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = CountingSource(hit, gate)
        val repo = repo(source)
        val a = async { repo.resolveTransient(query(0L)) }
        val b = async { repo.resolveTransient(query(0L)) }
        testScheduler.advanceUntilIdle()
        gate.complete(Unit)
        assertEquals(hit, a.await())
        assertEquals(hit, b.await())
        assertEquals(1, source.calls.get())
    }

    @Test fun `a streamed song persisted under a library id is stored from memory, no second walk`() = runTest {
        val source = CountingSource(hit)
        val lyricsDao = mockk<LyricsDao>(relaxed = true)
        val repo = repo(source, lyricsDao)
        repo.resolveTransient(query(0L))              // played under a transient id
        val stored = repo.resolveAndStore(query(990459L)) // now a library row, same YouTube id
        assertNotNull(stored)
        assertEquals(1, source.calls.get())
        coVerify { lyricsDao.upsert(match { it.trackId == 990459L && it.syncedLrc == hit.syncedLrc }) }
    }

    @Test fun `a definitive miss is remembered too`() = runTest {
        val source = CountingSource(null)
        val repo = repo(source)
        assertNull(repo.resolveTransient(query(0L)))
        assertNotNull(repo.cached(query(0L)))
        assertNull(repo.cached(query(0L))!!.result)
        assertNull(repo.resolveTransient(query(0L)))
        assertEquals(1, source.calls.get())
    }

    @Test fun `a failure is not remembered, so the next ask walks again`() = runTest {
        val source = CountingSource(hit, failFirst = true)
        val repo = repo(source)
        val first = runCatching { repo.resolveTransient(query(0L)) }
        assert(first.isFailure)
        assertNull(repo.cached(query(0L)))
        assertEquals(hit, repo.resolveTransient(query(0L)))
        assertEquals(2, source.calls.get())
    }

    @Test fun `fresh (Retry) asks the network again`() = runTest {
        val source = CountingSource(hit)
        val repo = repo(source)
        repo.resolveTransient(query(0L))
        repo.resolveTransient(query(0L), fresh = true)
        assertEquals(2, source.calls.get())
    }

    @Test fun `without a YouTube id, title and artist key the song`() = runTest {
        val source = CountingSource(hit)
        val repo = repo(source)
        repo.resolveTransient(query(0L, yt = null))
        repo.resolveAndStore(query(7L, yt = null).copy(title = " pop ", artist = "LIL UZI VERT"))
        assertEquals(1, source.calls.get())
    }
}
