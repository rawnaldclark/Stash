package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A song hidden on Spotify (a Spotify export's YourLibrary.json `bannedTracks`), kept for Your record's mixes: the web keeps the
 * same set in its archive (stash-player `src/lib/spotify/archive.ts` meta `hidden`), and its taste never offers one again.
 *
 * [key] is the record's song key (`textKey(bareTitle(title), artist)`: the same song across its remasters), [t] and [a] its words
 * as the export gave them (cleaned), so a list of them can say which songs they are. A newer import adds to them and takes none
 * away; Remove import empties the table.
 */
@Entity(tableName = "record_hidden")
data class RecordHiddenEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "t") val t: String,
    @ColumnInfo(name = "a") val a: String,
)
