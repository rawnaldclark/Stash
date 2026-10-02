package com.stash.core.data.sync.workers

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.sync.DownloadJobRegistry
import com.stash.core.data.sync.TrackDownloadOutcome
import com.stash.core.data.sync.TrackDownloader
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The drain itself, against a real queue (#532): whatever starts it (a cold start, a
 * sync, a tap), it downloads only rows the user asked for. v0.9.110's drain took
 * every row outside a sync, so a tap or a cold start downloaded months-old leftovers.
 * Proves the worker reads through the filtered query, not just that the query works.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DiscoveryDownloadWorkerAskedForTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase
    private val trackDownloader = mockk<TrackDownloader>()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns TrackDownloadOutcome.Failed("test")
    }

    @After fun tearDown() { db.close() }

    @Test fun `a drain downloads only what the user asked for`() = runBlocking {
        val tapped = song("Tapped")
        val leftover = song("Filed by an old Verify")
        db.downloadQueueDao().insert(DownloadQueueEntity(trackId = tapped, userRequested = true))
        db.downloadQueueDao().insert(DownloadQueueEntity(trackId = leftover))

        buildWorker().doWork()

        coVerify(exactly = 1) { trackDownloader.downloadTrack(match { it.id == tapped }, any()) }
        coVerify(exactly = 0) { trackDownloader.downloadTrack(match { it.id == leftover }, any()) }
        val untouched = db.downloadQueueDao().getByTrackId(leftover)!!
        assertEquals(DownloadStatus.PENDING, untouched.status)
        assertEquals(0, untouched.retryCount)
    }

    private suspend fun song(title: String): Long = db.trackDao().insert(
        TrackEntity(
            title = title, artist = "A", durationMs = 1000L, source = MusicSource.SPOTIFY,
            canonicalTitle = title.lowercase(), canonicalArtist = "a",
        )
    )

    /**
     * Real WorkerParameters from the builder: with relaxed mock ones, setForeground could
     * suspend forever on a mock future whenever WorkManager is initialised in the sandbox.
     */
    private fun buildWorker(): DiscoveryDownloadWorker = TestListenableWorkerBuilder<DiscoveryDownloadWorker>(context)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ) = DiscoveryDownloadWorker(
                appContext, workerParameters,
                downloadQueueDao = db.downloadQueueDao(),
                trackDao = db.trackDao(),
                trackDownloader = trackDownloader,
                audioDurationExtractor = mockk(relaxed = true),
                blocklistGuard = mockk { coEvery { isBlocked(any(), any(), any(), any()) } returns false },
                syncNotificationManager = mockk(relaxed = true),
                downloadJobs = DownloadJobRegistry(),
            )
        })
        .build()
}
