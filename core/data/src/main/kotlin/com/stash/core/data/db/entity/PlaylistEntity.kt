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

    /** Whether this playlist participates in sync/download. Kotlin default is
     *  `false` — users must explicitly opt-in via the Spotify Sync
     *  Preferences on the Sync tab. Prevents surprise downloads of every
     *  playlist on the user's Spotify account on the first sync. The SQL
     *  `defaultValue` stays `"1"` to preserve the existing schema and avoid
     *  a Room migration; Room-generated INSERTs always supply the Kotlin
     *  value so the SQL default is effectively unused in practice. */
    @ColumnInfo(name = "sync_enabled", defaultValue = "1")
    val syncEnabled: Boolean = false,

    /**
     * Timestamp of when this playlist was first added to the user's
     * library — stable across syncs, unlike [lastSynced] which resets
     * every sync run. Drives the Library tab's "Recently added" sort
     * order so user-added playlists stay where the user added them in
     * the list instead of reshuffling after each sync.
     *
     * Default = `Instant.now()` for Kotlin-side inserts; migration
     * v12→v13 backfills existing rows from `last_synced` if set, else
     * the migration's wall clock.
     */
    @ColumnInfo(name = "date_added", defaultValue = "0")
    val dateAdded: Instant = Instant.now(),
)
