package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A directory the user picked for All Songs to scan (SAF tree URI). */
@Entity(
    tableName = "scan_folders",
    indices = [Index(value = ["tree_uri"], unique = true)],
)
data class ScanFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "tree_uri") val treeUri: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)
