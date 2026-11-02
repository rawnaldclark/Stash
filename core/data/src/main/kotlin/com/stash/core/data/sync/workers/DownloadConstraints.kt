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

/**
 * [constraintsFor] for a daily background pass ([StashDiscoveryWorker]'s drain, [TagEnrichmentWorker]'s
 * tagging): "Any network" waits for Wi-Fi while Data saver is on, as their Last.fm and catalog
 * look-ups are background work nobody is waiting on. The two Wi-Fi modes are unchanged.
 */
fun backgroundConstraintsFor(mode: DownloadNetworkMode, dataSaverOn: Boolean): Constraints =
    constraintsFor(if (dataSaverOn && mode == DownloadNetworkMode.ANY_NETWORK) DownloadNetworkMode.WIFI_ANY else mode)

/**
 * Constraints for **manual user-initiated triggers** — the user explicitly
 * tapped "Refresh this mix" or the app booted with orphan downloads to
 * drain. They're asking for content right now; the system honors that on
 * whatever network is available.
 *
 * Differs from [constraintsFor]:
 *  - Charging requirement DROPPED (user is actively using the app).
 *  - Network requirement RELAXED from UNMETERED to CONNECTED — manual
 *    triggers don't gate on cellular preference. The user's
 *    DownloadNetworkMode pref still governs the periodic background
 *    cycle (via [constraintsFor]); it doesn't govern foreground intent.
 *
 * `setRequiresBatteryNotLow(true)` stays on — a 5% battery + manual
 * refresh is still a bad combination.
 *
 * v0.9.20 history: shipped in PR 5 respecting DownloadNetworkMode for
 * cellular gating. After a corruption-induced pref reset left the user
 * stuck (default mode = WIFI_AND_CHARGING → manual triggers required
 * unmetered → no firing on cellular), we relaxed to CONNECTED to match
 * how every major music app handles user-initiated downloads.
 */
@Suppress("UNUSED_PARAMETER")
fun constraintsForManualTrigger(mode: DownloadNetworkMode): Constraints = Constraints.Builder()
    .setRequiresBatteryNotLow(true)
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()
