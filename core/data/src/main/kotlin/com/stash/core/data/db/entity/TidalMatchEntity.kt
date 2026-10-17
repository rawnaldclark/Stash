package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * What the shared Tidal relay said about one song, so the app does not ask again.
 *
 * Every relay `/search` and `/stream` spends quota on a small shared pool of Tidal
 * accounts. Before this table each play, sync download, retry and upgrade sweep re-ran
 * `/search` + `/stream` for the same song. Now:
 *  - [tidalId] set (a confident `td:<n>` match): later lookups skip `/search` and ask
 *    `/stream` directly. Kept 30 days from [searchedAt].
 *  - [tidalId] null: `/search` had no confident lossless match. Kept 14 days.
 *  - [losslessMissAt] / [aacMissAt]: `/stream` answered 404 for that mode. Each kept
 *    14 days, separately, so a lossless miss never blocks the AAC lookup. Only
 *    `lossless_unavailable` earns the 14 days: any other 404 (`not_available`) is stamped
 *    backdated by 14 days − 6 h, so the same 14-day check drops it after 6 h
 *    (BitchordClient.stampMiss). These stamps are therefore not always the real time.
 *
 * Stream URLs are NEVER stored here: they expire within the hour.
 * Only definitive answers land here; a busy pool, a 5xx or an I/O error is never a miss.
 *
 * [key] is the song's identity: `isrc:<ISRC>` when known, else
 * `t:<artist>|<title>|<duration bucket>` (see BitchordClient.cacheKey).
 */
@Entity(tableName = "tidal_match_cache")
data class TidalMatchEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "tidal_id") val tidalId: String?,
    @ColumnInfo(name = "title") val title: String = "",
    @ColumnInfo(name = "artist") val artist: String = "",
    @ColumnInfo(name = "artwork") val artwork: String? = null,
    @ColumnInfo(name = "confidence") val confidence: Float = 0f,
    @ColumnInfo(name = "searched_at") val searchedAt: Long,
    @ColumnInfo(name = "lossless_miss_at") val losslessMissAt: Long? = null,
    @ColumnInfo(name = "aac_miss_at") val aacMissAt: Long? = null,
)
