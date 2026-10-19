package com.stash.core.data.sync.workers

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.ArtistGenreDao
import com.stash.core.data.db.dao.LastFmCacheDao
import com.stash.core.data.db.entity.ArtistGenreEntity
import com.stash.core.data.genre.WeightedGenre
import com.stash.core.data.genre.WeightedGenreCodec
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmApiException
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.lastfm.LastFmRateLimitedException
import com.stash.core.data.lastfm.LastFmTag
import com.stash.core.data.lastfm.LastFmTagsAnswer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * ArtistGenreWorker against a mocked DAO and Last.fm client. Credentials are set explicitly in
 * every test (CI builds have EMPTY Last.fm BuildConfig secrets, so nothing here may lean on them).
 */
class ArtistGenreWorkerTest {

    private val appContext: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val dao: ArtistGenreDao = mockk(relaxed = true)
    private val cacheDao: LastFmCacheDao = mockk(relaxed = true)
    private val api: LastFmApiClient = mockk()

    private val configured = LastFmCredentials(apiKey = "key", apiSecret = "secret")
    private val now = 1_800_000_000_000L

    /** Every row the worker wrote, in order, across all its upserts. */
    private val written = mutableListOf<ArtistGenreEntity>()

    init {
        coEvery { dao.upsertAll(any()) } answers { written += firstArg<List<ArtistGenreEntity>>() }
        coEvery { dao.get(any()) } returns null
    }

    @After fun releaseLock() {
        if (ArtistGenreWorker.runLock.isLocked) ArtistGenreWorker.runLock.unlock()
    }

    private fun worker(credentials: LastFmCredentials = configured) =
        ArtistGenreWorker(appContext, workerParams, dao, cacheDao, api, credentials).apply {
            clock = { now }
            pause = { }
        }

    private fun missing(vararg names: String) {
        coEvery { dao.missingArtists(any(), any()) } returns names.toList()
    }

    private fun answer(vararg pairs: Pair<String, Float>, fromCache: Boolean = false) =
        Result.success(LastFmTagsAnswer(pairs.map { LastFmTag(it.first, it.second) }, fromCache))

    private fun failure(e: Throwable): Result<LastFmTagsAnswer> = Result.failure(e)
    private val offline = failure(IOException("offline"))
    private val http429 = failure(LastFmRateLimitedException("Last.fm GET failed: HTTP 429 (rate limited)"))
    private val allKeysThrottled = failure(LastFmRateLimitedException("all keys throttled"))
    private val error29 = failure(LastFmApiException(LastFmApiException.RATE_LIMIT_EXCEEDED, "Rate limit exceeded"))
    private val serverError = failure(IllegalStateException("Last.fm GET failed: HTTP 500"))
    private val notFound = failure(LastFmApiException(LastFmApiException.NOT_FOUND, "artist not found"))
    private val invalidResource = failure(LastFmApiException(LastFmApiException.INVALID_RESOURCE, "Invalid resource"))

    private fun writtenFor(name: String) = written.single { it.artistName == name }

    // ── Guards ──────────────────────────────────────────────────────

    @Test fun `blank credentials - succeeds without touching the history or Last_fm`() = runTest {
        val result = worker(LastFmCredentials(apiKey = "", apiSecret = "")).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        coVerify(exactly = 0) { dao.missingArtists(any(), any()) }
        coVerify(exactly = 0) { api.getArtistTopTagsAnswer(any()) }
    }

    @Test fun `empty history - succeeds with no requests`() = runTest {
        missing()

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        coVerify(exactly = 0) { api.getArtistTopTagsAnswer(any()) }
        coVerify(exactly = 0) { dao.upsertAll(any()) }
    }

    @Test fun `asks for one batch of missing artists at the current time`() = runTest {
        missing()
        worker().doWork()
        coVerify(exactly = 1) { dao.missingArtists(ArtistGenreWorker.BATCH_SIZE, now) }
    }

    @Test fun `another run in progress - this one does nothing`() = runTest {
        missing("Drake")
        assertTrue(ArtistGenreWorker.runLock.tryLock())

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        coVerify(exactly = 0) { dao.missingArtists(any(), any()) }
    }

    @Test fun `the lock is released after a run, also a failed one`() = runTest {
        coEvery { dao.missingArtists(any(), any()) } throws IllegalStateException("db closed")
        try {
            worker().doWork()
        } catch (e: IllegalStateException) {
            // expected
        }
        assertTrue(!ArtistGenreWorker.runLock.isLocked)
    }

    @Test fun `expired Last_fm responses are pruned at the end, best effort`() = runTest {
        missing()
        coEvery { cacheDao.prune(any()) } throws IllegalStateException("disk full")

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        coVerify(exactly = 1) { cacheDao.prune(now - LastFmApiClient.CACHE_TTL_MS) }
    }

    // ── Answers ─────────────────────────────────────────────────────

    @Test fun `writes filtered genres for each artist`() = runTest {
        missing("Drake", "Radiohead")
        coEvery { api.getArtistTopTagsAnswer("Drake") } returns
            answer("seen live" to 1f, "Hip Hop" to 0.8f, "rnb" to 0.4f, "canadian" to 0.4f)
        coEvery { api.getArtistTopTagsAnswer("Radiohead") } returns
            answer("alternative" to 1f, "electronic" to 0.8f, "british" to 0.7f, fromCache = true)

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        val drake = writtenFor("Drake")
        assertEquals(ArtistGenreEntity.STATUS_TAGGED, drake.status)
        assertEquals(now, drake.fetchedAt)
        assertEquals(
            listOf(WeightedGenre("hip-hop", 1f), WeightedGenre("r&b", 0.5f)),
            WeightedGenreCodec.decode(drake.genres),
        )
        assertEquals(
            listOf("alternative", "electronic"),
            WeightedGenreCodec.decode(writtenFor("Radiohead").genres).map { it.genre },
        )
    }

    @Test fun `no genre tags - writes the untaggable marker`() = runTest {
        missing("Local Rip")
        coEvery { api.getArtistTopTagsAnswer("Local Rip") } returns answer("seen live" to 1f, "favorites" to 0.5f)

        worker().doWork()

        val row = writtenFor("Local Rip")
        assertEquals(ArtistGenreEntity.STATUS_UNTAGGABLE, row.status)
        assertEquals("[]", row.genres)
    }

    @Test fun `Last_fm errors 6 and 7 - untaggable`() = runTest {
        missing("Nobody Knows", "Invalid Name")
        coEvery { api.getArtistTopTagsAnswer("Nobody Knows") } returns notFound
        coEvery { api.getArtistTopTagsAnswer("Invalid Name") } returns invalidResource

        worker().doWork()

        assertEquals(ArtistGenreEntity.STATUS_UNTAGGABLE, writtenFor("Nobody Knows").status)
        assertEquals(ArtistGenreEntity.STATUS_UNTAGGABLE, writtenFor("Invalid Name").status)
    }

    // ── Refresh of an artist that has genres ────────────────────────

    private val oldGenres = WeightedGenreCodec.encode(listOf(WeightedGenre("hip-hop", 1f)))
    private fun tagged(name: String) = ArtistGenreEntity(name, oldGenres, ArtistGenreEntity.STATUS_TAGGED, fetchedAt = 1L)

    @Test fun `a refresh replaces genres with the new answer`() = runTest {
        missing("Drake")
        coEvery { dao.get("Drake") } returns tagged("Drake")
        coEvery { api.getArtistTopTagsAnswer("Drake") } returns answer("r&b" to 1f)

        worker().doWork()

        assertEquals(listOf("r&b"), WeightedGenreCodec.decode(writtenFor("Drake").genres).map { it.genre })
        assertEquals(now, writtenFor("Drake").fetchedAt)
    }

    @Test fun `a refresh finding no genre keeps the old genres`() = runTest {
        missing("Drake")
        coEvery { dao.get("Drake") } returns tagged("Drake")
        coEvery { api.getArtistTopTagsAnswer("Drake") } returns answer("seen live" to 1f)

        worker().doWork()

        val row = writtenFor("Drake")
        assertEquals(ArtistGenreEntity.STATUS_TAGGED, row.status)
        assertEquals(oldGenres, row.genres)
        assertEquals(now, row.fetchedAt)
    }

    @Test fun `a failed refresh keeps the old genres and comes due after the retry age`() = runTest {
        missing("Drake", "Next")
        coEvery { dao.get("Drake") } returns tagged("Drake")
        coEvery { api.getArtistTopTagsAnswer("Drake") } returns serverError
        coEvery { api.getArtistTopTagsAnswer("Next") } returns answer("rock" to 1f)

        worker().doWork()

        val row = writtenFor("Drake")
        assertEquals(ArtistGenreEntity.STATUS_TAGGED, row.status)
        assertEquals(oldGenres, row.genres)
        // Settled until now + FAILED_RETRY_MS, as the DAO counts TAGGED rows settled for TAGGED_REFRESH_MS.
        assertEquals(now + ArtistGenreEntity.FAILED_RETRY_MS, row.fetchedAt + ArtistGenreEntity.TAGGED_REFRESH_MS)
    }

    // ── Failures ────────────────────────────────────────────────────

    @Test fun `rate limits write nothing, even with cache hits and successes after them, and ask for a retry`() = runTest {
        missing("Throttled 1", "Cached", "Throttled 2", "Throttled 3", "Fresh")
        coEvery { api.getArtistTopTagsAnswer("Throttled 1") } returns http429
        coEvery { api.getArtistTopTagsAnswer("Cached") } returns answer("rock" to 1f, fromCache = true)
        coEvery { api.getArtistTopTagsAnswer("Throttled 2") } returns error29
        coEvery { api.getArtistTopTagsAnswer("Throttled 3") } returns allKeysThrottled
        coEvery { api.getArtistTopTagsAnswer("Fresh") } returns answer("jazz" to 1f)

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Retry)
        assertEquals(listOf("Cached", "Fresh"), written.map { it.artistName })
    }

    @Test fun `cache hits don't reset the failure streak - a throttled run still stops after five`() = runTest {
        val names = (1..20).map { "Artist $it" }
        missing(*names.toTypedArray())
        // Every other artist is a cache hit; the rest are refused by the breaker.
        names.forEachIndexed { i, name ->
            coEvery { api.getArtistTopTagsAnswer(name) } returns
                if (i % 2 == 1) answer("rock" to 1f, fromCache = true) else allKeysThrottled
        }

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Retry)
        // Five throttled + the four cache hits between them, then the stop.
        coVerify(exactly = 9) { api.getArtistTopTagsAnswer(any()) }
        assertEquals(4, written.size)
        assertTrue(written.none { it.status == ArtistGenreEntity.STATUS_FAILED })
    }

    @Test fun `a network-fetched answer resets the streak`() = runTest {
        val names = listOf("F1", "F2", "F3", "F4", "OK", "F5", "F6", "F7", "F8", "Last")
        missing(*names.toTypedArray())
        names.forEach { coEvery { api.getArtistTopTagsAnswer(it) } returns offline }
        coEvery { api.getArtistTopTagsAnswer("OK") } returns answer("rock" to 1f)
        coEvery { api.getArtistTopTagsAnswer("Last") } returns answer("jazz" to 1f)

        worker().doWork()

        coVerify(exactly = 10) { api.getArtistTopTagsAnswer(any()) }
        assertEquals(listOf("OK", "Last"), written.map { it.artistName })
    }

    @Test fun `network failures write nothing - an outage ends the run and asks for a retry`() = runTest {
        val names = (1..20).map { "Artist $it" }
        missing(*names.toTypedArray())
        coEvery { api.getArtistTopTagsAnswer(any()) } returns offline

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Retry)
        coVerify(exactly = ArtistGenreWorker.MAX_CONSECUTIVE_ERRORS) { api.getArtistTopTagsAnswer(any()) }
        assertTrue(written.isEmpty())
    }

    @Test fun `an artist whose own request fails is written FAILED, so it can't block the queue`() = runTest {
        missing("Broken Name", "Drake")
        coEvery { api.getArtistTopTagsAnswer("Broken Name") } returns serverError
        coEvery { api.getArtistTopTagsAnswer("Drake") } returns answer("hip-hop" to 1f)

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(ArtistGenreEntity.STATUS_FAILED, writtenFor("Broken Name").status)
        assertEquals(ArtistGenreEntity.STATUS_TAGGED, writtenFor("Drake").status)
    }

    @Test fun `failing artists at the head of the queue are written FAILED even when the run stops on them`() = runTest {
        val names = (1..8).map { "Bad $it" }
        missing(*names.toTypedArray())
        coEvery { api.getArtistTopTagsAnswer(any()) } returns serverError

        val result = worker().doWork()

        // Next run starts past them instead of stopping on the same five again; no retry storm.
        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(names.take(5), written.map { it.artistName })
        assertTrue(written.all { it.status == ArtistGenreEntity.STATUS_FAILED })
    }

    // ── Writing ─────────────────────────────────────────────────────

    @Test fun `a long run writes in batches, not once per artist`() = runTest {
        val names = (1..120).map { "Artist $it" }
        missing(*names.toTypedArray())
        coEvery { api.getArtistTopTagsAnswer(any()) } returns answer("rock" to 1f)
        val batches = mutableListOf<Int>()
        val rows = slot<List<ArtistGenreEntity>>()
        coEvery { dao.upsertAll(capture(rows)) } answers { batches += rows.captured.size; written += rows.captured }

        worker().doWork()

        assertEquals(listOf(50, 50, 20), batches)
        assertEquals(names, written.map { it.artistName })
    }

    @Test fun `cancellation propagates, what was found is still written and the lock is released`() = runTest {
        missing("Drake", "Cancelled Here", "Never Reached")
        coEvery { api.getArtistTopTagsAnswer("Drake") } returns answer("hip-hop" to 1f)
        // runCatching inside the client captures a cancellation into the Result.
        coEvery { api.getArtistTopTagsAnswer("Cancelled Here") } returns failure(CancellationException("stopped"))

        try {
            worker().doWork()
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            // expected
        }

        assertEquals(listOf("Drake"), written.map { it.artistName })
        coVerify(exactly = 0) { api.getArtistTopTagsAnswer("Never Reached") }
        assertTrue(!ArtistGenreWorker.runLock.isLocked)
    }

    @Test fun `a failed write doesn't fail the run`() = runTest {
        missing("Drake")
        coEvery { api.getArtistTopTagsAnswer("Drake") } returns answer("hip-hop" to 1f)
        coEvery { dao.upsertAll(any()) } throws IllegalStateException("disk full")

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
    }
}
