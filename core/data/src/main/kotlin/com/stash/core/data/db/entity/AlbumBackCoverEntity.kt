package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An album's back cover and the pages inside it, looked up once on the Cover Art Archive (and
 * Discogs, for what the archive lacks) for Now Playing's flip and its inside view.
 *
 * - [albumKey] is [com.stash.core.data.backcover.backCoverAlbumKey] of the album's primary artist
 *   and title, so every song on the album shares one row (COLLATE NOCASE).
 * - [imageUrl] is the scan's 1200px thumbnail, https. Null when the lookup ran and found no back
 *   cover; that sentinel is retried after [com.stash.core.data.backcover.AlbumBackCoverRepository]'s
 *   miss TTL, since scans are uploaded all the time.
 * - [releaseMbid] is the MusicBrainz release the scan belongs to (for diagnosis only).
 * - [attemptedAt] is when the lookup ran (epoch millis).
 * - [insideUrls] are the scans of the inside (booklet pages, gatefold panels, inner sleeve, tray),
 *   1200px, one per line in the archive's order. Null when the inside hasn't been looked for yet (a
 *   v63 row, or a lookup cut short once the back was found); empty when there is none.
 * - [backFromDiscogs] and [insideFromDiscogs] say the back, or the inside pages, came from Discogs
 *   (its 600px images) rather than the archive: the archive is asked first, Discogs only for what
 *   it lacks. [discogsPage] is the discogs.com master or release page they come from, for the
 *   "Data provided by Discogs" link the Discogs API terms ask for; null when nothing came from it.
 *
 * A pure cache: no foreign keys, one row per album played. A failed lookup (offline, MusicBrainz
 * down) writes nothing, so it is tried again later.
 */
@Entity(tableName = "album_back_covers")
data class AlbumBackCoverEntity(
    @PrimaryKey
    @ColumnInfo(name = "album_key", collate = ColumnInfo.NOCASE)
    val albumKey: String,
    @ColumnInfo(name = "image_url") val imageUrl: String? = null,
    @ColumnInfo(name = "release_mbid") val releaseMbid: String? = null,
    @ColumnInfo(name = "attempted_at") val attemptedAt: Long,
    @ColumnInfo(name = "inside_urls") val insideUrls: String? = null,
    @ColumnInfo(name = "back_from_discogs", defaultValue = "0") val backFromDiscogs: Boolean = false,
    @ColumnInfo(name = "inside_from_discogs", defaultValue = "0") val insideFromDiscogs: Boolean = false,
    @ColumnInfo(name = "discogs_page") val discogsPage: String? = null,
)
