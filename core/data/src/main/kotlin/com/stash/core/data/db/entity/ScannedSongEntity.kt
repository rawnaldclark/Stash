package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A song found by the All Songs scanner. Isolated from `tracks`; deleted with its folder.
 *
 * [fileSizeBytes] and [lastModified] are the file's stamp as SAF last reported it: a rescan
 * re-reads the tags of any file whose stamp moved (a retag, a replaced file), and keeps
 * the row's [id] so a queue that holds the song still finds it.
 */
@Entity(
    tableName = "scanned_songs",
    foreignKeys = [
        ForeignKey(
            entity = ScanFolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["uri"], unique = true),
        Index(value = ["folder_id"]),
    ],
)
data class ScannedSongEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "folder_id") val folderId: Long,
    @ColumnInfo(name = "uri") val uri: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "artist") val artist: String,
    @ColumnInfo(name = "album") val album: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "file_format") val fileFormat: String,
    @ColumnInfo(name = "file_size_bytes") val fileSizeBytes: Long,
    /** SAF `COLUMN_LAST_MODIFIED` in epoch ms; 0 when the provider doesn't report it. */
    @ColumnInfo(name = "last_modified") val lastModified: Long,
    @ColumnInfo(name = "date_added") val dateAdded: Long,
    /**
     * The embedded cover's file NAME in the scanned_art directory (songs with the same image share
     * one file; a name, not a path, so it survives a restore or a move of the app's files).
     * NULL = not looked at yet, or the last try failed (the next scan retries); "" = none embedded.
     */
    @ColumnInfo(name = "album_art_path") val albumArtPath: String? = null,
)
