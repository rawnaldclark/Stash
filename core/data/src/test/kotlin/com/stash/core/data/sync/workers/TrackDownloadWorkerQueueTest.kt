package com.stash.core.data.sync.workers

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.files.LocalFileOps
import com.stash.core.data.files.TrackFileReplacement
import com.stash.core.data.sync.DownloadCancellationController
import com.stash.core.data.sync.DownloadJobRegistry
import com.stash.core.data.sync.DownloadSlots
import com.stash.core.data.sync.TrackDownloadOutcome
import com.stash.core.data.sync.TrackDownloader
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Downloads queue against a running sync (review on #732), with a real database, job registry and worker:
 * - One pool of download slots for every way a song downloads: a sync started while other downloads hold most of the
 *   slots claims only as many songs as are free, and the rest stay Pending. The fake downloader takes a slot the way
 *   DownloadManager does, so a worker that already holds one must not need a second.
 * - Clear queue stops the song downloading and the one waiting, and neither a late finish nor a later run brings them back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackDownloadWorkerQueueTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase
    private val slots = DownloadSlots()
    private val jobs = DownloadJobRegistry()
    private val trackDownloader = mockk<TrackDownloader>()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    @Test fun `a sync while other downloads hold the slots leaves the songs it can't start pending`() = runBlocking {
        val queueIds = (1..4).map { queueSong(it) }
        // Another path (a retry, the discovery drain) holds all but one slot.
        repeat(DownloadSlots.COUNT - 1) { check(slots.permits.tryAcquire()) }
        val gate = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        coEvery { trackDownloader.downloadTrack(any(), any()) } coAnswers {
            slots.withSlot { // DownloadManager's own take: already held, so no second slot
                started.incrementAndGet()
                gate.await()
                TrackDownloadOutcome.Deferred
            }
        }

        val run = async(Dispatchers.Default) { worker().doWork() }
        withTimeout(5_000) { while (started.get() == 0) delay(10) }
        delay(200) // room for a wrongly claimed second row to show

        val statuses = queueIds.map { db.downloadQueueDao().getStatusById(it) }
        assertEquals(1, statuses.count { it == DownloadStatus.IN_PROGRESS })
        assertEquals(3, statuses.count { it == DownloadStatus.PENDING })
        assertEquals(1, started.get())

        // The other downloads finish: every song gets its turn, and the run ends.
        repeat(DownloadSlots.COUNT - 1) { slots.permits.release() }
        gate.complete(Unit)
        withTimeout(5_000) { run.await() }
        assertEquals(4, started.get())
    }

    @Test fun `a slot held by the caller is not taken twice`() = runBlocking {
        repeat(DownloadSlots.COUNT) { check(slots.permits.tryAcquire()) }
        // Every slot is held by its caller; a nested take inside holding runs at once instead of waiting forever.
        val ran = withTimeout(1_000) { slots.holding { slots.withSlot { true } } }
        assertTrue(ran)
        assertEquals(0, slots.permits.availablePermits)
    }

    @Test fun `clear queue stops the running and the waiting download for good`() = runBlocking {
        val running = queueSong(1)
        val waiting = queueSong(2)
        repeat(DownloadSlots.COUNT - 1) { check(slots.permits.tryAcquire()) } // one runs, one waits
        val landed = tmp.newFile("song1.opus").apply { writeBytes(ByteArray(20_000)) }
        val gate = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        coEvery { trackDownloader.downloadTrack(any(), any()) } coAnswers {
            started.incrementAndGet()
            // A download that finishes after the cancel: its file still lands.
            withContext(NonCancellable) { gate.await() }
            TrackDownloadOutcome.Success(landed.absolutePath)
        }

        val run = async(Dispatchers.Default) { worker().doWork() }
        withTimeout(5_000) { while (started.get() == 0) delay(10) }
        assertEquals(DownloadStatus.IN_PROGRESS, db.downloadQueueDao().getStatusById(running))
        assertEquals(DownloadStatus.PENDING, db.downloadQueueDao().getStatusById(waiting))
        assertTrue(jobs.isLive(running))

        val controller = DownloadCancellationController(context, db.downloadQueueDao(), jobs)
        assertEquals(2, controller.cancelAll())

        // The slots free up (the waiting song could claim now) and the cancelled download finishes late.
        repeat(DownloadSlots.COUNT - 1) { slots.permits.release() }
        gate.complete(Unit)
        withTimeout(5_000) { run.await() }

        assertEquals(1, started.get())
        assertEquals(DownloadStatus.SKIPPED, db.downloadQueueDao().getStatusById(running))
        assertEquals(DownloadStatus.SKIPPED, db.downloadQueueDao().getStatusById(waiting))
        assertFalse(jobs.isLive(running))
        val runningTrack = db.downloadQueueDao().getById(running)!!.trackId
        assertFalse(db.trackDao().getById(runningTrack)!!.isDownloaded)

        // A later sync leaves them cancelled.
        worker().doWork()
        assertEquals(1, started.get())
        assertEquals(DownloadStatus.SKIPPED, db.downloadQueueDao().getStatusById(running))
        assertEquals(DownloadStatus.SKIPPED, db.downloadQueueDao().getStatusById(waiting))
    }

    private suspend fun queueSong(n: Int): Long {
        val trackId = db.trackDao().insert(
            TrackEntity(
                title = "Song $n", artist = "Artist", durationMs = 1000L, source = MusicSource.BOTH,
                canonicalTitle = "song $n", canonicalArtist = "artist",
            ),
        )
        return db.downloadQueueDao().insert(DownloadQueueEntity(trackId = trackId, userRequested = true))
    }

    private fun worker(): TrackDownloadWorker =
        TestListenableWorkerBuilder<TrackDownloadWorker>(context)
            .setInputData(workDataOf(DiffWorker.KEY_SYNC_ID to 1L))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ) = TrackDownloadWorker(
                    appContext, workerParameters,
                    downloadQueueDao = db.downloadQueueDao(),
                    trackDao = db.trackDao(),
                    syncHistoryDao = mockk(relaxed = true),
                    syncStateManager = mockk(relaxed = true),
                    syncNotificationManager = mockk(relaxed = true),
                    trackDownloader = trackDownloader,
                    tokenManager = mockk(relaxed = true),
                    audioDurationExtractor = mockk(relaxed = true),
                    blocklistGuard = mockk(relaxed = true),
                    streamingPreference = mockk(relaxed = true),
                    classifier = mockk(relaxed = true),
                    reconciliationUseCase = mockk(relaxed = true),
                    fileExistenceSessionFactory = mockk(relaxed = true),
                    fileAdopter = mockk(relaxed = true),
                    syncLog = mockk(relaxed = true),
                    flacUpgradeSweeper = mockk(relaxed = true),
                    downloadJobs = jobs,
                    fileReplacement = TrackFileReplacement(db.trackDao(), LocalFileOps(appContext)),
                    downloadSlots = slots,
                )
            })
            .build()
}
