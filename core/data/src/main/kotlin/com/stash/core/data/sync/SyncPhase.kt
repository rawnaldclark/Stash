package com.stash.core.data.sync

/**
 * Sealed interface representing the current phase of a sync operation.
 *
 * Each phase carries a [progress] value (0.0..1.0) indicating how far along
 * the overall sync has progressed when this phase completes.
 *
 * Unlike [com.stash.core.model.SyncState] (a simple enum for persistence),
 * this type is a rich, in-memory representation used by [SyncStateManager]
 * to drive UI updates and notification progress.
 */
sealed interface SyncPhase {

    /** Normalised progress within 0.0..1.0 for the overall sync operation. */
    val progress: Float

    /** No sync is running. */
    data object Idle : SyncPhase {
        override val progress: Float = 0f
    }

    /** Refreshing or validating OAuth tokens. */
    data object Authenticating : SyncPhase {
        override val progress: Float = 0.05f
    }

    /**
     * Retrieving playlist metadata from the remote source.
     *
     * @property playlistsFetched Running count of playlists/mixes fetched so
     *   far this phase. No total denominator: the true playlist count for
     *   Spotify (folder-walked) and YouTube isn't known until the fetch
     *   itself finishes, so this is a live counter for display ("Fetched 42
     *   playlists…") rather than a fraction — a fabricated total would be
     *   worse than none. [progress] still nudges forward (capped) as the
     *   count grows so a long fetch visibly moves instead of sitting dead
     *   at the phase's starting value.
     */
    data class FetchingPlaylists(
        val playlistsFetched: Int = 0,
    ) : SyncPhase {
        override val progress: Float
            get() = (0.05f + minOf(playlistsFetched, 150) * 0.001f).coerceAtMost(0.20f)
    }

    /**
     * Comparing remote snapshots against local data to find differences.
     *
     * @property playlistsDiffed Playlists reconciled so far this phase.
     * @property totalPlaylists  Total playlists to reconcile — known
     *   upfront here (unlike fetch), since the snapshot rows already exist
     *   in the DB by the time this phase starts.
     */
    data class Diffing(
        val playlistsDiffed: Int = 0,
        val totalPlaylists: Int = 0,
    ) : SyncPhase {
        override val progress: Float
            get() {
                val base = 0.20f
                val span = 0.05f
                val fraction = if (totalPlaylists > 0) playlistsDiffed.toFloat() / totalPlaylists else 0f
                return base + span * fraction
            }
    }

    /**
     * Reconciling the local download queue against the library before any
     * bytes move: sweeping orphaned queue rows, resetting exhausted/stale
     * retries, and re-queuing undownloaded tracks with no active queue
     * entry. This is DB housekeeping, not network — separated from Diffing
     * (remote-vs-local snapshot comparison) so a long pass here reads as
     * "checking your library" instead of a stalled Diffing phase.
     *
     * @property step  Which housekeeping step is currently running.
     * @property total Total housekeeping steps.
     */
    data class VerifyingLibrary(
        val step: Int = 0,
        val total: Int = 5,
    ) : SyncPhase {
        override val progress: Float
            get() {
                val base = 0.25f
                val span = 0.05f
                val fraction = if (total > 0) step.toFloat() / total else 0f
                return base + span * fraction
            }
    }

    /**
     * Downloading new or updated tracks.
     *
     * @property downloaded Number of tracks downloaded so far.
     * @property total      Total number of tracks to download.
     */
    data class Downloading(
        val downloaded: Int = 0,
        val total: Int = 0,
    ) : SyncPhase {
        override val progress: Float
            get() {
                // Download phase spans 25%..95% of the overall progress.
                val base = 0.30f
                val span = 0.65f
                val fraction = if (total > 0) downloaded.toFloat() / total else 0f
                return base + span * fraction
            }
    }

    /** Writing final metadata, updating playlists, cleaning up. */
    data object Finalizing : SyncPhase {
        override val progress: Float = 0.95f
    }

    /** Sync completed successfully. */
    data object Completed : SyncPhase {
        override val progress: Float = 1.0f
    }

    /**
     * Sync failed with an error.
     *
     * @property message Human-readable error description.
     * @property cause   Optional underlying throwable.
     */
    data class Error(
        val message: String,
        val cause: Throwable? = null,
    ) : SyncPhase {
        override val progress: Float = 0f
    }
}
