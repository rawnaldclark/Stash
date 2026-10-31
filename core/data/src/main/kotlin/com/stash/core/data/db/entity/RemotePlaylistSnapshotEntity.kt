package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import java.time.Instant

/**
 * Room entity representing a point-in-time snapshot of a remote playlist
 * as fetched during a sync run.
 *
 * Each row captures what the remote source reported for one playlist at
 * the moment of fetching. Rows are keyed by [syncId] + [sourcePlaylistId]
 * so the same playlist can appear in multiple sync runs.
 */
@Entity(
    tableName = "remote_playlist_snapshots",
    indices = [
        Index(value = ["sync_id"]),
        Index(value = ["sync_id", "source_playlist_id"], unique = true),
    ],
)
data class RemotePlaylistSnapshotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Foreign key to the [SyncHistoryEntity.id] this snapshot belongs to. */
    @ColumnInfo(name = "sync_id")
    val syncId: Long,

    /** Music source that owns this playlist. */
    val source: MusicSource,

    /** Playlist ID on the remote service (e.g. Spotify playlist URI). */
    @ColumnInfo(name = "source_playlist_id")
    val sourcePlaylistId: String,

    /** Account generation that fetched this list; rejects stale sync work after reconnect/disconnect. */
    @ColumnInfo(name = "soundcloud_connection_id")
    val soundCloudConnectionId: String? = null,

    /** Display name of the playlist as reported by the source. */
    @ColumnInfo(name = "playlist_name")
    val playlistName: String,

    /** Classification of the playlist (daily mix, liked songs, custom). */
    @ColumnInfo(name = "playlist_type")
    val playlistType: PlaylistType = PlaylistType.CUSTOM,

    /** Daily-mix number, if applicable. */
    @ColumnInfo(name = "mix_number")
    val mixNumber: Int? = null,

    /**
     * Change detection for Spotify library playlists (see
     * [com.stash.core.data.sync.workers.SpotifyMirrorStamp]):
     *  - a fetched playlist: Spotify's revision from the library listing, set
     *    only when Spotify's own count verified the fetch. The diff stamps it
     *    onto the playlist once the tracks are mirrored.
     *  - a playlist whose fetch the sync skipped: the stamp it trusted, which
     *    the diff matches against the playlist's own to leave it as it is.
     *
     * Null for everything else (mixes, Liked Songs, YouTube, an unverified
     * fetch): those are always diffed and never stamped.
     */
    @ColumnInfo(name = "snapshot_id")
    val snapshotId: String? = null,

    /** Number of tracks the source reports for this playlist. */
    @ColumnInfo(name = "track_count")
    val trackCount: Int = 0,

    /** URL for playlist artwork. */
    @ColumnInfo(name = "art_url")
    val artUrl: String? = null,

    /** Timestamp when this snapshot was captured. */
    @ColumnInfo(name = "fetched_at")
    val fetchedAt: Instant = Instant.now(),

    /**
     * True when the remote source returned a partial result for this playlist
     * — either continuation pagination failed mid-walk, or the fetched count
     * fell below 95% of the source-reported [expectedCount].
     *
     * Defaults to false for back-compat with rows written before v16.
     */
    @ColumnInfo(name = "partial")
    val partial: Boolean = false,

    /**
     * The remote source's reported track count for this playlist (when the
     * response carries a header that exposes it). Used together with
     * [partial] to render diagnostics like "1247 / 2000 (partial)". Null
     * when the source provides no count (e.g. YT Music Liked Songs).
     */
    @ColumnInfo(name = "expected_count")
    val expectedCount: Int? = null,
)
