package com.stash.core.data.sync.workers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.prefs.DataSaverSnapshot
import com.stash.core.data.prefs.DownloadNetworkPreference
import com.stash.core.data.prefs.StashMixPreference
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * WorkManager keeps the constraints a one-shot was queued with, so the Data saver switch flipping
 * must queue a waiting one again ([DataSaverBackgroundWork.onSwitch]): a pass held for Wi-Fi goes
 * on mobile data once Data saver is off. A mix the user refreshed by hand gets its art on any
 * connection. One preference that won't read doesn't stop the rest. The test WorkManager never
 * meets a network constraint, so queued work stays ENQUEUED and its constraint can be read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DataSaverRequeueTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var workManager: WorkManager

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    @After fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun backgroundWork(mixesOn: Boolean = false) = DataSaverBackgroundWork(
        context,
        stashMixPreference = mockk<StashMixPreference> { coEvery { current() } returns mixesOn },
        downloadNetworkPreference = mockk<DownloadNetworkPreference>(relaxed = true),
    )

    /** The unfinished work under [name]. */
    private fun pending(name: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }

    @Test fun `turning Data saver off lets the art backfill held for Wi-Fi run on mobile data`() = runBlocking<Unit> {
        DataSaverSnapshot.write(context, true)
        ArtBackfillWorker.enqueueOneTime(context)
        assertThat(pending(ArtBackfillWorker.WORK_NAME).single().constraints.requiredNetworkType)
            .isEqualTo(NetworkType.UNMETERED)

        backgroundWork().onSwitch(false)

        val now = pending(ArtBackfillWorker.WORK_NAME).single()
        assertThat(now.state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(now.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
    }

    @Test fun `turning Data saver on holds a waiting art backfill for Wi-Fi`() = runBlocking<Unit> {
        DataSaverSnapshot.write(context, false)
        ArtBackfillWorker.enqueueOneTime(context)

        backgroundWork().onSwitch(true)

        assertThat(pending(ArtBackfillWorker.WORK_NAME).single().constraints.requiredNetworkType)
            .isEqualTo(NetworkType.UNMETERED)
    }

    @Test fun `the first-launch track info pass is queued again when Data saver goes off`() = runBlocking<Unit> {
        DataSaverSnapshot.write(context, true)
        TrackInfoEnrichmentWorker.enqueueFirstRun(context)

        backgroundWork(mixesOn = true).onSwitch(false)

        // With no constraint left the test WorkManager starts it at once (and the plain factory
        // can't build a Hilt worker), so read the one that replaced the held pass, whatever its state.
        val infos = workManager.getWorkInfosForUniqueWork("stash_track_info_enrichment_oneshot").get()
        assertThat(infos.single { it.state != WorkInfo.State.CANCELLED }.constraints.requiredNetworkType)
            .isEqualTo(NetworkType.NOT_REQUIRED)
    }

    @Test fun `nothing waiting means nothing is queued`() = runBlocking<Unit> {
        DataSaverSnapshot.write(context, true)

        backgroundWork().onSwitch(false)

        assertThat(pending(ArtBackfillWorker.WORK_NAME)).isEmpty()
    }

    /** "Refresh this mix" under Data saver: its art isn't left queued behind a run waiting for Wi-Fi. */
    @Test fun `a mix refreshed by hand gets its art on any connection`() = runBlocking<Unit> {
        DataSaverSnapshot.write(context, true)
        ArtBackfillWorker.enqueueOneTime(context) // the background one, held for Wi-Fi

        ArtBackfillWorker.enqueueFromMixRefresh(context, userAsked = true)

        val now = pending(ArtBackfillWorker.WORK_NAME)
        assertThat(now).hasSize(1)
        assertThat(now.single().constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
    }

    @Test fun `the daily refresh's art still waits for Wi-Fi under Data saver`() = runBlocking<Unit> {
        DataSaverSnapshot.write(context, true)

        ArtBackfillWorker.enqueueFromMixRefresh(context, userAsked = false)

        assertThat(pending(ArtBackfillWorker.WORK_NAME).single().constraints.requiredNetworkType)
            .isEqualTo(NetworkType.UNMETERED)
    }

    /** One DataStore error must not end the app-lifetime collector, nor skip the steps that don't need it. */
    @Test fun `a Stash Mixes switch that won't read doesn't throw or skip the art backfill`() = runBlocking<Unit> {
        DataSaverSnapshot.write(context, true)
        ArtBackfillWorker.enqueueOneTime(context)
        val work = DataSaverBackgroundWork(
            context,
            stashMixPreference = mockk<StashMixPreference> { coEvery { current() } throws IOException("datastore") },
            downloadNetworkPreference = mockk<DownloadNetworkPreference>(relaxed = true),
        )

        work.onSwitch(false)

        assertThat(DataSaverSnapshot.isOn(context)).isFalse()
        assertThat(pending(ArtBackfillWorker.WORK_NAME).single().constraints.requiredNetworkType)
            .isEqualTo(NetworkType.CONNECTED)
    }
}
