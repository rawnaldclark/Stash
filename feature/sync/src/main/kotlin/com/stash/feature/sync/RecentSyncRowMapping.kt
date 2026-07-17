package com.stash.feature.sync

import com.stash.core.model.SyncDisplayStatus
import com.stash.feature.sync.components.RecentSyncRow
import com.stash.feature.sync.components.SyncRowStatus
import com.stash.feature.sync.components.formatSyncBytes
import com.stash.feature.sync.components.formatSyncDuration

/**
 * Pure mapping from a [SyncHistoryInfo] record to the view-layer [RecentSyncRow].
 *
 * [relativeTime] is a PARAMETER (filled by the Compose call site via
 * `formatRelativeTime`) rather than computed here — `formatRelativeTime` wraps
 * Android `DateUtils`, which returns null under `isReturnDefaultValues=true` and
 * NPEs in unit tests. Keeping it out makes this function pure and unit-testable.
 * `formatSyncDuration` / `formatSyncBytes` are pure Kotlin and stay inline.
 */
fun SyncHistoryInfo.toRecentSyncRow(relativeTime: String): RecentSyncRow {
    val online = streamingMode
    return RecentSyncRow(
        id = id,
        modeLabel = when (online) { true -> "Online"; false -> "Offline"; null -> null },
        relativeTime = relativeTime,
        duration = formatSyncDuration(startedAt, completedAt),
        added = if (online == true) newTracksFound else tracksDownloaded,
        addedNoun = if (online == true) "surfaced" else "downloaded",
        playlists = playlistsChecked,
        sizeLabel = formatSyncBytes(bytesDownloaded),
        failed = tracksFailed,
        status = when (displayStatus) {
            SyncDisplayStatus.Success -> SyncRowStatus.HEALTHY
            is SyncDisplayStatus.PartialSuccess -> SyncRowStatus.PARTIAL
            is SyncDisplayStatus.Interrupted -> SyncRowStatus.PARTIAL
            is SyncDisplayStatus.Failed -> SyncRowStatus.FAILED
            SyncDisplayStatus.Cancelled -> SyncRowStatus.CANCELLED
            SyncDisplayStatus.Running -> SyncRowStatus.PARTIAL
            SyncDisplayStatus.Idle -> SyncRowStatus.PARTIAL
        },
        errorMessage = errorMessage,
        diagnostics = diagnostics,
    )
}
