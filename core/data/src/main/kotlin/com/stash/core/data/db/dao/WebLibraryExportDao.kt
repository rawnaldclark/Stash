package com.stash.core.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.SharedMixEntity
import com.stash.core.model.Track
import kotlinx.coroutines.flow.Flow

/**
 * Read-only queries behind "Export for Stash on the web" (WebLibraryExporter). Nothing here writes.
 *
 * Blocked songs never leave: every query drops a track the blocklist matches, by the same three keys
 * the Library's playlist feed uses ([TrackDao.getByPlaylist]).
 *
 * Songs are read as [SongColumns] only (the ten columns a web song is made from), never `t.*`: a big library
 * doesn't pull every file path and download flag into memory just to throw them away.
 */
@Dao
interface WebLibraryExportDao {

    /** The columns of a track that a web song is made from, and nothing else. */
    data class SongColumns(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        @ColumnInfo(name = "duration_ms") val durationMs: Long,
        val isrc: String?,
        @ColumnInfo(name = "spotify_uri") val spotifyUri: String?,
        @ColumnInfo(name = "youtube_id") val youtubeId: String?,
        @ColumnInfo(name = "album_art_url") val albumArtUrl: String?,
        /** Epoch ms. */
        @ColumnInfo(name = "date_added") val dateAdded: Long,
    ) {
        /** As a domain [Track], so it goes through the same portable description song links use. */
        fun toTrack(): Track = Track(
            id = id,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            isrc = isrc,
            spotifyUri = spotifyUri,
            youtubeId = youtubeId,
            albumArtUrl = albumArtUrl,
            dateAdded = dateAdded,
        )
    }

    /** A liked song and when it was liked. */
    data class LikedRow(
        @Embedded val track: SongColumns,
        /** The Stash like, else the earliest time the song entered a Liked Songs playlist; null when neither is known. */
        @ColumnInfo(name = "liked_at") val likedAt: Long?,
    )

    /** One song of a playlist, with when it was added there. */
    data class PlaylistItemRow(
        @Embedded val track: SongColumns,
        @ColumnInfo(name = "added_at") val addedAt: Long?,
    )

    /** One play: the song and when it started. */
    data class PlayRow(
        @Embedded val track: SongColumns,
        @ColumnInfo(name = "played_at") val playedAt: Long,
    )

    /**
     * Your likes, newest first: a Stash like (`stash_liked_at`), or a song in a Liked Songs playlist mirrored from
     * Spotify or YouTube Music (or Stash's own). The same two meanings the likes-only backup uses
     * ([com.stash.core.data.db.DatabaseBackupManager.pruneToLikes]).
     */
    @Query(
        """
        SELECT $SONG_COLUMNS, COALESCE(t.stash_liked_at, (
            SELECT MIN(pt.added_at) FROM playlist_tracks pt
            JOIN playlists p ON p.id = pt.playlist_id
            WHERE pt.track_id = t.id AND pt.removed_at IS NULL AND p.type IN ('LIKED_SONGS', 'STASH_LIKED')
        )) AS liked_at
        FROM tracks t
        WHERE (
            t.stash_liked_at IS NOT NULL
            OR EXISTS (
                SELECT 1 FROM playlist_tracks pt
                JOIN playlists p ON p.id = pt.playlist_id
                WHERE pt.track_id = t.id AND pt.removed_at IS NULL AND p.type IN ('LIKED_SONGS', 'STASH_LIKED')
            )
        )
        AND $NOT_BLOCKED
        ORDER BY liked_at DESC, t.id ASC
        """,
    )
    suspend fun likes(): List<LikedRow>

    /**
     * The songs liked in Stash (`stash_liked_at`: the heart Now Playing shows), newest first, a blocked song left out. Your
     * record's song rows show their hearts by it (a song only in a Liked Songs playlist synced from Spotify isn't one).
     */
    @Query("SELECT $SONG_COLUMNS FROM tracks t WHERE t.stash_liked_at IS NOT NULL AND $NOT_BLOCKED ORDER BY t.stash_liked_at DESC, t.id ASC")
    suspend fun stashLikes(): List<SongColumns>

    /**
     * Your own playlists ([com.stash.core.model.PlaylistType.CUSTOM]) as Library shows them (online, see
     * [PlaylistDao.getAllVisible]): active, and synced, pinned, made in Stash, a followed shared mix, or holding a
     * song you can play. Daily Mixes, Stash Mixes, Liked Songs and the Downloads list are other types and never
     * come here. Oldest first.
     */
    @Query(
        """
        SELECT p.* FROM playlists p
        WHERE p.type = 'CUSTOM'
          AND p.is_active = 1
          AND (
              p.sync_enabled = 1
              OR p.pinned_to_home_at IS NOT NULL
              OR p.source_id LIKE 'share:%'
              OR p.source_id LIKE 'custom_%'
              OR EXISTS (
                  SELECT 1 FROM playlist_tracks pt
                  JOIN tracks t ON pt.track_id = t.id
                  WHERE pt.playlist_id = p.id
                    AND pt.removed_at IS NULL
                    AND (t.is_downloaded = 1 OR t.is_streamable = 1 OR t.is_streamable_checked_at IS NULL)
              )
          )
        ORDER BY p.date_added ASC, p.id ASC
        """,
    )
    suspend fun playlists(): List<PlaylistEntity>

    /** A playlist's first [limit] songs in its order (removed ones left out). */
    @Query(
        """
        SELECT $SONG_COLUMNS, pt.added_at AS added_at FROM playlist_tracks pt
        JOIN tracks t ON t.id = pt.track_id
        WHERE pt.playlist_id = :playlistId
          AND pt.removed_at IS NULL
          AND $NOT_BLOCKED
        ORDER BY pt.position ASC, pt.added_at ASC, t.id ASC
        LIMIT :limit
        """,
    )
    suspend fun playlistItems(playlistId: Long, limit: Int): List<PlaylistItemRow>

    /** Shared mixes this phone follows (someone else's mix), by playlist. */
    @Query(
        "SELECT * FROM shared_mixes WHERE role = '${SharedMixEntity.ROLE_FOLLOWER}' " +
            "AND status = '${SharedMixEntity.STATUS_ACTIVE}'",
    )
    suspend fun followedMixes(): List<SharedMixEntity>

    /** The newest [limit] plays, newest first. */
    @Query(
        """
        SELECT $SONG_COLUMNS, le.started_at AS played_at FROM listening_events le
        JOIN tracks t ON t.id = le.track_id
        WHERE $NOT_BLOCKED
        ORDER BY le.started_at DESC, le.id DESC
        LIMIT :limit
        """,
    )
    suspend fun recentPlays(limit: Int): List<PlayRow>

    /**
     * How many likes [likes] returns (the picker's count). Songs without a title or artist are left out, as the file
     * leaves them out.
     */
    @Query(
        """
        SELECT COUNT(*) FROM tracks t
        WHERE (
            t.stash_liked_at IS NOT NULL
            OR EXISTS (
                SELECT 1 FROM playlist_tracks pt
                JOIN playlists p ON p.id = pt.playlist_id
                WHERE pt.track_id = t.id AND pt.removed_at IS NULL AND p.type IN ('LIKED_SONGS', 'STASH_LIKED')
            )
        )
        AND TRIM(t.title) != '' AND TRIM(t.artist) != ''
        AND $NOT_BLOCKED
        """,
    )
    suspend fun likeCount(): Int

    /** How many plays [recentPlays] returns with [limit] (the picker's count). */
    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT le.id FROM listening_events le
            JOIN tracks t ON t.id = le.track_id
            WHERE $NOT_BLOCKED AND TRIM(t.title) != '' AND TRIM(t.artist) != '' AND le.started_at > 0
            LIMIT :limit
        )
        """,
    )
    suspend fun playCount(limit: Int): Int

    /** How many songs of a playlist would go (removed, blocked and untitled ones left out). */
    @Query(
        """
        SELECT COUNT(*) FROM playlist_tracks pt
        JOIN tracks t ON t.id = pt.track_id
        WHERE pt.playlist_id = :playlistId
          AND pt.removed_at IS NULL
          AND TRIM(t.title) != '' AND TRIM(t.artist) != ''
          AND $NOT_BLOCKED
        """,
    )
    suspend fun playlistItemCount(playlistId: Long): Int

    /** A play's words and start, for the importer's "the same play is never added twice" (library-file v1 "Merging"). */
    data class PlayKeyRow(val title: String, val artist: String, val isrc: String?, @ColumnInfo(name = "started_at") val startedAt: Long)

    /** Every play that started between [fromMs] and [toMs] (inclusive), with its song's words. */
    @Query(
        """
        SELECT t.title, t.artist, t.isrc, le.started_at FROM listening_events le
        JOIN tracks t ON t.id = le.track_id
        WHERE le.started_at BETWEEN :fromMs AND :toMs
        """,
    )
    suspend fun playKeysBetween(fromMs: Long, toMs: Long): List<PlayKeyRow>

    // ── Your whole listening history (library-file v1 "Listens") ────────────────────────────────────────────────────
    //
    // Only this phone's own listens: `origin_device IS NULL`. A play mirrored from another device or imported (Stash on the
    // web, Last.fm, ListenBrainz) comes into the web from where it was made, so it never goes out again from here.
    // (`recentPlays` above, the file's `history`, includes them on purpose; see RemotePlaysDaoTest's checklist.)
    // Skips have no origin column: only ListeningRecorder writes them, so every skip is this phone's own.
    //
    // Read in pages, newest first, by a (time, id) cursor: a decade is about 100,000 plays, and a page of 5,000 small rows
    // is all that is in memory at once. `started_at <= :beforeAt` is a range on the indexed column, so each page starts
    // with an index seek instead of re-reading every newer row.

    /** One of this phone's plays, with just the words and times the file needs. */
    data class ListenRow(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        @ColumnInfo(name = "started_at") val startedAt: Long,
        @ColumnInfo(name = "listened_ms") val listenedMs: Long?,
        @ColumnInfo(name = "duration_ms") val durationMs: Long,
    )

    /** One of this phone's skips: when it happened and how far the song had got. */
    data class SkipRow(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        @ColumnInfo(name = "skipped_at") val skippedAt: Long,
        @ColumnInfo(name = "position_ms") val positionMs: Long,
    )

    /**
     * Up to [limit] of this phone's plays older than the cursor ([beforeAt], [beforeId]), newest first. The first page passes
     * [Long.MAX_VALUE] for both; the next passes the last row's `started_at` and `id`.
     */
    @Query(
        """
        SELECT le.id, t.title, t.artist, t.album, le.started_at, le.listened_ms, t.duration_ms
        FROM listening_events le
        JOIN tracks t ON t.id = le.track_id
        WHERE le.origin_device IS NULL
          AND le.started_at > 0
          AND le.started_at <= :beforeAt
          AND (le.started_at < :beforeAt OR le.id < :beforeId)
          AND TRIM(t.title) != '' AND TRIM(t.artist) != ''
          AND $NOT_BLOCKED
        ORDER BY le.started_at DESC, le.id DESC
        LIMIT :limit
        """,
    )
    suspend fun ownPlaysBefore(beforeAt: Long, beforeId: Long, limit: Int): List<ListenRow>

    /** Up to [limit] of this phone's skips older than the cursor, newest first ([ownPlaysBefore]'s paging). */
    @Query(
        """
        SELECT se.id, t.title, t.artist, t.album, se.skipped_at, se.position_ms
        FROM track_skip_events se
        JOIN tracks t ON t.id = se.track_id
        WHERE se.skipped_at > 0
          AND se.skipped_at <= :beforeAt
          AND (se.skipped_at < :beforeAt OR se.id < :beforeId)
          AND TRIM(t.title) != '' AND TRIM(t.artist) != ''
          AND $NOT_BLOCKED
        ORDER BY se.skipped_at DESC, se.id DESC
        LIMIT :limit
        """,
    )
    suspend fun ownSkipsBefore(beforeAt: Long, beforeId: Long, limit: Int): List<SkipRow>

    /** How many plays [ownPlaysBefore] pages through (the picker's "12,480 plays"). */
    @Query(
        """
        SELECT COUNT(*) FROM listening_events le
        JOIN tracks t ON t.id = le.track_id
        WHERE le.origin_device IS NULL AND le.started_at > 0
          AND TRIM(t.title) != '' AND TRIM(t.artist) != ''
          AND $NOT_BLOCKED
        """,
    )
    suspend fun ownPlayCount(): Int

    /** When the first of those plays started (the picker's "since March 2025"); null when there is none. */
    @Query(
        """
        SELECT MIN(le.started_at) FROM listening_events le
        JOIN tracks t ON t.id = le.track_id
        WHERE le.origin_device IS NULL AND le.started_at > 0
          AND TRIM(t.title) != '' AND TRIM(t.artist) != ''
          AND $NOT_BLOCKED
        """,
    )
    suspend fun ownFirstPlayAt(): Long?

    /** When the last of those plays started (Your record's import record: its last listen); null when there is none. */
    @Query(
        """
        SELECT MAX(le.started_at) FROM listening_events le
        JOIN tracks t ON t.id = le.track_id
        WHERE le.origin_device IS NULL AND le.started_at > 0
          AND TRIM(t.title) != '' AND TRIM(t.artist) != ''
          AND $NOT_BLOCKED
        """,
    )
    suspend fun ownLastPlayAt(): Long?

    /**
     * One artist and album of this phone's plays, totalled ([ownAlbumTotals]). Your record's Home card ranks albums and artists
     * from these (stash-player record-card.ts `fromOwn`), so a decade of plays comes back as one row per album, not a row per play.
     */
    data class OwnAlbumTotal(
        val artist: String,
        val album: String,
        /** One of its songs: the cover lookup's way in when the album isn't found by its name. */
        val title: String,
        /** A cover one of its songs has, when any does. */
        @ColumnInfo(name = "album_art_url") val albumArtUrl: String?,
        val plays: Int,
        /** Each play its song's length, else [GUESS_MS] (the web counts this browser's own plays so on its card). */
        @ColumnInfo(name = "listened_ms") val listenedMs: Long,
        @ColumnInfo(name = "first_at") val firstAt: Long,
        @ColumnInfo(name = "last_at") val lastAt: Long,
    )

    /**
     * The plays [ownPlaysBefore] pages through, totalled by the song's artist and album as written, the album played last
     * first (so a name spelled two ways is shown as it was spelled lately).
     */
    @Query(
        """
        SELECT t.artist AS artist, t.album AS album, MAX(t.title) AS title, MAX(t.album_art_url) AS album_art_url,
               COUNT(*) AS plays,
               SUM(CASE WHEN t.duration_ms > 0 THEN t.duration_ms ELSE $GUESS_MS END) AS listened_ms,
               MIN(le.started_at) AS first_at, MAX(le.started_at) AS last_at
        FROM listening_events le
        JOIN tracks t ON t.id = le.track_id
        WHERE le.origin_device IS NULL AND le.started_at > 0
          AND TRIM(t.title) != '' AND TRIM(t.artist) != ''
          AND $NOT_BLOCKED
        GROUP BY t.artist, t.album
        ORDER BY last_at DESC, t.artist ASC, t.album ASC
        """,
    )
    suspend fun ownAlbumTotals(): List<OwnAlbumTotal>

    /**
     * What the phone's own history looks like from far off: how many plays and skips, the newest of each, the time played,
     * and the blocklist's size. Any change to the plays, the skips or the blocklist changes it, so Your record's Home card
     * reads the history again only then ([ownHistoryStamp]), never just because Home came back.
     */
    data class OwnHistoryStamp(
        val plays: Int,
        @ColumnInfo(name = "last_play") val lastPlay: Long,
        @ColumnInfo(name = "listened_ms") val listenedMs: Long,
        val skips: Int,
        @ColumnInfo(name = "last_skip") val lastSkip: Long,
        val blocked: Int,
    )

    /**
     * [OwnHistoryStamp], again each time Room sees `listening_events`, `track_skip_events` or `track_blocklist` written (and
     * only those: a sync rewriting `tracks` doesn't wake it). Cheap: counts and sums over indexed tables, no join.
     */
    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM listening_events WHERE origin_device IS NULL) AS plays,
            (SELECT COALESCE(MAX(id), 0) FROM listening_events) AS last_play,
            (SELECT COALESCE(SUM(listened_ms), 0) FROM listening_events WHERE origin_device IS NULL) AS listened_ms,
            (SELECT COUNT(*) FROM track_skip_events) AS skips,
            (SELECT COALESCE(MAX(id), 0) FROM track_skip_events) AS last_skip,
            (SELECT COUNT(*) FROM track_blocklist) AS blocked
        """,
    )
    fun ownHistoryStamp(): Flow<OwnHistoryStamp>

    /**
     * Emits each time Room sees `tracks` written (the newest id, which a rename doesn't move: read it as "tracks changed", not
     * for its value). A song renamed, moved to another album or unblocked changes what Your record's card shows without
     * touching [ownHistoryStamp]. Cheap: one index seek. A sync writes `tracks` many times a second: the card's reader settles
     * and spaces these out.
     */
    @Query("SELECT COALESCE(MAX(id), 0) FROM tracks")
    fun tracksWritten(): Flow<Long>

    /** A song's words as a library track has them, and the longest length any track with those words has. */
    data class LibraryLength(
        @ColumnInfo(name = "title") val title: String,
        @ColumnInfo(name = "artist") val artist: String,
        @ColumnInfo(name = "duration_ms") val durationMs: Long,
    )

    /**
     * The library's song lengths, for a Last.fm scrobble's length in Your record (plays.ts `libraryLengths`, spec §7 step 2: the
     * web reads its likes, playlists and plays). The longest `duration_ms` by title and artist as written, of the tracks the
     * library holds: liked, in a playlist (any kind: a mix's song lasts as long as anywhere else; never one taken out), on the
     * phone, or played here. Kept to 1 s to 6 h, as the web keeps them: a length outside is broken. Grouped in the database, one
     * row per spelling; the caller folds them by song key.
     */
    @Query(
        """
        SELECT t.title AS title, t.artist AS artist, MAX(t.duration_ms) AS duration_ms FROM tracks t
        WHERE t.duration_ms BETWEEN 1000 AND 21600000
          AND (
              t.stash_liked_at IS NOT NULL
              OR t.is_downloaded = 1
              OR EXISTS (SELECT 1 FROM playlist_tracks pt WHERE pt.track_id = t.id AND pt.removed_at IS NULL)
              OR EXISTS (SELECT 1 FROM listening_events e WHERE e.track_id = t.id)
          )
        GROUP BY t.title, t.artist
        """,
    )
    suspend fun libraryLengths(): List<LibraryLength>

    /**
     * What the library here holds, in brief: the songs liked here, and the live songs of the listener's own playlists and of the
     * Liked Songs lists (count and id sum each), as [likes] and [playlists] read them. Daily Mixes, Stash Mixes and the Downloads list are left out: a sync or a mix
     * refresh rewrites them and changes nothing a library song is.
     */
    data class LibraryStamp(
        @ColumnInfo(name = "liked") val liked: Long,
        @ColumnInfo(name = "liked_ids") val likedIds: Long,
        @ColumnInfo(name = "listed") val listed: Long,
        @ColumnInfo(name = "listed_ids") val listedIds: Long,
    )

    /**
     * [LibraryStamp], again each time Room sees `tracks`, `playlists` or `playlist_tracks` written (the caller keeps only the
     * changes): Your record's mixes follow a song liked or added to one of your playlists, as the web's follow its library.
     */
    @Query(LIBRARY_STAMP)
    fun libraryStamp(): Flow<LibraryStamp>

    /** [libraryStamp] now (what the library read is kept by). */
    @Query(LIBRARY_STAMP)
    suspend fun libraryStampNow(): LibraryStamp

    companion object {
        /**
         * [LibraryStamp]'s query: the playlists [likes] and [playlists] read (Liked Songs lists, and your own CUSTOM ones while
         * active), with the songs liked here.
         */
        const val LIBRARY_STAMP = """
            SELECT
                (SELECT COUNT(*) FROM tracks WHERE stash_liked_at IS NOT NULL) AS liked,
                (SELECT COALESCE(SUM(id), 0) FROM tracks WHERE stash_liked_at IS NOT NULL) AS liked_ids,
                (SELECT COUNT(*) FROM playlist_tracks pt JOIN playlists p ON p.id = pt.playlist_id
                    WHERE pt.removed_at IS NULL AND ((p.type = 'CUSTOM' AND p.is_active = 1) OR p.type IN ('LIKED_SONGS', 'STASH_LIKED'))) AS listed,
                (SELECT COALESCE(SUM(pt.track_id), 0) FROM playlist_tracks pt JOIN playlists p ON p.id = pt.playlist_id
                    WHERE pt.removed_at IS NULL AND ((p.type = 'CUSTOM' AND p.is_active = 1) OR p.type IN ('LIKED_SONGS', 'STASH_LIKED'))) AS listed_ids
        """

        /** A play's length when its song's is unknown: about three and a half minutes (the web's `LASTFM_GUESS_MS`). */
        const val GUESS_MS = 210_000L

        /** [SongColumns] of a track (`t`). */
        const val SONG_COLUMNS =
            "t.id, t.title, t.artist, t.album, t.duration_ms, t.isrc, t.spotify_uri, t.youtube_id, t.album_art_url, t.date_added"

        /** A track (`t`) the blocklist does not match. */
        const val NOT_BLOCKED = """NOT EXISTS (
            SELECT 1 FROM track_blocklist bl
            WHERE bl.canonical_key = (t.canonical_artist || '|' || t.canonical_title)
               OR (bl.spotify_uri IS NOT NULL AND bl.spotify_uri = t.spotify_uri)
               OR (bl.youtube_id IS NOT NULL AND bl.youtube_id = t.youtube_id)
        )"""
    }
}
