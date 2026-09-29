package com.stash.core.data.sync

import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.Operation
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first

private const val TAG = "PeriodicWork"

/**
 * [WorkManager.enqueueUniquePeriodicWork] that brings back a periodic job
 * that has finished.
 *
 * A periodic job never runs again once it is finished, and a periodic worker
 * that throws ends FAILED. UPDATE leaves finished work as it is, so a schedule
 * re-registered with UPDATE on every app start stayed dead for good: on a
 * fresh install the daily mix refresh raced the first-launch one-shot, threw
 * once, and never ran again on that install.
 *
 * When the job under [name] exists and all of it is finished, enqueues
 * [request] with [ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE]; otherwise
 * with [livePolicy], so a live schedule keeps its next run time. (KEEP
 * already replaces finished work; routing KEEP callers through here too keeps
 * that true if one ever switches to UPDATE.)
 *
 * Suspends rather than blocks to read the job's state: a blocking read
 * deadlocks when it runs on WorkManager's own task thread (see
 * [SyncScheduler.startScheduledSync]'s chain read).
 */
suspend fun WorkManager.enqueueUniquePeriodicWorkReviving(
    name: String,
    livePolicy: ExistingPeriodicWorkPolicy,
    request: PeriodicWorkRequest,
): Operation {
    val infos = getWorkInfosForUniqueWorkFlow(name).first()
    val finished = infos.isNotEmpty() && infos.all { it.state.isFinished }
    if (finished) Log.i(TAG, "'$name' is ${infos.map { it.state }}; enqueueing it again")
    val policy = if (finished) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else livePolicy
    return enqueueUniquePeriodicWork(name, policy, request)
}
