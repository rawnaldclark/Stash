package com.stash.core.data.social

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends what [LikeOutbox] still holds while the app isn't running, and retries what failed: needs a connection, backs off
 * exponentially (30 s, doubling, capped at 5 h by WorkManager) while a service keeps saying no. Done when nothing is left to
 * try on its own (everything sent, or only likes parked for "tap to retry").
 */
@HiltWorker
class LikeMirrorWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: LikeCoordinator,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = resultOf(coordinator.drainAll(MAX_GROUPS_PER_RUN))

    companion object {
        const val UNIQUE_WORK_NAME = "stash_like_mirror_drain"

        /** WorkManager's back-off start; it doubles per retry up to WorkManager's 5 h ceiling. */
        internal const val BACKOFF_SECONDS = 30L

        /**
         * Songs one run sends before it hands over to the next (a retry, on the back-off). With the 1.5 s pacing this keeps a
         * run well inside WorkManager's 10 minutes, and a long offline backlog trickles out instead of arriving as one burst,
         * the most bot-shaped thing a mirror can do.
         */
        internal const val MAX_GROUPS_PER_RUN = 60

        /** Retry while anything is left that the drain would try on its own; done otherwise. */
        internal fun resultOf(report: LikeDrainReport): ListenableWorker.Result =
            if (report.retryable > 0) ListenableWorker.Result.retry() else ListenableWorker.Result.success()

        internal fun request(delayMs: Long): OneTimeWorkRequest = OneTimeWorkRequestBuilder<LikeMirrorWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
    }
}

/** How the coordinator reaches WorkManager and the network: a seam, so its tests decide what is online. */
interface LikeMirrorScheduler {
    /** Queues the worker after [delayMs], needing a connection; one already waiting or running is kept. */
    fun schedule(delayMs: Long = 0L)

    /** Whether a connection that reaches the internet is up now (WorkManager's own `CONNECTED`). */
    fun online(): Boolean

    /** Calls [onBack] each time such a connection comes up, for the app's lifetime. */
    fun watchConnectivity(onBack: () -> Unit)
}

@Singleton
class WorkManagerLikeMirrorScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : LikeMirrorScheduler {

    override fun schedule(delayMs: Long) {
        // KEEP: a drain already waiting on its back-off, or running, stays as it is. A fresh heart doesn't need the worker:
        // the coordinator sends it at once in-process.
        runCatching {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(LikeMirrorWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, LikeMirrorWorker.request(delayMs))
        }.onFailure { Log.w(TAG, "couldn't queue the like drain", it) }
    }

    override fun online(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun watchConnectivity(onBack: () -> Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    // Capabilities change often (signal, metering); only the step to "validated" means "back online"
                    @Volatile private var validated = false

                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        val now = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        if (now && !validated) onBack()
                        validated = now
                    }

                    override fun onLost(network: Network) {
                        validated = false
                    }
                },
            )
        }.onFailure { Log.w(TAG, "couldn't watch connectivity for likes", it) }
    }

    private companion object {
        const val TAG = "LikeMirrorScheduler"
    }
}
