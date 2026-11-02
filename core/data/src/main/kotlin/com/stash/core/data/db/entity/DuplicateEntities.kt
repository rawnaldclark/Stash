package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An id a merge took away from the library (schema 76), pointing at the row that now stands for
 * that song. `tracks` holds one YouTube video id and one Spotify URI per row (both UNIQUE), so when
 * two rows of one song become one, the merged-away row's ids have nowhere to live. Without this
 * table the next sync, like or hand-off that names one of them finds no row and makes the
 * duplicate again.
 *
 * Read wherever a song is found by an id before a new row is made: the sync's diff
 * (`bySpotifyUri` / `byYoutubeId`), a like on a stream, a listen saved by
 * `ensureTrackPersisted`, and a queued like-mirroring op that still holds the merged-away row's
 * id ([KIND_TRACK_ID]).
 *
 * One alias per (kind, value): an id names one song. The row it points at is CASCADE, so deleting
 * that row takes its aliases along; a later merge of that row moves them to its own survivor.
 */
@Entity(
    tableName = "track_aliases",
    primaryKeys = ["kind", "value"],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["track_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["track_id"])],
)
data class TrackAliasEntity(
    /** What [value] is: [KIND_YOUTUBE_ID], [KIND_SPOTIFY_URI], [KIND_ISRC] or [KIND_TRACK_ID]. */
    val kind: String,
    /** The merged-away id, exactly as the merged row stored it (a row id as its decimal string). */
    val value: String,
    /** The row that stands for the song now. */
    @ColumnInfo(name = "track_id")
    val trackId: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val KIND_YOUTUBE_ID = "youtube_id"
        const val KIND_SPOTIFY_URI = "spotify_uri"
        const val KIND_ISRC = "isrc"

        /** The merged-away row's own `tracks.id`: what a queued op or a saved player queue still holds. */
        const val KIND_TRACK_ID = "track_id"
    }
}

/**
 * One line per row the duplicate cleanup or a "Merge" took out of the library (schema 76), so a
 * support request ("a song vanished") can be answered and, if it must, undone by hand: what the
 * row was, which row it went into, and what moved. There is no automatic undo (see
 * [com.stash.core.data.library.duplicates.TrackMerger]).
 *
 * No foreign key: the line outlives both rows. Never read by the app except Library Health's
 * summary and diagnostics.
 */
@Entity(
    tableName = "track_merge_log",
    indices = [Index(value = ["at"])],
)
data class TrackMergeLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Epoch millis. */
    val at: Long,
    /** [REASON_AUTO_MERGE], [REASON_AUTO_ORPHAN] or [REASON_USER_MERGE]. */
    val reason: String,
    /** The row that now stands for the song; null for an orphan that was only deleted. */
    @ColumnInfo(name = "kept_track_id")
    val keptTrackId: Long?,
    /** The row that is gone. */
    @ColumnInfo(name = "removed_track_id")
    val removedTrackId: Long,
    val title: String,
    val artist: String,
    /** JSON: the removed row's identity and file, and what moved (memberships, plays, likes, aliases). */
    val detail: String,
) {
    companion object {
        /** The one-time cleanup merged an id-less stub into a row of the same song (or that row into the stub's file). */
        const val REASON_AUTO_MERGE = "AUTO_MERGE"

        /** The one-time cleanup deleted an id-less stub nothing used: no playlist, play, like, file or mix. */
        const val REASON_AUTO_ORPHAN = "AUTO_ORPHAN"

        /** The user tapped Merge in Library Health → Possible duplicates. */
        const val REASON_USER_MERGE = "USER_MERGE"
    }
}

/**
 * A pair the user marked "Not a duplicate" in Library Health → Possible duplicates (schema 76):
 * never offered again. Stored with the smaller id first ([of]). CASCADE on both rows; a merge moves
 * a dismissal from the merged-away row to the row it went into.
 */
@Entity(
    tableName = "duplicate_dismissals",
    primaryKeys = ["track_a", "track_b"],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["track_a"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["track_b"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["track_b"])],
)
data class DuplicateDismissalEntity(
    @ColumnInfo(name = "track_a")
    val trackA: Long,
    @ColumnInfo(name = "track_b")
    val trackB: Long,
    @ColumnInfo(name = "dismissed_at")
    val dismissedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        /** The dismissal of the pair ([a], [b]) in either order, smaller id first. */
        fun of(a: Long, b: Long, at: Long = System.currentTimeMillis()): DuplicateDismissalEntity =
            DuplicateDismissalEntity(trackA = minOf(a, b), trackB = maxOf(a, b), dismissedAt = at)
    }
}
