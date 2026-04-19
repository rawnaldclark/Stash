package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Last.fm tag applied to a track (with weight) for Stash Mix recipe
 * filtering.
 *
 * Tags arrive in two flavors distinguished by [source]:
 *  - [SOURCE_TRACK]: returned by `track.getTopTags` — highly specific but
 *    only available for well-known tracks.
 *  - [SOURCE_ARTIST]: returned by `artist.getTopTags` — used as a fallback
 *    when the track-level call returns no tags. Every listed artist
 *    on Last.fm has tags, so this catches deep-cut tracks too.
 *
 * [weight] is the Last.fm `count` field (popularity of the tag for this
 * identity) normalized to 0.0..1.0 by dividing by the max count across
 * the same response. Higher = more definitional.
 *
 * Composite primary key `(track_id, tag)` ensures idempotent upserts:
 * re-enriching a track replaces old tag rows cleanly.
 */
@Entity(
    tableName = "track_tags",
    primaryKeys = ["track_id", "tag"],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["track_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["tag"]),
        Index(value = ["track_id"]),
    ],
)
data class TrackTagEntity(
    @ColumnInfo(name = "track_id")
    val trackId: Long,
    @ColumnInfo(name = "tag")
    val tag: String,
    @ColumnInfo(name = "weight")
    val weight: Float,
    /** [SOURCE_TRACK] or [SOURCE_ARTIST]. */
    @ColumnInfo(name = "source")
    val source: String,
    /** Epoch millis; used to decide when to re-enrich stale tags. */
    @ColumnInfo(name = "fetched_at")
    val fetchedAt: Long,
) {
    companion object {
        const val SOURCE_TRACK = "TRACK"
        const val SOURCE_ARTIST = "ARTIST"
    }
}
