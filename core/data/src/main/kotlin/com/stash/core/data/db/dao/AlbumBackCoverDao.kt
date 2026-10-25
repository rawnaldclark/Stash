package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stash.core.data.db.entity.AlbumBackCoverEntity
import kotlinx.coroutines.flow.Flow

/** The album back covers Now Playing flips to. Written only by `AlbumBackCoverRepository`. */
@Dao
interface AlbumBackCoverDao {

    /** One album's row, or null when it has never been looked up. */
    @Query("SELECT * FROM album_back_covers WHERE album_key = :albumKey COLLATE NOCASE")
    fun observe(albumKey: String): Flow<AlbumBackCoverEntity?>

    @Query("SELECT * FROM album_back_covers WHERE album_key = :albumKey COLLATE NOCASE")
    suspend fun get(albumKey: String): AlbumBackCoverEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: AlbumBackCoverEntity)

    /**
     * Forgets what came from Discogs in rows looked up before [before] (Discogs' terms: its data
     * kept no longer than six hours). The archive's part stays; the row is left to be looked up
     * again (attempted at the epoch, older than any TTL; a Discogs inside back to "not looked
     * for yet").
     */
    @Query(
        """
        UPDATE album_back_covers SET
            image_url = CASE WHEN back_from_discogs THEN NULL ELSE image_url END,
            release_mbid = CASE WHEN back_from_discogs THEN NULL ELSE release_mbid END,
            inside_urls = CASE WHEN inside_from_discogs THEN NULL ELSE inside_urls END,
            back_from_discogs = 0,
            inside_from_discogs = 0,
            discogs_page = NULL,
            attempted_at = 0
        WHERE (back_from_discogs OR inside_from_discogs) AND attempted_at < :before
        """,
    )
    suspend fun forgetDiscogsBefore(before: Long)
}
