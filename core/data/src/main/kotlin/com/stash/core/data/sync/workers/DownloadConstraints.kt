package com.stash.core.data.sync.workers

import androidx.work.Constraints
import androidx.work.NetworkType
import com.stash.core.model.DownloadNetworkMode

/**
 * Maps a user-selected [DownloadNetworkMode] to WorkManager [Constraints]
 * shared by every download-gated background worker
 * ([StashDiscoveryWorker], [TagEnrichmentWorker]).
 *
 * Shared as a top-level helper so both workers stay in lockstep — if a
 * new mode (e.g. a user-chosen quiet-hours schedule) is added later,
 * one change here propagates everywhere.
 *
 * `setRequiresBatteryNotLow(true)` stays on in every mode so an unplugged
 * phone at 5% battery doesn't burn its last reserves draining the
 * discovery queue.
 */
fun constraintsFor(mode: DownloadNetworkMode): Constraints = Constraints.Builder().apply {
    setRequiresBatteryNotLow(true)
    when (mode) {
        DownloadNetworkMode.WIFI_AND_CHARGING -> {
            setRequiredNetworkType(NetworkType.UNMETERED)
            setRequiresCharging(true)
        }
        DownloadNetworkMode.WIFI_ANY -> {
            setRequiredNetworkType(NetworkType.UNMETERED)
        }
        DownloadNetworkMode.ANY_NETWORK -> {
            setRequiredNetworkType(NetworkType.CONNECTED)
        }
    }
}.build()
