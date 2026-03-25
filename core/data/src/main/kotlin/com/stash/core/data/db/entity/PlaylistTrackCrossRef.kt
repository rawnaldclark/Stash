package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import java.time.Instant

/**
 * Many-to-many join table between [PlaylistEntity] and [TrackEntity].
 *
 * Supports soft-delete via [removedAt]: when non-null the track has been
 * removed from the playlist but its row is retained for sync diffing.
 */
@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlist_id", "track_id"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["track_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["track_id"]),
    ],
)
data class PlaylistTrackCrossRef(
    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,

    @ColumnInfo(name = "track_id")
    val trackId: Long,

    val position: Int = 0,

    @ColumnInfo(name = "added_at")
    val addedAt: Instant = Instant.now(),

    @ColumnInfo(name = "removed_at")
    val removedAt: Instant? = null,
)
