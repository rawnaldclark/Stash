package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.stash.core.model.MusicSource
import java.time.Instant

/**
 * Room entity storing connected music service account metadata.
 */
@Entity(
    tableName = "source_accounts",
    indices = [
        Index(value = ["source"], unique = true),
    ],
)
data class SourceAccountEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val source: MusicSource,

    @ColumnInfo(name = "display_name")
    val displayName: String = "",

    val email: String = "",

    @ColumnInfo(name = "avatar_url")
    val avatarUrl: String? = null,

    @ColumnInfo(name = "is_connected")
    val isConnected: Boolean = false,

    @ColumnInfo(name = "connected_at")
    val connectedAt: Instant? = null,

    @ColumnInfo(name = "last_sync_at")
    val lastSyncAt: Instant? = null,
)
