package com.stash.core.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.stash.core.data.db.entity.ScanFolderEntity
import com.stash.core.data.db.entity.ScannedSongEntity
import kotlinx.coroutines.flow.Flow

/** What a rescan needs to know about a song it already has, without loading its tags. */
data class ScannedSongStamp(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "uri") val uri: String,
    @ColumnInfo(name = "file_size_bytes") val fileSizeBytes: Long,
    @ColumnInfo(name = "last_modified") val lastModified: Long,
    @ColumnInfo(name = "date_added") val dateAdded: Long,
)

/** A row and the cover file name it points at. */
data class ScannedSongArtRef(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "art") val art: String,
)

/** A row whose cover hasn't been looked for yet (see ScannedSongEntity.albumArtPath). */
data class ScannedSongArtTarget(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "uri") val uri: String,
)

@Dao
interface ScannedSongDao {
    @Query("SELECT * FROM scan_folders ORDER BY added_at")
    fun observeFolders(): Flow<List<ScanFolderEntity>>

    @Query("SELECT * FROM scan_folders ORDER BY added_at")
    suspend fun getFolders(): List<ScanFolderEntity>

    @Query("SELECT * FROM scan_folders WHERE tree_uri = :treeUri LIMIT 1")
    suspend fun getFolderByTreeUri(treeUri: String): ScanFolderEntity?

    /** Returns -1 when the tree is already registered. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolder(folder: ScanFolderEntity): Long

    @Query("DELETE FROM scan_folders WHERE id = :id")
    suspend fun deleteFolder(id: Long)

    @Query("SELECT * FROM scanned_songs ORDER BY title COLLATE NOCASE")
    fun observeSongs(): Flow<List<ScannedSongEntity>>

    @Query("SELECT id, uri, file_size_bytes, last_modified, date_added FROM scanned_songs WHERE folder_id = :folderId")
    suspend fun getStamps(folderId: Long): List<ScannedSongStamp>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongs(songs: List<ScannedSongEntity>)

    /** Re-read tags for files that changed on disk; rows keep their ids. */
    @Update
    suspend fun updateSongs(songs: List<ScannedSongEntity>)

    @Query("DELETE FROM scanned_songs WHERE folder_id = :folderId AND uri IN (:uris)")
    suspend fun deleteByUris(folderId: Long, uris: List<String>)

    /** For files the user deleted from All Songs. A uri is unique across folders. */
    @Query("DELETE FROM scanned_songs WHERE uri IN (:uris)")
    suspend fun deleteByUrisAnyFolder(uris: List<String>)

    @Query("SELECT id, uri FROM scanned_songs WHERE folder_id = :folderId AND album_art_path IS NULL")
    suspend fun getUnarted(folderId: Long): List<ScannedSongArtTarget>

    @Query("UPDATE scanned_songs SET album_art_path = :path WHERE id = :id")
    suspend fun setAlbumArtPath(id: Long, path: String)

    @Query("SELECT id, album_art_path AS art FROM scanned_songs WHERE folder_id = :folderId AND album_art_path IS NOT NULL AND album_art_path != ''")
    suspend fun getArtRefs(folderId: Long): List<ScannedSongArtRef>

    /** Back to "not looked at yet": the cover file is gone. */
    @Query("UPDATE scanned_songs SET album_art_path = NULL WHERE id IN (:ids)")
    suspend fun clearAlbumArt(ids: List<Long>)

    /** Every cover file name a row still points at (for the sweep). */
    @Query("SELECT album_art_path FROM scanned_songs WHERE album_art_path IS NOT NULL AND album_art_path != ''")
    suspend fun getArtPaths(): List<String>
}