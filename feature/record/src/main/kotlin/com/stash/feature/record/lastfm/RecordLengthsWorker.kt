package com.stash.feature.record.lastfm

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Runs Your record's lengths pass ([RecordLengths.run]) as a plain job: quiet, with no notification.
 *
 * - Asked for ([start]), it runs at once, with no network asked of it: the free fill (the songs Spotify or the library times)
 *   needs none. Its lookups go only on a network the app's Wi-Fi-only setting allows; with none, the run ends after the fill and
 *   the pass comes back as a job waiting for that network ([waitForNetwork]: unmetered when Wi-Fi only, else any), queued
 *   behind the run. A change of the setting reaches a job still waiting ([applyWifiOnly]), as it reaches a waiting sync.
 * - A run held by a limit (the day's lookups spent, a rate limit) comes back at the time it lifts: a second, waiting job
 *   ([wakeAt]) whose only work is to ask for a pass then.
 * - A job the system stops (its ten minutes are up, the app died) runs again by itself and plans afresh: timed songs drop out of
 *   the plan and the misses are kept. The writers that could add songs to time stop it first ([RecordLengths.pause]) and ask for
 *   a new one after, which takes the place of any still waiting.
 */
@HiltWorker
class RecordLengthsWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val lengths: RecordLengths,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (inputData.getBoolean(KEY_WAKE, false)) start(applicationContext) else lengths.run(waited = TAG_WIFI in tags || TAG_ANY in tags)
        return Result.success()
    }

    companion object {
        private const val TAG = "YourRecord"
        private const val NAME = "record-lengths"
        private const val WAKE = "record-lengths-wake"
        private const val KEY_WAKE = "wake"

        /** How the job was asked for: at once (no network asked), or waiting for a network by the setting then. */
        internal const val TAG_NOW = "record-lengths-now"
        internal const val TAG_WIFI = "record-lengths-wifi"
        internal const val TAG_ANY = "record-lengths-any-network"

        /** A pass now, with no network asked of it, in the place of any queued or waiting. */
        fun start(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request(null))
        }

        /** The pass again once there is a network [wifiOnly] allows (and [delayMs] has passed), behind the run ending now. */
        fun waitForNetwork(context: Context, wifiOnly: Boolean, delayMs: Long = 0) {
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request(wifiOnly, delayMs))
        }

        /**
         * The Wi-Fi-only setting changed (or was read at start, or a run just queued its wait): a pass waiting for a network waits by
         * it now (SyncScheduler `applyWifiOnlyToWaitingSync`'s aim). The waiting job is updated in place, its id and delay kept, so
         * one queued behind a pass still running takes the change too, and the running one is never touched; one asked for at once
         * needs no network.
         */
        suspend fun applyWifiOnly(
            context: Context,
            wifiOnly: Boolean,
            /** The pass's jobs as read (a seam: the read can be stale by the time of the update). */
            infos: suspend (WorkManager) -> List<WorkInfo> = { it.getWorkInfosForUniqueWorkFlow(NAME).first() },
        ) {
            val work = WorkManager.getInstance(context)
            for (info in infos(work)) {
                if (!waitsByOther(info.state, info.tags, wifiOnly)) continue
                try {
                    work.updateWork(request(wifiOnly, info.initialDelayMillis, info.id)).await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Gone since it was read (a pass asked for replaced it): nothing waits to take the setting
                    Log.w(TAG, "the lengths pass's waiting job was gone before it took the Wi-Fi-only setting", e)
                }
            }
        }

        /** Whether a job ([state], [tags]) waits for a network by the other setting than [wifiOnly], and can still take a change. */
        internal fun waitsByOther(state: WorkInfo.State, tags: Set<String>, wifiOnly: Boolean): Boolean {
            val want = if (wifiOnly) TAG_WIFI else TAG_ANY
            val waiting = state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED
            return waiting && (TAG_WIFI in tags || TAG_ANY in tags) && want !in tags
        }

        /** The network a job waits for: none ([wifiOnly] null: the free fill, at once), unmetered, or any. */
        internal fun networkFor(wifiOnly: Boolean?): NetworkType = when (wifiOnly) {
            null -> NetworkType.NOT_REQUIRED
            true -> NetworkType.UNMETERED
            false -> NetworkType.CONNECTED
        }

        private fun request(wifiOnly: Boolean?, delayMs: Long = 0, id: UUID? = null): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<RecordLengthsWorker>()
                .apply { if (id != null) setId(id) }
                .setConstraints(Constraints.Builder().setRequiredNetworkType(networkFor(wifiOnly)).build())
                .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .addTag(
                    when (wifiOnly) {
                        null -> TAG_NOW
                        true -> TAG_WIFI
                        false -> TAG_ANY
                    },
                )
                .build()

        /** A pass asked for at [atMs] (epoch ms), replacing any wake-up asked for before. */
        fun wakeAt(context: Context, atMs: Long) {
            val delay = (atMs - System.currentTimeMillis()).coerceAtLeast(0)
            WorkManager.getInstance(context).enqueueUniqueWork(
                WAKE,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<RecordLengthsWorker>()
                    .setInputData(workDataOf(KEY_WAKE to true))
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .build(),
            )
        }

        /** The pass ends, queued, running, waiting for a network or to come back, and nothing runs again by itself. */
        fun cancel(context: Context) {
            val work = WorkManager.getInstance(context)
            work.cancelUniqueWork(NAME)
            work.cancelUniqueWork(WAKE)
        }
    }
}
