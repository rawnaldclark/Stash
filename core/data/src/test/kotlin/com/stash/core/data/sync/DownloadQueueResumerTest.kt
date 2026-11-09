package com.stash.core.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.DownloadQueueResumer.Companion.resumePolicy
import com.stash.core.data.sync.DownloadQueueResumer.Companion.runStateOf
import com.stash.core.data.sync.DownloadQueueResumer.WaitingRun
import com.stash.core.data.sync.workers.TrackDownloadWorker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [DownloadQueueResumer]: the enqueue policy and the queue state (pure), and what a start does with
 * a real test WorkManager: an automatic start skips a paused queue, a full phone, Online mode and an
 * empty queue; it follows the Sync tab's Wi-Fi only; the user's start clears the pause and runs on
 * any network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DownloadQueueResumerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dao = mockk<DownloadQueueDao>(relaxed = true)
    private val paused = MutableStateFlow(false)
    private val storageFull = MutableStateFlow(false)
    private val queueState = mockk<DownloadQueueState>(relaxed = true).also {
        every { it.paused } returns paused
        coEvery { it.isPaused() } answers { paused.value }
        coEvery { it.setPaused(any()) } answers { paused.value = firstArg() }
        every { it.storageFull } returns storageFull
        every { it.setStorageFull(any()) } answers { storageFull.value = firstArg() }
    }
    private var online = false
    private val streaming = mockk<StreamingPreference>(relaxed = true).also {
        coEvery { it.current() } answers { online }
    }
    private var wifiOnly = true
    private val syncPrefs = mockk<SyncPreferencesManager>(relaxed = true).also {
        every { it.preferences } answers { flowOf(SyncPreferences(wifiOnly = wifiOnly)) }
    }
    private val musicRepository = mockk<MusicRepository>(relaxed = true)
    private val notifications = mockk<SyncNotificationManager>(relaxed = true)
    private val singleTrack = mockk<SingleTrackDownloadEnqueuer>(relaxed = true)

    private lateinit var resumer: DownloadQueueResumer

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        coEvery { dao.hasResumableSyncDownload(any()) } returns true
        resumer = DownloadQueueResumer(
            context, dao, queueState, streaming, syncPrefs, mockk(relaxed = true), musicRepository,
            mockk(relaxed = true), notifications, singleTrack,
        )
    }

    // ── The policy and the state ────────────────────────────────────────

    @Test fun `the user's start takes over when nothing runs, queues behind a run, and never stacks`() {
        assertEquals(ExistingWorkPolicy.REPLACE, resumePolicy(ResumeTrigger.USER, running = false, listOf(NetworkType.UNMETERED), NetworkType.CONNECTED))
        assertEquals(ExistingWorkPolicy.REPLACE, resumePolicy(ResumeTrigger.USER, running = false, emptyList(), NetworkType.CONNECTED))
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, resumePolicy(ResumeTrigger.USER, running = true, emptyList(), NetworkType.CONNECTED))
        assertNull(resumePolicy(ResumeTrigger.USER, running = true, listOf(NetworkType.CONNECTED), NetworkType.CONNECTED))
    }

    @Test fun `an automatic start never cancels anything`() {
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, resumePolicy(ResumeTrigger.AUTO, running = false, emptyList(), NetworkType.UNMETERED))
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, resumePolicy(ResumeTrigger.AUTO, running = true, emptyList(), NetworkType.UNMETERED))
        // A waiting run reads the queue when it starts: leave it.
        assertNull(resumePolicy(ResumeTrigger.AUTO, running = false, listOf(NetworkType.UNMETERED), NetworkType.UNMETERED))
        assertNull(resumePolicy(ResumeTrigger.AUTO, running = true, listOf(NetworkType.UNMETERED), NetworkType.CONNECTED))
        // ...unless it waits for Wi-Fi this start no longer needs, and nothing runs.
        assertEquals(ExistingWorkPolicy.REPLACE, resumePolicy(ResumeTrigger.AUTO, running = false, listOf(NetworkType.UNMETERED), NetworkType.CONNECTED))
    }

    @Test fun `the queue state, by what holds it back first`() {
        val wifiRun = WaitingRun(NetworkType.UNMETERED, Long.MAX_VALUE)
        val anyRun = WaitingRun(NetworkType.CONNECTED, Long.MAX_VALUE)
        fun state(
            paused: Boolean = false,
            full: Boolean = false,
            running: Boolean = false,
            waiting: List<WaitingRun> = emptyList(),
            network: NetworkKind = NetworkKind.UNMETERED,
        ) = runStateOf(paused, full, running, waiting, network, nowMs = 1_000L)

        assertEquals(QueueRunState.StorageFull, state(paused = true, full = true, running = true))
        assertEquals(QueueRunState.Paused, state(paused = true, running = true))
        assertEquals(QueueRunState.Running, state(running = true, waiting = listOf(wifiRun), network = NetworkKind.NONE))
        assertEquals(QueueRunState.Idle, state())
        assertEquals(QueueRunState.WaitingForWifi, state(waiting = listOf(wifiRun), network = NetworkKind.METERED))
        assertEquals(QueueRunState.WaitingForWifi, state(waiting = listOf(wifiRun), network = NetworkKind.NONE))
        assertEquals(QueueRunState.WaitingForConnection, state(waiting = listOf(anyRun, wifiRun), network = NetworkKind.NONE))
        assertEquals(QueueRunState.Scheduled, state(waiting = listOf(anyRun), network = NetworkKind.METERED))
        assertEquals(
            QueueRunState.RetryAt(61_000L),
            state(waiting = listOf(anyRun, WaitingRun(NetworkType.CONNECTED, 61_000L), WaitingRun(NetworkType.CONNECTED, 500L))),
        )
    }

    // ── Starts ──────────────────────────────────────────────────────────

    @Test fun `an automatic start follows Wi-Fi only`() = runBlocking {
        wifiOnly = true
        resumer.resume(ResumeTrigger.AUTO)

        val run = resumeRuns().single()
        assertEquals(WorkInfo.State.ENQUEUED, run.state)
        assertEquals(NetworkType.UNMETERED, run.constraints.requiredNetworkType)
        assertTrue(run.constraints.requiresBatteryNotLow())
        coVerify(exactly = 1) { musicRepository.resumeWaitingDownloads(false) }
    }

    @Test fun `an automatic start without Wi-Fi only takes any connection`() = runBlocking {
        wifiOnly = false
        resumer.resume(ResumeTrigger.AUTO)

        assertEquals(NetworkType.CONNECTED, resumeRuns().single().constraints.requiredNetworkType)
    }

    @Test fun `an automatic start does nothing while paused or out of space`() = runBlocking {
        paused.value = true
        resumer.resume(ResumeTrigger.AUTO)
        paused.value = false
        storageFull.value = true
        resumer.resume(ResumeTrigger.AUTO)

        assertTrue(resumeRuns().isEmpty())
        coVerify(exactly = 0) { musicRepository.resumeWaitingDownloads(any()) }
    }

    @Test fun `Online mode never starts the resume run but still drains what the user asked for`() = runBlocking {
        online = true
        resumer.resume(ResumeTrigger.AUTO)

        assertTrue(resumeRuns().isEmpty())
        coVerify(exactly = 0) { dao.hasResumableSyncDownload(any()) }
        coVerify(exactly = 1) { musicRepository.resumeWaitingDownloads(false) }
    }

    @Test fun `nothing waiting starts no resume run`() = runBlocking {
        coEvery { dao.hasResumableSyncDownload(any()) } returns false
        resumer.resume(ResumeTrigger.AUTO)

        assertTrue(resumeRuns().isEmpty())
    }

    @Test fun `a delayed start leaves the drain alone`() = runBlocking {
        resumer.resume(ResumeTrigger.AUTO, DownloadQueueResumer.AFTER_SYNC_DELAY)

        val run = resumeRuns().single()
        // What a sync's end asks for: the run starts a couple of minutes later, under the background rules.
        assertEquals(DownloadQueueResumer.AFTER_SYNC_DELAY.inWholeMilliseconds, run.initialDelayMillis)
        assertEquals(NetworkType.UNMETERED, run.constraints.requiredNetworkType)
        coVerify(exactly = 0) { musicRepository.resumeWaitingDownloads(any()) }
    }

    @Test fun `a rate-limited start carries its round to the run`() = runBlocking {
        resumer.resume(ResumeTrigger.AUTO, DownloadQueueResumer.RATE_LIMIT_DELAY, rateLimitRound = 3)

        val run = resumeRuns().single()
        assertEquals(DownloadQueueResumer.RATE_LIMIT_DELAY.inWholeMilliseconds, run.initialDelayMillis)
        @Suppress("RestrictedApi")
        val input = androidx.work.impl.WorkManagerImpl.getInstance(context).workDatabase.workSpecDao()
            .getWorkSpec(run.id.toString())!!.input
        assertEquals(3, input.getInt(TrackDownloadWorker.KEY_RATE_LIMIT_ROUND, -1))
        assertTrue(input.getBoolean(TrackDownloadWorker.KEY_RESUME, false))
    }

    @Test fun `a second automatic start leaves the waiting run alone`() = runBlocking {
        resumer.resume(ResumeTrigger.AUTO)
        val first = resumeRuns().single().id
        resumer.resume(ResumeTrigger.AUTO)

        assertEquals(listOf(first), resumeRuns().filter { !it.state.isFinished }.map { it.id })
    }

    @Test fun `the user's start clears the pause and the full phone and runs on any network`() = runBlocking {
        paused.value = true
        storageFull.value = true
        resumer.resume(ResumeTrigger.AUTO) // held: starts nothing
        resumer.resume(ResumeTrigger.USER)

        assertEquals(false, paused.value)
        assertEquals(false, storageFull.value)
        verify(exactly = 1) { notifications.cancelDownloadsPaused() }
        val live = resumeRuns().filter { !it.state.isFinished }
        assertEquals(NetworkType.CONNECTED, live.single().constraints.requiredNetworkType)
        // No battery rule either: Resume on a low battery waited while the screen said it was starting.
        assertFalse(live.single().constraints.requiresBatteryNotLow())
        coVerify(exactly = 1) { musicRepository.resumeWaitingDownloads(true) }
    }

    @Test fun `the user's start replaces a run waiting for Wi-Fi`() = runBlocking {
        resumer.resume(ResumeTrigger.AUTO)
        assertEquals(NetworkType.UNMETERED, resumeRuns().single().constraints.requiredNetworkType)

        resumer.resume(ResumeTrigger.USER)

        val live = resumeRuns().filter { !it.state.isFinished }
        assertEquals(NetworkType.CONNECTED, live.single().constraints.requiredNetworkType)
    }

    @Test fun `the app coming back resumes at most once a minute`() = runBlocking {
        resumer.onForeground(nowMs = 100_000L)
        resumer.onForeground(nowMs = 130_000L)
        resumer.onForeground(nowMs = 161_000L)

        coVerify(exactly = 2) { musicRepository.resumeWaitingDownloads(false) }
    }

    @Test fun `pause keeps the queue stopped and cancels the runs`() = runBlocking {
        resumer.resume(ResumeTrigger.AUTO)
        assertTrue("a run to cancel", resumeRuns().isNotEmpty())
        val single = singleRetryWaiting()
        resumer.pause()

        assertEquals(true, paused.value)
        assertTrue(resumeRuns().all { it.state == WorkInfo.State.CANCELLED })
        // A single retry waiting on its backoff or a rate-limit round goes too.
        assertEquals(WorkInfo.State.CANCELLED, WorkManager.getInstance(context).getWorkInfoById(single).get()!!.state)
        verify(exactly = 1) { notifications.cancelDownloadProgress() }
    }

    @Test fun `retry all hands rows neither run takes to their own retry`() = runBlocking {
        coEvery { dao.getAllPendingBySources(any()) } returns listOf(row(1L))
        coEvery { dao.pendingDiscoveryDownloads() } returns listOf(row(2L))

        resumer.retryClaimed(listOf(1L, 2L, 3L))

        coVerify(exactly = 1) { singleTrack.enqueue(3L) }
        coVerify(exactly = 0) { singleTrack.enqueue(1L) }
        coVerify(exactly = 0) { singleTrack.enqueue(2L) }
        assertEquals(false, paused.value)
    }

    @Test fun `retry all clears the pause but downloads under the automatic rules`() = runBlocking {
        paused.value = true
        wifiOnly = true

        resumer.retryClaimed(listOf(1L))

        assertEquals(false, paused.value)
        val run = resumeRuns().single()
        // The run takes the whole queue, not just the retried songs: not over mobile data the user turned off.
        assertEquals(NetworkType.UNMETERED, run.constraints.requiredNetworkType)
        assertTrue(run.constraints.requiresBatteryNotLow())
        coVerify(exactly = 1) { musicRepository.resumeWaitingDownloads(false) }
    }

    @Test fun `retry all in Online mode puts a sync's rows back in the queue, nothing more`() = runBlocking {
        online = true
        coEvery { dao.getAllPendingBySources(any()) } returns listOf(row(1L))

        resumer.retryClaimed(listOf(1L, 3L))

        // Row 1 downloads when the user switches to Offline; row 3 no run takes, so it gets its own retry.
        assertTrue(resumeRuns().isEmpty())
        coVerify(exactly = 0) { singleTrack.enqueue(1L) }
        coVerify(exactly = 1) { singleTrack.enqueue(3L) }
    }

    /** A single retry waiting to run (its backoff, a rate-limit round), tagged as the enqueuer tags them. */
    private fun singleRetryWaiting(): java.util.UUID {
        val request = androidx.work.OneTimeWorkRequestBuilder<TrackDownloadWorker>()
            .addTag(SingleTrackDownloadEnqueuer.TAG)
            .setInitialDelay(java.time.Duration.ofMinutes(15))
            .build()
        WorkManager.getInstance(context).enqueue(request).result.get()
        return request.id
    }

    private fun row(id: Long) = com.stash.core.data.db.entity.DownloadQueueEntity(id = id, trackId = id, searchQuery = "q")

    private fun resumeRuns(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(TrackDownloadWorker.RESUME_WORK_NAME).get()
}
