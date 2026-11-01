package com.stash.core.data.sync.workers

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.stash.core.data.prefs.DataSaverSnapshot
import com.stash.core.data.prefs.DownloadNetworkPreference
import com.stash.core.data.prefs.StashMixPreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The network background art and metadata work asks for: [NetworkType.UNMETERED] while Data saver
 * is on (it waits for Wi-Fi), else [otherwise], what that work asked for before Data saver existed.
 *
 * Only background work goes through this. A download, or a mix the user just made or asked to
 * refresh, is the user's own choice and keeps its constraint.
 */
fun backgroundNetworkType(dataSaverOn: Boolean, otherwise: NetworkType = NetworkType.CONNECTED): NetworkType =
    if (dataSaverOn) NetworkType.UNMETERED else otherwise

/**
 * Queues [request] under [name] again, in place of the one-shot there, when that one is still
 * waiting to run (enqueued, none of it running): WorkManager keeps the constraints a request was
 * queued with, so a pass held for Wi-Fi would otherwise stay held after Data saver is turned off.
 * Nothing waiting (it ran, or never was queued) or a run under way: left alone. Returns whether it
 * re-queued. Suspends to read the state (a blocking read deadlocks on WorkManager's own thread).
 */
suspend fun WorkManager.requeueIfWaiting(name: String, request: OneTimeWorkRequest): Boolean {
    val infos = getWorkInfosForUniqueWorkFlow(name).first()
    val waiting = infos.any { it.state == WorkInfo.State.ENQUEUED } && infos.none { it.state == WorkInfo.State.RUNNING }
    if (waiting) enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    return waiting
}

/**
 * Keeps [DataSaverSnapshot] in step with the Data saver switch and, when it flips, re-schedules the
 * background art and metadata work so its network constraint follows: WorkManager keeps the
 * constraints a request was enqueued with. The app collects the switch (DataStore) into [onSwitch]
 * for its whole lifetime, and calls it once before its startup scheduling so that reads a mirror
 * left stale by an update or a restore already put right.
 *
 * Re-scheduled: the periodic mix refresh, discovery drain, tagging and track-info passes, and the
 * one-shots still waiting for their network (the art backfill, the automatic mix refresh, the
 * first-launch track-info pass). A one-shot already running keeps its constraint. The mix workers
 * aren't scheduled while Stash Mixes is off: they are cancelled then, and turning Mixes back on
 * schedules them with the current constraint. The art backfill isn't a mix worker.
 *
 * Never throws: one DataStore or WorkManager error logs and skips its step, so the app-lifetime
 * collector that calls this keeps going.
 */
@Singleton
class DataSaverBackgroundWork @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stashMixPreference: StashMixPreference,
    private val downloadNetworkPreference: DownloadNetworkPreference,
) {
    suspend fun onSwitch(on: Boolean) {
        val changed = guarded(on, "mirror write") { DataSaverSnapshot.write(context, on) } ?: return
        if (!changed) return
        guarded(on, "art backfill") { ArtBackfillWorker.requeueIfWaiting(context, on) }
        val mixesOn = guarded(on, "Stash Mixes switch") { stashMixPreference.current() } ?: return
        if (!mixesOn) return
        guarded(on, "mix refresh") {
            StashMixRefreshWorker.schedulePeriodic(context)
            StashMixRefreshWorker.requeueAutomaticIfWaiting(context, on)
        }
        guarded(on, "discovery drain and tagging") {
            val mode = downloadNetworkPreference.current()
            StashDiscoveryWorker.schedulePeriodic(context, mode)
            TagEnrichmentWorker.schedulePeriodic(context, mode)
        }
        guarded(on, "track info") {
            TrackInfoEnrichmentWorker.schedulePeriodic(context)
            TrackInfoEnrichmentWorker.requeueFirstRunIfWaiting(context, on)
        }
    }

    /** [block]'s value, or null (logged) when it threw anything but a cancellation. */
    private suspend fun <T> guarded(on: Boolean, step: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Data saver=$on: $step failed", e)
        null
    }

    private companion object {
        const val TAG = "DataSaver"
    }
}
