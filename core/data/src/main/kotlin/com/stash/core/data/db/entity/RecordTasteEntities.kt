package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One song of Your record's taste (stash-player `src/lib/spotify/taste.ts` `SongStat`), worked out from the whole history by
 * feature:record and rewritten whole each time, in one transaction. [key] is the song key (`textKey(bareTitle(title), artist)`:
 * one song across its remasters), [rank] its place in the taste's order (weight, then listens, then key: 0 is the song that
 * means the most now). The words are the most common spelling; [artistKey] the folded primary artist.
 *
 * [avoid]: the taste keeps it out of mixes (hidden on Spotify, always skipped, or an interlude skipped). A song put back
 * (`record_taste_keep`) is still marked here until the taste is worked out again: whoever reads it takes the put-back keys away.
 */
@Entity(tableName = "record_taste_songs", indices = [Index(value = ["artist_key"])])
data class RecordTasteSongEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "rank") val rank: Int,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "artist") val artist: String,
    @ColumnInfo(name = "album") val album: String? = null,
    @ColumnInfo(name = "sid") val sid: String? = null,
    @ColumnInfo(name = "artist_key") val artistKey: String,
    @ColumnInfo(name = "starts") val starts: Int,
    @ColumnInfo(name = "plays") val plays: Int,
    @ColumnInfo(name = "full") val full: Int,
    @ColumnInfo(name = "chosen") val chosen: Int,
    @ColumnInfo(name = "skips") val skips: Int,
    @ColumnInfo(name = "years") val years: Int,
    @ColumnInfo(name = "listened_ms") val listenedMs: Long,
    @ColumnInfo(name = "first") val first: Long,
    @ColumnInfo(name = "last") val last: Long,
    @ColumnInfo(name = "peak") val peak: Int,
    @ColumnInfo(name = "peak_at") val peakAt: Long,
    @ColumnInfo(name = "weight") val weight: Double,
    @ColumnInfo(name = "depth") val depth: Double,
    @ColumnInfo(name = "avoid") val avoid: Boolean,
)

/**
 * The taste's one row ([id] always [ONLY]): its version, when it was worked out (epoch ms) and the rest of it as JSON ([body]:
 * the totals, eras, constants, lost, obsessions, found, years, yearTops, topArtists and seedArtists). No row: no taste, which is
 * also how the mixes know there is no import (the taste is made only while there is one, and goes with it).
 */
@Entity(tableName = "record_taste")
data class RecordTasteEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Int = ONLY,
    @ColumnInfo(name = "v") val v: Int,
    @ColumnInfo(name = "computed_at") val computedAt: Long,
    @ColumnInfo(name = "body") val body: String,
) {
    companion object {
        const val ONLY = 1
    }
}

/**
 * A song put back from Settings › Your record › "Kept out of your mixes" (owner decision D1; archive.ts meta `keep`): never kept
 * out of a mix again. It isn't kept, though: a put-back favourite not heard in a year is still a lost one. [at]: when (epoch ms).
 * Kept through re-imports; Remove import empties the table, and so does removing the Last.fm scrobbles when they were all there was.
 */
@Entity(tableName = "record_taste_keep")
data class RecordTasteKeepEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "at") val at: Long,
)
