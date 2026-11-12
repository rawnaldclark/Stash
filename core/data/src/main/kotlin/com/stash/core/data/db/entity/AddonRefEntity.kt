package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * What an add-on said about one song, so the app does not ask it again (add-ons phase 2 §5.2).
 * Today only the Tidal rung writes here ([addonId] `tidal`), through either of its two wires: the
 * legacy relay and the v1 add-on read and write the same rows, so the config switch between them
 * never empties the cache. It replaced `tidal_match_cache` in v80, every row carried over.
 *
 * Keyed by song identity ([songKey]), not by library track: search downloads have no library row
 * yet, and Spotify and YouTube copies of one song share one answer. Every Tidal relay or add-on
 * `/search` and `/stream` spends quota on a small shared pool of Tidal accounts, so:
 *  - [remoteId] set (a confident `td:<n>` match): later lookups skip `/search` and ask
 *    `/stream` directly. Kept 30 days from [resolvedAt].
 *  - [remoteId] null: `/search` had no confident lossless match. Kept 14 days.
 *  - [losslessMissAt] / [aacMissAt]: `/stream` had nothing in that mode. Each kept 14 days,
 *    separately, so a lossless miss never blocks the AAC lookup. Only a definitive miss earns the
 *    14 days (the relay's `lossless_unavailable`, v1's `quality_unavailable`): any other is
 *    stamped backdated by 14 days − 6 h, so the same 14-day check drops it after 6 h
 *    (TidalClient.stampMiss). These stamps are therefore not always the real time.
 *
 * Stream URLs are NEVER stored here: they expire within the hour.
 * Only definitive answers land here; a busy pool, a 5xx or an I/O error is never a miss.
 *
 * [songKey] is `isrc:<ISRC>` when known, else `t:<artist>|<title>|<duration bucket>`, then
 * `|explicit` or `|clean` for the twin the pick prefers (TidalClient.cacheKey). AAC mode's own
 * miss stamp is a row under `aaconly|` + that key.
 */
@Entity(tableName = "addon_refs", primaryKeys = ["addon_id", "song_key"])
data class AddonRefEntity(
    @ColumnInfo(name = "addon_id") val addonId: String,
    @ColumnInfo(name = "song_key") val songKey: String,
    @ColumnInfo(name = "remote_id") val remoteId: String?,
    @ColumnInfo(name = "title") val title: String = "",
    @ColumnInfo(name = "artist") val artist: String = "",
    @ColumnInfo(name = "artwork") val artwork: String? = null,
    @ColumnInfo(name = "confidence") val confidence: Float = 0f,
    @ColumnInfo(name = "resolved_at") val resolvedAt: Long,
    @ColumnInfo(name = "lossless_miss_at") val losslessMissAt: Long? = null,
    @ColumnInfo(name = "aac_miss_at") val aacMissAt: Long? = null,
)
