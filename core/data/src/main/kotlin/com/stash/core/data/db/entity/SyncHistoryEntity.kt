package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.stash.core.model.SyncState
import com.stash.core.model.SyncTrigger
import java.time.Instant

/**
 * Room entity recording the outcome of each sync run.
 */
@Entity(tableName = "sync_history")
data class SyncHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "started_at")
    val startedAt: Instant = Instant.now(),

    @ColumnInfo(name = "completed_at")
    val completedAt: Instant? = null,

    val status: SyncState = SyncState.IDLE,

    @ColumnInfo(name = "playlists_checked")
    val playlistsChecked: Int = 0,

    @ColumnInfo(name = "new_tracks_found")
    val newTracksFound: Int = 0,

    @ColumnInfo(name = "tracks_downloaded")
    val tracksDownloaded: Int = 0,

    @ColumnInfo(name = "tracks_failed")
    val tracksFailed: Int = 0,

    @ColumnInfo(name = "bytes_downloaded")
    val bytesDownloaded: Long = 0,

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,

    val trigger: SyncTrigger = SyncTrigger.MANUAL,
)
