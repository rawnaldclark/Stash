package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Declarative definition of a Stash Mix — a "recipe" the
 * [com.stash.core.data.mix.MixGenerator] runs against the local library
 * to produce a rotating playlist.
 *
 * Tag filter semantics:
 *  - [includeTagsCsv] is a comma-separated list of Last.fm tags. Empty
 *    string = no tag filter (match every track — used for Rediscovery /
 *    Heavy Rotation which score purely on listening signal).
 *  - [excludeTagsCsv] hard-filters out tracks tagged with ANY of these.
 *
 * Affinity + freshness:
 *  - [affinityBias] shifts the scoring lean. Negative (-1.0 → 0) =
 *    rediscovery-leaning (prefer tracks not recently played, low-rotation
 *    favorites). Positive (0 → 1.0) = heavy-rotation-leaning (prefer
 *    high-play tracks).
 *  - [freshnessWindowDays] excludes tracks played within the window.
 *    Used by Rediscovery to guarantee "not in last 60 days."
 *
 * Discovery:
 *  - [discoveryRatio] reserves this fraction of [targetLength] slots for
 *    tracks that aren't yet in the library — [DiscoveryQueueEntity] rows
 *    flow through [com.stash.core.data.sync.workers.StashDiscoveryWorker]
 *    to fill those slots with fresh downloads.
 *
 * Playlist linkage:
 *  - [playlistId] is the `playlists.id` of the materialized mix. Null on
 *    first refresh (a new playlist gets created); populated thereafter so
 *    subsequent refreshes replace the track list in place and the Home
 *    card URL doesn't change.
 */
@Entity(
    tableName = "stash_mix_recipes",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["playlist_id"]),
        Index(value = ["is_active"]),
    ],
)
data class StashMixRecipeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,

    val description: String? = null,

    @ColumnInfo(name = "include_tags_csv")
    val includeTagsCsv: String = "",

    @ColumnInfo(name = "exclude_tags_csv")
    val excludeTagsCsv: String = "",

    @ColumnInfo(name = "era_start_year")
    val eraStartYear: Int? = null,

    @ColumnInfo(name = "era_end_year")
    val eraEndYear: Int? = null,

    @ColumnInfo(name = "affinity_bias")
    val affinityBias: Float = 0f,

    @ColumnInfo(name = "discovery_ratio")
    val discoveryRatio: Float = 0f,

    @ColumnInfo(name = "freshness_window_days")
    val freshnessWindowDays: Int = 0,

    @ColumnInfo(name = "target_length")
    val targetLength: Int = 50,

    @ColumnInfo(name = "is_builtin")
    val isBuiltin: Boolean = false,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "playlist_id")
    val playlistId: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "last_refreshed_at")
    val lastRefreshedAt: Long? = null,
)
