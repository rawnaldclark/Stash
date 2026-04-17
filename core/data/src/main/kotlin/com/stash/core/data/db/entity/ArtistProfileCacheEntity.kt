package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * SWR cache row for an [com.stash.data.ytmusic.model.ArtistProfile] JSON blob.
 *
 * - [artistId] is the normalized InnerTube browseId (UC-prefix form) — the
 *   same key the in-memory LRU uses in [com.stash.core.data.cache.ArtistCache].
 * - [json] is the kotlinx-serialization encoding of the full
 *   [com.stash.data.ytmusic.model.ArtistProfile]; decoded lazily on read.
 * - [fetchedAt] is epoch-millis; used to compute staleness against the 6-hour TTL.
 *
 * This table backs the disk tier of a two-tier cache. The row count is
 * capped via the dao's `evictOldest(keep = 20)` query so we don't accumulate
 * unbounded JSON blobs for artists the user only visited once.
 */
@Entity(tableName = "artist_profile_cache")
data class ArtistProfileCacheEntity(
    @PrimaryKey @ColumnInfo(name = "artist_id") val artistId: String,
    @ColumnInfo(name = "json") val json: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)
