package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached Last.fm genres of one artist the user has listened to (v60), behind Top genres in
 * "Your listening".
 *
 * Keyed by [com.stash.core.data.genre.ArtistGenreKey]: the credit's primary artist (the same
 * collapse `artist_images` uses) without a trailing "feat." clause. COLLATE NOCASE so case
 * variants of a credit share one row and one Last.fm request.
 *
 * - [genres]: JSON list of up to three (genre, weight 0..1) pairs, canonical genre keys,
 *   strongest first ([com.stash.core.data.genre.WeightedGenreCodec]); `[]` unless [status] is
 *   [STATUS_TAGGED].
 * - [status]: what the last fetch found — genres ([STATUS_TAGGED], refreshed after
 *   [TAGGED_REFRESH_MS]; a refresh that finds nothing better keeps them), no genre at all
 *   ([STATUS_UNTAGGABLE]: Last.fm doesn't know the artist, or tags it only with non-genres; asked
 *   again after [UNTAGGABLE_RETRY_MS]), or a request that failed for this artist itself rather
 *   than for an outage or rate limit ([STATUS_FAILED], asked again after [FAILED_RETRY_MS]).
 * - [fetchedAt]: when that was, epoch millis.
 *
 * A small pure cache like `artist_images`: no foreign keys (artist names are free text on
 * `tracks`), one row per listened artist, orphans harmless.
 */
@Entity(tableName = "artist_genres")
data class ArtistGenreEntity(
    @PrimaryKey
    @ColumnInfo(name = "artist_name", collate = ColumnInfo.NOCASE)
    val artistName: String,
    @ColumnInfo(name = "genres") val genres: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
) {
    companion object {
        const val STATUS_TAGGED = "TAGGED"
        const val STATUS_UNTAGGABLE = "UNTAGGABLE"
        const val STATUS_FAILED = "FAILED"

        /** An artist's genres are fetched again after this long: tags drift as an artist's audience grows. */
        const val TAGGED_REFRESH_MS = 180L * 24 * 60 * 60 * 1000

        /**
         * An artist Last.fm had no genre for is asked again after this long: new artists gain
         * tags as people listen, but not from one week to the next.
         */
        const val UNTAGGABLE_RETRY_MS = 90L * 24 * 60 * 60 * 1000

        /** An artist whose own request failed is asked again after this long. */
        const val FAILED_RETRY_MS = 24L * 60 * 60 * 1000
    }
}
