package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.stash.core.model.MusicSource
import java.time.Instant

/**
 * Room entity representing a single audio track stored on-device.
 *
 * Indexes are designed around the most common query patterns:
 * - Lookup by Spotify URI or YouTube ID (unique nullable).
 * - Browsing by artist / album.
 * - Sorting by date added, last played, or play count.
 * - Duplicate detection via the composite (title, artist) index.
 */
@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["spotify_uri"], unique = true),
        Index(value = ["youtube_id"], unique = true),
        Index(value = ["artist"]),
        Index(value = ["album"]),
        Index(value = ["date_added"]),
        Index(value = ["last_played"]),
        Index(value = ["play_count"]),
        Index(value = ["title", "artist"]),
    ],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val title: String,

    val artist: String,

    val album: String = "",

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0,

    @ColumnInfo(name = "file_path")
    val filePath: String? = null,

    @ColumnInfo(name = "file_format")
    val fileFormat: String = "opus",

    @ColumnInfo(name = "quality_kbps")
    val qualityKbps: Int = 0,

    @ColumnInfo(name = "file_size_bytes")
    val fileSizeBytes: Long = 0,

    val source: MusicSource = MusicSource.SPOTIFY,

    @ColumnInfo(name = "spotify_uri")
    val spotifyUri: String? = null,

    @ColumnInfo(name = "youtube_id")
    val youtubeId: String? = null,

    @ColumnInfo(name = "album_art_url")
    val albumArtUrl: String? = null,

    @ColumnInfo(name = "album_art_path")
    val albumArtPath: String? = null,

    @ColumnInfo(name = "date_added")
    val dateAdded: Instant = Instant.now(),

    @ColumnInfo(name = "last_played")
    val lastPlayed: Instant? = null,

    @ColumnInfo(name = "play_count")
    val playCount: Int = 0,

    @ColumnInfo(name = "is_downloaded")
    val isDownloaded: Boolean = false,

    @ColumnInfo(name = "canonical_title")
    val canonicalTitle: String = "",

    @ColumnInfo(name = "canonical_artist")
    val canonicalArtist: String = "",

    @ColumnInfo(name = "match_confidence")
    val matchConfidence: Float = 0f,

    @ColumnInfo(name = "match_dismissed")
    val matchDismissed: Boolean = false,

    /**
     * User-reported "this downloaded the wrong song." Set via a Now Playing
     * overflow action once the user realizes a sync-matched track plays a
     * different song than its Spotify metadata says. Surfaces the track in
     * the Failed Matches screen so the resync flow can present alternative
     * candidates; cleared when the user approves a replacement match or
     * dismisses the flag.
     */
    @ColumnInfo(name = "match_flagged", defaultValue = "0")
    val matchFlagged: Boolean = false,

    /**
     * User-level "never download this track again" marker. When set, DiffWorker
     * skips both the download queue insert and the playlist_tracks link during
     * sync — the track effectively disappears from the user's library without
     * being forgotten by identity matching. Cleared via the Blocked Songs
     * viewer in Settings. Downstream lookups (findBySpotifyUri / ByYoutubeId /
     * canonical) still hit blacklisted rows so every future sync attempt for
     * the same identity is also blocked.
     */
    @ColumnInfo(name = "is_blacklisted", defaultValue = "0")
    val isBlacklisted: Boolean = false,
)
