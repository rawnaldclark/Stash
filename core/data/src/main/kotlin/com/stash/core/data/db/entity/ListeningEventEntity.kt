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
 * The table is kept lightweight (no song metadata) — that comes from the
 * joined [TrackEntity] row. v59 adds what can't be re-derived later: the
 * listener's local day and minute, and the real listening time. Rows are
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
        Index(value = ["yt_scrobbled"]),
        // v59: Top songs, Stats and Your Year read day ranges grouped by song or day. The index
        // covers every column those reads need, so they never touch a table row (see
        // ListeningHistorySql: 5-10x faster at 60k listens than a (local_day, track_id) index).
        Index(value = ["local_day", "track_id", "started_at", "listened_ms", "local_minute"]),
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

    /**
     * True once successfully submitted to YouTube Music as a history ping.
     * Mirrors [scrobbled] but for the YT Music recommender-graph write path.
     * Set to `true` when a ping lands (2xx), when the track is UGC-only and
     * has no canonical ATV/OMV equivalent (don't pollute Recap), and when
     * the feature is disabled and we want to skip processing the backlog.
     */
    @ColumnInfo(name = "yt_scrobbled", defaultValue = "0")
    val ytScrobbled: Boolean = false,

    /**
     * v0.9.13: timestamp (epoch-millis) when the play crossed the
     * completion threshold (`min(durationMs/2, 240_000)` clamped to
     * `[30_000, 240_000]` per `ListeningRecorder`). NULL = not yet
     * marked complete; non-NULL = a "real" listen for analytics
     * purposes (auto-save threshold counting, future Top Tracks
     * surfaces).
     */
    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null,

    /**
     * v54 (link-sync spec §7.2): the device a play came from when it was not
     * played here (a Stash on the web library file or send, later the mirror);
     * NULL for this phone's own plays. Such a play shows in History and in a
     * later export, and never feeds Stash Mixes, play statistics, auto-save or
     * a scrobble destination (see [com.stash.core.data.db.dao.ListeningEventDao]).
     */
    @ColumnInfo(name = "origin_device")
    val originDevice: String? = null,

    /**
     * v59: the calendar day the listen started on, where the listener was at the time: days since
     * 1970-01-01 in the phone's time zone when it was written (see
     * [com.stash.core.data.listening.ListeningDay]). Stored rather than derived in SQL because
     * SQLite's `'localtime'` follows the phone's *current* zone, which moves every past listen a
     * day after travel. NULL only on a row written before v59 and not yet reached by
     * [com.stash.core.data.listening.ListeningDayBackfill]; queries read
     * `COALESCE(local_day, started_at / 86400000)` until then.
     */
    @ColumnInfo(name = "local_day")
    val localDay: Long? = null,

    /** v59: the minute of that day the listen started, 0..1439, in the same zone as [localDay]. */
    @ColumnInfo(name = "local_minute")
    val localMinute: Int? = null,

    /**
     * v59: how long the song actually played in this listen, in real (wall-clock) milliseconds,
     * pauses and buffering excluded. Written by [com.stash.core.media.listening.ListeningRecorder]
     * when the listen ends (next song, a repeat-one restart, stop), and also on a pause so the time
     * survives the app being killed while paused. NULL = unknown: a row from before v59, a play from
     * another device, or the app died before any write. Totals then fall back to the song's length.
     *
     * If the app dies after a pause and the song played on, the value is the time up to that pause:
     * a lower bound, never invented. It is never below the time the listen took to count (half the
     * song, 30 s..4 min, at 1x), because nothing is written before then.
     */
    @ColumnInfo(name = "listened_ms")
    val listenedMs: Long? = null,
)
