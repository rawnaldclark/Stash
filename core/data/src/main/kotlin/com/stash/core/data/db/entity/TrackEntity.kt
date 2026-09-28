package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.stash.core.model.MusicSource
import java.time.Instant

/**
 * Room entity representing a single audio track stored on-device.
 *
 * Indexes are designed around the most common query patterns:
 * - Lookup by Spotify URI or YouTube ID (unique nullable).
 * - Browsing by artist / album.
 * - Sorting by date added, last played, or play count.
 * - Duplicate detection via the composite (title, artist) index.
 */
@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["spotify_uri"], unique = true),
        Index(value = ["youtube_id"], unique = true),
        Index(value = ["artist"]),
        Index(value = ["album"]),
        Index(value = ["date_added"]),
        Index(value = ["last_played"]),
        Index(value = ["play_count"]),
        Index(value = ["title", "artist"]),
    ],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val title: String,

    val artist: String,

    val album: String = "",

    /**
     * v0.9.26 — the album's primary artist credit, distinct from the
     * per-track [artist] string. For a Drake & 21 Savage collab album,
     * `albumArtist = "Drake, 21 Savage"` for every track on the record
     * even though individual tracks may credit just "Drake" or
     * "21 Savage". Required to disambiguate two albums with the same
     * title released by different artists (e.g. two "Singles" releases)
     * — the Library Albums query groups by (album, album_artist), so
     * collisions resolve cleanly and album art doesn't bleed across.
     *
     * Empty string is the sentinel for "unknown" so the Library
     * Albums query can still group those tracks together coherently;
     * the [com.stash.app.StashApplication.maybeBackfillTrackAlbums]
     * pass fills in `album_artist` from the file's path-encoded artist
     * folder for legacy rows.
     */
    @ColumnInfo(name = "album_artist", defaultValue = "")
    val albumArtist: String = "",

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0,

    @ColumnInfo(name = "file_path")
    val filePath: String? = null,

    @ColumnInfo(name = "file_format")
    val fileFormat: String = "opus",

    @ColumnInfo(name = "quality_kbps")
    val qualityKbps: Int = 0,

    @ColumnInfo(name = "file_size_bytes")
    val fileSizeBytes: Long = 0,

    val source: MusicSource = MusicSource.SPOTIFY,

    @ColumnInfo(name = "spotify_uri")
    val spotifyUri: String? = null,

    @ColumnInfo(name = "youtube_id")
    val youtubeId: String? = null,

    @ColumnInfo(name = "album_art_url")
    val albumArtUrl: String? = null,

    @ColumnInfo(name = "album_art_path")
    val albumArtPath: String? = null,

    @ColumnInfo(name = "date_added")
    val dateAdded: Instant = Instant.now(),

    @ColumnInfo(name = "last_played")
    val lastPlayed: Instant? = null,

    @ColumnInfo(name = "play_count")
    val playCount: Int = 0,

    @ColumnInfo(name = "is_downloaded")
    val isDownloaded: Boolean = false,

    @ColumnInfo(name = "canonical_title")
    val canonicalTitle: String = "",

    @ColumnInfo(name = "canonical_artist")
    val canonicalArtist: String = "",

    @ColumnInfo(name = "match_confidence")
    val matchConfidence: Float = 0f,

    @ColumnInfo(name = "match_dismissed")
    val matchDismissed: Boolean = false,

    /**
     * User-reported "this downloaded the wrong song." Set via a Now Playing
     * overflow action once the user realizes a sync-matched track plays a
     * different song than its Spotify metadata says. Surfaces the track in
     * the Failed Matches screen so the resync flow can present alternative
     * candidates; cleared when the user approves a replacement match or
     * dismisses the flag.
     */
    @ColumnInfo(name = "match_flagged", defaultValue = "0")
    val matchFlagged: Boolean = false,

    /**
     * International Standard Recording Code — per-master unique identifier
     * from Spotify's `external_ids.isrc`. Null for YouTube-sourced tracks
     * and for legacy Spotify rows inserted before the matcher started
     * requesting it. When present, the YouTube matcher can use it as the
     * highest-precision signal to disambiguate clean vs. explicit masters
     * and different remasters of the same song.
     */
    val isrc: String? = null,

    /**
     * Spotify's parental-advisory flag. Null for YouTube-sourced tracks and
     * for legacy Spotify rows inserted before the field was threaded through;
     * false/true for Spotify rows inserted after v12. Used by the matcher to
     * prefer candidates whose explicitness matches the source track.
     */
    val explicit: Boolean? = null,

    /**
     * InnerTube's per-video classification, persisted here so the Fix-
     * Wrong-Versions backfill can skip a network round-trip on tracks
     * it's already verified. Stores the string form of [MusicVideoType]
     * (ATV / OMV / UGC / OFFICIAL_SOURCE_MUSIC / PODCAST_EPISODE), or
     * null when we haven't classified the track yet. A non-null value
     * means the audio was the given type at verification time — it can
     * go stale if the user manually rehooks the track elsewhere, but in
     * practice InnerTube doesn't re-classify existing videoIds.
     */
    @ColumnInfo(name = "music_video_type")
    val musicVideoType: String? = null,

    /**
     * Cached canonical ATV/OMV video id for YouTube Music recommender-graph
     * scrobbling. Null when uncached. Populated the first time
     * [com.stash.core.data.youtube.YtCanonicalResolver] resolves a
     * non-ATV/OMV track (UGC uploads, YouTube-Library imports) so we don't
     * re-search InnerTube every time the user re-plays it. Never overwritten
     * once set — the canonical id for a given (artist, title) doesn't move.
     */
    @ColumnInfo(name = "yt_canonical_video_id")
    val ytCanonicalVideoId: String? = null,

    /**
     * Bit-depth of the on-disk audio (16, 24, 32). NULL when unknown
     * (legacy rows pre-backfill, lossy codecs where bit-depth is
     * meaningless, files whose container doesn't expose it). Surfaced
     * by `AudioDurationExtractor.extract` from the file itself, not
     * from the source's catalog metadata — the file is truth.
     */
    @ColumnInfo(name = "bits_per_sample")
    val bitsPerSample: Int? = null,

    /**
     * Audio sample rate in Hz (44100, 48000, 96000, 192000). NULL when
     * unknown. See [bitsPerSample] for source-of-truth notes.
     */
    @ColumnInfo(name = "sample_rate_hz")
    val sampleRateHz: Int? = null,

    /**
     * v0.9.13: timestamp (epoch-millis) when this track was saved to
     * the user's Spotify Liked Songs by either auto-save or manual
     * heart. NULL = not saved. Forward-only; never cleared.
     */
    @ColumnInfo(name = "spotify_saved_at")
    val spotifySavedAt: Long? = null,

    /**
     * v0.9.13: timestamp (epoch-millis) when this track was liked on
     * YouTube Music via the heart button. NULL = not liked. Forward-only.
     */
    @ColumnInfo(name = "ytmusic_saved_at")
    val ytMusicSavedAt: Long? = null,

    /**
     * Timestamp (epoch-millis) when Stash loved this track on Last.fm via the
     * heart button. NULL = Stash never loved it.
     *
     * This is not merely deduplication. `track.unlove` is destructive to data
     * Stash does not own: a user may have loved a track on Last.fm years before
     * installing Stash, and un-hearting it here must not silently delete that.
     * The un-love only fires when this column is set, i.e. only for loves Stash
     * itself created — the same "never un-Like what Stash never Liked" contract
     * the Spotify and YT columns enforce.
     */
    @ColumnInfo(name = "lastfm_loved_at")
    val lastFmLovedAt: Long? = null,

    /**
     * When the integrity sweep last found this track's file gone while the row
     * still said downloaded — i.e. "the user had this and it vanished", which
     * is what a restored backup looks like on a fresh install. Cleared the
     * moment the track is downloaded again, and never set by a deliberate
     * delete, so it only ever means "lost, not discarded".
     */
    // Declared before stashLikedAt on purpose: field order feeds Room's identity hash for schema 46.
    @ColumnInfo(name = "download_missing_at")
    val downloadMissingAt: Long? = null,

    /**
     * v0.9.13: timestamp (epoch-millis) when this track was added to
     * the local Stash "Liked Songs" playlist via the heart button.
     * NULL = not yet. Forward-only.
     */
    @ColumnInfo(name = "stash_liked_at")
    val stashLikedAt: Long? = null,

    /**
     * v0.9.16: Last.fm canonical recording MBID. Captured by
     * TrackInfoEnrichmentWorker. Join key for any future MetaBrainz-
     * stack work; null while the worker hasn't yet enriched this row.
     */
    @ColumnInfo(name = "mbid")
    val mbid: String? = null,

    /**
     * v0.9.16: User's lifetime Last.fm playcount for this track.
     * Richer than [playCount] (which only counts in-Stash plays) for
     * users with pre-Stash listening history. Null = not yet enriched.
     */
    @ColumnInfo(name = "lastfm_user_playcount")
    val lastfmUserPlaycount: Int? = null,

    /**
     * v0.9.16: Public Last.fm listener count. Used as a popularity
     * bucket for novelty calibration in the recommender.
     */
    @ColumnInfo(name = "lastfm_listeners")
    val lastfmListeners: Long? = null,

    /**
     * v0.9.16: Did the user love this track on Last.fm? Explicit
     * positive label, weighted heavier than scrobble counts.
     */
    @ColumnInfo(name = "lastfm_user_loved", defaultValue = "0")
    val lastfmUserLoved: Boolean = false,

    /**
     * v0.9.25: Integrated loudness in LUFS (ITU-R BS.1770-4 / EBU R128).
     * NULL = measurement not yet attempted. `Float.NaN` = measurement
     * attempted-and-failed sentinel — distinguishable from "never tried"
     * so the backfill worker can retry on a new algorithm version or
     * skip permanently-broken files. Typical range is roughly -30 to -6
     * LUFS; pop masters cluster around -8 to -14, streaming targets are
     * -14 (Spotify) to -16 (Apple/YouTube).
     */
    @ColumnInfo(name = "loudness_lufs")
    val loudnessLufs: Float? = null,

    /**
     * v0.9.25: True-peak (sample-peak) level in dBFS, always negative or
     * zero. NULL = not measured. Used by the soft-clip limiter to derive
     * the maximum safe gain before reconstruction overs would clip the
     * output. Captured alongside [loudnessLufs] in the same FFmpeg
     * ebur128 pass.
     */
    @ColumnInfo(name = "true_peak_dbfs")
    val truePeakDbfs: Float? = null,

    /**
     * v0.9.25: Epoch-millis timestamp of the loudness-measurement
     * attempt (success OR failure). NULL = never attempted. Lets the
     * backfill worker run a "stale measurement" sweep if the BS.1770
     * implementation ever changes, and powers the weekly resurrection
     * query that re-attempts NaN-marked rows in case a transient
     * decode failure cleared up.
     */
    @ColumnInfo(name = "loudness_measured_at")
    val loudnessMeasuredAt: Long? = null,

    /**
     * v0.9.27: Cached result of an `AvailabilityCheckWorker` lookup
     * against Kennyy's Qobuz proxy. True = the proxy can resolve a
     * stream URL for this track (the streaming engine can play it
     * without a downloaded file); false = lookup failed and the track
     * is offline-only. Defaults to false so legacy rows surface as
     * "not yet known streamable" until the worker drains them.
     */
    @ColumnInfo(name = "is_streamable", defaultValue = "0")
    val isStreamable: Boolean = false,

    /**
     * v0.9.27: Epoch-millis timestamp of the streamability lookup
     * (success OR failure). NULL = never checked — the tristate
     * sentinel the `AvailabilityCheckWorker` queries to find rows
     * still in need of an initial check. Mirrors the
     * `loudness_measured_at` pattern from v0.9.25.
     */
    @ColumnInfo(name = "is_streamable_checked_at")
    val isStreamableCheckedAt: Long? = null,

    /**
     * v0.9.35: epoch-millis of the most recent successful tag + art
     * embedding pass on the file at [filePath]. NULL = never tagged
     * (legacy v0.9.34 rows + any pre-v0.9.35 download). 0L = backfill
     * tried and gave up irrecoverably (file missing on disk, ffmpeg
     * error, SAF row we can't operate on in place). Non-null non-zero
     * = the on-disk file carries the v0.9.35 tag set.
     *
     * The backfill worker queries `WHERE is_downloaded = 1 AND
     * file_path IS NOT NULL AND metadata_embedded_at IS NULL`. Both
     * success and failure stamps remove the row from the result set
     * so the worker terminates.
     */
    @ColumnInfo(name = "metadata_embedded_at")
    val metadataEmbeddedAt: Long? = null,

    /**
     * v0.9.36: epoch-millis of the most recent lyrics-fetch attempt
     * that produced a result. NULL = never tried (legacy rows + any
     * pre-v0.9.36 download). 0L = backfill tried and found no
     * matching lyrics (LRCLIB miss + YT Music fallback miss). Non-null
     * non-zero = success epoch-millis; a `lyrics` row exists for this
     * track. Mirror of `metadata_embedded_at`'s tristate sentinel
     * semantics from v0.9.35: both success and failure stamps remove
     * the row from the backfill worker's `lyrics_fetched_at IS NULL`
     * predicate so the worker terminates.
     */
    @ColumnInfo(name = "lyrics_fetched_at")
    val lyricsFetchedAt: Long? = null,
)
