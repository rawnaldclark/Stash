package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import java.time.Instant

/**
 * Room entity representing a playlist synced from a music service.
 */
@Entity(
    tableName = "playlists",
    indices = [
        Index(value = ["source_id"], unique = true),
    ],
)
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,

    val source: MusicSource,

    @ColumnInfo(name = "source_id")
    val sourceId: String = "",

    val type: PlaylistType = PlaylistType.CUSTOM,

    @ColumnInfo(name = "mix_number")
    val mixNumber: Int? = null,

    @ColumnInfo(name = "last_synced")
    val lastSynced: Instant? = null,

    @ColumnInfo(name = "track_count")
    val trackCount: Int = 0,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "art_url")
    val artUrl: String? = null,

    @ColumnInfo(name = "snapshot_id")
    val snapshotId: String? = null,

    /** Whether this playlist participates in sync/download. Default true
     *  so new playlists are included automatically; users opt-out via the
     *  Spotify Sync Preferences on the Sync tab. */
    @ColumnInfo(name = "sync_enabled", defaultValue = "1")
    val syncEnabled: Boolean = true,
)
