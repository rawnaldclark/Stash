package com.stash.core.data.db.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

/**
 * FTS4 virtual table enabling full-text search across track title, artist,
 * and album columns. Backed by the [TrackEntity] content table.
 *
 * Usage: join against the `tracks` table using `rowid` to resolve matches.
 */
@Fts4(
    contentEntity = TrackEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
)
@Entity(tableName = "tracks_fts")
data class TrackFts(
    val title: String,
    val artist: String,
    val album: String,
)
