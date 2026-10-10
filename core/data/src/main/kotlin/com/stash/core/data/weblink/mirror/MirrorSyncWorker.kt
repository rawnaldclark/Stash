package com.stash.core.data.weblink.mirror

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * One mirror run in the background (spec §9, §12): after a library change (10 s later, replacing a pending one), when the app
 * comes to the foreground (at most once a minute), and every 6 hours while mirroring is on. Every request needs a network; the
 * periodic one also a battery that isn't low. An offline run is retried with a backoff; the diff waits meanwhile, so nothing
 * is lost. Nothing runs while nothing mirrors.
 */
@HiltWorker
class MirrorSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: MirrorEngine,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        when (val r = engine.sync()) {
            MirrorRun.Done, MirrorRun.NotLinked -> Result.success()
            is MirrorRun.Failed -> Result.success() // a retry soon won't help; the next change or the periodic run tries again
            is MirrorRun.Retry -> if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success().also { Log.i(TAG, "giving up for now: ${r.message}") }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "mirror run crashed: ${e.javaClass.simpleName}")
        if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "WebLinkMirror"
        const val WORK_CHANGE = "stash_mirror_change"
        const val WORK_NOW = "stash_mirror_now"
        const val WORK_PERIODIC = "stash_mirror_periodic"
        const val MAX_RETRIES = 5
        const val CHANGE_DELAY_S = 10L
        const val PERIOD_H = 6L

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun changeRequest(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<MirrorSyncWorker>()
            .setConstraints(network)
            .setInitialDelay(CHANGE_DELAY_S, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        fun nowRequest(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<MirrorSyncWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        fun periodicRequest(): PeriodicWorkRequest = PeriodicWorkRequestBuilder<MirrorSyncWorker>(PERIOD_H, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
            .build()
    }
}

/** Where mirror runs are asked for (WorkManager in the app; tests record the calls). */
interface MirrorScheduler {
    /** The library changed in a kind that mirrors: a run 10 s from now (a pending one is replaced, so a burst is one run). */
    fun changed()

    /** The app came to the foreground. */
    fun now()

    /** Mirroring is on (the 6-hour run), or off (no background work at all). */
    fun periodic(on: Boolean)
}

@Singleton
class WorkManagerMirrorScheduler @Inject constructor(@ApplicationContext private val context: Context) : MirrorScheduler {
    private val wm get() = WorkManager.getInstance(context)
    private var periodicOn: Boolean? = null

    override fun changed() {
        wm.enqueueUniqueWork(MirrorSyncWorker.WORK_CHANGE, ExistingWorkPolicy.REPLACE, MirrorSyncWorker.changeRequest())
    }

    override fun now() {
        wm.enqueueUniqueWork(MirrorSyncWorker.WORK_NOW, ExistingWorkPolicy.KEEP, MirrorSyncWorker.nowRequest())
    }

    override fun periodic(on: Boolean) {
        if (periodicOn == on) return
        periodicOn = on
        if (on) {
            wm.enqueueUniquePeriodicWork(MirrorSyncWorker.WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, MirrorSyncWorker.periodicRequest())
        } else {
            wm.cancelUniqueWork(MirrorSyncWorker.WORK_PERIODIC)
            wm.cancelUniqueWork(MirrorSyncWorker.WORK_CHANGE)
        }
    }
}
