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
 * `syncedAgoText`, against a clock that ticks each minute) rather than computed
 * here — `syncedAgoText` wraps Android `DateUtils`, which returns null under `isReturnDefaultValues=true` and
 * NPEs in unit tests. Keeping it out makes this function pure and unit-testable.
 * `formatSyncDuration` / `formatSyncBytes` are pure Kotlin and stay inline.
 */
fun SyncHistoryInfo.toRecentSyncRow(
    relativeTime: String,
    onlineLabel: String = "Online",
    offlineLabel: String = "Offline",
    surfacedNoun: String = "surfaced",
    downloadedNoun: String = "downloaded",
): RecentSyncRow {
    val online = streamingMode
    return RecentSyncRow(
        id = id,
        modeLabel = when (online) { true -> onlineLabel; false -> offlineLabel; null -> null },
        relativeTime = relativeTime,
        duration = formatSyncDuration(startedAt, completedAt),
        added = if (online == true) newTracksFound else tracksDownloaded,
        addedNoun = if (online == true) surfacedNoun else downloadedNoun,
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
        unreadCount = unreadPlaylists.count,
        unreadNames = unreadPlaylists.names,
    )
}

fun SyncHistoryInfo.toRecentSyncRow(
    relativeTime: String,
    res: android.content.res.Resources,
): RecentSyncRow = toRecentSyncRow(
    relativeTime = relativeTime,
    onlineLabel = res.getString(R.string.sync_history_mode_online),
    offlineLabel = res.getString(R.string.sync_history_mode_offline),
    surfacedNoun = res.getString(R.string.sync_history_surfaced),
    downloadedNoun = res.getString(R.string.sync_history_downloaded),
)
