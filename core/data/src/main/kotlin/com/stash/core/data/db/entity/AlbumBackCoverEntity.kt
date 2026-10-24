package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An album's back cover, looked up once on the Cover Art Archive for Now Playing's flip.
 *
 * - [albumKey] is [com.stash.core.data.backcover.backCoverAlbumKey] of the album's primary artist
 *   and title, so every song on the album shares one row (COLLATE NOCASE).
 * - [imageUrl] is the scan's 1200px thumbnail, https. Null when the lookup ran and found no back
 *   cover; that sentinel is retried after [com.stash.core.data.backcover.AlbumBackCoverRepository]'s
 *   miss TTL, since scans are uploaded all the time.
 * - [releaseMbid] is the MusicBrainz release the scan belongs to (for diagnosis only).
 * - [attemptedAt] is when the lookup ran (epoch millis).
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
)
