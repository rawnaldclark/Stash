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

    /** Retrieving playlist metadata from the remote source. */
    data object FetchingPlaylists : SyncPhase {
        override val progress: Float = 0.20f
    }

    /** Comparing remote snapshots against local data to find differences. */
    data object Diffing : SyncPhase {
        override val progress: Float = 0.25f
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
                val base = 0.25f
                val span = 0.70f
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
