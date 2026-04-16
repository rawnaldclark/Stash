package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.stash.core.model.DownloadFailureType
import com.stash.core.model.DownloadStatus
import java.time.Instant

/**
 * Room entity representing a pending or completed download in the queue.
 */
@Entity(
    tableName = "download_queue",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["track_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SyncHistoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["sync_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["track_id"]),
        Index(value = ["sync_id"]),
        Index(value = ["status"]),
    ],
)
data class DownloadQueueEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "track_id")
    val trackId: Long,

    @ColumnInfo(name = "sync_id")
    val syncId: Long? = null,

    val status: DownloadStatus = DownloadStatus.PENDING,

    @ColumnInfo(name = "search_query")
    val searchQuery: String = "",

    @ColumnInfo(name = "youtube_url")
    val youtubeUrl: String? = null,

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,

    @ColumnInfo(name = "failure_type")
    val failureType: DownloadFailureType = DownloadFailureType.NONE,

    @ColumnInfo(name = "rejected_video_id")
    val rejectedVideoId: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),

    @ColumnInfo(name = "completed_at")
    val completedAt: Instant? = null,
)
