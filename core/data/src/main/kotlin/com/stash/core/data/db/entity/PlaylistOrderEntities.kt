package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A playlist the listener put into their own order (Edit order). Its row's presence is the switch: without one the
 * playlist plays in its service's order ([PlaylistTrackCrossRef.position]), which a sync keeps writing either way.
 * Kept apart from [PlaylistEntity] so no sync's whole-row update can switch it back.
 */
@Entity(
    tableName = "playlist_order",
    foreignKeys = [
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlist_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class PlaylistOrderEntity(
    @PrimaryKey @ColumnInfo(name = "playlist_id") val playlistId: Long,
)

/**
 * One song's place in its playlist's own order: ranks spaced [com.stash.core.data.db.dao.PlaylistOrderDao.SPACING]
 * apart, so a move rewrites one row. Not tied to the membership row: a refresh that clears and re-adds a playlist's
 * songs keeps their places. A member without a rank (a song that arrived after the last move) sorts after every
 * ranked one, in the service's order: new songs land at the bottom.
 */
@Entity(
    tableName = "playlist_order_entries",
    primaryKeys = ["playlist_id", "track_id"],
    foreignKeys = [
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlist_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["track_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["playlist_id", "rank"]), Index(value = ["track_id"])],
)
data class PlaylistOrderEntryEntity(
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "track_id") val trackId: Long,
    val rank: Long,
)
