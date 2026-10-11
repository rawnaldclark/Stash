package com.stash.data.lyrics

import com.stash.core.common.Clock
import com.stash.core.data.db.dao.LyricsDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.LyricsEntity
import com.stash.core.data.prefs.LyricsPreference
import com.stash.core.data.prefs.LyricsSourcePreference
import com.stash.data.lyrics.sidecar.LyricsSidecarWriter
import com.stash.data.lyrics.source.AppleTtmlLyricsSource
import com.stash.data.lyrics.source.LyricsQuery
import com.stash.data.lyrics.source.LyricsResult
import com.stash.data.lyrics.source.LyricsSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * One network walk per song, one store per track: the memory, the join and the single-flight
 * store behind Now Playing's prefetch, a second screen, and a streamed track being persisted
 * under its library id. Several sources, so the walk's failure/miss rules are exercised too.
 */
class LyricsRepositoryCacheTest {

    /** A clock the TTL tests move. */
    private val clock = object : Clock {
        var nowMs = 1_700_000_000_000L
        override fun now() = nowMs
    }

    private val lrcHit = LyricsResult("lrclib", "plain", "[00:01.00]plain", false, null, "42")
    private val ttmlHit = LyricsResult(AppleTtmlLyricsSource.SOURCE_ID, "plain", "[00:01.00]plain", false, null, "a1", ttml = "<tt/>")

    private fun preference(): LyricsPreference = mockk(relaxed = true) {
        every { sourcePreference } returns flowOf(LyricsSourcePreference.APPLE_MUSIC)
    }

    /** A source that counts its calls, answers [result] (or throws while [failing]), and can be held open with [gate]. */
    private class FakeSource(
        override val id: String,
        private val result: LyricsResult?,
        var failing: Boolean = false,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : LyricsSource {
        val calls = AtomicInteger()
        override val displayName = id
        override suspend fun resolve(query: LyricsQuery): LyricsResult? {
            calls.incrementAndGet()
            gate?.await()
            if (failing) throw IOException("$id down")
            return result
        }
    }

    /** DAO fakes whose stamp is live, as Room's is. */
    private val stamps = HashMap<Long, MutableStateFlow<Long?>>()
    private val trackDao: TrackDao = mockk(relaxed = true) {
        every { observeLyricsFetchedAt(any()) } answers { stamps.getOrPut(firstArg()) { MutableStateFlow(null) } }
        coEvery { setLyricsFetchedAt(any(), any()) } answers {
            stamps.getOrPut(firstArg()) { MutableStateFlow(null) }.value = secondArg()
        }
    }
    private val lyricsDao: LyricsDao = mockk(relaxed = true) {
        coEvery { this@mockk.get(any()) } returns null
    }
    private val sidecar: LyricsSidecarWriter = mockk(relaxed = true)

    private fun repo(vararg sources: LyricsSource) =
        LyricsRepository(sources.toList(), lyricsDao, trackDao, sidecar, clock, preference())

    private fun query(trackId: Long, yt: String? = "vid1", durationMs: Long? = 200_000, album: String? = null, interactive: Boolean = true) =
        LyricsQuery(
            trackId = trackId, title = "POP", artist = "Lil Uzi Vert", album = album, albumArtist = null,
            durationMs = durationMs, youtubeVideoId = yt, interactive = interactive,
        )

    /** Waits (real time: walks run on Dispatchers.IO) until [check] holds. */
    private suspend fun eventually(check: () -> Boolean) = withContext(Dispatchers.Default) {
        repeat(60) { if (check()) return@withContext; delay(50) }
    }

    // ── the memory and the join ──────────────────────────────────────────────────────────────

    @Test fun `a song asked twice is walked once, and the answer is in memory`() = runTest {
        val lrclib = FakeSource("lrclib", lrcHit)
        val repo = repo(lrclib)
        assertNull(repo.cached(query(0L)))
        assertEquals(lrcHit, repo.resolveTransient(query(0L)))
        assertEquals(lrcHit, repo.resolveTransient(query(0L)))
        assertEquals(1, lrclib.calls.get())
        assertEquals(lrcHit, repo.cached(query(0L))!!.result)
    }

    @Test fun `concurrent asks for one song join the walk in flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val lrclib = FakeSource("lrclib", lrcHit, gate = gate)
        val repo = repo(lrclib)
        val a = async { repo.resolveTransient(query(0L)) }
        val b = async { repo.resolveTransient(query(0L)) }
        testScheduler.advanceUntilIdle()
        gate.complete(Unit)
        assertEquals(lrcHit, a.await())
        assertEquals(lrcHit, b.await())
        assertEquals(1, lrclib.calls.get())
    }

    @Test fun `a streamed song persisted under a library id is stored from memory, no second walk`() = runTest {
        val lrclib = FakeSource("lrclib", lrcHit)
        val repo = repo(lrclib)
        repo.resolveTransient(query(0L))
        assertNotNull(repo.resolveAndStore(query(990459L)))
        assertEquals(1, lrclib.calls.get())
        coVerify { lyricsDao.upsert(match { it.trackId == 990459L && it.syncedLrc == lrcHit.syncedLrc }) }
    }

    @Test fun `a definitive miss across every source is remembered`() = runTest {
        val apple = FakeSource(AppleTtmlLyricsSource.SOURCE_ID, null)
        val lrclib = FakeSource("lrclib", null)
        val repo = repo(apple, lrclib)
        assertNull(repo.resolveTransient(query(0L)))
        assertNull(repo.cached(query(0L))!!.result)
        assertNull(repo.resolveTransient(query(0L)))
        assertEquals(1, apple.calls.get())
        assertEquals(1, lrclib.calls.get())
    }

    @Test fun `a failure is not remembered, so the next ask walks again`() = runTest {
        val apple = FakeSource(AppleTtmlLyricsSource.SOURCE_ID, null, failing = true)
        val lrclib = FakeSource("lrclib", null)
        val repo = repo(apple, lrclib)
        assertTrue(runCatching { repo.resolveTransient(query(0L)) }.isFailure)
        assertNull(repo.cached(query(0L)))
        apple.failing = false
        repo.resolveTransient(query(0L))
        assertEquals(2, apple.calls.get())
    }

    @Test fun `fresh (Retry) asks the network again`() = runTest {
        val lrclib = FakeSource("lrclib", lrcHit)
        val repo = repo(lrclib)
        repo.resolveTransient(query(0L))
        repo.resolveTransient(query(0L), fresh = true)
        assertEquals(2, lrclib.calls.get())
    }

    // ── S1: one store per track ──────────────────────────────────────────────────────────────

    @Test fun `concurrent stores for one track write the row, the stamp and the sidecar once`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val lrclib = FakeSource("lrclib", lrcHit, gate = gate)
        val repo = repo(lrclib)
        // The mini player's prefetch, Now Playing's prefetch and Now Playing's trigger, together.
        val calls = List(3) { async { repo.resolveAndStore(query(77L), skipIfStamped = true) } }
        testScheduler.advanceUntilIdle()
        gate.complete(Unit)
        calls.forEach { assertNotNull(it.await()) }
        assertEquals(1, lrclib.calls.get())
        coVerify(exactly = 1) { lyricsDao.upsert(any()) }
        coVerify(exactly = 1) { trackDao.setLyricsFetchedAt(77L, clock.nowMs) }
        coVerify(exactly = 1) { sidecar.write(77L, any()) }
    }

    @Test fun `a store asked after the track was stamped writes nothing`() = runTest {
        val repo = repo(FakeSource("lrclib", lrcHit))
        coEvery { lyricsDao.get(77L) } returns LyricsEntity(77L, "plain", "[00:01.00]plain", false, null, "lrclib", "42", 1L)
        stamps[77L] = MutableStateFlow(1L)
        assertNotNull(repo.resolveAndStore(query(77L), skipIfStamped = true))
        coVerify(exactly = 0) { lyricsDao.upsert(any()) }
        coVerify(exactly = 0) { sidecar.write(any(), any()) }
    }

    @Test fun `the prefetch runs a song once however many ask`() = runTest {
        val lrclib = FakeSource("lrclib", lrcHit)
        val repo = repo(lrclib)
        repo.prefetch(query(77L), store = true) // the mini player's ViewModel
        repo.prefetch(query(77L), store = true) // Now Playing's
        eventually { stamps[77L]?.value != null }
        withContext(Dispatchers.Default) { delay(200) }
        assertEquals(1, lrclib.calls.get())
        coVerify(exactly = 1) { lyricsDao.upsert(any()) }
        coVerify(exactly = 1) { sidecar.write(77L, any()) }
    }

    // ── S2: the title + artist key ───────────────────────────────────────────────────────────

    @Test fun `without a YouTube id, title and artist alone don't make a song`() = runTest {
        val lrclib = FakeSource("lrclib", lrcHit)
        val repo = repo(lrclib)
        repo.resolveTransient(query(0L, yt = null, durationMs = 200_000))
        repo.resolveTransient(query(0L, yt = null, durationMs = 95_000))              // another "POP"
        repo.resolveTransient(query(0L, yt = null, durationMs = 200_000, album = "B")) // another album
        assertEquals(3, lrclib.calls.get())
        // Same song, case and spacing aside: one walk.
        repo.resolveTransient(query(0L, yt = null, durationMs = 200_400).copy(title = " pop ", artist = "LIL UZI VERT"))
        assertEquals(3, lrclib.calls.get())
    }

    // ── S3: a source-preference change forgets ───────────────────────────────────────────────

    @Test fun `changing the source preference forgets every answer`() = runTest {
        val lrclib = FakeSource("lrclib", lrcHit)
        val repo = repo(lrclib)
        repo.resolveTransient(query(0L))
        repo.setSourcePreference(LyricsSourcePreference.LRC_ONLY)
        assertNull(repo.cached(query(0L)))
        repo.resolveTransient(query(0L))
        assertEquals(2, lrclib.calls.get())
    }

    // ── S4: a degraded hit is kept only briefly ──────────────────────────────────────────────

    @Test fun `a hit after an earlier source failed is kept two minutes, then asked again`() = runTest {
        val apple = FakeSource(AppleTtmlLyricsSource.SOURCE_ID, ttmlHit, failing = true) // Apple timed out
        val lrclib = FakeSource("lrclib", lrcHit)
        val repo = repo(apple, lrclib)
        assertEquals(lrcHit, repo.resolveTransient(query(0L)))
        assertTrue(repo.cached(query(0L))!!.degraded)
        // Within the window (e.g. the stream being persisted 30s in): memory, no network.
        clock.nowMs += 30_000
        assertEquals(lrcHit, repo.resolveTransient(query(0L)))
        assertEquals(1, apple.calls.get())
        // Later, Apple has finished on its side: the next ask gets the word-synced version.
        clock.nowMs += 2 * 60_000
        apple.failing = false
        assertNull(repo.cached(query(0L)))
        assertEquals(ttmlHit, repo.resolveTransient(query(0L)))
        assertEquals(2, apple.calls.get())
        assertEquals(false, repo.cached(query(0L))!!.degraded)
    }

    // ── nits ─────────────────────────────────────────────────────────────────────────────────

    @Test fun `Apple's clean miss is kept with the answer and stamped when stored`() = runTest {
        val apple = FakeSource(AppleTtmlLyricsSource.SOURCE_ID, null) // asked, no TTML
        val lrclib = FakeSource("lrclib", lrcHit)
        val repo = repo(apple, lrclib)
        repo.resolveTransient(query(0L))
        repo.resolveAndStore(query(990459L))
        coVerify { lyricsDao.upsert(match { it.trackId == 990459L && it.ttmlCheckedAt == clock.nowMs }) }
    }

    @Test fun `someone watching doesn't join a worker's slow background walk`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val lrclib = FakeSource("lrclib", lrcHit, gate = gate)
        val repo = repo(lrclib)
        val background = async { repo.resolveTransient(query(0L, interactive = false)) }
        val watching = async { repo.resolveTransient(query(0L, interactive = true)) }
        testScheduler.advanceUntilIdle()
        gate.complete(Unit)
        background.await()
        watching.await()
        assertEquals(2, lrclib.calls.get())
    }
}
