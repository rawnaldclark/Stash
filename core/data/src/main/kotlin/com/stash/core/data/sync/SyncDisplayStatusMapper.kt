package com.stash.core.data.sync

import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.model.SyncDisplayStatus
import com.stash.core.model.SyncState

/**
 * Marker text used by [com.stash.core.data.repository.MusicRepositoryImpl.runMigrations]
 * when it resets stale sync records whose process was killed mid-run.
 *
 * Kept public so the display mapper below can recognise the marker and
 * classify the run as [SyncDisplayStatus.Interrupted] rather than a
 * generic failure.
 */
const val INTERRUPTED_ERROR_MARKER = "Interrupted"

/**
 * Derives a user-facing [SyncDisplayStatus] from a raw [SyncHistoryEntity].
 *
 * Decision tree:
 *
 *  1. [SyncState.IDLE] → [SyncDisplayStatus.Idle]
 *  2. Terminal states:
 *     - COMPLETED + zero failed → Success
 *     - COMPLETED + any failed  → PartialSuccess (the sync finalizer always
 *        marks COMPLETED regardless of failures)
 *     - FAILED + errorMessage contains "Interrupted" → Interrupted
 *     - FAILED + tracksDownloaded > 0 (but not interrupted) → PartialSuccess
 *        (the sync errored late but some tracks did succeed; count them)
 *     - FAILED + no downloads → Failed (genuine pipeline failure)
 *  3. Non-terminal states (AUTHENTICATING, FETCHING_PLAYLISTS, DIFFING,
 *     DOWNLOADING, FINALIZING) → Running
 */
fun SyncHistoryEntity.toDisplayStatus(): SyncDisplayStatus {
    return when (status) {
        SyncState.IDLE -> SyncDisplayStatus.Idle

        SyncState.COMPLETED -> {
            if (tracksFailed > 0) {
                SyncDisplayStatus.PartialSuccess(
                    downloaded = tracksDownloaded,
                    failed = tracksFailed,
                )
            } else {
                SyncDisplayStatus.Success
            }
        }

        SyncState.FAILED -> {
            val isInterrupted = errorMessage
                ?.contains(INTERRUPTED_ERROR_MARKER, ignoreCase = true) == true
            when {
                isInterrupted -> SyncDisplayStatus.Interrupted(
                    downloaded = tracksDownloaded,
                    failed = tracksFailed,
                )
                tracksDownloaded > 0 -> SyncDisplayStatus.PartialSuccess(
                    downloaded = tracksDownloaded,
                    failed = tracksFailed,
                )
                else -> SyncDisplayStatus.Failed(reason = errorMessage)
            }
        }

        SyncState.AUTHENTICATING,
        SyncState.FETCHING_PLAYLISTS,
        SyncState.DIFFING,
        SyncState.DOWNLOADING,
        SyncState.FINALIZING -> SyncDisplayStatus.Running
    }
}
