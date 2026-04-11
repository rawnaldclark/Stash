package com.stash.core.model

/**
 * Richer, display-oriented summary of a sync run's outcome.
 *
 * The underlying [SyncState] enum only distinguishes running / completed /
 * failed — it cannot express nuances like "mostly succeeded with some
 * failures" or "interrupted but 900 tracks were already downloaded".
 *
 * This sealed class is derived from a sync record at display time and
 * captures the outcomes users actually care about:
 *
 * - [Idle]          — no sync has ever run.
 * - [Running]       — sync is actively in progress.
 * - [Success]       — sync ran to completion with zero failures.
 * - [PartialSuccess] — sync ran to completion but some tracks could not
 *                     be matched or downloaded.
 * - [Interrupted]   — sync was terminated mid-run (process kill, phone die,
 *                     force-close). Any tracks downloaded before the
 *                     interruption are preserved and counted.
 * - [Failed]        — sync errored out with zero downloads; a genuine
 *                     pipeline failure.
 */
sealed class SyncDisplayStatus {

    /** No sync has ever been recorded for this install. */
    data object Idle : SyncDisplayStatus()

    /** A sync is actively running (non-terminal state). */
    data object Running : SyncDisplayStatus()

    /** Sync completed with zero failed downloads. */
    data object Success : SyncDisplayStatus()

    /**
     * Sync completed but not every track succeeded.
     *
     * @property downloaded Number of tracks successfully downloaded.
     * @property failed     Number of tracks that failed to match or download.
     */
    data class PartialSuccess(
        val downloaded: Int,
        val failed: Int,
    ) : SyncDisplayStatus()

    /**
     * Sync was interrupted (process kill, phone die, force-close).
     *
     * [downloaded] reflects the tracks already saved to disk before the
     * interruption, provided [com.stash.core.data.sync.workers.TrackDownloadWorker]
     * flushed its incremental tallies.
     *
     * @property downloaded Number of tracks downloaded before interruption.
     * @property failed     Number of tracks that failed before interruption.
     */
    data class Interrupted(
        val downloaded: Int,
        val failed: Int,
    ) : SyncDisplayStatus()

    /**
     * Sync errored out before any tracks could be downloaded (auth failure,
     * network error before downloads started, pipeline exception, etc.).
     *
     * @property reason Optional human-readable error message.
     */
    data class Failed(
        val reason: String?,
    ) : SyncDisplayStatus()
}
