package com.stash.core.data.sync

import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.model.SyncDisplayStatus
import com.stash.core.model.SyncState
import com.stash.core.model.SyncStepResult
import kotlinx.serialization.json.Json

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
 *  1b. [SyncState.CANCELLED] → [SyncDisplayStatus.Cancelled] (user-cancelled,
 *      kept distinct from a zero-track success)
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

        SyncState.CANCELLED -> SyncDisplayStatus.Cancelled

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

private val stepsJson = Json { ignoreUnknownKeys = true }

/** The playlists a sync listed but couldn't read: how many, and the first ones by name. */
data class UnreadPlaylists(val count: Int, val names: List<String>) {
    companion object {
        val NONE = UnreadPlaylists(0, emptyList())
    }
}

/**
 * The playlists a sync listed but couldn't read, from its recorded steps
 * ([SyncHistoryEntity.diagnostics], [SyncStepResult.unreadPlaylists]): what the
 * Sync History row counts and names. [UnreadPlaylists.NONE] for a run with none,
 * and for steps that don't decode, which the row then shows as it always did.
 */
fun unreadPlaylistsIn(diagnostics: String?): UnreadPlaylists {
    if (diagnostics.isNullOrBlank()) return UnreadPlaylists.NONE
    val steps = runCatching { stepsJson.decodeFromString<List<SyncStepResult>>(diagnostics) }.getOrNull()
        ?: return UnreadPlaylists.NONE
    return UnreadPlaylists(
        count = steps.sumOf { maxOf(it.unreadPlaylistCount, it.unreadPlaylists.size) },
        names = steps.flatMap { it.unreadPlaylists },
    )
}
