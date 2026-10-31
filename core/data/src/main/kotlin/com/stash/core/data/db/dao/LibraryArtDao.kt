package com.stash.core.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query

/**
 * The library's covers by words, for Your record (feature:record `LibraryArtIndex`): the songs that have a cover of their own
 * (a file here, or a remote address), as the few columns a cover is found by. Read once per record opened. Nothing here writes.
 */
@Dao
interface LibraryArtDao {

    /** A track's words and its cover: a file here ([path], absolute), else a remote address ([url]). */
    data class TrackArt(
        val title: String,
        val artist: String,
        @ColumnInfo(name = "album_artist") val albumArtist: String,
        val album: String,
        @ColumnInfo(name = "album_art_path") val path: String?,
        @ColumnInfo(name = "album_art_url") val url: String?,
    )

    /** A scanned song's words and its embedded cover's file name (in `filesDir/scanned_art/`). */
    data class ScannedArt(
        val title: String,
        val artist: String,
        val album: String,
        @ColumnInfo(name = "album_art_path") val name: String,
    )

    /** Every track with a cover, oldest row first. */
    @Query(
        """
        SELECT title, artist, album_artist, album, album_art_path, album_art_url FROM tracks
        WHERE (album_art_path IS NOT NULL AND album_art_path != '') OR (album_art_url IS NOT NULL AND album_art_url != '')
        ORDER BY id
        """,
    )
    suspend fun trackArt(): List<TrackArt>

    /** Every scanned song with an embedded cover ("" is none, NULL not looked at yet), oldest row first. */
    @Query(
        """
        SELECT title, artist, album, album_art_path FROM scanned_songs
        WHERE album_art_path IS NOT NULL AND album_art_path != ''
        ORDER BY id
        """,
    )
    suspend fun scannedArt(): List<ScannedArt>
}
