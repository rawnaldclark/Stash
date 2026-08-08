package com.stash.core.data.sync.workers

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.ArtistImageDao
import com.stash.core.data.db.entity.ArtistImageEntity
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.ArtistSummary
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MockK tests for [ArtistImageBackfillWorker] — the worker that resolves the
 * official artist photo for every artist shown on the Library Artists tab.
 *
 * Mirrors [LoudnessBackfillWorkerTest]'s constructor-injection + mocked
 * WorkerParameters pattern. The assertions focus on the two contracts that
 * keep the backfill safe to re-run: candidate collapse via
 * [com.stash.core.common.primaryArtist] and the "no photo" sentinel that
 * stops unresolvable names being re-polled.
 */
class ArtistImageBackfillWorkerTest {

    private val appContext: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val artistImageDao: ArtistImageDao = mockk(relaxed = true)
    private val ytMusicApiClient: YTMusicApiClient = mockk(relaxed = true)

    private fun newWorker() =
        ArtistImageBackfillWorker(appContext, workerParams, artistImageDao, ytMusicApiClient)

    private fun summary(id: String, name: String, avatar: String?) =
        ArtistSummary(id = id, name = name, avatarUrl = avatar)

    @Test fun `empty candidate set returns success without any resolution or write`() = runTest {
        coEvery { artistImageDao.distinctArtistNames() } returns emptyList()

        val result = newWorker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        coVerify(exactly = 0) { ytMusicApiClient.resolveArtist(any()) }
        coVerify(exactly = 0) { artistImageDao.upsert(any()) }
    }

    @Test fun `collapses raw credits to primary acts and stores photos keyed by display name`() =
        runTest {
            coEvery { artistImageDao.distinctArtistNames() } returns
                listOf("Aarne, Toxi$", "Kozak System")
            coEvery { ytMusicApiClient.resolveArtist("Aarne") } returns
                summary("aarne", "Aarne", "https://photo/aarne.jpg")
            coEvery { ytMusicApiClient.resolveArtist("Kozak System") } returns
                summary("kozak", "Kozak System", "https://photo/kozak.jpg")

            val result = newWorker().doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            coVerify(exactly = 1) {
                artistImageDao.upsert(
                    match {
                        it.artistName == "Aarne" &&
                            it.imageUrl == "https://photo/aarne.jpg" &&
                            it.attemptedAt > 0L
                    },
                )
            }
            coVerify(exactly = 1) {
                artistImageDao.upsert(
                    match {
                        it.artistName == "Kozak System" &&
                            it.imageUrl == "https://photo/kozak.jpg" &&
                            it.attemptedAt > 0L
                    },
                )
            }
        }

    @Test fun `unresolvable and throwing names are stamped as sentinels without aborting batch`() =
        runTest {
            coEvery { artistImageDao.distinctArtistNames() } returns
                listOf("Mystery Band", "Aarne", "Null Act")
            coEvery { ytMusicApiClient.resolveArtist("Mystery Band") } returns
                summary("mystery", "Mystery Band", null)
            coEvery { ytMusicApiClient.resolveArtist("Aarne") } returns
                summary("aarne", "Aarne", "https://photo/aarne.jpg")
            coEvery { ytMusicApiClient.resolveArtist("Null Act") } throws RuntimeException("down")

            val result = newWorker().doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            // The failed search must not stop the Aarne photo from being written.
            coVerify(exactly = 1) {
                artistImageDao.upsert(
                    match { it.artistName == "Aarne" && it.imageUrl == "https://photo/aarne.jpg" },
                )
            }
            // Null avatar + stamp = permanent sentinel, so "Mystery Band" is
            // never re-polled on a later run.
            coVerify(exactly = 1) {
                artistImageDao.upsert(
                    match { it.artistName == "Mystery Band" && it.imageUrl == null && it.attemptedAt > 0L },
                )
            }
            coVerify(exactly = 1) {
                artistImageDao.upsert(
                    match { it.artistName == "Null Act" && it.imageUrl == null && it.attemptedAt > 0L },
                )
            }
        }

    @Test fun `skips names already attempted so they are never re-polled`() = runTest {
        coEvery { artistImageDao.observedNames() } returns listOf("Aarne", "Mystery Band")
        coEvery { artistImageDao.distinctArtistNames() } returns
            listOf("Aarne, Toxi$", "Mystery Band", "Kozak System")
        coEvery { ytMusicApiClient.resolveArtist("Kozak System") } returns
            summary("kozak", "Kozak System", "https://photo/kozak.jpg")

        newWorker().doWork()

        coVerify(exactly = 1) { ytMusicApiClient.resolveArtist("Kozak System") }
        coVerify(exactly = 0) { ytMusicApiClient.resolveArtist("Aarne") }
        coVerify(exactly = 0) { ytMusicApiClient.resolveArtist("Mystery Band") }
        coVerify(exactly = 1) { artistImageDao.upsert(any()) }
    }

    @Test fun `caps each run at BATCH_SIZE names leaving the remainder for a later run`() = runTest {
        val manyNames = (1..200).map { "Artist $it" }
        coEvery { artistImageDao.distinctArtistNames() } returns manyNames

        val result = newWorker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        coVerify(exactly = 150) { artistImageDao.upsert(any()) }
        coVerify(exactly = 150) { ytMusicApiClient.resolveArtist(any()) }
        coVerify(exactly = 1) {
            artistImageDao.upsert(
                match { it.artistName == "Artist 1" && it.imageUrl == null },
            )
        }
    }

    @Test fun `primary-artist collapse keeps the written rows aligned with the UI grouping`() =
        runTest {
            // Two different raw credits that both collapse to the same primary
            // act must produce a single deduplicated photo row.
            coEvery { artistImageDao.distinctArtistNames() } returns
                listOf("Aarne, Toxi$", "Aarne, Big Baby Tape")
            coEvery { ytMusicApiClient.resolveArtist("Aarne") } returns
                summary("aarne", "Aarne", "https://photo/aarne.jpg")

            newWorker().doWork()

            coVerify(exactly = 1) { ytMusicApiClient.resolveArtist("Aarne") }
            coVerify(exactly = 1) {
                artistImageDao.upsert(
                    match { it.artistName == "Aarne" && it.imageUrl == "https://photo/aarne.jpg" },
                )
            }
            coVerify(exactly = 1) { artistImageDao.upsert(any()) }
        }
}
