package com.stash.core.data.lossless

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.model.WorkSpec
import androidx.work.testing.WorkManagerTestInitHelper
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.AutoFlacUpgradePreference
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.sync.SyncPreferences
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.data.sync.workers.FlacUpgradeWorker
import com.stash.core.model.Track
import com.stash.core.model.UpgradeResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The FLAC upgrade sweep's guards: the post-sync run is opt-in, nothing runs against
 * the Lossless or Stream only settings, a batch still running or waiting is never
 * wiped, recent misses are skipped, and both callers queue a sweep the worker can
 * re-check against the user's settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FlacUpgradeSweeperTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase

    private var autoUpgrade = true
    private var streamOnly = false
    private var losslessOn = true
    private var wifiOnly = true

    private val autoPref = mockk<AutoFlacUpgradePreference> { coEvery { current() } answers { autoUpgrade } }
    private val streaming = mockk<StreamingPreference> { coEvery { current() } answers { streamOnly } }
    private val syncPrefs = mockk<SyncPreferencesManager> {
        every { preferences } answers { flowOf(SyncPreferences(wifiOnly = wifiOnly)) }
    }
    private val upgrader = object : LosslessUpgrader {
        override suspend fun isLosslessEnabled(): Boolean = losslessOn
        override suspend fun upgradeToLossless(track: Track, sweep: Boolean): UpgradeResult =
            error("the sweep only queues; the worker upgrades")
    }

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() {
        db.close()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun sweeper() = FlacUpgradeSweeper(
        context, db.trackDao(), db.flacUpgradeQueueDao(), upgrader, streaming, syncPrefs, autoPref,
    )

    private suspend fun lossy(id: Long, flacNoMatchAt: Long? = null): Long = db.trackDao().insert(
        TrackEntity(
            id = id,
            title = "Track $id",
            artist = "Artist",
            filePath = "/music/artist/track-$id.opus",
            fileFormat = "opus",
            isDownloaded = true,
            canonicalTitle = "track $id",
            canonicalArtist = "artist",
            flacNoMatchAt = flacNoMatchAt,
        ),
    )

    /** The live batch worker's stored request, or null when none is queued or running. */
    private fun liveSweep(): WorkSpec? =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(FlacUpgradeWorker.UNIQUE_WORK_NAME).get()
            .singleOrNull { !it.state.isFinished }
            ?.let { WorkManagerImpl.getInstance(context).workDatabase.workSpecDao().getWorkSpec(it.id.toString()) }

    @Test fun `after a sync with auto-upgrade off, nothing is queued`() = runBlocking {
        autoUpgrade = false
        lossy(1L)

        assertEquals(FlacSweepResult.AutoUpgradeOff, sweeper().afterSync(justDownloaded = emptySet()))
        assertEquals(0, db.flacUpgradeQueueDao().countAll())
        assertNull(liveSweep())
    }

    @Test fun `after a sync with auto-upgrade on, lossy downloads are queued, minus the ones just downloaded`() =
        runBlocking {
            lossy(1L)
            lossy(2L)

            val result = sweeper().afterSync(justDownloaded = setOf(2L))

            assertEquals(FlacSweepResult.Queued(1), result)
            assertEquals(listOf(1L), db.flacUpgradeQueueDao().pendingTrackIds())
            assertTrue(liveSweep()!!.input.getBoolean(FlacUpgradeWorker.KEY_AUTO_SWEEP, false))
        }

    @Test fun `Check for upgrades is a sweep too, so the worker re-checks the Lossless setting`() = runBlocking {
        // The batch can wait hours behind relay pacing; if the user turns Lossless off
        // meanwhile, the worker drops it. It also leaves alone songs picked since (#531).
        autoUpgrade = false // the opt-in only governs the post-sync run
        lossy(1L)

        assertEquals(FlacSweepResult.Queued(1), sweeper().runNow())
        assertTrue(liveSweep()!!.input.getBoolean(FlacUpgradeWorker.KEY_AUTO_SWEEP, false))
    }

    @Test fun `Stream only or Lossless off queues nothing`() = runBlocking {
        lossy(1L)

        streamOnly = true
        assertEquals(FlacSweepResult.StreamingMode, sweeper().runNow())
        streamOnly = false
        losslessOn = false
        assertEquals(FlacSweepResult.LosslessDisabled, sweeper().afterSync(justDownloaded = emptySet()))

        assertEquals(0, db.flacUpgradeQueueDao().countAll())
        assertNull(liveSweep())
    }

    @Test fun `a batch waiting between paced retries is left alone`() = runBlocking {
        val (waiting, other) = listOf(lossy(1L), lossy(2L))
        db.flacUpgradeQueueDao().startBatch(listOf(waiting))
        // A paced worker sits ENQUEUED until its backoff ends.
        WorkManager.getInstance(context).enqueueUniqueWork(
            FlacUpgradeWorker.UNIQUE_WORK_NAME,
            androidx.work.ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<FlacUpgradeWorker>().setInitialDelay(1, TimeUnit.HOURS).build(),
        ).result.get()

        assertEquals(FlacSweepResult.AlreadyRunning, sweeper().runNow())
        assertEquals(FlacSweepResult.AlreadyRunning, sweeper().afterSync(justDownloaded = emptySet()))
        // startBatch() would have wiped the waiting row and queued `other` in its place.
        assertEquals(listOf(waiting), db.flacUpgradeQueueDao().pendingTrackIds())
        assertTrue(other !in db.flacUpgradeQueueDao().pendingTrackIds())
    }

    @Test fun `a track with no lossless match in the last two weeks is skipped, older misses go last`() =
        runBlocking {
            val day = TimeUnit.DAYS.toMillis(1)
            val now = System.currentTimeMillis()
            lossy(1L, flacNoMatchAt = now - 20 * day)
            lossy(2L, flacNoMatchAt = now - day)
            lossy(3L)

            assertEquals(FlacSweepResult.Queued(2), sweeper().runNow())
            assertEquals(listOf(3L, 1L), db.flacUpgradeQueueDao().pendingTrackIds())
        }

    @Test fun `nothing to upgrade when every lossy track missed recently`() = runBlocking {
        lossy(1L, flacNoMatchAt = System.currentTimeMillis())

        assertEquals(FlacSweepResult.NothingToUpgrade, sweeper().runNow())
        assertNull(liveSweep())
    }

    @Test fun `the batch keeps the Sync tab's Wi-Fi only rule`() = runBlocking {
        lossy(1L)
        sweeper().runNow()
        assertEquals(NetworkType.UNMETERED, liveSweep()!!.constraints.requiredNetworkType)

        WorkManager.getInstance(context).cancelUniqueWork(FlacUpgradeWorker.UNIQUE_WORK_NAME).result.get()
        wifiOnly = false
        sweeper().runNow()
        assertEquals(NetworkType.CONNECTED, liveSweep()!!.constraints.requiredNetworkType)
    }
}
