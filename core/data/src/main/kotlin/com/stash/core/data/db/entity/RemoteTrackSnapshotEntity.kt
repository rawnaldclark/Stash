package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a single track within a remote playlist snapshot.
 *
 * These rows are ephemeral: they are written during the "fetch" phase of a
 * sync run and consumed during the "diff" phase. Old snapshots are pruned
 * after a configurable retention period.
 */
@Entity(
    tableName = "remote_track_snapshots",
    indices = [
        Index(value = ["sync_id"]),
        Index(value = ["snapshot_playlist_id"]),
    ],
)
data class RemoteTrackSnapshotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Foreign key to the [com.stash.core.data.db.entity.SyncHistoryEntity.id]. */
    @ColumnInfo(name = "sync_id")
    val syncId: Long,

    /**
     * Foreign key to [RemotePlaylistSnapshotEntity.id].
     * Links this track to the specific playlist snapshot it belongs to.
     */
    @ColumnInfo(name = "snapshot_playlist_id")
    val snapshotPlaylistId: Long,

    /** Track title as reported by the source. */
    val title: String,

    /** Primary artist name. */
    val artist: String,

    /** Album name, if available. */
    val album: String? = null,

    /** Track duration in milliseconds. */
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0,

    /** Spotify track URI (e.g. "spotify:track:abc123"). */
    @ColumnInfo(name = "spotify_uri")
    val spotifyUri: String? = null,

    /** YouTube video ID for matching or fallback download. */
    @ColumnInfo(name = "youtube_id")
    val youtubeId: String? = null,

    /** Album artwork URL. */
    @ColumnInfo(name = "album_art_url")
    val albumArtUrl: String? = null,

    /** Zero-based position of this track within the playlist. */
    val position: Int = 0,
)
