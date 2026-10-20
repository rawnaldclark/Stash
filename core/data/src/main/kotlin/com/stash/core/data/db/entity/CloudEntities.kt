package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v61: a connected cloud storage account, Dropbox or OneDrive (spec 2026-10-19-cloud-storage-design.md).
 *
 * Holds only who the account is. Its OAuth tokens live in the Tink-encrypted token store under
 * this row's [id], never in the database, so a restored backup brings the account back without
 * its login (the user reconnects), the same rule every other login follows.
 */
@Entity(
    tableName = "cloud_accounts",
    indices = [Index(value = ["provider", "remote_id"], unique = true)],
)
data class CloudAccountEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** [com.stash.core.model.CloudProvider.id], e.g. "onedrive". */
    val provider: String,
    /** The provider's id for the user (Graph `/me` id). */
    @ColumnInfo(name = "remote_id")
    val remoteId: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    /** The sign-in name shown under the account (mail or user principal name). */
    val email: String? = null,
    @ColumnInfo(name = "connected_at")
    val connectedAt: Long,
)

/**
 * v61: a folder the user picked as music in one cloud account, and the state of its scan.
 *
 * The scan is resumable: [scanCursor] is the next page to fetch mid-scan (an `@odata.nextLink`)
 * or, once a pass completed, the `@odata.deltaLink` the next pass starts from
 * ([cursorIsDelta] = true). A full pass (first scan, or after the provider answered 410 for an
 * expired delta link) bumps [scanGeneration] and stamps every item it sees; when it completes,
 * items still carrying an older generation are gone from the cloud and are removed.
 */
@Entity(
    tableName = "cloud_folders",
    foreignKeys = [
        ForeignKey(
            entity = CloudAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["account_id", "item_id"], unique = true)],
)
data class CloudFolderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "account_id")
    val accountId: Long,
    /** The provider's id for the folder. */
    @ColumnInfo(name = "item_id")
    val itemId: String,
    val name: String,
    /** Where the folder sits, for display ("/Music/Rips"). */
    val path: String,
    @ColumnInfo(name = "added_at")
    val addedAt: Long,
    @ColumnInfo(name = "scan_cursor")
    val scanCursor: String? = null,
    @ColumnInfo(name = "cursor_is_delta", defaultValue = "0")
    val cursorIsDelta: Boolean = false,
    /** True while a full pass is in progress (it must finish before stale items are removed). */
    @ColumnInfo(name = "full_scan", defaultValue = "1")
    val fullScan: Boolean = true,
    @ColumnInfo(name = "scan_generation", defaultValue = "0")
    val scanGeneration: Long = 0,
    @ColumnInfo(name = "last_scan_at")
    val lastScanAt: Long? = null,
    /** [CloudScanStatus] name. */
    @ColumnInfo(name = "last_scan_status")
    val lastScanStatus: String? = null,
    @ColumnInfo(name = "last_scan_error")
    val lastScanError: String? = null,
)

/** What the last scan of a [CloudFolderEntity] did. */
// WAITING = waiting for the network rule (Wi-Fi) or a reconnect.
enum class CloudScanStatus { RUNNING, OK, ERROR, WAITING }

/**
 * v61: the cloud side of a [com.stash.core.model.MusicSource.CLOUD] track: which file it is and
 * what the scan last saw of it. One row per `tracks` row ([trackId] is both key and FK, so
 * deleting the track drops this row). The scan compares [cTag] to decide whether to read the
 * file's tags again.
 */
@Entity(
    tableName = "cloud_items",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["track_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CloudAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CloudFolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["account_id", "item_id"], unique = true),
        Index(value = ["folder_id", "seen_generation"]),
    ],
)
data class CloudItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "track_id")
    val trackId: Long,
    @ColumnInfo(name = "account_id")
    val accountId: Long,
    @ColumnInfo(name = "folder_id")
    val folderId: Long,
    @ColumnInfo(name = "item_id")
    val itemId: String,
    /** The file name, extension included. */
    val name: String,
    /** Folder names below the picked folder, joined with '/' ("Artist/Album"); empty at its top. */
    @ColumnInfo(name = "relative_path")
    val relativePath: String,
    @ColumnInfo(name = "parent_item_id")
    val parentItemId: String?,
    @ColumnInfo(name = "c_tag")
    val cTag: String?,
    @ColumnInfo(name = "e_tag")
    val eTag: String?,
    val size: Long,
    @ColumnInfo(name = "mime_type")
    val mimeType: String?,
    @ColumnInfo(name = "seen_generation")
    val seenGeneration: Long,
    @ColumnInfo(name = "scanned_at")
    val scannedAt: Long,
    /**
     * The file's full path in the cloud, lower-cased, when the provider reports changes by path
     * (Dropbox's `deleted` entries carry no id, only `path_lower`). Null for OneDrive.
     */
    @ColumnInfo(name = "remote_path")
    val remotePath: String? = null,
)

/**
 * Where one downloaded song stands in "Save to Google Drive" / "Back up my downloads"
 * (v62). [EXISTING] rows are downloads that existed when backup was turned on: not queued, only
 * counted for "Back up N existing downloads".
 */
enum class CloudUploadState { EXISTING, QUEUED, DONE, SKIPPED, FAILED }

/** Why a row was queued: the user's tap on one song/album/playlist, or the backup switch. */
enum class CloudUploadOrigin { MANUAL, BACKUP }

/**
 * v62: one row per local track that is (or was) on its way to the user's Google Drive.
 * [trackId] is both key and FK, so a track is never uploaded twice and deleting it drops the row;
 * [accountId] CASCADEs too, so disconnecting Drive clears every pending upload (the files already
 * in Drive are not touched).
 *
 * The resumable session lives here so an upload carries on after a lost connection or process
 * death: [sessionUri] (Drive's `upload_id` address, valid about a week) is only reused while the
 * local file still has [sessionSize] bytes and md5 [sessionMd5]; otherwise a new session starts.
 * [bytesSent] is the server's last committed offset, for progress.
 *
 * Once uploaded, [driveFileId] names the Drive file; the track's `cloud_items` row (folder "Saved
 * from Stash") is what links them for the scanner.
 */
@Entity(
    tableName = "cloud_uploads",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["track_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CloudAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["account_id"]),
        Index(value = ["state", "queued_at"]),
    ],
)
data class CloudUploadEntity(
    @PrimaryKey
    @ColumnInfo(name = "track_id")
    val trackId: Long,
    @ColumnInfo(name = "account_id")
    val accountId: Long,
    /** [CloudUploadState] name. */
    val state: String,
    /** [CloudUploadOrigin] name. */
    val origin: String,
    @ColumnInfo(name = "queued_at")
    val queuedAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "session_uri")
    val sessionUri: String? = null,
    @ColumnInfo(name = "session_size")
    val sessionSize: Long? = null,
    @ColumnInfo(name = "session_md5")
    val sessionMd5: String? = null,
    @ColumnInfo(name = "bytes_sent", defaultValue = "0")
    val bytesSent: Long = 0,
    @ColumnInfo(name = "drive_file_id")
    val driveFileId: String? = null,
    @ColumnInfo(name = "attempts", defaultValue = "0")
    val attempts: Int = 0,
    /** Plain words for the last problem with this song (never an API body). */
    val error: String? = null,
)
