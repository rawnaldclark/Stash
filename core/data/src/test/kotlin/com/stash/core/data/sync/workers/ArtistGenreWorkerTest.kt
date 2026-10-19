package com.stash.core.data.sync.workers

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.ArtistGenreDao
import com.stash.core.data.db.entity.ArtistGenreEntity
import com.stash.core.data.genre.WeightedGenre
import com.stash.core.data.genre.WeightedGenreCodec
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmApiException
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.lastfm.LastFmTag
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
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
    private val api: LastFmApiClient = mockk()

    private val configured = LastFmCredentials(apiKey = "key", apiSecret = "secret")
    private val now = 1_800_000_000_000L

    /** Every row the worker wrote, in order, across all its upserts. */
    private val written = mutableListOf<ArtistGenreEntity>()

    init {
        coEvery { dao.upsertAll(any()) } answers { written += firstArg<List<ArtistGenreEntity>>() }
    }

    private fun worker(credentials: LastFmCredentials = configured) =
        ArtistGenreWorker(appContext, workerParams, dao, api, credentials).apply {
            clock = { now }
            pause = { }
        }

    private fun missing(vararg names: String) {
        coEvery { dao.missingArtists(any(), any()) } returns names.toList()
    }

    private fun tags(vararg pairs: Pair<String, Float>) = Result.success(pairs.map { LastFmTag(it.first, it.second) })
    private val offline: Result<List<LastFmTag>> = Result.failure(IOException("offline"))
    private val notFound: Result<List<LastFmTag>> =
        Result.failure(LastFmApiException(LastFmApiException.NOT_FOUND, "The artist you supplied could not be found"))

    private fun writtenFor(name: String) = written.single { it.artistName == name }

    @Test fun `blank credentials - succeeds without touching the history or Last_fm`() = runTest {
        val result = worker(LastFmCredentials(apiKey = "", apiSecret = "")).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        coVerify(exactly = 0) { dao.missingArtists(any(), any()) }
        coVerify(exactly = 0) { api.getArtistTopTags(any()) }
    }

    @Test fun `empty history - succeeds with no requests`() = runTest {
        missing()

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        coVerify(exactly = 0) { api.getArtistTopTags(any()) }
        coVerify(exactly = 0) { dao.upsertAll(any()) }
    }

    @Test fun `asks for one batch of missing artists at the current time`() = runTest {
        missing()
        worker().doWork()
        coVerify(exactly = 1) { dao.missingArtists(ArtistGenreWorker.BATCH_SIZE, now) }
    }

    @Test fun `writes filtered genres for each artist`() = runTest {
        missing("Drake", "Radiohead")
        coEvery { api.getArtistTopTags("Drake") } returns tags("seen live" to 1f, "Hip Hop" to 0.8f, "rnb" to 0.4f, "canadian" to 0.4f)
        coEvery { api.getArtistTopTags("Radiohead") } returns tags("alternative" to 1f, "alternative rock" to 0.8f, "british" to 0.7f)

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
            listOf("alternative", "alternative rock"),
            WeightedGenreCodec.decode(writtenFor("Radiohead").genres).map { it.genre },
        )
    }

    @Test fun `no genre tags - writes the untaggable marker`() = runTest {
        missing("Local Rip")
        coEvery { api.getArtistTopTags("Local Rip") } returns tags("seen live" to 1f, "favorites" to 0.5f)

        worker().doWork()

        val row = writtenFor("Local Rip")
        assertEquals(ArtistGenreEntity.STATUS_UNTAGGABLE, row.status)
        assertEquals("[]", row.genres)
    }

    @Test fun `Last_fm not knowing the artist - writes the untaggable marker`() = runTest {
        missing("Nobody Knows")
        coEvery { api.getArtistTopTags("Nobody Knows") } returns notFound

        worker().doWork()

        assertEquals(ArtistGenreEntity.STATUS_UNTAGGABLE, writtenFor("Nobody Knows").status)
    }

    @Test fun `a failure followed by a success is the artist's own - written as FAILED`() = runTest {
        missing("Broken Name", "Drake")
        coEvery { api.getArtistTopTags("Broken Name") } returns offline
        coEvery { api.getArtistTopTags("Drake") } returns tags("hip-hop" to 1f)

        worker().doWork()

        assertEquals(ArtistGenreEntity.STATUS_FAILED, writtenFor("Broken Name").status)
        assertEquals(ArtistGenreEntity.STATUS_TAGGED, writtenFor("Drake").status)
    }

    @Test fun `trailing failures are left unwritten - they may be an outage`() = runTest {
        missing("Drake", "Then Offline")
        coEvery { api.getArtistTopTags("Drake") } returns tags("hip-hop" to 1f)
        coEvery { api.getArtistTopTags("Then Offline") } returns offline

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(listOf("Drake"), written.map { it.artistName })
    }

    @Test fun `an outage ends the run early, writes nothing and asks for a retry`() = runTest {
        val names = (1..20).map { "Artist $it" }
        missing(*names.toTypedArray())
        coEvery { api.getArtistTopTags(any()) } returns offline

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Retry)
        coVerify(exactly = ArtistGenreWorker.MAX_CONSECUTIVE_ERRORS) { api.getArtistTopTags(any()) }
        assertTrue(written.isEmpty())
    }

    @Test fun `a long run writes in batches, not once per artist`() = runTest {
        val names = (1..120).map { "Artist $it" }
        missing(*names.toTypedArray())
        coEvery { api.getArtistTopTags(any()) } returns tags("rock" to 1f)
        val batches = mutableListOf<Int>()
        val rows = slot<List<ArtistGenreEntity>>()
        coEvery { dao.upsertAll(capture(rows)) } answers { batches += rows.captured.size; written += rows.captured }

        worker().doWork()

        assertEquals(listOf(50, 50, 20), batches)
        assertEquals(names, written.map { it.artistName })
    }

    @Test fun `cancellation propagates and what was found is still written`() = runTest {
        missing("Drake", "Cancelled Here", "Never Reached")
        coEvery { api.getArtistTopTags("Drake") } returns tags("hip-hop" to 1f)
        // runCatching inside the client captures a cancellation into the Result.
        coEvery { api.getArtistTopTags("Cancelled Here") } returns Result.failure(CancellationException("stopped"))

        try {
            worker().doWork()
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            // expected
        }

        assertEquals(listOf("Drake"), written.map { it.artistName })
        coVerify(exactly = 0) { api.getArtistTopTags("Never Reached") }
    }

    @Test fun `a failed write doesn't fail the run`() = runTest {
        missing("Drake")
        coEvery { api.getArtistTopTags("Drake") } returns tags("hip-hop" to 1f)
        coEvery { dao.upsertAll(any()) } throws IllegalStateException("disk full")

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
    }
}
