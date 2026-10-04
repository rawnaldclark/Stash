package com.stash.core.data.sync.workers

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.lossless.LosslessUpgrader
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.core.model.FlacUpgradeStatus
import com.stash.core.model.Track
import com.stash.core.model.UpgradeResult
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The batch FLAC-upgrade drain loop (spec 2026-07-22 §3): one upgrade per
 * PENDING row, per-track terminal statuses, vanished-track resilience, and
 * the empty-queue fast path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FlacUpgradeWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase
    private val syncNotificationManager = mockk<SyncNotificationManager>(relaxed = true)

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() { db.close() }

    private suspend fun seedTracks(count: Int): List<Long> = (1..count).map {
        db.trackDao().insert(
            TrackEntity(
                title = "T$it", artist = "x",
                canonicalTitle = "t$it", canonicalArtist = "x",
            ),
        )
    }

    /** Upgrader fake that serves canned results in order; throws when dry. */
    private fun upgraderReturning(
        vararg results: UpgradeResult,
        enabled: Boolean = true,
        upgradeCalls: AtomicInteger? = null,
    ): LosslessUpgrader {
        val queue = ArrayDeque(results.toList())
        return object : LosslessUpgrader {
            override suspend fun upgradeToLossless(track: Track, sweep: Boolean): UpgradeResult {
                upgradeCalls?.incrementAndGet()
                return queue.removeFirst()
            }

            override suspend fun isLosslessEnabled(): Boolean = enabled
        }
    }

    private fun buildWorker(
        upgrader: LosslessUpgrader,
        autoSweep: Boolean = false,
    ): FlacUpgradeWorker =
        TestListenableWorkerBuilder<FlacUpgradeWorker>(context)
            .setInputData(workDataOf(FlacUpgradeWorker.KEY_AUTO_SWEEP to autoSweep))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ) = FlacUpgradeWorker(
                    appContext, workerParameters,
                    queueDao = db.flacUpgradeQueueDao(),
                    trackDao = db.trackDao(),
                    losslessUpgrader = upgrader,
                    syncNotificationManager = syncNotificationManager,
                )
            })
            .build()

    @Test fun `drains pending rows and records per-track outcomes`() = runBlocking {
        val ids = seedTracks(3)
        db.flacUpgradeQueueDao().startBatch(ids)
        val dao = db.flacUpgradeQueueDao()

        val result = buildWorker(
            upgraderReturning(UpgradeResult.Upgraded, UpgradeResult.NoMatch, UpgradeResult.Error),
        ).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(1, dao.countByStatus(FlacUpgradeStatus.DONE))
        assertEquals(1, dao.countByStatus(FlacUpgradeStatus.NO_MATCH))
        assertEquals(1, dao.countByStatus(FlacUpgradeStatus.FAILED))
        assertEquals(0, dao.countByStatus(FlacUpgradeStatus.PENDING))
        verify { syncNotificationManager.showFlacUpgradeSummary(upgraded = 1, noMatch = 1, failed = 1, skipped = 0) }
    }

    @Test fun `a track with no lossless match is remembered, an error is not`() = runBlocking {
        val (miss, error, upgraded) = seedTracks(3)
        db.flacUpgradeQueueDao().startBatch(listOf(miss, error, upgraded))

        buildWorker(
            upgraderReturning(UpgradeResult.NoMatch, UpgradeResult.Error, UpgradeResult.Upgraded),
        ).doWork()

        // The sweep skips a stamped track for two weeks; an error is usually transient.
        assertTrue(db.trackDao().getById(miss)!!.flacNoMatchAt != null)
        assertEquals(null, db.trackDao().getById(error)!!.flacNoMatchAt)
        assertEquals(null, db.trackDao().getById(upgraded)!!.flacNoMatchAt)
    }

    @Test fun `a paced sweep stops, leaves the rest pending, and retries later`() = runBlocking {
        val ids = seedTracks(3)
        db.flacUpgradeQueueDao().startBatch(ids)
        val dao = db.flacUpgradeQueueDao()
        val calls = AtomicInteger()

        val result = buildWorker(
            upgraderReturning(UpgradeResult.Upgraded, UpgradeResult.Paced, UpgradeResult.Upgraded, upgradeCalls = calls),
        ).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(2, calls.get()) // the third row was never tried
        assertEquals(1, dao.countByStatus(FlacUpgradeStatus.DONE))
        assertEquals(2, dao.countByStatus(FlacUpgradeStatus.PENDING))
        assertEquals(0, dao.countByStatus(FlacUpgradeStatus.NO_MATCH))
    }

    @Test fun `rows whose track vanished are skipped, the rest still process`() = runBlocking {
        val ids = seedTracks(2)
        db.flacUpgradeQueueDao().startBatch(ids)
        // CASCADE removes the queue row with the track — the worker's snapshot
        // of pending ids may still contain it; the null-track path must not
        // stall the batch.
        db.trackDao().deleteById(ids[0])

        val result = buildWorker(upgraderReturning(UpgradeResult.Upgraded)).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(1, db.flacUpgradeQueueDao().countByStatus(FlacUpgradeStatus.DONE))
        assertEquals(0, db.flacUpgradeQueueDao().countByStatus(FlacUpgradeStatus.PENDING))
    }

    @Test fun `empty queue succeeds immediately without touching the upgrader`() = runBlocking {
        val untouchable = object : LosslessUpgrader {
            override suspend fun upgradeToLossless(track: Track, sweep: Boolean): UpgradeResult =
                error("upgrader must not be called for an empty queue")

            override suspend fun isLosslessEnabled(): Boolean = true
        }

        val result = buildWorker(untouchable).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
    }

    @Test fun `disabled lossless setting clears a pending AUTO-SWEEP batch without upgrading`() = runBlocking {
        val ids = seedTracks(2)
        val dao = db.flacUpgradeQueueDao()
        dao.startBatch(ids)
        val upgradeCalls = AtomicInteger(0)

        val result = buildWorker(
            upgraderReturning(
                enabled = false,
                upgradeCalls = upgradeCalls,
            ),
            autoSweep = true,
        ).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(0, upgradeCalls.get())
        assertEquals(0, dao.countAll())
        verify(exactly = 0) {
            syncNotificationManager.showFlacUpgradeSummary(any(), any(), any(), any())
        }
    }

    @Test fun `user-enqueued batch still upgrades with the master toggle off`() = runBlocking {
        // The worker has two producers. FlacUpgradeEnqueuer (the Library
        // multi-select "Upgrade to FLAC") enqueues WITHOUT the auto-sweep
        // flag: that path is an explicit user override, same contract as
        // Now Playing's forced single-track upgrade, and must run even
        // when the Settings master toggle is off.
        val ids = seedTracks(1)
        val dao = db.flacUpgradeQueueDao()
        dao.startBatch(ids)

        val result = buildWorker(
            upgraderReturning(UpgradeResult.Upgraded, enabled = false),
        ).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(1, dao.countByStatus(FlacUpgradeStatus.DONE))
        assertEquals(0, dao.countByStatus(FlacUpgradeStatus.PENDING))
        verify { syncNotificationManager.showFlacUpgradeSummary(upgraded = 1, noMatch = 0, failed = 0, skipped = 0) }
    }

    // -- #531 review: a song the user picked is left alone -----------------------

    /** Upgrader fake that records which tracks it was asked to upgrade. */
    private fun recordingUpgrader(asked: MutableList<Long>): LosslessUpgrader = object : LosslessUpgrader {
        override suspend fun upgradeToLossless(track: Track, sweep: Boolean): UpgradeResult {
            asked += track.id
            return UpgradeResult.Upgraded
        }

        override suspend fun isLosslessEnabled(): Boolean = true
    }

    /** What a finished wrong-match swap leaves on the row. */
    private suspend fun pick(trackId: Long) {
        db.trackDao().completeSwap(
            trackId = trackId,
            youtubeId = "picked-$trackId",
            filePath = "/music/x/t$trackId.opus",
            fileSizeBytes = 1L,
            fileFormat = "opus",
            qualityKbps = 160,
            sampleRateHz = 48_000,
            bitsPerSample = null,
            durationMs = null,
            metadataEmbeddedAt = null,
            pickedAt = 1_000L,
            downloadedAt = 1_000L,
        )
    }

    @Test fun `an automatic sweep skips a song the user picked after it was queued`() = runBlocking {
        val (picked, other) = seedTracks(2)
        db.flacUpgradeQueueDao().startBatch(listOf(picked, other))
        // The sweep can run hours after it was queued (relay pacing); the
        // swap finished in between.
        pick(picked)
        val asked = mutableListOf<Long>()

        val result = buildWorker(recordingUpgrader(asked), autoSweep = true).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        // Upgrading it would re-run the lossless lookup that likely chose the
        // wrong recording, and write that FLAC over the user's pick.
        assertEquals(listOf(other), asked)
        assertEquals(0, db.flacUpgradeQueueDao().countByStatus(FlacUpgradeStatus.PENDING))
        // The summary says why one song wasn't upgraded.
        verify { syncNotificationManager.showFlacUpgradeSummary(upgraded = 1, noMatch = 0, failed = 0, skipped = 1) }
    }

    @Test fun `a batch the user chose still upgrades a song they picked`() = runBlocking {
        val (picked) = seedTracks(1)
        db.flacUpgradeQueueDao().startBatch(listOf(picked))
        pick(picked)
        val asked = mutableListOf<Long>()

        buildWorker(recordingUpgrader(asked), autoSweep = false).doWork()

        assertEquals(listOf(picked), asked)
    }
}
