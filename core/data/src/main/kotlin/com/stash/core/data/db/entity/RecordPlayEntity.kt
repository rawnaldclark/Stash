package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One play brought into Your record from somewhere else (today: a Spotify data export), kept exactly as Stash on the web keeps
 * it in its archive (stash-player `src/lib/spotify/plays.ts` `ArchivePlay`), so the phone and the web give the same plays the
 * same ids, and the record reads the rows as they are.
 *
 * - [id] is `textKey(title, artist)|startedAt` (the play's identity, the same the mirror gives a play): a newer export of the same
 *   history adds only what is new.
 * - [t], [a], [al]: the song's words as the export gave them (cleaned, cut to 300 UTF-16 units). [sid]: its Spotify track id
 *   (22 characters), when the export named one.
 * - [s]: when the play started (epoch ms, UTC); [ms]: how long it played; [f]: the archive's flag bits (CHOSEN 1, DONE 2,
 *   SKIPPED 4, SHUFFLE 8, ACCOUNT 16, REASONS 32, LASTFM 64, ESTIMATED 128, STASH 256).
 *
 * Read by id (a page at a time) or in a whole pass (the import record's counts, the Home card's albums, the taste); the index on
 * `s` (v71) is for the reads by time (Your record's "This week, years ago").
 *
 * These plays are never Stash's own: they are not in `listening_events`, so they are never scrobbled, never mirrored to another
 * device, never in the "Export for Stash on the web" history, and never count as a play of a song here. Remove import empties
 * the table.
 */
@Entity(tableName = "record_plays", indices = [Index(value = ["s"], name = "index_record_plays_s")])
data class RecordPlayEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "t") val t: String,
    @ColumnInfo(name = "a") val a: String,
    @ColumnInfo(name = "al") val al: String? = null,
    @ColumnInfo(name = "sid") val sid: String? = null,
    @ColumnInfo(name = "s") val s: Long,
    @ColumnInfo(name = "ms") val ms: Long,
    @ColumnInfo(name = "f") val f: Int,
)
