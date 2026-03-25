package com.stash.core.model

data class SyncStatus(
    val state: SyncState = SyncState.IDLE,
    val progressPercent: Float = 0f,
    val currentPhase: String = "",
    val tracksDownloaded: Int = 0,
    val totalTracksToDownload: Int = 0,
    val lastSyncTimestamp: Long? = null,
    val errorMessage: String? = null,
)

enum class SyncState {
    IDLE, AUTHENTICATING, FETCHING_PLAYLISTS, DIFFING, DOWNLOADING, FINALIZING, COMPLETED, FAILED
}
