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
import com.stash.core.data.db.entity.CloudUploadEntity
import com.stash.core.data.db.entity.CloudUploadState
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import kotlinx.coroutines.flow.Flow

/** One cloud folder with how many songs its scan has found, for Settings. */
data class CloudFolderWithCount(
    val id: Long,
    @ColumnInfo(name = "account_id") val accountId: Long,
    /** The provider's id for the folder (Google Drive: "picked", or "stash-uploads" for saved songs). */
    @ColumnInfo(name = "item_id") val itemId: String,
    val name: String,
    val path: String,
    @ColumnInfo(name = "last_scan_at") val lastScanAt: Long?,
    @ColumnInfo(name = "last_scan_status") val lastScanStatus: String?,
    @ColumnInfo(name = "last_scan_error") val lastScanError: String?,
    @ColumnInfo(name = "track_count") val trackCount: Int,
)

/** A download not linked to any cloud file, with what its Save to Drive track key is made of. */
data class UnlinkedDownload(
    val id: Long,
    val isrc: String?,
    val artist: String,
    val title: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
)

/** How many of an account's upload rows are in one [CloudUploadState]. */
data class CloudUploadCount(val state: String, val count: Int)

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
        SELECT f.id, f.account_id, f.item_id, f.name, f.path, f.last_scan_at, f.last_scan_status, f.last_scan_error,
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

    // The account's cloud songs, for the disconnect dialog. Only `CLOUD` tracks: a local song
    // linked to the copy Stash saved in Google Drive is never removed by a disconnect.

    @Query("SELECT COUNT(*) FROM cloud_items i INNER JOIN tracks t ON t.id = i.track_id WHERE i.account_id = :accountId AND t.source = 'CLOUD'")
    suspend fun countItems(accountId: Long): Int

    @Query(
        "SELECT COUNT(*) FROM cloud_items i INNER JOIN tracks t ON t.id = i.track_id " +
            "WHERE i.account_id = :accountId AND t.source = 'CLOUD' AND t.is_downloaded = 1",
    )
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

    /** The provider ids of a folder's files (Google Drive: the picked songs already scanned). */
    @Query("SELECT item_id FROM cloud_items WHERE folder_id = :folderId")
    suspend fun folderItemIds(folderId: Long): List<String>

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

    // ── Uploads: Save to Google Drive / Back up my downloads (v62) ───────
    //
    // A song can be uploaded when it is a download on this phone (a file Stash wrote) and not a
    // cloud song itself ([UPLOADABLE]). Cloud songs are already in the user's cloud, and their one
    // `cloud_items` row is taken by the file they came from.

    @Query("SELECT * FROM cloud_uploads WHERE track_id = :trackId")
    suspend fun getUpload(trackId: Long): CloudUploadEntity?

    /** REPLACE is safe here: nothing references an upload row. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putUpload(row: CloudUploadEntity)

    @Update
    suspend fun updateUpload(row: CloudUploadEntity)

    /** The oldest queued upload: the worker's next song. */
    @Query("SELECT * FROM cloud_uploads WHERE state = 'QUEUED' ORDER BY queued_at ASC, track_id ASC LIMIT 1")
    suspend fun nextQueuedUpload(): CloudUploadEntity?

    @Query("SELECT COUNT(*) FROM cloud_uploads WHERE state = 'QUEUED'")
    suspend fun countQueuedUploads(): Int

    // The worker writes single columns, never a whole row it read earlier: the user may have
    // queued, unqueued or re-saved the song meanwhile.

    /** A new resumable session for [trackId] (null clears it: the old one expired). */
    @Query(
        """
        UPDATE cloud_uploads SET session_uri = :uri, session_size = :size, session_md5 = :md5,
            bytes_sent = 0, updated_at = :now
        WHERE track_id = :trackId
        """,
    )
    suspend fun setUploadSession(trackId: Long, uri: String?, size: Long?, md5: String?, now: Long)

    /** Bytes Drive has committed so far (progress, and where a resume would start). */
    @Query("UPDATE cloud_uploads SET bytes_sent = :bytes, updated_at = :now WHERE track_id = :trackId")
    suspend fun setUploadProgress(trackId: Long, bytes: Long, now: Long)

    /** The song is done with, one way or another ([state] DONE or SKIPPED); its session is spent. */
    @Query(
        """
        UPDATE cloud_uploads SET state = :state, drive_file_id = COALESCE(:fileId, drive_file_id), error = :error,
            session_uri = NULL, session_size = NULL, session_md5 = NULL, updated_at = :now
        WHERE track_id = :trackId
        """,
    )
    suspend fun finishUpload(trackId: Long, state: String, fileId: String?, error: String?, now: Long)

    /**
     * One more failed try for [trackId]: it goes to the back of the queue, and after
     * [maxAttempts] tries it is FAILED (the card counts it; saving it again starts over).
     */
    @Query(
        """
        UPDATE cloud_uploads SET attempts = attempts + 1, error = :error, queued_at = :now, updated_at = :now,
            state = CASE WHEN attempts + 1 >= :maxAttempts THEN 'FAILED' ELSE state END
        WHERE track_id = :trackId
        """,
    )
    suspend fun failUploadAttempt(trackId: Long, error: String, maxAttempts: Int, now: Long)

    @Query("SELECT state, COUNT(*) AS count FROM cloud_uploads WHERE account_id = :accountId GROUP BY state")
    fun observeUploadCounts(accountId: Long): Flow<List<CloudUploadCount>>

    /** Of [trackIds], the ones that can be uploaded (downloaded, file on record, not a cloud song). */
    @Query("SELECT id FROM tracks WHERE id IN (:trackIds) AND $UPLOADABLE")
    suspend fun uploadableTrackIds(trackIds: List<Long>): List<Long>

    /**
     * Gives every uploadable download without an upload row one, in [state]: `EXISTING` when the
     * backup switch is turned on (they are only counted, for "Back up N existing downloads"),
     * `QUEUED` for downloads made while it is on. Rows that exist are left alone.
     */
    @Query(
        """
        INSERT OR IGNORE INTO cloud_uploads (track_id, account_id, state, origin, queued_at, updated_at, bytes_sent, attempts)
        SELECT id, :accountId, :state, 'BACKUP', :now, :now, 0, 0 FROM tracks WHERE $UPLOADABLE
        """,
    )
    suspend fun insertUploadsForDownloads(accountId: Long, state: String, now: Long)

    /**
     * Downloads with no cloud link, for the Drive scan to recognise a song Stash saved (from this
     * phone before a disconnect, or from another phone) as one already here, instead of adding it
     * a second time.
     */
    @Query("SELECT id, isrc, artist, title, duration_ms FROM tracks WHERE $UPLOADABLE AND NOT EXISTS (SELECT 1 FROM cloud_items i WHERE i.track_id = tracks.id)")
    suspend fun unlinkedDownloads(): List<UnlinkedDownload>

    /** How many uploadable downloads have no upload row: what the backup switch watches for. */
    @Query("SELECT COUNT(*) FROM tracks WHERE $UPLOADABLE AND NOT EXISTS (SELECT 1 FROM cloud_uploads u WHERE u.track_id = tracks.id)")
    fun observeDownloadsWithoutUpload(): Flow<Int>

    /** "Back up N existing downloads": queues them all. */
    @Query("UPDATE cloud_uploads SET state = 'QUEUED', origin = 'BACKUP', queued_at = :now, updated_at = :now WHERE state = 'EXISTING'")
    suspend fun queueExistingUploads(now: Long): Int

    /** The backup switch turned off: what it queued goes back to waiting (the user's own saves carry on). */
    @Query("UPDATE cloud_uploads SET state = 'EXISTING', updated_at = :now WHERE state = 'QUEUED' AND origin = 'BACKUP'")
    suspend fun unqueueBackupUploads(now: Long): Int

    /**
     * Queues [trackIds] (already checked uploadable) for [accountId]. A song already queued stays
     * as it is; any other row (saved before, skipped, failed, waiting) is queued again: the worker
     * finds a copy still in Drive by its tag and only links it again.
     *
     * @return how many were newly queued.
     */
    @Transaction
    suspend fun queueUploads(trackIds: List<Long>, accountId: Long, origin: String, now: Long): Int {
        var queued = 0
        for (id in trackIds) {
            val row = getUpload(id)
            when {
                row == null -> putUpload(
                    CloudUploadEntity(
                        trackId = id, accountId = accountId, state = CloudUploadState.QUEUED.name,
                        origin = origin, queuedAt = now, updatedAt = now,
                    ),
                )
                row.state == CloudUploadState.QUEUED.name -> continue
                else -> updateUpload(
                    row.copy(
                        state = CloudUploadState.QUEUED.name, origin = origin, queuedAt = now, updatedAt = now,
                        attempts = 0, error = null, accountId = accountId,
                    ).let {
                        // A session belongs to the account that opened it.
                        if (row.accountId == accountId) it
                        else it.copy(sessionUri = null, sessionSize = null, sessionMd5 = null, bytesSent = 0)
                    },
                )
            }
            queued++
        }
        return queued
    }

    /**
     * Links the Drive file in [item] (just uploaded, or found already there) to the local track
     * [trackId], so the Drive scan treats that file as this song and never files it as a second
     * one. The track keeps playing its own file.
     *
     * When the file already has a row for another track: a stream-only cloud song nobody has
     * used yet (this phone's scan filed the upload a moment before it was linked) gives way to the
     * local track; anything else (a downloaded cloud song, one in a playlist, liked or played,
     * another local song) keeps the file, and nothing is linked (returns false): no one's likes,
     * plays or playlists are ever lost to a link.
     *
     * The row's `seen_generation` is the folder's generation read here, inside the transaction,
     * so a full pass that started meanwhile can't sweep the link it just saw.
     */
    @Transaction
    suspend fun linkUpload(trackId: Long, item: CloudItemEntity): Boolean {
        getItem(item.accountId, item.itemId)?.let { other ->
            if (other.trackId != trackId) {
                val track = getTrack(other.trackId)
                if (track != null && (track.source != MusicSource.CLOUD || track.isDownloaded || hasUserData(track.id))) return false
                removeItems(listOf(other.trackId))
            }
        }
        val generation = getFolder(item.folderId)?.scanGeneration ?: item.seenGeneration
        return linkItem(trackId, item.copy(seenGeneration = generation))
    }

    /**
     * True when [trackId] is in a playlist, liked, or has been played: a song that must not be
     * deleted to make room for a link.
     */
    @Query(
        """
        SELECT EXISTS(SELECT 1 FROM playlist_tracks WHERE track_id = :trackId)
            OR EXISTS(SELECT 1 FROM listening_events WHERE track_id = :trackId)
            OR EXISTS(SELECT 1 FROM tracks WHERE id = :trackId AND (stash_liked_at IS NOT NULL OR play_count > 0))
        """,
    )
    suspend fun hasUserData(trackId: Long): Boolean

    /** [linkUpload]'s second half: points [trackId]'s link at [item]'s file, which no other row holds now. */
    suspend fun linkItem(trackId: Long, item: CloudItemEntity): Boolean {
        val mine = getItemForTrack(trackId)
        when {
            mine == null -> insertItem(item.copy(trackId = trackId))
            mine.accountId == item.accountId && mine.itemId == item.itemId -> updateItem(item.copy(trackId = trackId))
            else -> {
                // Linked to an earlier upload (trashed since): the new file takes its place.
                deleteItemRows(listOf(trackId))
                insertItem(item.copy(trackId = trackId))
            }
        }
        return true
    }

    companion object {
        /** Below SQLite's 999 bind-variable limit with room to spare. */
        const val BIND_CHUNK = 500

        /** A `tracks` row Stash can upload: a download with its file on record, not a cloud song. */
        const val UPLOADABLE = "is_downloaded = 1 AND file_path IS NOT NULL AND source != 'CLOUD'"
    }
}
