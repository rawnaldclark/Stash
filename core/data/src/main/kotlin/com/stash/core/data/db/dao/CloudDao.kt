package com.stash.core.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.stash.core.data.db.entity.CloudAccountEntity
import com.stash.core.data.db.entity.CloudFolderEntity
import com.stash.core.data.db.entity.CloudItemEntity
import com.stash.core.data.db.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

/** One cloud folder with how many songs its scan has found, for Settings. */
data class CloudFolderWithCount(
    val id: Long,
    @ColumnInfo(name = "account_id") val accountId: Long,
    val name: String,
    val path: String,
    @ColumnInfo(name = "last_scan_at") val lastScanAt: Long?,
    @ColumnInfo(name = "last_scan_status") val lastScanStatus: String?,
    @ColumnInfo(name = "last_scan_error") val lastScanError: String?,
    @ColumnInfo(name = "track_count") val trackCount: Int,
)

/** What removing cloud songs did: rows deleted, and downloaded songs kept as local files. */
data class CloudRemoval(val deletedTracks: Int, val keptAsLocal: Int)

/**
 * Cloud storage accounts, picked folders and the scanned items behind `MusicSource.CLOUD`
 * tracks (spec 2026-10-19-cloud-storage-design.md).
 *
 * Removing cloud songs always goes through [removeItems]-style transactions here, never a bare
 * delete of an account or folder: `cloud_items` CASCADE from both, but the `tracks` rows they
 * point at do not, so a bare delete would leave cloud songs nothing can play. A song the user
 * downloaded survives as a LOCAL file (its audio is on the phone and may exist nowhere else
 * once the cloud copy is gone); one that only streamed is deleted.
 */
@Dao
interface CloudDao {

    // ── Accounts ─────────────────────────────────────────────────────────

    @Query("SELECT * FROM cloud_accounts ORDER BY connected_at ASC")
    fun observeAccounts(): Flow<List<CloudAccountEntity>>

    @Query("SELECT * FROM cloud_accounts ORDER BY connected_at ASC")
    suspend fun getAccounts(): List<CloudAccountEntity>

    @Query("SELECT * FROM cloud_accounts WHERE id = :id")
    suspend fun getAccount(id: Long): CloudAccountEntity?

    @Query("SELECT * FROM cloud_accounts WHERE provider = :provider AND remote_id = :remoteId")
    suspend fun findAccount(provider: String, remoteId: String): CloudAccountEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAccount(account: CloudAccountEntity): Long

    @Update
    suspend fun updateAccount(account: CloudAccountEntity)

    @Query("DELETE FROM cloud_accounts WHERE id = :id")
    suspend fun deleteAccountRow(id: Long)

    // ── Folders ──────────────────────────────────────────────────────────

    @Query(
        """
        SELECT f.id, f.account_id, f.name, f.path, f.last_scan_at, f.last_scan_status, f.last_scan_error,
               (SELECT COUNT(*) FROM cloud_items i WHERE i.folder_id = f.id) AS track_count
        FROM cloud_folders f
        ORDER BY f.account_id ASC, f.path ASC
        """,
    )
    fun observeFolders(): Flow<List<CloudFolderWithCount>>

    @Query("SELECT * FROM cloud_folders WHERE account_id = :accountId ORDER BY path ASC")
    suspend fun getFolders(accountId: Long): List<CloudFolderEntity>

    @Query("SELECT * FROM cloud_folders ORDER BY account_id ASC, path ASC")
    suspend fun getAllFolders(): List<CloudFolderEntity>

    @Query("SELECT * FROM cloud_folders WHERE id = :id")
    suspend fun getFolder(id: Long): CloudFolderEntity?

    /** -1 when the folder is already picked (unique account + item). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolder(folder: CloudFolderEntity): Long

    @Update
    suspend fun updateFolder(folder: CloudFolderEntity)

    @Query("DELETE FROM cloud_folders WHERE id = :id")
    suspend fun deleteFolderRow(id: Long)

    // ── Items ────────────────────────────────────────────────────────────

    @Query("SELECT * FROM cloud_items WHERE account_id = :accountId AND item_id = :itemId")
    suspend fun getItem(accountId: Long, itemId: String): CloudItemEntity?

    @Query("SELECT * FROM cloud_items WHERE track_id = :trackId")
    suspend fun getItemForTrack(trackId: Long): CloudItemEntity?

    /**
     * ABORT, never REPLACE: a REPLACE on the (account_id, item_id) index would silently drop
     * another row for the same file and orphan its track (nothing could remove it after).
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItem(item: CloudItemEntity)

    @Update
    suspend fun updateItem(item: CloudItemEntity)

    /**
     * Where an unchanged file sits now. A rename or move keeps the file's id and content
     * version, so the scan sees it as unchanged; its path and parent must still follow it, or a
     * later deletion of its OLD folder (by path or by parent id) would take it along.
     */
    @Query(
        """
        UPDATE cloud_items SET folder_id = :folderId, name = :name, relative_path = :relativePath,
            parent_item_id = :parentItemId, remote_path = :remotePath,
            seen_generation = :generation, scanned_at = :now
        WHERE track_id = :trackId
        """,
    )
    suspend fun refreshLocation(
        trackId: Long,
        folderId: Long,
        name: String,
        relativePath: String,
        parentItemId: String?,
        remotePath: String?,
        generation: Long,
        now: Long,
    )

    /** Marks an unchanged item as seen by this pass, so the pass's stale sweep keeps it. */
    @Query("UPDATE cloud_items SET seen_generation = :generation, scanned_at = :now WHERE track_id = :trackId")
    suspend fun markSeen(trackId: Long, generation: Long, now: Long)

    @Query("SELECT COUNT(*) FROM cloud_items WHERE account_id = :accountId")
    suspend fun countItems(accountId: Long): Int

    @Query("SELECT COUNT(*) FROM cloud_items i INNER JOIN tracks t ON t.id = i.track_id WHERE i.account_id = :accountId AND t.is_downloaded = 1")
    suspend fun countDownloadedItems(accountId: Long): Int

    // ── Tracks the scan writes ───────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTrack(track: TrackEntity): Long

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getTrack(id: Long): TrackEntity?

    /**
     * Rewrites what the scan owns on a cloud track whose file changed: names, format, art,
     * duration, size, and the stream address (its `?v=` moves with the cTag). Leaves alone
     * everything the user or the player owns: likes, plays, download state, playlists.
     */
    @Query(
        """
        UPDATE tracks SET
            title = :title, artist = :artist, album = :album, album_artist = :albumArtist,
            duration_ms = :durationMs, file_format = :fileFormat, quality_kbps = :qualityKbps,
            bits_per_sample = :bitsPerSample, sample_rate_hz = :sampleRateHz,
            album_art_path = COALESCE(:albumArtPath, album_art_path),
            cloud_uri = :cloudUri,
            file_size_bytes = CASE WHEN is_downloaded = 1 THEN file_size_bytes ELSE :sizeBytes END
        WHERE id = :trackId AND source = 'CLOUD'
        """,
    )
    suspend fun updateScannedTrack(
        trackId: Long,
        title: String,
        artist: String,
        album: String,
        albumArtist: String,
        durationMs: Long,
        fileFormat: String,
        qualityKbps: Int,
        bitsPerSample: Int?,
        sampleRateHz: Int?,
        albumArtPath: String?,
        cloudUri: String,
        sizeBytes: Long,
    )

    /**
     * A new cloud song: its track row and its item row, together or not at all. If the file
     * already has a row (another scan of the same folder got there first), nothing is inserted
     * and that row's track id comes back: one file is never two library songs.
     */
    @Transaction
    suspend fun insertScanned(track: TrackEntity, item: CloudItemEntity): Long {
        getItem(item.accountId, item.itemId)?.let { return it.trackId }
        val id = insertTrack(track)
        insertItem(item.copy(trackId = id))
        return id
    }

    /** Sibling cover art found after the songs: only fills songs that have none. */
    @Query("UPDATE tracks SET album_art_path = :path WHERE id IN (:trackIds) AND album_art_path IS NULL")
    suspend fun setArtIfMissing(trackIds: List<Long>, path: String)

    /** The songs directly in the cloud folder [parentItemId] (a deleted OneDrive folder reports only itself). */
    @Query("SELECT * FROM cloud_items WHERE account_id = :accountId AND parent_item_id = :parentItemId")
    suspend fun itemsInParent(accountId: Long, parentItemId: String): List<CloudItemEntity>

    // ── Removal (see the class KDoc) ─────────────────────────────────────

    @Query("DELETE FROM tracks WHERE is_downloaded = 0 AND source = 'CLOUD' AND id IN (:trackIds)")
    suspend fun deleteStreamOnlyTracks(trackIds: List<Long>): Int

    @Query("UPDATE tracks SET source = 'LOCAL', cloud_uri = NULL WHERE source = 'CLOUD' AND id IN (:trackIds)")
    suspend fun keepAsLocal(trackIds: List<Long>): Int

    @Query("DELETE FROM cloud_items WHERE track_id IN (:trackIds)")
    suspend fun deleteItemRows(trackIds: List<Long>)

    @Query("SELECT track_id FROM cloud_items WHERE folder_id = :folderId AND seen_generation < :generation")
    suspend fun staleTrackIds(folderId: Long, generation: Long): List<Long>

    @Query("SELECT track_id FROM cloud_items WHERE folder_id = :folderId")
    suspend fun folderTrackIds(folderId: Long): List<Long>

    /**
     * The songs in [folderId] at [path] or under it (a deleted Dropbox entry may be a whole
     * folder). [path] is lower-cased like `remote_path`. `substr` instead of LIKE, so a path
     * holding `%` or `_` matches only itself.
     */
    @Query(
        """
        SELECT * FROM cloud_items
        WHERE folder_id = :folderId
          AND (remote_path = :path OR substr(remote_path, 1, length(:path) + 1) = :path || '/')
        """,
    )
    suspend fun itemsUnderPath(folderId: Long, path: String): List<CloudItemEntity>

    @Query("SELECT track_id FROM cloud_items WHERE account_id = :accountId")
    suspend fun accountTrackIds(accountId: Long): List<Long>

    /**
     * Removes the cloud songs [trackIds]: deletes the ones that only streamed, turns the
     * downloaded ones into LOCAL files, drops their `cloud_items` rows. Chunked under SQLite's
     * bind limit; one transaction.
     */
    @Transaction
    suspend fun removeItems(trackIds: List<Long>): CloudRemoval {
        var deleted = 0
        var kept = 0
        trackIds.chunked(BIND_CHUNK).forEach { chunk ->
            deleted += deleteStreamOnlyTracks(chunk)
            kept += keepAsLocal(chunk)
            deleteItemRows(chunk)
        }
        return CloudRemoval(deleted, kept)
    }

    /** The end of a completed full pass: everything it didn't see is gone from the cloud. */
    @Transaction
    suspend fun removeStale(folderId: Long, generation: Long): CloudRemoval =
        removeItems(staleTrackIds(folderId, generation))

    /** Unpicks a folder and removes its songs (downloaded ones stay, as LOCAL files). */
    @Transaction
    suspend fun removeFolder(folderId: Long): CloudRemoval {
        val removal = removeItems(folderTrackIds(folderId))
        deleteFolderRow(folderId)
        return removal
    }

    /** Disconnect: removes the account, its folders and its songs (downloaded ones stay, as LOCAL files). */
    @Transaction
    suspend fun removeAccount(accountId: Long): CloudRemoval {
        val removal = removeItems(accountTrackIds(accountId))
        deleteAccountRow(accountId)
        return removal
    }

    companion object {
        /** Below SQLite's 999 bind-variable limit with room to spare. */
        const val BIND_CHUNK = 500
    }
}
