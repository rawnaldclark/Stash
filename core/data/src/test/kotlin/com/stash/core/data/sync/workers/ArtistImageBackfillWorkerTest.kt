package com.stash.core.data.sync.workers

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.ArtistImageDao
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.ArtistPhotoResolution
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MockK tests for [ArtistImageBackfillWorker] — the worker that resolves the
 * official artist photo for every artist shown on the Library Artists tab.
 *
 * Mirrors [LoudnessBackfillWorkerTest]'s constructor-injection + mocked
 * WorkerParameters pattern. The assertions focus on the contracts that keep
 * the backfill safe to re-run: candidate collapse via
 * [com.stash.core.common.primaryArtist], the case-insensitive observed-set
 * guard, and the tri-state [ArtistPhotoResolution] semantics — only a GENUINE
 * no-avatar answer (NoAvatar) stamps the permanent sentinel, while a Failed
 * request writes NOTHING so the name is retried on the next pass.
 */
class ArtistImageBackfillWorkerTest {

    private val appContext: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val artistImageDao: ArtistImageDao = mockk(relaxed = true)
    private val ytMusicApiClient: YTMusicApiClient = mockk(relaxed = true)

    private fun newWorker() =
        ArtistImageBackfillWorker(appContext, workerParams, artistImageDao, ytMusicApiClient)

    @Test fun `empty candidate set returns success without any resolution or write`() = runTest {
        coEvery { artistImageDao.distinctArtistNames() } returns emptyList()

        val result = newWorker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        coVerify(exactly = 0) { ytMusicApiClient.resolveArtistPhoto(any()) }
        coVerify(exactly = 0) { artistImageDao.upsertAll(any()) }
    }

    @Test fun `collapses raw credits to primary acts and stores photos keyed by display name`() =
        runTest {
            coEvery { artistImageDao.distinctArtistNames() } returns
                listOf("Aarne, Toxi$", "Kozak System")
            coEvery { ytMusicApiClient.resolveArtistPhoto("Aarne") } returns
                ArtistPhotoResolution.Resolved("https://photo/aarne.jpg")
            coEvery { ytMusicApiClient.resolveArtistPhoto("Kozak System") } returns
                ArtistPhotoResolution.Resolved("https://photo/kozak.jpg")

            val result = newWorker().doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            // One transactional batched write for the whole pass.
            coVerify(exactly = 1) {
                artistImageDao.upsertAll(
                    match { list ->
                        list.any {
                            it.artistName == "Aarne" &&
                                it.imageUrl == "https://photo/aarne.jpg" &&
                                it.attemptedAt > 0L
                        } && list.any {
                            it.artistName == "Kozak System" &&
                                it.imageUrl == "https://photo/kozak.jpg" &&
                                it.attemptedAt > 0L
                        }
                    },
                )
            }
        }

    @Test fun `NoAvatar stamps a permanent sentinel but Failed writes nothing`() = runTest {
        coEvery { artistImageDao.distinctArtistNames() } returns
            listOf("Mystery Band", "Aarne", "Down Act")
        coEvery { ytMusicApiClient.resolveArtistPhoto("Mystery Band") } returns
            ArtistPhotoResolution.NoAvatar
        coEvery { ytMusicApiClient.resolveArtistPhoto("Aarne") } returns
            ArtistPhotoResolution.Resolved("https://photo/aarne.jpg")
        coEvery { ytMusicApiClient.resolveArtistPhoto("Down Act") } returns
            ArtistPhotoResolution.Failed

        val result = newWorker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        // The failed request must not stop the Aarne photo from being written.
        coVerify(exactly = 1) {
            artistImageDao.upsertAll(
                match { list -> list.any { it.artistName == "Aarne" && it.imageUrl == "https://photo/aarne.jpg" } },
            )
        }
        // NoAvatar + stamp = permanent sentinel, so "Mystery Band" is never
        // re-polled on a later run.
        coVerify(exactly = 1) {
            artistImageDao.upsertAll(
                match { list -> list.any { it.artistName == "Mystery Band" && it.imageUrl == null && it.attemptedAt > 0L } },
            )
        }
        // Failed = the API did not answer. NO sentinel row may be written for
        // "Down Act" — it stays in the candidate set and is retried next pass.
        coVerify(exactly = 0) {
            artistImageDao.upsertAll(
                match { list -> list.any { it.artistName == "Down Act" } },
            )
        }
    }

    @Test fun `a throwing resolution is treated as failed and never aborts the batch`() = runTest {
        coEvery { artistImageDao.distinctArtistNames() } returns
            listOf("Down Act", "Aarne")
        coEvery { ytMusicApiClient.resolveArtistPhoto("Down Act") } throws
            RuntimeException("rate limited")
        coEvery { ytMusicApiClient.resolveArtistPhoto("Aarne") } returns
            ArtistPhotoResolution.Resolved("https://photo/aarne.jpg")

        val result = newWorker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        coVerify(exactly = 1) {
            artistImageDao.upsertAll(
                match { list -> list.any { it.artistName == "Aarne" && it.imageUrl == "https://photo/aarne.jpg" } },
            )
        }
        coVerify(exactly = 0) {
            artistImageDao.upsertAll(
                match { list -> list.any { it.artistName == "Down Act" } },
            )
        }
    }

    @Test fun `skips names already attempted so they are never re-polled`() = runTest {
        coEvery { artistImageDao.observedNames() } returns listOf("Aarne", "Mystery Band")
        coEvery { artistImageDao.distinctArtistNames() } returns
            listOf("Aarne, Toxi$", "Mystery Band", "Kozak System")
        coEvery { ytMusicApiClient.resolveArtistPhoto("Kozak System") } returns
            ArtistPhotoResolution.Resolved("https://photo/kozak.jpg")

        newWorker().doWork()

        coVerify(exactly = 1) { ytMusicApiClient.resolveArtistPhoto("Kozak System") }
        coVerify(exactly = 0) { ytMusicApiClient.resolveArtistPhoto("Aarne") }
        coVerify(exactly = 0) { ytMusicApiClient.resolveArtistPhoto("Mystery Band") }
        coVerify(exactly = 1) { artistImageDao.upsertAll(any()) }
    }

    @Test fun `case variants of an attempted name are also skipped`() = runTest {
        // The PK is COLLATE NOCASE and the observed set is lowercased — a
        // case-variant credit must not trigger a second resolution.
        coEvery { artistImageDao.observedNames() } returns listOf("Aarne")
        coEvery { artistImageDao.distinctArtistNames() } returns listOf("AARNE")

        newWorker().doWork()

        coVerify(exactly = 0) { ytMusicApiClient.resolveArtistPhoto(any()) }
        coVerify(exactly = 0) { artistImageDao.upsertAll(any()) }
    }

    @Test fun `caps each run at BATCH_SIZE names leaving the remainder for a later run`() = runTest {
        val manyNames = (1..200).map { "Artist $it" }
        coEvery { artistImageDao.distinctArtistNames() } returns manyNames
        coEvery { ytMusicApiClient.resolveArtistPhoto(any()) } returns
            ArtistPhotoResolution.NoAvatar

        val result = newWorker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        // Only the first 150 candidates are resolved per pass.
        coVerify(exactly = 150) { ytMusicApiClient.resolveArtistPhoto(any()) }
        coVerify(exactly = 1) { artistImageDao.upsertAll(any()) }
    }

    @Test fun `primary-artist collapse dedupes collab credits into a single row`() = runTest {
        // Two different raw credits that both collapse to the same primary
        // act must produce a single deduplicated photo row.
        coEvery { artistImageDao.distinctArtistNames() } returns
            listOf("Aarne, Toxi$", "Aarne, Big Baby Tape")
        coEvery { ytMusicApiClient.resolveArtistPhoto("Aarne") } returns
            ArtistPhotoResolution.Resolved("https://photo/aarne.jpg")

        newWorker().doWork()

        coVerify(exactly = 1) { ytMusicApiClient.resolveArtistPhoto("Aarne") }
        coVerify(exactly = 1) {
            artistImageDao.upsertAll(
                match { list ->
                    list.size == 1 &&
                        list.first().artistName == "Aarne" &&
                        list.first().imageUrl == "https://photo/aarne.jpg"
                },
            )
        }
    }

    @Test fun `case variant primary artists are deduped to a single search`() = runTest {
        // "Aarne" and "AARNE" both collapse to themselves (single-artist credits);
        // the case-insensitive dedup must keep only one instead of issuing two
        // YT Music searches for the same act.
        coEvery { artistImageDao.distinctArtistNames() } returns
            listOf("Aarne", "AARNE")
        coEvery { ytMusicApiClient.resolveArtistPhoto(any()) } returns
            ArtistPhotoResolution.Resolved("https://photo/aarne.jpg")

        newWorker().doWork()

        coVerify(exactly = 1) { ytMusicApiClient.resolveArtistPhoto(any()) }
    }

    @Test fun `all-transient-failure batch returns Result_retry to engage backoff`() = runTest {
        // Every candidate gets no response (API errors). No row is written,
        // and the worker signals WorkManager to retry with the configured
        // exponential backoff instead of silently succeeding.
        coEvery { artistImageDao.distinctArtistNames() } returns listOf("Down Act", "Mystery Band")
        coEvery { ytMusicApiClient.resolveArtistPhoto(any()) } throws
            RuntimeException("rate limited")

        val result = newWorker().doWork()

        assertTrue(result is ListenableWorker.Result.Retry)
        coVerify(exactly = 0) { artistImageDao.upsertAll(any()) }
    }
}