package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A candidate track that [StashMixRefreshWorker] decided should be added
 * to the library to fill a mix's discovery slot. The row starts in
 * [STATUS_PENDING] and flows through search → download → commit via
 * [com.stash.core.data.sync.workers.StashDiscoveryWorker], ending in
 * [STATUS_DONE] or [STATUS_FAILED].
 *
 * The `seed_artist` column records which of the user's artists generated
 * this suggestion (via Last.fm `artist.getSimilar`), so the UI can later
 * surface "because you listen to <seed_artist>" context on the resulting
 * download.
 */
@Entity(
    tableName = "discovery_queue",
    foreignKeys = [
        ForeignKey(
            entity = StashMixRecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipe_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["status"]),
        Index(value = ["recipe_id"]),
        Index(value = ["queued_at"]),
    ],
)
data class DiscoveryQueueEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "recipe_id")
    val recipeId: Long,

    val artist: String,

    val title: String,

    @ColumnInfo(name = "seed_artist")
    val seedArtist: String,

    val status: String = STATUS_PENDING,

    @ColumnInfo(name = "track_id")
    val trackId: Long? = null,

    @ColumnInfo(name = "queued_at")
    val queuedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null,

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_SEARCHED = "SEARCHED"
        const val STATUS_DOWNLOADING = "DOWNLOADING"
        const val STATUS_DONE = "DONE"
        const val STATUS_FAILED = "FAILED"
    }
}
