package com.stash.core.data.sync.workers

import android.content.Context
import android.util.Log
import androidx.work.NetworkType
import com.stash.core.data.prefs.DataSaverSnapshot
import com.stash.core.data.prefs.DownloadNetworkPreference
import com.stash.core.data.prefs.StashMixPreference
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * Keeps [DataSaverSnapshot] in step with the Data saver switch and, when it flips, re-schedules the
 * periodic art and metadata workers so their network constraint follows: WorkManager keeps the
 * constraints a request was enqueued with. The app collects the switch (DataStore) into [onSwitch]
 * for its whole lifetime; the first value also repairs a mirror left stale by a restore.
 *
 * One-shot work already queued keeps the constraint it was queued with; the next one picks up the
 * new value. Nothing is scheduled while Stash Mixes is off: those workers are cancelled then, and
 * turning Mixes back on schedules them with the current constraint.
 */
@Singleton
class DataSaverBackgroundWork @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stashMixPreference: StashMixPreference,
    private val downloadNetworkPreference: DownloadNetworkPreference,
) {
    suspend fun onSwitch(on: Boolean) {
        if (!DataSaverSnapshot.write(context, on)) return
        if (!stashMixPreference.current()) return
        // Guarded: a WorkManager hiccup must not end the app-lifetime collector that calls this.
        runCatching {
            StashMixRefreshWorker.schedulePeriodic(context)
            StashDiscoveryWorker.schedulePeriodic(context, downloadNetworkPreference.current())
            TrackInfoEnrichmentWorker.schedulePeriodic(context)
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            Log.w(TAG, "re-scheduling background work for Data saver=$on failed", it)
        }
    }

    private companion object {
        const val TAG = "DataSaver"
    }
}
