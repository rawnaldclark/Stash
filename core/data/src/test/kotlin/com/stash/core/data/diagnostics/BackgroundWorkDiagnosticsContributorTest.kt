package com.stash.core.data.diagnostics

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// Test workers. Public, because WorkManager's default factory builds them by
// reflection; it tags each request with the class's fully qualified name.

class FetchTestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
}

class PeriodicTestWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = Result.success()
}

/** Finishes at once with output data that must never reach the bundle. Sorts before "Fetch" by name. */
class DoneTestWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = Result.success(workDataOf("title" to "secret-output-title"))
}

/**
 * The "Background work" section: WorkManager's queue by worker class name and
 * nothing else a request carries, and Android's battery rules in plain words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class BackgroundWorkDiagnosticsContributorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var workManager: WorkManager

    @Before fun setUp() {
        // A fixed clock stamps every enqueue at T0, so due times read the same on every run.
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setClock { T0 }.build())
        workManager = WorkManager.getInstance(context)
    }

    private fun contributor() = BackgroundWorkDiagnosticsContributor(context).also { it.nowMs = { T0 } }

    /** Stays ENQUEUED: the test scheduler meets no constraint or delay unless told to. */
    private fun waitingFetch() = OneTimeWorkRequestBuilder<FetchTestWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresCharging(true).build())
        .setInitialDelay(12, TimeUnit.MINUTES)
        .build()

    @Test fun `lists each worker by class name with its state, constraints, period and due time`() = runTest {
        // Enqueued against name order: within a state the section sorts by name.
        workManager.enqueue(
            PeriodicWorkRequestBuilder<PeriodicTestWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build(),
        ).result.get()
        workManager.enqueue(waitingFetch()).result.get()

        val s = contributor().section()

        val fetch = "  FetchTestWorker · ENQUEUED · needs network UNMETERED, charging · due in 12m"
        val periodic = "  PeriodicTestWorker · ENQUEUED · needs battery not low · periodic every 12h · due now"
        assertThat(s).contains(fetch)
        assertThat(s).contains(periodic)
        assertThat(s.indexOf(fetch)).isLessThan(s.indexOf(periodic))
        assertThat(s).endsWith("  Totals: RUNNING 0 · ENQUEUED 2 · BLOCKED 0 · FAILED 0 · CANCELLED 0 · SUCCEEDED 0")
    }

    @Test fun `prints only the worker's simple class name, never its other tags or its output`() = runTest {
        val request = OneTimeWorkRequestBuilder<DoneTestWorker>().addTag("secret-track-title").build()
        workManager.enqueue(request).result.get()
        // Precondition: it ran, and WorkManager holds both the tag and the output.
        val info = workManager.getWorkInfoById(request.id).get()!!
        assertThat(info.tags).contains("secret-track-title")
        assertThat(info.outputData.getString("title")).isEqualTo("secret-output-title")

        val s = contributor().section()

        assertThat(s).contains("  DoneTestWorker · SUCCEEDED")
        assertThat(s).doesNotContain("secret-track-title")
        assertThat(s).doesNotContain("secret-output-title")
        assertThat(s).doesNotContain("com.stash") // simple name only
    }

    @Test fun `waiting work sorts before finished work, the list stops at 40 and ends with a count per state`() = runTest {
        // Finished first, then 41 waiting: by state it sorts last and the cap cuts it (by name it would lead).
        workManager.enqueue(OneTimeWorkRequestBuilder<DoneTestWorker>().build()).result.get()
        repeat(41) { workManager.enqueue(waitingFetch()).result.get() }

        val s = contributor().section()

        assertThat(Regex("  FetchTestWorker · ENQUEUED").findAll(s).count()).isEqualTo(40)
        assertThat(s).contains("  … 2 more")
        assertThat(s).doesNotContain("DoneTestWorker")
        assertThat(s).endsWith("  Totals: RUNNING 0 · ENQUEUED 41 · BLOCKED 0 · FAILED 0 · CANCELLED 0 · SUCCEEDED 1")
    }

    @Test fun `a retried, stopped item shows its attempts, how overdue it is and why it stopped`() {
        val info = WorkInfo(
            id = UUID.randomUUID(),
            state = WorkInfo.State.ENQUEUED,
            tags = setOf("sync_download", "com.stash.core.data.sync.workers.TrackDownloadWorker"),
            runAttemptCount = 3,
            nextScheduleTimeMillis = T0 - (2 * HOUR + 48 * MINUTE),
            stopReason = WorkInfo.STOP_REASON_QUOTA,
        )

        assertThat(contributor().describe(info, T0))
            .isEqualTo("TrackDownloadWorker · ENQUEUED · attempts 3 · overdue 2h 48m · stopped: QUOTA")
    }

    @Test fun `reads Android's rules for the app in plain words`() = runTest {
        shadowOf(context.getSystemService(PowerManager::class.java)).apply {
            setIgnoringBatteryOptimizations(context.packageName, true)
            setIsPowerSaveMode(true)
            setIsDeviceIdleMode(true)
        }
        shadowOf(context.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(true)
        shadowOf(context.getSystemService(UsageStatsManager::class.java))
            .setCurrentAppStandbyBucket(UsageStatsManager.STANDBY_BUCKET_RARE)
        shadowOf(context.getSystemService(ConnectivityManager::class.java))
            .setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED)
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)

        assertThat(contributor().section()).startsWith(
            """
            Android's rules for Stash:
              Battery optimization:  unrestricted
              Background restricted: yes
              App standby bucket:    rare
              Battery saver:         on
              Dozing now:            yes
              Data Saver:            on (Stash allowed)
              Notifications allowed: no
            """.trimIndent(),
        )
    }

    @Test @Config(sdk = [26])
    fun `rules Android added in 9 read n-a below it`() = runTest {
        val s = contributor().section()

        assertThat(s).contains("  Background restricted: n/a (Android <9)")
        assertThat(s).contains("  App standby bucket:    n/a (Android <9)")
    }

    @Test fun `a failing read costs its line, never the section`() = runTest {
        val noPower = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.POWER_SERVICE) throw SecurityException("denied") else super.getSystemService(name)
        }

        val s = BackgroundWorkDiagnosticsContributor(noPower).section()

        assertThat(s).contains("  Battery optimization:  unavailable (SecurityException)")
        assertThat(s).contains("  Background restricted: no")
        assertThat(s).contains("  Totals: RUNNING 0")
    }

    private companion object {
        const val T0 = 1_758_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
