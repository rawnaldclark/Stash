package com.stash.core.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.stash.core.data.sync.workers.DailySyncTriggerWorker
import com.stash.core.data.sync.workers.PlaylistFetchWorker
import com.stash.core.model.SyncTrigger
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Auto-sync's daily trigger, "Sync now" while Auto-sync is on, and what Stop
 * and turning Auto-sync off cancel.
 *
 * Runs WorkManager's real scheduling through its test driver. The sync chain's
 * steps are stand-ins that finish once [stepGate] completes, so a test can hold
 * a step RUNNING. A step whose constraints the driver hasn't met stays waiting,
 * as it would for its network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DailySyncTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = MutableStateFlow(SyncPreferences(autoSyncEnabled = true))
    private val prefsManager = mockk<SyncPreferencesManager> { every { preferences } returns prefs }
    private lateinit var scheduler: SyncScheduler
    private lateinit var workManager: WorkManager

    /** Chain steps finish when this completes; already complete = at once. */
    private var stepGate = CompletableDeferred(Unit)

    /** The input each chain step last started with, by worker class name. */
    private val stepInputs = mutableMapOf<String, Data>()

    @Before fun setUp() {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker =
                if (workerClassName == DailySyncTriggerWorker::class.java.name) {
                    DailySyncTriggerWorker(appContext, workerParameters, prefsManager, scheduler)
                } else {
                    stepInputs[workerClassName] = workerParameters.inputData
                    object : CoroutineWorker(appContext, workerParameters) {
                        override suspend fun doWork(): Result {
                            stepGate.await()
                            return Result.success()
                        }
                    }
                }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .setWorkerFactory(factory)
                .build(),
        )
        workManager = WorkManager.getInstance(context)
        scheduler = SyncScheduler(context, SyncStateManager())
    }

    @After fun tearDown() {
        stepGate.complete(Unit) // lets a held step finish
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    // -- "Sync now" ---------------------------------------------------------------

    /** The reported bug: with Auto-sync on, every tap was ignored until the scheduled time. */
    @Test fun `Sync now starts a sync while Auto-sync is on`() {
        scheduler.scheduleDailySync(6, 0)

        scheduler.triggerManualSync()

        val fetch = step()
        assertEquals(0L, fetch.initialDelayMillis)
        driver.setAllConstraintsMet(fetch.id)
        assertEquals(WorkInfo.State.SUCCEEDED, step().state)
    }

    @Test fun `Sync now replaces a scheduled sync that is waiting to start`() {
        fireTrigger() // the scheduled chain, waiting for Wi-Fi
        val scheduled = chainIds()
        assertEquals(NetworkType.UNMETERED, step().constraints.requiredNetworkType)

        scheduler.triggerManualSync()

        assertTrue(chainIds().none { it in scheduled })
        assertEquals(NetworkType.CONNECTED, step().constraints.requiredNetworkType)
    }

    @Test fun `Sync now is ignored while a sync is running`() {
        holdSyncRunning()
        val running = chainIds()

        scheduler.triggerManualSync()

        assertEquals(running, chainIds())
        assertEquals(WorkInfo.State.RUNNING, step().state)
    }

    /** Fetched, then Wi-Fi dropped: the Sync tab shows no sync, and a tap must not look dead. */
    @Test fun `Sync now replaces a sync paused between steps`() {
        fireTrigger()
        driver.setAllConstraintsMet(step().id) // fetch runs and finishes
        assertEquals(WorkInfo.State.ENQUEUED, step("sync_diff").state) // waits for Wi-Fi
        val paused = chainIds()

        scheduler.triggerManualSync()

        assertTrue(chainIds().none { it in paused })
        assertEquals(NetworkType.CONNECTED, step().constraints.requiredNetworkType)
    }

    /** An upgrade from the one-time schedule: its chain waits, hours away. */
    @Test fun `the old schedule's delayed chain neither blocks Sync now nor gets doubled`() {
        val legacy = enqueueLegacyChain()

        fireTrigger()
        assertEquals(setOf(legacy), chainIds()) // the trigger left it alone

        scheduler.triggerManualSync()
        assertEquals(0L, step().initialDelayMillis) // Sync now replaced it
    }

    // -- The schedule ------------------------------------------------------------------

    @Test fun `scheduling enqueues only the daily trigger, first run at the chosen time`() {
        scheduler.scheduleDailySync(6, 0)

        val trigger = trigger()!!
        assertEquals(TimeUnit.HOURS.toMillis(24), trigger.periodicityInfo!!.repeatIntervalMillis)
        assertAbout(scheduler.computeDelayToNextSync(6, 0)!!, trigger.initialDelayMillis)
        assertTrue(chain().isEmpty())

        scheduler.scheduleDailySync(18, 30) // a changed time takes effect
        assertAbout(scheduler.computeDelayToNextSync(18, 30)!!, trigger()!!.initialDelayMillis)
    }

    @Test fun `no sync days cancels the daily trigger`() {
        scheduler.scheduleDailySync(6, 0)
        assertNotNull(trigger())

        scheduler.scheduleDailySync(6, 0, days = DayOfWeekSet.NONE)

        assertNull(trigger())
    }

    // -- The trigger ---------------------------------------------------------------------

    @Test fun `the trigger starts the scheduled sync, on Wi-Fi only when set`() {
        fireTrigger()
        val fetch = step()
        assertEquals(0L, fetch.initialDelayMillis)
        assertEquals(NetworkType.UNMETERED, fetch.constraints.requiredNetworkType)
        assertTrue(fetch.constraints.requiresBatteryNotLow())

        scheduler.cancelSync()
        prefs.value = prefs.value.copy(wifiOnly = false)
        fireTrigger()

        assertEquals(NetworkType.CONNECTED, step().constraints.requiredNetworkType)
    }

    @Test fun `the trigger does nothing when Auto-sync is off`() {
        prefs.value = SyncPreferences(autoSyncEnabled = false)

        fireTrigger()

        assertTrue(chain().isEmpty())
    }

    @Test fun `the trigger does nothing when today isn't a sync day`() {
        // Only the day after tomorrow: neither today nor the day an early-morning run counts for.
        val notToday = DayOfWeekSet.NONE.with(LocalDate.now().dayOfWeek.plus(2), true)
        prefs.value = SyncPreferences(autoSyncEnabled = true, syncDays = notToday.bitmask)

        fireTrigger()

        assertTrue(chain().isEmpty())
    }

    @Test fun `a run Doze held past midnight counts for the day it was due`() {
        // 2026-09-27 is a Sunday.
        assertEquals(DayOfWeek.SUNDAY, DailySyncTriggerWorker.dueDay(23, 30, clockAt(2026, 9, 28, 0, 10)))
        assertEquals(DayOfWeek.SUNDAY, DailySyncTriggerWorker.dueDay(23, 30, clockAt(2026, 9, 27, 23, 30)))
        assertEquals(DayOfWeek.MONDAY, DailySyncTriggerWorker.dueDay(6, 0, clockAt(2026, 9, 28, 9, 0)))
    }

    @Test fun `the trigger replaces its own waiting sync with the current Wi-Fi rule`() {
        fireTrigger() // waits for Wi-Fi
        val waiting = chainIds()
        prefs.value = prefs.value.copy(wifiOnly = false)

        fireTrigger() // the next day's run

        assertTrue(chainIds().none { it in waiting })
        assertEquals(NetworkType.CONNECTED, step().constraints.requiredNetworkType)
    }

    @Test fun `turning Wi-Fi-only off re-queues a waiting scheduled sync now`() = runBlocking {
        fireTrigger() // waits for Wi-Fi

        scheduler.applyWifiOnlyToWaitingSync(wifiOnly = false)

        assertEquals(NetworkType.CONNECTED, step().constraints.requiredNetworkType)
    }

    @Test fun `a queued Sync now is never turned into a Wi-Fi-only sync`() = runBlocking {
        scheduler.triggerManualSync() // waits for a network
        val queued = chainIds()

        fireTrigger()
        scheduler.applyWifiOnlyToWaitingSync(wifiOnly = true)

        assertEquals(queued, chainIds())
        assertEquals(NetworkType.CONNECTED, step().constraints.requiredNetworkType)
    }

    @Test fun `the trigger leaves a running sync alone`() {
        holdScheduledSyncRunning()
        val running = chainIds()

        fireTrigger()

        assertEquals(running, chainIds())
        assertEquals(WorkInfo.State.RUNNING, step().state)
    }

    /** Without this a periodic job runs 24 h after the last run ended, drifting later. */
    @Test fun `each trigger run pins the next run to the chosen time`() {
        val at = LocalDateTime.now().plusHours(3) // far from the plain 24 h period
        prefs.value = SyncPreferences(autoSyncEnabled = true, syncHour = at.hour, syncMinute = at.minute)

        fireTrigger(at.hour, at.minute)

        val expected = System.currentTimeMillis() + scheduler.computeDelayToNextSync(at.hour, at.minute)!!
        assertAbout(expected, trigger()!!.nextScheduleTimeMillis)
    }

    /**
     * The trigger stays alive (a periodic worker that throws is FAILED for good), and
     * retries soon: a pin set before queuing would push the retry to the next slot.
     */
    @Test fun `a run that fails to queue the day's sync retries soon, not at the next slot`() {
        val at = LocalDateTime.now().plusHours(3)
        prefs.value = SyncPreferences(autoSyncEnabled = true, syncHour = at.hour, syncMinute = at.minute)
        scheduler = spyk(scheduler)
        coEvery { scheduler.startScheduledSync(any()) } throws IllegalStateException("database busy")

        fireTrigger(at.hour, at.minute)

        val inMs = trigger()!!.nextScheduleTimeMillis - System.currentTimeMillis()
        assertTrue("retry within the hour, got in $inMs ms", inMs < TimeUnit.HOURS.toMillis(1))
    }

    /** The fetch step records it in the sync history ([PlaylistFetchWorker.KEY_TRIGGER]). */
    @Test fun `the fetch step is told whether the schedule or the user started the sync`() {
        fireTrigger()
        driver.setAllConstraintsMet(step().id)
        assertEquals(SyncTrigger.SCHEDULED.name, fetchTrigger())

        scheduler.cancelSync()
        scheduler.triggerManualSync()
        driver.setAllConstraintsMet(step().id)
        assertEquals(SyncTrigger.MANUAL.name, fetchTrigger())
    }

    // -- Stop and Auto-sync off ------------------------------------------------------------

    @Test fun `Stop cancels the sync but keeps the daily schedule`() {
        scheduler.scheduleDailySync(6, 0)
        holdSyncRunning()

        scheduler.cancelSync()

        assertEquals(WorkInfo.State.CANCELLED, step().state)
        assertNotNull(trigger())
    }

    @Test fun `turning Auto-sync off cancels the trigger and a scheduled sync not yet started`() = runBlocking {
        fireTrigger() // the scheduled chain, waiting for Wi-Fi

        scheduler.cancelDailySync()

        assertNull(trigger())
        assertEquals(WorkInfo.State.CANCELLED, step().state)
    }

    @Test fun `turning Auto-sync off cancels the old schedule's delayed chain`() = runBlocking {
        enqueueLegacyChain()

        scheduler.cancelDailySync()

        assertEquals(WorkInfo.State.CANCELLED, step().state)
    }

    @Test fun `turning Auto-sync off leaves a queued Sync now alone`() = runBlocking {
        scheduler.triggerManualSync() // waits for a network

        scheduler.cancelDailySync()

        assertEquals(WorkInfo.State.ENQUEUED, step().state)
    }

    @Test fun `turning Auto-sync off lets a scheduled sync that has started finish`() = runBlocking {
        holdScheduledSyncRunning()
        scheduler.cancelDailySync()
        assertEquals(WorkInfo.State.RUNNING, step().state)

        stepGate.complete(Unit) // fetch finishes; diff waits for Wi-Fi
        assertEquals(WorkInfo.State.ENQUEUED, step("sync_diff").state)
        scheduler.cancelDailySync()
        assertEquals(WorkInfo.State.ENQUEUED, step("sync_diff").state)
    }

    // -- App start --------------------------------------------------------------------------

    @Test fun `the startup check clears a leftover trigger while Auto-sync is off`() = runBlocking {
        scheduler.scheduleDailySync(6, 0)

        scheduler.ensureDailySync(SyncPreferences(autoSyncEnabled = false))

        assertNull(trigger())
    }

    @Test fun `the startup check adds the trigger when Auto-sync is on, and keeps an existing one's time`() = runBlocking {
        scheduler.ensureDailySync(SyncPreferences(autoSyncEnabled = true, syncHour = 6, syncMinute = 0))
        val first = trigger()!!
        assertAbout(scheduler.computeDelayToNextSync(6, 0)!!, first.initialDelayMillis)

        scheduler.ensureDailySync(SyncPreferences(autoSyncEnabled = true, syncHour = 18, syncMinute = 30))

        assertEquals(first.id, trigger()!!.id)
        assertEquals(first.initialDelayMillis, trigger()!!.initialDelayMillis)
    }

    // -- Helpers ------------------------------------------------------------------------------

    private val driver get() = WorkManagerTestInitHelper.getTestDriver(context)!!

    private fun chain(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_WORK_NAME).get()

    private fun chainIds() = chain().map { it.id }.toSet()

    /** The chain step tagged [tag]. */
    private fun step(tag: String = "sync_fetch"): WorkInfo = chain().single { tag in it.tags }

    private fun fetchTrigger(): String? =
        stepInputs[PlaylistFetchWorker::class.java.name]?.getString(PlaylistFetchWorker.KEY_TRIGGER)

    /** The live daily trigger, or null when there's none. */
    private fun trigger(): WorkInfo? =
        workManager.getWorkInfosForUniqueWork(SyncScheduler.TRIGGER_WORK_NAME).get()
            .singleOrNull { !it.state.isFinished }

    /** Schedules Auto-sync at [hour]:[minute] and runs its trigger now, as WorkManager would then. */
    private fun fireTrigger(hour: Int = 6, minute: Int = 0) {
        scheduler.scheduleDailySync(hour, minute)
        driver.setInitialDelayMet(trigger()!!.id)
    }

    /** A manual sync held with its fetch step RUNNING. */
    private fun holdSyncRunning() {
        stepGate = CompletableDeferred()
        scheduler.triggerManualSync()
        driver.setAllConstraintsMet(step().id)
        assertEquals(WorkInfo.State.RUNNING, step().state)
    }

    /** The trigger's sync held with its fetch step RUNNING. */
    private fun holdScheduledSyncRunning() {
        fireTrigger()
        stepGate = CompletableDeferred()
        driver.setAllConstraintsMet(step().id)
        assertEquals(WorkInfo.State.RUNNING, step().state)
    }

    /** What the one-time schedule left queued for an upgrading user: the sync itself, hours away. */
    private fun enqueueLegacyChain(): java.util.UUID {
        val legacy = OneTimeWorkRequestBuilder<PlaylistFetchWorker>()
            .setInitialDelay(18, TimeUnit.HOURS)
            .addTag("sync_fetch")
            .build()
        workManager.enqueueUniqueWork(SyncScheduler.UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, legacy)
        return legacy.id
    }

    private fun clockAt(year: Int, month: Int, day: Int, hour: Int, minute: Int): Clock {
        val zone = ZoneId.of("UTC")
        return Clock.fixed(LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant(), zone)
    }

    /** Delays computed a moment apart differ by the milliseconds between them. */
    private fun assertAbout(expectedMs: Long, actualMs: Long) =
        assertTrue("expected about $expectedMs, got $actualMs", abs(expectedMs - actualMs) < 5_000)
}
