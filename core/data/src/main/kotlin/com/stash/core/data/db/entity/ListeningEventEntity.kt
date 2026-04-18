package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local listening history — one row per track play that crossed the
 * "counts as a listen" threshold (≥30s or ≥50% of duration, Last.fm
 * convention). Used for two things:
 *
 *  1. **Scrobbling** — optional Last.fm submission via
 *     `com.stash.core.data.lastfm.LastFmScrobbler`. The `scrobbled`
 *     flag avoids double-submitting on retry.
 *  2. **Stash Mixes** — a future local recommendation engine reads
 *     play counts + recency from this table to build algorithmic
 *     playlists independent of Spotify's Daily Mix algorithm.
 *
 * The table is kept lightweight (no duration, no metadata) — everything
 * else can be re-derived from the joined [TrackEntity] row. Rows are
 * retained forever; a future pruning job can cap history if needed.
 */
@Entity(
    tableName = "listening_events",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["track_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["track_id"]),
        Index(value = ["started_at"]),
        Index(value = ["scrobbled"]),
    ],
)
data class ListeningEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "track_id")
    val trackId: Long,

    /** Epoch millis when playback started (before the listen-threshold). */
    @ColumnInfo(name = "started_at")
    val startedAt: Long,

    /**
     * True once successfully scrobbled to Last.fm. Stays false when the
     * user hasn't connected Last.fm, when scrobbling failed, or when the
     * feature isn't enabled — the scrobbler retries these on next launch.
     */
    @ColumnInfo(name = "scrobbled")
    val scrobbled: Boolean = false,
)
