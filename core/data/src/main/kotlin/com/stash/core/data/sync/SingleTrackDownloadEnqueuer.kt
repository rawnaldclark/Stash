package com.stash.core.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.stash.core.data.prefs.DownloadNetworkPreference
import com.stash.core.data.sync.workers.TrackDownloadWorker
import com.stash.core.data.sync.workers.constraintsForManualTrigger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * Enqueues a one-shot [TrackDownloadWorker] run for a single
 * `download_queue` row. Used by the Failed Downloads viewer to
 * immediately retry a row the user tapped, without waiting for the
 * next scheduled sync.
 *
 * Uses a unique work name keyed by `queueId` so a second tap on the
 * same row coalesces with the in-flight retry instead of queueing two.
 *
 * Concurrency for "Retry all" / "Retry group" comes from the caller
 * fanning out N independent enqueue calls — each one runs its own
 * `TrackDownloadWorker` instance. WorkManager's per-worker queue +
 * the OS scheduler cap concurrency naturally; no semaphore needed
 * here.
 *
 * Constraints come from [constraintsForManualTrigger] — same helper
 * every other manual-trigger enqueuer in this module uses (sync chain,
 * `DiscoveryDownloadWorker`, `StashMixRefreshWorker`). Without
 * constraints, WorkManager would fire the worker on a dead battery /
 * no-network, the download would fail immediately inside
 * `TrackDownloadWorker`, and the user would see the row flip back to
 * FAILED with a confusing reason.
 *
 * `enqueue` is `suspend` because reading the current
 * [com.stash.core.model.DownloadNetworkMode] from
 * [DownloadNetworkPreference] is a suspending DataStore read. The
 * eventual caller (`FailedDownloadsViewModel.retry` in Task 16) runs
 * in a `viewModelScope.launch { ... }`, so suspending is natural.
 */
@Singleton
class SingleTrackDownloadEnqueuer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadNetworkPreference: DownloadNetworkPreference,
) {
    companion object {
        fun workName(queueId: Long): String = "single_track_$queueId"

        /** Every single-song run carries it: the Downloads screen counts them as downloads running or waiting. */
        const val TAG = "single_track_retry"
    }

    suspend fun enqueue(queueId: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(queueId),
            ExistingWorkPolicy.KEEP,
            request(queueId, initialDelay = Duration.ZERO),
        )
    }

    /**
     * The same song again after [delay], when YouTube asked us to wait (HTTP 429, its bot check):
     * a retry with the usual backoff would ask again within a minute. Queued behind the run that
     * calls this (APPEND_OR_REPLACE), which [enqueue]'s KEEP would drop. [rateLimitRound] counts
     * the rounds, so the worker stops after [TrackDownloadWorker.MAX_RATE_LIMIT_ROUNDS].
     */
    suspend fun enqueueLater(queueId: Long, delay: Duration, rateLimitRound: Int = 0) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(queueId),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(queueId, initialDelay = delay, rateLimitRound = rateLimitRound),
        )
    }

    /**
     * A transient failure (a network drop, say) hands the row back and asks WorkManager to retry
     * ([TrackDownloadWorker]'s single-song mode): exponential from a minute.
     */
    private suspend fun request(queueId: Long, initialDelay: Duration, rateLimitRound: Int = 0) = OneTimeWorkRequestBuilder<TrackDownloadWorker>()
        .setInputData(
            Data.Builder()
                .putLong(TrackDownloadWorker.KEY_QUEUE_ID, queueId)
                .putInt(TrackDownloadWorker.KEY_RATE_LIMIT_ROUND, rateLimitRound)
                .build()
        )
        .setConstraints(constraintsForManualTrigger(downloadNetworkPreference.current()))
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        .apply { if (initialDelay > Duration.ZERO) setInitialDelay(initialDelay.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
        .addTag(TAG)
        .build()
}
