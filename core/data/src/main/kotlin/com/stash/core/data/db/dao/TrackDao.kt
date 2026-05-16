package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.stash.core.data.db.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

/**
 * Summary projection for artist browsing.
 *
 * @property artist  Artist name.
 * @property trackCount  Number of tracks by this artist.
 * @property totalDurationMs  Combined duration of all tracks by this artist.
 */
data class ArtistSummary(
    val artist: String,
    val trackCount: Int,
    val totalDurationMs: Long,
    val artUrl: String?,
)

/**
 * v0.9.13: Projection of just the three Like-state timestamp columns.
 * Subscribed by Now Playing so the heart icon stays in sync with Room
 * across screen open/close cycles — the player's cached Track is a
 * snapshot taken at track-load time and doesn't refresh from the
 * `tracks` table after `markStashLiked`/`markSpotifySaved`/etc. fire.
 *
 * Forward-only semantics make any non-null value the canonical answer
 * for "is this destination saved?".
 */
data class TrackLikeState(
    val id: Long,
    @androidx.room.ColumnInfo(name = "stash_liked_at") val stashLikedAt: Long?,
    @androidx.room.ColumnInfo(name = "spotify_saved_at") val spotifySavedAt: Long?,
    @androidx.room.ColumnInfo(name = "ytmusic_saved_at") val ytMusicSavedAt: Long?,
)

/**
 * v0.9.13: Tiny projection used by the one-shot codec-backfill at app
 * startup. Just id + path; we don't need the full TrackEntity.
 */
data class TrackPathRow(
    val id: Long,
    @androidx.room.ColumnInfo(name = "file_path") val filePath: String,
)

/**
 * Summary projection for album browsing.
 *
 * @property album  Album name.
 * @property artist  Primary artist on the album.
 * @property trackCount  Number of tracks in the album.
 * @property artPath  Local path to album artwork, if available.
 */
data class AlbumSummary(
    val album: String,
    val artist: String,
    val trackCount: Int,
    val artPath: String?,
    val artUrl: String?,
)

/**
 * One row of the Library Health histogram. [format] is the codec tag
 * (`aac` / `opus` / `flac` / `unknown`); [kbpsBucket] is a coarse-grained
 * label for the bitrate band. [trackCount] is the number of downloaded
 * tracks falling into this (format, bucket) pair; [avgKbps] is the actual
 * average bitrate within the bucket so the UI can show "~127 kbps" instead
 * of just "~128" when relevant.
 */
data class LibraryHealthBucket(
    val format: String,
    val kbpsBucket: String,
    val trackCount: Int,
    val avgKbps: Double,
)

/**
 * Minimal projection used by the Library Health backfill: just enough to
 * hand each row off to `AudioDurationExtractor.extract` and write the
 * results back via [TrackDao.setFormatAndQuality]. Used by the one-time
 * fixup that populates `file_format` / `quality_kbps` for tracks
 * downloaded before the v0.8.1 fix taught the sync writer to record
 * those columns.
 */
data class TrackBackfillRow(
    val id: Long,
    val filePath: String,
)

/**
 * Minimal row projection for the art-backfill pipeline. Returned by
 * [TrackDao.findArtBackfillCandidates] — only the fields needed to
 * attempt a backfill without dragging in the full [TrackEntity] shape.
 */
data class ArtBackfillRow(
    val id: Long,
    val artist: String,
    val title: String,
    @androidx.room.ColumnInfo(name = "youtube_id")
    val youtubeId: String?,
)

/**
 * Minimal row projection for the duration-backfill pass. File path is all
 * we need — `AudioDurationExtractor.extractMs` reads ground-truth duration
 * directly from the container.
 */
data class DurationBackfillRow(
    val id: Long,
    @androidx.room.ColumnInfo(name = "file_path")
    val filePath: String,
)

/**
 * Minimal projection for the startup file-integrity sweep — only needs id
 * and the stored path so we can verify the file actually exists on disk.
 * `file_path` is nullable in the schema; rows with `is_downloaded=1` and
 * `file_path IS NULL` are themselves a corrupt state we want to repair.
 */
data class DownloadedFileRef(
    val id: Long,
    @androidx.room.ColumnInfo(name = "file_path")
    val filePath: String?,
)

/**
 * Data-access object for [TrackEntity].
 *
 * Provides CRUD operations, various sorted/filtered queries, full-text
 * search via FTS4, and play-tracking helpers.
 */
@Dao
interface TrackDao {

    // ── Inserts ─────────────────────────────────────────────────────────

    /** Insert a single track, replacing on conflict (e.g. same Spotify URI). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: TrackEntity): Long

    /** Insert multiple tracks, replacing on conflict. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<TrackEntity>): List<Long>

    // ── Update / Delete ─────────────────────────────────────────────────

    /** Update an existing track entity. */
    @Update
    suspend fun update(track: TrackEntity)

    /** Delete a track entity. */
    @Delete
    suspend fun delete(track: TrackEntity)

    /** Delete a track row by id. Used by BlocklistGuard's atomic block transaction. */
    @Query("DELETE FROM tracks WHERE id = :trackId")
    suspend fun deleteById(trackId: Long)

    /**
     * Top artists in the library ranked by track count. Used by the
     * Stash Discover seeding fallback — when a fresh-install user has no
     * listening_events yet, their library's most-present artists are the
     * best proxy for "what they care about" to feed Last.fm's
     * `artist.getSimilar`. Only considers downloaded, non-blacklisted
     * tracks so pending-download stubs don't skew the ranking.
     */
    @Query(
        """
        SELECT t.artist
        FROM tracks t
        LEFT JOIN track_blocklist bl
            ON bl.canonical_key = (t.canonical_artist || '|' || t.canonical_title)
            OR (bl.spotify_uri IS NOT NULL AND bl.spotify_uri = t.spotify_uri)
            OR (bl.youtube_id  IS NOT NULL AND bl.youtube_id  = t.youtube_id)
        WHERE t.is_downloaded = 1
          AND t.artist != ''
          AND bl.canonical_key IS NULL
        GROUP BY LOWER(t.artist)
        ORDER BY COUNT(*) DESC
        LIMIT :limit
        """
    )
    suspend fun getTopArtistsByTrackCount(limit: Int): List<String>

    /**
     * Top downloaded tracks by Last.fm scrobble count, returned as
     * (artist, title) pairs ready for [com.stash.core.data.mix.MixSeedGenerator]'s
     * TRACK_SIMILAR seed source. Used as the third tier (final fallback)
     * of the seedTracks fallback chain — for users with no recent listening
     * events but who have been scrobbling to Last.fm for a while, the LFM
     * playcount carries meaningful taste signal.
     *
     * Filters to is_downloaded = 1 because the seed needs to actually be a
     * library track (not a stub); and lastfm_user_playcount > 0 to ensure
     * meaningful signal (NULL or 0 means "never scrobbled / no LFM data").
     */
    @Query(
        """
        SELECT artist AS artist, title AS title
        FROM tracks
        WHERE is_downloaded = 1
          AND lastfm_user_playcount IS NOT NULL
          AND lastfm_user_playcount > 0
        ORDER BY lastfm_user_playcount DESC
        LIMIT :limit
        """
    )
    suspend fun getTopTracksByLfmPlaycount(limit: Int): List<TrackArtistTitle>

    // ── List queries (all reactive) ─────────────────────────────────────

    /** All tracks ordered by most-recently-added first. v0.9.15: filters blocked. */
    @Query(
        """
        SELECT t.* FROM tracks t
        LEFT JOIN track_blocklist bl
            ON bl.canonical_key = (t.canonical_artist || '|' || t.canonical_title)
            OR (bl.spotify_uri IS NOT NULL AND bl.spotify_uri = t.spotify_uri)
            OR (bl.youtube_id  IS NOT NULL AND bl.youtube_id  = t.youtube_id)
        WHERE bl.canonical_key IS NULL
        ORDER BY t.date_added DESC
        """
    )
    fun getAllByDateAdded(): Flow<List<TrackEntity>>

    /** All tracks by a specific artist, ordered by album then title. */
    @Query("SELECT * FROM tracks WHERE artist = :artist ORDER BY album ASC, title ASC")
    fun getByArtist(artist: String): Flow<List<TrackEntity>>

    /**
     * All tracks belonging to a playlist, resolved through the
     * [playlist_tracks] join table. Only includes non-removed entries.
     */
    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN playlist_tracks pt ON t.id = pt.track_id
        INNER JOIN playlists p ON pt.playlist_id = p.id
        LEFT JOIN track_blocklist bl
            ON bl.canonical_key = (t.canonical_artist || '|' || t.canonical_title)
            OR (bl.spotify_uri IS NOT NULL AND bl.spotify_uri = t.spotify_uri)
            OR (bl.youtube_id  IS NOT NULL AND bl.youtube_id  = t.youtube_id)
        WHERE pt.playlist_id = :playlistId
          AND pt.removed_at IS NULL
          AND bl.canonical_key IS NULL
        ORDER BY
            CASE WHEN p.type = 'DAILY_MIX' THEN pt.added_at END DESC,
            pt.position ASC
        """
    )
    fun getByPlaylist(playlistId: Long): Flow<List<TrackEntity>>

    /** Most-recently-added downloaded tracks, limited to [limit] results. */
    /**
     * Returns every downloaded track whose `file_path` starts with [pathPrefix].
     * Used by the library-migration flow to enumerate tracks still stored in
     * internal app-private storage when the user picks an external SAF target.
     */
    @Query("""
        SELECT * FROM tracks
        WHERE is_downloaded = 1
          AND file_path IS NOT NULL
          AND file_path LIKE :pathPrefix || '%'
    """)
    suspend fun getDownloadedTracksWithPathPrefix(pathPrefix: String): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE is_downloaded = 1 ORDER BY date_added DESC LIMIT :limit")
    fun getRecentlyAdded(limit: Int): Flow<List<TrackEntity>>

    /** Most-played tracks, limited to [limit] results. */
    @Query("SELECT * FROM tracks WHERE play_count > 0 ORDER BY play_count DESC LIMIT :limit")
    fun getMostPlayed(limit: Int): Flow<List<TrackEntity>>

    // ── Single-item lookups ─────────────────────────────────────────────

    /** Find a track by its Spotify URI, or null if not found. */
    @Query("SELECT * FROM tracks WHERE spotify_uri = :spotifyUri LIMIT 1")
    suspend fun findBySpotifyUri(spotifyUri: String): TrackEntity?

    /** Find a track by its YouTube video ID, or null if not found. */
    @Query("SELECT * FROM tracks WHERE youtube_id = :youtubeId LIMIT 1")
    suspend fun findByYoutubeId(youtubeId: String): TrackEntity?

    /**
     * Find a track by its canonical title + artist combination.
     * Used for cross-source deduplication.
     */
    @Query(
        """
        SELECT * FROM tracks
        WHERE canonical_title = :title AND canonical_artist = :artist
        LIMIT 1
        """
    )
    suspend fun findByCanonicalIdentity(title: String, artist: String): TrackEntity?

    /**
     * Consolidated lookup used by the sync diff pass: matches by Spotify
     * URI first, then YouTube ID, then canonical identity — in the same
     * priority order as the three separate calls it replaces. Folds the
     * old 3-query N+1 into a single DB round-trip per remote track.
     *
     * Pass `null` for any identifier you don't have; the `:xIsNull` flag
     * lets us skip that branch cheaply (SQLite short-circuits the OR).
     * Canonical fallback is always required — callers normalise the
     * remote title/artist before calling.
     */
    @Query(
        """
        SELECT * FROM tracks
        WHERE (:spotifyUriIsNull = 0 AND spotify_uri = :spotifyUri)
           OR (:youtubeIdIsNull = 0 AND youtube_id = :youtubeId)
           OR (canonical_title = :canonicalTitle AND canonical_artist = :canonicalArtist)
        ORDER BY
            CASE
                WHEN :spotifyUriIsNull = 0 AND spotify_uri = :spotifyUri THEN 0
                WHEN :youtubeIdIsNull = 0 AND youtube_id = :youtubeId THEN 1
                ELSE 2
            END
        LIMIT 1
        """
    )
    suspend fun findByAnyIdentity(
        spotifyUri: String?,
        spotifyUriIsNull: Int,
        youtubeId: String?,
        youtubeIdIsNull: Int,
        canonicalTitle: String,
        canonicalArtist: String,
    ): TrackEntity?

    /** Find a track by primary key. */
    @Query("SELECT * FROM tracks WHERE id = :trackId LIMIT 1")
    suspend fun getById(trackId: Long): TrackEntity?

    // ── Download tracking ────────────────────────────────────────────────

    /**
     * Atomically marks a track as downloaded and records its file path,
     * size, and (optionally) its delivered audio-quality columns.
     *
     * `sampleRateHz` and `bitsPerSample` are nullable for backward compat
     * with non-lossless callers (yt-dlp path doesn't surface them) and use
     * `COALESCE` so passing null preserves any value populated by an
     * earlier download — important when the lossy fallback runs after a
     * lossless attempt completed.
     *
     * @param trackId       Primary key of the track.
     * @param filePath      Absolute path to the downloaded audio file on disk.
     * @param fileSizeBytes Size of the downloaded file in bytes.
     * @param downloadedAt  Wall-clock millis the row was marked complete.
     * @param sampleRateHz  Audio sample rate in Hz, or null to preserve existing.
     * @param bitsPerSample Audio bit-depth, or null to preserve existing.
     */
    @Query(
        """
        UPDATE tracks
        SET is_downloaded = 1,
            file_path = :filePath,
            file_size_bytes = :fileSizeBytes,
            date_added = :downloadedAt,
            sample_rate_hz = COALESCE(:sampleRateHz, sample_rate_hz),
            bits_per_sample = COALESCE(:bitsPerSample, bits_per_sample)
        WHERE id = :trackId
        """
    )
    suspend fun markAsDownloaded(
        trackId: Long,
        filePath: String,
        fileSizeBytes: Long,
        downloadedAt: Long = System.currentTimeMillis(),
        sampleRateHz: Int? = null,
        bitsPerSample: Int? = null,
    )

    /**
     * v0.9.26 — fill `album` on a row that previously had no album recorded,
     * using the value the user provided via album-context download (Album
     * Discovery's "Download all" or per-row download from the album page).
     * No-op when the column already has a value, so prior cross-identity
     * matches don't get clobbered.
     *
     * Without this, an existing tracks row matched by canonical identity
     * (same artist+title from a Spotify sync, no album recorded) stays
     * with album = "" and the Library Albums tab still doesn't see it
     * even though the user just downloaded it from a specific album page.
     */
    @Query("UPDATE tracks SET album = :album WHERE id = :trackId AND album = ''")
    suspend fun updateAlbumIfEmpty(trackId: Long, album: String)

    /** v0.9.26 — see [updateAlbumIfEmpty]; same shape for the new column. */
    @Query("UPDATE tracks SET album_artist = :albumArtist WHERE id = :trackId AND album_artist = ''")
    suspend fun updateAlbumArtistIfEmpty(trackId: Long, albumArtist: String)

    /**
     * v0.9.26 — rows the album-metadata backfill should attempt to fill in.
     * Only `is_downloaded = 1 AND album = ''` rows with a non-null file path
     * are candidates (we need the file on disk to read its embedded tag).
     */
    @Query(
        """
        SELECT id, file_path AS filePath
        FROM tracks
        WHERE is_downloaded = 1
          AND (album = '' OR album_artist = '')
          AND file_path IS NOT NULL
        """
    )
    suspend fun tracksNeedingAlbumBackfill(): List<TrackBackfillRow>

    /**
     * Records the codec and bitrate of a downloaded file. Called immediately
     * after [markAsDownloaded] from the sync path, with values read from the
     * file's own container via `AudioDurationExtractor.extract`. Historically
     * these columns sat at their defaults (`"opus"` / `0`) for every
     * sync-downloaded row even though the file on disk was AAC 128 — Library
     * Health relies on these being truthful to compute format-141 yield etc.
     */
    @Query(
        """
        UPDATE tracks
        SET file_format = :fileFormat,
            quality_kbps = :qualityKbps
        WHERE id = :trackId
        """
    )
    suspend fun setFormatAndQuality(trackId: Long, fileFormat: String, qualityKbps: Int)

    /**
     * Unconditionally overwrites `duration_ms`. Distinct from the older
     * [fillMissingDuration] which guards on `duration_ms = 0`. Used by the
     * download path to reconcile cases where Spotify's track length and
     * the YouTube match's actual length disagree (live cuts, extended
     * versions). Don't call this for stub tracks pre-download — use
     * [fillMissingDuration] if you only want to fill on absence.
     */
    @Query("UPDATE tracks SET duration_ms = :durationMs WHERE id = :trackId")
    suspend fun setDuration(trackId: Long, durationMs: Long)

    // ── Library Health ──────────────────────────────────────────────────

    /**
     * Returns the format/bitrate breakdown of every downloaded track.
     * Bitrates are bucketed in SQL into the standard music-codec bands so
     * the UI doesn't have to render a histogram with one column per
     * literal kbps value (Opus VBR alone produces dozens of distinct
     * numbers). Used by the Library Health screen.
     */
    @Query(
        """
        SELECT
            file_format AS format,
            CASE
                WHEN quality_kbps = 0 THEN 'unknown'
                WHEN quality_kbps < 100 THEN '<100'
                WHEN quality_kbps BETWEEN 100 AND 144 THEN '~128'
                WHEN quality_kbps BETWEEN 145 AND 192 THEN '~160'
                WHEN quality_kbps BETWEEN 193 AND 244 THEN '~192-224'
                WHEN quality_kbps BETWEEN 245 AND 288 THEN '~256'
                WHEN quality_kbps BETWEEN 289 AND 360 THEN '~320'
                ELSE '>320'
            END AS kbpsBucket,
            COUNT(*) AS trackCount,
            AVG(quality_kbps) AS avgKbps
        FROM tracks
        WHERE is_downloaded = 1
        GROUP BY format, kbpsBucket
        ORDER BY trackCount DESC
        """
    )
    suspend fun getLibraryHealthBuckets(): List<LibraryHealthBucket>

    /**
     * Returns id + filePath for every downloaded track that's still
     * sitting at the historical defaults (`file_format = "opus"` AND
     * `quality_kbps = 0`). Used by the Library Health backfill — after
     * the v0.8.1 download path started writing real metadata, anything
     * left at defaults is a row that pre-dates the fix and can be
     * populated by reading the file once.
     */
    @Query(
        """
        SELECT id, file_path AS filePath
        FROM tracks
        WHERE is_downloaded = 1
          AND file_path IS NOT NULL
          AND file_format = 'opus'
          AND quality_kbps = 0
        """
    )
    suspend fun getRowsNeedingFormatBackfill(): List<TrackBackfillRow>

    /**
     * Returns id + filePath for every downloaded track whose file_size_bytes
     * is still 0 — typically rows from legacy download paths that never
     * populated the column. Used by the Library Health backfill: read the
     * actual size from disk and write it back so the Home Storage stat is
     * accurate. Mirror of [getRowsNeedingFormatBackfill].
     */
    @Query(
        """
        SELECT id, file_path AS filePath
        FROM tracks
        WHERE is_downloaded = 1
          AND file_path IS NOT NULL
          AND file_size_bytes = 0
        """
    )
    suspend fun getRowsNeedingSizeBackfill(): List<TrackBackfillRow>

    /**
     * Updates a single row's file_size_bytes. Companion to [markAsDownloaded]
     * for the backfill path that reads sizes from disk after the fact.
     */
    @Query(
        """
        UPDATE tracks
        SET file_size_bytes = :sizeBytes
        WHERE id = :trackId
        """
    )
    suspend fun setFileSize(trackId: Long, sizeBytes: Long)

    /**
     * Returns up to 500 downloaded lossless tracks whose `bits_per_sample`
     * or `sample_rate_hz` is still NULL. Used by [QualityInfoBackfillWorker]
     * to fill in quality metadata for tracks downloaded before v0.9.11.
     *
     * Codec set mirrors the Library lossless filter so we don't pointlessly
     * probe Opus/MP3/AAC files where bit-depth has no meaning.
     */
    @Query(
        """
        SELECT * FROM tracks
        WHERE is_downloaded = 1
          AND file_path IS NOT NULL
          AND LOWER(file_format) IN ('flac', 'alac', 'wav', 'ape', 'tta', 'wv', 'aiff')
          AND (bits_per_sample IS NULL OR sample_rate_hz IS NULL)
        LIMIT 500
        """
    )
    suspend fun getMissingQualityInfo(): List<TrackEntity>

    /**
     * Writes a single track's quality metadata. Called per-row by the
     * backfill worker after `AudioDurationExtractor.extract` reads the
     * file. Pass nulls to leave columns untouched (no COALESCE here —
     * the worker only calls this when at least one value is non-null).
     */
    @Query(
        """
        UPDATE tracks
        SET sample_rate_hz = COALESCE(:sampleRateHz, sample_rate_hz),
            bits_per_sample = COALESCE(:bitsPerSample, bits_per_sample)
        WHERE id = :trackId
        """
    )
    suspend fun updateQualityInfo(trackId: Long, sampleRateHz: Int?, bitsPerSample: Int?)

    // ── Loudness normalization (v0.9.25) ────────────────────────────────

    /**
     * Returns up to [limit] downloaded tracks whose loudness has not yet
     * been measured. The `loudness_measured_at IS NULL` filter skips both
     * successfully-measured rows AND failed-sentinel rows (NaN-marked) —
     * any measurement attempt, success or failure, writes the timestamp.
     *
     * `file_path IS NOT NULL` excludes stub rows (queued for download but
     * not yet on disk); the backfill worker can only measure files we have.
     *
     * A separate weekly resurrection query (added later in the plan) picks
     * up the failed-sentinel rows after a cooldown for a single retry pass.
     */
    @Query(
        """
        SELECT * FROM tracks
        WHERE loudness_measured_at IS NULL
          AND file_path IS NOT NULL
        LIMIT :limit
        """
    )
    suspend fun tracksNeedingLoudness(limit: Int): List<TrackEntity>

    /**
     * Writes a successful loudness measurement: integrated loudness in
     * LUFS, true-peak in dBFS, and the wall-clock timestamp of the
     * measurement. Called by the backfill worker / TrackFinalizer after
     * FFmpeg ebur128 returns valid numbers.
     */
    @Query(
        """
        UPDATE tracks
        SET loudness_lufs = :lufs,
            true_peak_dbfs = :peak,
            loudness_measured_at = :now
        WHERE id = :id
        """
    )
    suspend fun updateLoudness(id: Long, lufs: Float, peak: Float, now: Long)

    /**
     * Marks a track's measurement as attempted-and-failed. Writes
     * `Float.NaN` (intent-level sentinel) to `loudness_lufs` and the
     * wall-clock timestamp to `loudness_measured_at`; `true_peak_dbfs`
     * is left untouched (no UPDATE clause for it).
     *
     * **Round-trip note:** Android/Room's `bindDouble` + SQLite normalise
     * NaN to NULL on storage — readback of `loudness_lufs` after this
     * call comes back as `null`, not NaN. The actual failure-vs-pristine
     * discriminator is `loudness_measured_at`: pristine rows have it null,
     * any attempt (success OR failure) sets it. [tracksNeedingLoudness]
     * is built around this physical discriminator, so failed rows are
     * correctly excluded. The downstream gain computer reads null LUFS
     * as "no gain to apply" — semantically identical to the
     * never-measured branch from the user's perspective.
     *
     * A separate weekly resurrection query (added later in the plan)
     * picks up the failed-sentinel rows after a cooldown for a single
     * retry attempt in case the failure was transient (decode error,
     * file moved during scan, etc).
     */
    @Query(
        """
        UPDATE tracks
        SET loudness_lufs = :nanSentinel,
            loudness_measured_at = :now
        WHERE id = :id
        """
    )
    suspend fun markLoudnessFailed(id: Long, now: Long, nanSentinel: Float = Float.NaN)

    // ── Play tracking ───────────────────────────────────────────────────

    /** Atomically increment [play_count] for the given track. */
    @Query("UPDATE tracks SET play_count = play_count + 1 WHERE id = :trackId")
    suspend fun incrementPlayCount(trackId: Long)

    /** Update the [last_played] timestamp for the given track. */
    @Query("UPDATE tracks SET last_played = :timestamp WHERE id = :trackId")
    suspend fun updateLastPlayed(trackId: Long, timestamp: Long)

    /**
     * v0.9.27: Returns the most recently played track that is actually
     * downloaded. Used by the playback service to fulfill the Android Auto
     * Media Resumption contract (onPlaybackResumption).
     */
    @Query("SELECT * FROM tracks WHERE is_downloaded = 1 AND last_played IS NOT NULL ORDER BY last_played DESC LIMIT 1")
    suspend fun getLastPlayedTrack(): TrackEntity?

    /**
     * v0.9.13: Mark a track as saved to Spotify Liked Songs.
     * Called by [LikeDestinationDispatcher] after a successful
     * `PUT /v1/me/tracks` call. Forward-only; once set, never cleared
     * by Stash (user can clear externally via Spotify Web).
     */
    @Query("UPDATE tracks SET spotify_saved_at = :ts WHERE id = :trackId")
    suspend fun markSpotifySaved(trackId: Long, ts: Long)

    /**
     * v0.9.13: Mark a track as liked on YouTube Music.
     * Called after a successful InnerTube `like/like` call.
     */
    @Query("UPDATE tracks SET ytmusic_saved_at = :ts WHERE id = :trackId")
    suspend fun markYtMusicSaved(trackId: Long, ts: Long)

    /**
     * v0.9.13: Mark a track as added to the local Stash "Liked Songs"
     * playlist. Called by [StashLikedPlaylistRepository.add] after the
     * cross-ref is in place.
     */
    @Query("UPDATE tracks SET stash_liked_at = :ts WHERE id = :trackId")
    suspend fun markStashLiked(trackId: Long, ts: Long)

    /**
     * v0.9.13: Clear the Stash-liked timestamp. Paired with
     * [StashLikedPlaylistRepository.remove] when the user un-likes a
     * track via the heart toggle. Spotify/YT timestamps are NOT
     * cleared by Stash unlike — those remain forward-only by design
     * (see auto-save scrobbler one-way contract).
     */
    @Query("UPDATE tracks SET stash_liked_at = NULL WHERE id = :trackId")
    suspend fun clearStashLiked(trackId: Long)

    /**
     * v0.9.13: One-shot startup backfill. Returns id + filePath for
     * every downloaded track stuck at the legacy `file_format = 'opus'`
     * default. The caller derives the real codec from the path
     * extension and writes it back via [updateFileFormat].
     */
    @Query(
        """
        SELECT id, file_path FROM tracks
        WHERE is_downloaded = 1
          AND LOWER(file_format) = 'opus'
          AND file_path IS NOT NULL
          AND file_path != ''
        """
    )
    suspend fun getOpusDefaultedTracks(): List<TrackPathRow>

    /** v0.9.13: Pair with [getOpusDefaultedTracks] to write the corrected codec. */
    @Query("UPDATE tracks SET file_format = :format WHERE id = :id")
    suspend fun updateFileFormat(id: Long, format: String)

    /**
     * v0.9.13: Live-observe a track's three Like-state timestamps.
     * Now Playing subscribes to this so the heart icon reflects the
     * persisted state on every screen open, not the stale snapshot
     * cached in the player's in-memory Track.
     */
    @Query("SELECT id, stash_liked_at, spotify_saved_at, ytmusic_saved_at FROM tracks WHERE id = :trackId")
    fun observeLikeState(trackId: Long): Flow<TrackLikeState?>

    /**
     * v0.9.13: Live-observe a full track row by id. Now Playing uses
     * this as the canonical source for currentTrack — the Player only
     * provides id+title+artist+album+art via MediaItem extras, but
     * every other field (filePath, fileFormat, like timestamps,
     * bit-depth, sample rate, quality, youtubeId, spotifyUri, etc.)
     * has to come from the database. Without this, the codec badge
     * shows "OPUS" for every track and the heart icon fails to
     * persist across screen open/close.
     */
    @Query("SELECT * FROM tracks WHERE id = :trackId")
    fun observeById(trackId: Long): Flow<TrackEntity?>

    /**
     * v0.9.13: Count of tracks marked as auto-saved to Spotify since
     * `sinceMs`. Drives the Settings diagnostics line.
     */
    @Query("SELECT COUNT(*) FROM tracks WHERE spotify_saved_at > :sinceMs")
    fun autoSavedSinceCount(sinceMs: Long): Flow<Int>

    /**
     * Backfill: set date_added to now for all downloaded Spotify tracks.
     * These tracks had date_added set at sync time (when discovered), not
     * download time. This one-time fix makes them appear in Recently Added.
     */
    @Query("UPDATE tracks SET date_added = :now WHERE is_downloaded = 1 AND source = 'SPOTIFY'")
    suspend fun backfillSpotifyDateAdded(now: Long = System.currentTimeMillis())

    /**
     * One-time cleanup: removes all seeder-inserted "filler" tracks.
     *
     * The original DatabaseSeeder populated the library with 25 fake tracks
     * (Blinding Lights, Levitating, Peaches, etc.) marked as downloaded with
     * fake file paths under `/storage/emulated/0/Stash/`. Real downloads are
     * always stored under the app's internal files directory, so this LIKE
     * clause uniquely identifies seeder rows without touching user data.
     *
     * @return The number of rows deleted.
     */
    @Query("DELETE FROM tracks WHERE file_path LIKE '/storage/emulated/0/Stash/%'")
    suspend fun deleteSeederTracks(): Int

    // ── Orphan detection ───────────────────────────────────────────────

    /**
     * Returns downloaded tracks that have no active playlist membership.
     *
     * A track is "orphaned" when it was part of a daily mix that refreshed
     * (its playlist_tracks rows were cleared) and it does not belong to any
     * other playlist (liked songs, custom, or another mix).
     *
     * Excludes tracks with source = 'BOTH' because those are local/custom
     * imports that should never be auto-deleted.
     */
    @Query(
        """
        SELECT t.* FROM tracks t
        WHERE t.is_downloaded = 1
          AND t.source != 'BOTH'
          AND t.id NOT IN (
              SELECT pt.track_id FROM playlist_tracks pt
              WHERE pt.removed_at IS NULL
          )
        """
    )
    suspend fun getOrphanedDownloadedTracks(): List<TrackEntity>

    // ── Full-text search ────────────────────────────────────────────────

    /**
     * Search tracks by title, artist, or album using FTS4.
     *
     * The query string supports SQLite FTS match syntax (e.g. prefix
     * searches with `*`).
     */
    @Query(
        """
        SELECT tracks.* FROM tracks
        JOIN tracks_fts ON tracks.rowid = tracks_fts.rowid
        LEFT JOIN track_blocklist bl
            ON bl.canonical_key = (tracks.canonical_artist || '|' || tracks.canonical_title)
            OR (bl.spotify_uri IS NOT NULL AND bl.spotify_uri = tracks.spotify_uri)
            OR (bl.youtube_id  IS NOT NULL AND bl.youtube_id  = tracks.youtube_id)
        WHERE tracks_fts MATCH :query
          AND bl.canonical_key IS NULL
        """
    )
    fun search(query: String): Flow<List<TrackEntity>>

    /**
     * Search only downloaded tracks by title, artist, or album using FTS4.
     */
    @Query(
        """
        SELECT tracks.* FROM tracks
        JOIN tracks_fts ON tracks.rowid = tracks_fts.rowid
        LEFT JOIN track_blocklist bl
            ON bl.canonical_key = (tracks.canonical_artist || '|' || tracks.canonical_title)
            OR (bl.spotify_uri IS NOT NULL AND bl.spotify_uri = tracks.spotify_uri)
            OR (bl.youtube_id  IS NOT NULL AND bl.youtube_id  = tracks.youtube_id)
        WHERE tracks_fts MATCH :query
          AND bl.canonical_key IS NULL
          AND tracks.is_downloaded = 1
        """
    )
    fun searchDownloaded(query: String): Flow<List<TrackEntity>>

    // ── Count / storage queries ─────────────────────────────────────────

    /** Total number of downloaded tracks (reactive). */
    @Query("SELECT COUNT(*) FROM tracks WHERE is_downloaded = 1")
    fun getTotalCount(): Flow<Int>

    /** Total number of downloaded tracks (one-shot). */
    @Query("SELECT COUNT(*) FROM tracks WHERE is_downloaded = 1")
    suspend fun getCount(): Int

    /** Sum of all downloaded track file sizes in bytes (reactive). */
    @Query("SELECT COALESCE(SUM(file_size_bytes), 0) FROM tracks WHERE is_downloaded = 1")
    fun getTotalStorageBytes(): Flow<Long>

    /**
     * Count of downloaded lossless tracks (reactive). Mirrors the codec
     * set used by the Library screen's "FLAC" filter (see
     * `LibraryViewModel.LOSSLESS_CODECS` and `core/ui/.../FlacBadge.kt`)
     * so Home and Library agree on what counts as lossless. Case-
     * insensitive because some writers (e.g. `LocalImportCoordinator`
     * which writes the raw file extension) can produce uppercase or
     * mixed-case format strings — this query catches them all.
     */
    @Query(
        """
        SELECT COUNT(*)
        FROM tracks
        WHERE is_downloaded = 1
          AND LOWER(file_format) IN ('flac', 'alac', 'wav', 'ape', 'tta', 'wv', 'aiff')
        """
    )
    fun getFlacCount(): Flow<Int>

    /**
     * Sum of file sizes (bytes) for downloaded lossless tracks (reactive).
     * Codec set + case-insensitivity mirror [getFlacCount] — see KDoc there.
     */
    @Query(
        """
        SELECT COALESCE(SUM(file_size_bytes), 0)
        FROM tracks
        WHERE is_downloaded = 1
          AND LOWER(file_format) IN ('flac', 'alac', 'wav', 'ape', 'tta', 'wv', 'aiff')
        """
    )
    fun getFlacStorageBytes(): Flow<Long>

    /** Count of downloaded tracks from Spotify. */
    @Query("SELECT COUNT(*) FROM tracks WHERE is_downloaded = 1 AND source = 'SPOTIFY'")
    fun getSpotifyDownloadedCount(): Flow<Int>

    /** Count of downloaded tracks from YouTube. */
    @Query("SELECT COUNT(*) FROM tracks WHERE is_downloaded = 1 AND source = 'YOUTUBE'")
    fun getYouTubeDownloadedCount(): Flow<Int>

    // ── Aggregate queries ───────────────────────────────────────────────

    /**
     * All distinct artists with their track count and total duration.
     * Ordered by artist name ascending.
     */
    @Query(
        """
        SELECT artist,
               COUNT(*) AS trackCount,
               SUM(duration_ms) AS totalDurationMs,
               album_art_url AS artUrl
        FROM tracks
        GROUP BY artist
        ORDER BY COUNT(*) DESC, artist ASC
        """
    )
    fun getAllArtists(): Flow<List<ArtistSummary>>

    /**
     * All distinct albums with their primary artist, track count, and
     * local art path. Ordered by track count then album name.
     *
     * v0.9.26 — grouped by album only (not `album, artist`). Multi-artist
     * albums like Drake & 21 Savage's "Her Loss" or Drake & PartyNextDoor's
     * "Some Sexy Songs 4 U" have per-track artist credits that vary
     * ("Drake", "Drake, 21 Savage", "21 Savage", "Drake, PARTYNEXTDOOR",
     * etc.), which under the old GROUP BY produced one fragment per
     * artist-string permutation. The display artist is picked as the
     * most-frequent artist value within the album — for SSS4U that's
     * "Drake, PARTYNEXTDOOR" since both feature on most tracks.
     * MAX(art) is a deterministic tie-break; album art is consistent
     * across an album's tracks in practice.
     */
    @Query(
        """
        SELECT t.album AS album,
               CASE
                   WHEN t.album_artist != '' THEN t.album_artist
                   ELSE (
                       SELECT artist FROM tracks
                       WHERE album = t.album AND artist != ''
                         AND COALESCE(album_artist, '') = COALESCE(t.album_artist, '')
                       GROUP BY artist
                       ORDER BY COUNT(*) DESC
                       LIMIT 1
                   )
               END AS artist,
               COUNT(*) AS trackCount,
               MAX(t.album_art_path) AS artPath,
               MAX(t.album_art_url) AS artUrl
        FROM tracks t
        WHERE t.album != ''
        GROUP BY t.album, t.album_artist
        ORDER BY COUNT(*) DESC, t.album ASC
        """
    )
    fun getAllAlbums(): Flow<List<AlbumSummary>>

    // ── Match dismissal & reconciliation ────────────────────────────────

    /** Mark a track as permanently dismissed from matching. */
    @Query("UPDATE tracks SET match_dismissed = 1 WHERE id = :trackId")
    suspend fun dismissMatch(trackId: Long)

    /** Set the YouTube video ID for a track so future syncs don't re-queue it. */
    @Query("UPDATE tracks SET youtube_id = :youtubeId WHERE id = :trackId")
    suspend fun updateYoutubeId(trackId: Long, youtubeId: String)

    /**
     * Set the cached canonical ATV/OMV video id for this track. Called once
     * per track by [com.stash.core.data.youtube.YtCanonicalResolver] when it
     * resolves a non-ATV/OMV track via InnerTube search. Never re-runs —
     * the resolver only fills when `yt_canonical_video_id IS NULL`.
     */
    @Query(
        """
        UPDATE tracks
        SET yt_canonical_video_id = :videoId
        WHERE id = :trackId
          AND yt_canonical_video_id IS NULL
        """
    )
    suspend fun updateYtCanonicalVideoId(trackId: Long, videoId: String)

    /**
     * Atomically refresh the display + lookup metadata for a track after
     * [com.stash.data.download.matching.YtLibraryCanonicalizer] swaps its
     * videoId. The title/canonical fields drive playlist display, search,
     * and dedup; album + album_art_url drive the playlist mosaic; duration
     * keeps playback scrubbing accurate.
     *
     * [album], [albumArtUrl] are COALESCE'd so a null/blank source
     * (yt-dlp fallback results don't carry album metadata) doesn't wipe
     * out an existing value. [durationMs] uses CASE so zero/unknown
     * durations don't overwrite a known one.
     */
    @Query(
        """
        UPDATE tracks
        SET title = :title,
            canonical_title = :canonicalTitle,
            canonical_artist = :canonicalArtist,
            album = CASE WHEN :album IS NULL OR :album = '' THEN album ELSE :album END,
            album_art_url = COALESCE(:albumArtUrl, album_art_url),
            duration_ms = CASE WHEN :durationMs > 0 THEN :durationMs ELSE duration_ms END
        WHERE id = :trackId
        """
    )
    suspend fun updateCanonicalMetadata(
        trackId: Long,
        title: String,
        canonicalTitle: String,
        canonicalArtist: String,
        album: String?,
        albumArtUrl: String?,
        durationMs: Long,
    )

    /**
     * Fill-only-if-blank variant for use by [DownloadManager.resolveUrl]
     * when a plain YouTube search finds a matched track for a stub. The
     * prototypical caller is the Stash-Discover pipeline: it creates
     * tracks with null album art, blank album, zero duration, null
     * youtube_id — every field we could pull from the match. Unlike
     * [updateCanonicalMetadata], this never overwrites a populated
     * field, so Spotify-sourced tracks (where album/art came from the
     * Spotify API on sync) don't get their metadata rewritten to
     * whatever YouTube thinks it should be.
     */
    @Query(
        """
        UPDATE tracks
        SET album = CASE WHEN album IS NULL OR album = '' THEN :album ELSE album END,
            album_art_url = CASE
                WHEN album_art_url IS NULL OR album_art_url = '' THEN :albumArtUrl
                ELSE album_art_url
            END,
            duration_ms = CASE WHEN duration_ms = 0 THEN :durationMs ELSE duration_ms END,
            youtube_id = CASE
                WHEN youtube_id IS NULL OR youtube_id = '' THEN :youtubeId
                ELSE youtube_id
            END
        WHERE id = :trackId
        """
    )
    suspend fun fillMissingMetadata(
        trackId: Long,
        album: String?,
        albumArtUrl: String?,
        durationMs: Long,
        youtubeId: String?,
    )

    /**
     * Art-only sibling of [fillMissingMetadata]. Used by the art-backfill
     * worker and as a narrow fallback from the match pipeline when the
     * only field we have is an album-art URL (e.g. Last.fm `track.getInfo`
     * returned art but no other canonical metadata we'd want to write).
     *
     * Kept separate from [fillMissingMetadata] because that query also
     * touches `youtube_id`, which has a UNIQUE index — if two Discovery
     * candidates resolve to the same YouTube video, the second fillMissing
     * fails the whole row via SQLiteConstraintException and the art write
     * is lost collaterally. This single-column update side-steps that.
     */
    @Query(
        """
        UPDATE tracks
        SET album_art_url = :albumArtUrl
        WHERE id = :trackId
          AND (album_art_url IS NULL OR album_art_url = '')
        """
    )
    suspend fun fillMissingAlbumArtUrl(trackId: Long, albumArtUrl: String)

    /**
     * Unconditional album-art URL overwrite for the existing-track branch
     * in DiffWorker. Used when a re-sync brings a snapshot whose
     * `albumArtUrl` differs from what's stored — typically because the
     * upgrader gained new CDN support and stale rows still hold the
     * pre-upgrade URL. Companion to [fillMissingAlbumArtUrl] but without
     * the "only-if-blank" guard. Caller is responsible for not passing
     * a downgrade (e.g. only call when the incoming URL has flowed
     * through [com.stash.core.common.ArtUrlUpgrader]).
     */
    @Query("UPDATE tracks SET album_art_url = :albumArtUrl WHERE id = :trackId")
    suspend fun updateAlbumArtUrl(trackId: Long, albumArtUrl: String)

    /**
     * Duration-only sibling of [fillMissingMetadata]. Used by the primary
     * download path post-markAsDownloaded and by [ArtBackfillWorker]'s
     * duration pass. Guarded by `duration_ms = 0` so known-good durations
     * from Spotify / match pipelines aren't overwritten.
     */
    @Query(
        """
        UPDATE tracks
        SET duration_ms = :durationMs
        WHERE id = :trackId
          AND duration_ms = 0
        """
    )
    suspend fun fillMissingDuration(trackId: Long, durationMs: Long)

    /**
     * Candidate projection for [ArtBackfillWorker]'s duration pass:
     * downloaded tracks whose duration is still zero but whose file is
     * on disk (so `MediaMetadataRetriever` can extract the real value).
     *
     * Cheaper than [findArtBackfillCandidates] because this only needs
     * the file path — no network round-trips are implied by a hit in
     * this result set. Same `LIMIT` safety cap so a huge backlog can't
     * take down the worker.
     */
    @Query(
        """
        SELECT id, file_path
        FROM tracks
        WHERE is_downloaded = 1
          AND duration_ms = 0
          AND file_path IS NOT NULL
          AND file_path != ''
        LIMIT :limit
        """
    )
    suspend fun findDurationBackfillCandidates(limit: Int): List<DurationBackfillRow>

    /**
     * Candidate projection for [ArtBackfillWorker]: downloaded tracks whose
     * art is still missing. Returns the minimum fields needed to attempt
     * a backfill: the track id for the final UPDATE, artist + title for
     * the Last.fm `track.getInfo` lookup, and `youtube_id` as the last-
     * resort fallback (synthetic `https://i.ytimg.com/vi/<id>/hqdefault.jpg`).
     * Only rows where the backfill has any chance of succeeding are
     * returned — tracks with a blank artist or title can't match anything
     * upstream, so we skip them entirely.
     */
    @Query(
        """
        SELECT id, artist, title, youtube_id
        FROM tracks
        WHERE is_downloaded = 1
          AND (album_art_url IS NULL OR album_art_url = '')
          AND artist != ''
          AND title != ''
        LIMIT :limit
        """
    )
    suspend fun findArtBackfillCandidates(limit: Int): List<ArtBackfillRow>

    /**
     * Atomically reverts a track to an undownloaded state so the download
     * pipeline will re-resolve + re-fetch it. Used by the YT-library
     * backfill worker to force canonicalization on tracks whose videoId
     * currently points at an OMV / UGC / PODCAST_EPISODE upload. The
     * file_path + file_size are cleared so playback fallbacks can't try
     * to hit a file we're about to delete.
     */
    @Query(
        """
        UPDATE tracks
        SET is_downloaded = 0,
            file_path = NULL,
            file_size_bytes = 0
        WHERE id = :trackId
        """
    )
    suspend fun resetForReDownload(trackId: Long)

    /**
     * All `is_downloaded=1` rows with their stored `file_path`. Drives the
     * startup file-integrity sweep that verifies every "downloaded" track
     * actually has a readable file on disk. A track whose file vanished
     * (user file-manager delete, SAF grant revoked, external storage
     * unmounted at the wrong moment) keeps `is_downloaded=1` until something
     * notices the file is gone — this query produces the candidate set.
     */
    @Query(
        """
        SELECT id, file_path FROM tracks
        WHERE is_downloaded = 1
        """
    )
    suspend fun getDownloadedFileRefs(): List<DownloadedFileRef>

    /**
     * Bulk-reset rows to undownloaded state. Companion to
     * [resetForReDownload] but takes a list — used by the startup integrity
     * sweep which collects all missing-file ids and resets them in one
     * statement. `quality_kbps`/`file_format` deliberately untouched: the
     * row may still hold useful metadata for a future re-download attempt.
     */
    @Query(
        """
        UPDATE tracks
        SET is_downloaded = 0,
            file_path = NULL,
            file_size_bytes = 0
        WHERE id IN (:trackIds)
        """
    )
    suspend fun bulkResetForReDownload(trackIds: List<Long>)

    /**
     * YT-source tracks the Quick-scan backfill should verify. Two routes
     * land a row in this set:
     *
     *  1. **Known-bad stored type** — `music_video_type` is OMV, UGC, or
     *     PODCAST_EPISODE from a prior Deep scan. Candidate without any
     *     InnerTube round-trip; the worker can re-queue immediately.
     *  2. **Unknown type + suspect title** — `music_video_type IS NULL`
     *     and the title matches one of the music-video / lyric-video /
     *     audio-upload markers observed on real VEVO uploads. The worker
     *     verifies via InnerTube and writes the resolved type back so
     *     the next scan is fast.
     *
     * Tracks whose stored type is ATV or OFFICIAL_SOURCE_MUSIC are
     * excluded — they're already canonical. Deep scan uses the separate
     * [getAllDownloadedYtTracks] query which ignores title and stored
     * type entirely.
     *
     * Pattern note: brackets + "HD" / "Upscaled" / "Lyric" variants were
     * added 2026-04-21 after a real-library audit showed 0 matches with
     * the old "(Official Video)"-only patterns despite 38 clearly-video
     * titles in the library (e.g. "(Official HD Video)", "(Lyric Video)",
     * "[Official Music Video]"). `(Remastered)`, `(Live)`, and bare
     * `Performance` are deliberately excluded — those are frequently
     * the user's intended version and re-downloading is likely wrong.
     */
    @Query(
        """
        SELECT * FROM tracks
        WHERE source = 'YOUTUBE'
          AND is_downloaded = 1
          AND youtube_id IS NOT NULL
          AND (
              music_video_type IN ('OMV', 'UGC', 'PODCAST_EPISODE')
              OR (
                  music_video_type IS NULL
                  AND (
                      title LIKE '%(Official Video%' COLLATE NOCASE
                      OR title LIKE '%(Official HD Video%' COLLATE NOCASE
                      OR title LIKE '%(Official Music Video%' COLLATE NOCASE
                      OR title LIKE '%(Music Video%' COLLATE NOCASE
                      OR title LIKE '%[Official Video%' COLLATE NOCASE
                      OR title LIKE '%[Official Music Video%' COLLATE NOCASE
                      OR title LIKE '%[Music Video%' COLLATE NOCASE
                      OR title LIKE '%(Lyric Video%' COLLATE NOCASE
                      OR title LIKE '%(Official Lyric Video%' COLLATE NOCASE
                      OR title LIKE '%[Lyric Video%' COLLATE NOCASE
                      OR title LIKE '%(Visualizer%' COLLATE NOCASE
                      OR title LIKE '%(Audio)%' COLLATE NOCASE
                      OR title LIKE '%(Official Audio%' COLLATE NOCASE
                      OR title LIKE '%(MV)%' COLLATE NOCASE
                      OR title LIKE '%(Video)%' COLLATE NOCASE
                      OR title LIKE '%HD Video%' COLLATE NOCASE
                  )
              )
          )
        """
    )
    suspend fun getYtSourceVideoTitleCandidates(): List<TrackEntity>

    /**
     * Every downloaded YT-source track with a resolved videoId. Used by
     * the Deep-scan backfill mode — verifies each track's live
     * musicVideoType via InnerTube regardless of title. Slow (one
     * player-endpoint call per row, capped by the worker's concurrency
     * semaphore) but the populated `music_video_type` column makes
     * subsequent Quick scans effectively instant.
     */
    @Query(
        """
        SELECT * FROM tracks
        WHERE source = 'YOUTUBE'
          AND is_downloaded = 1
          AND youtube_id IS NOT NULL
        """
    )
    suspend fun getAllDownloadedYtTracks(): List<TrackEntity>

    /**
     * Persist the per-track [MusicVideoType] resolved by the backfill
     * worker. Stored as the enum's `.name` so migrations and adhoc SQL
     * tooling can read it without needing the enum's ordering.
     */
    @Query("UPDATE tracks SET music_video_type = :type WHERE id = :trackId")
    suspend fun updateMusicVideoType(trackId: Long, type: String?)

    /**
     * v0.9.16: Track ids whose Last.fm enrichment hasn't run yet
     * (mbid + lastfm_user_playcount columns are both NULL). Used by
     * [com.stash.core.data.sync.workers.TrackInfoEnrichmentWorker]
     * to drive its batched per-day enrichment loop.
     */
    @Query(
        """
        SELECT id FROM tracks
        WHERE is_downloaded = 1
          AND mbid IS NULL
          AND lastfm_user_playcount IS NULL
        ORDER BY date_added DESC
        LIMIT :limit
        """
    )
    suspend fun findTracksNeedingLastfmEnrichment(limit: Int): List<Long>

    /**
     * v0.9.16: Persist the Last.fm `track.getInfo` payload for a single
     * track. Sentinels (mbid = "", userPlaycount = 0) are written when
     * Last.fm has no data so [findTracksNeedingLastfmEnrichment]
     * doesn't re-pick the row on subsequent passes.
     */
    @Query(
        """
        UPDATE tracks SET
            mbid = :mbid,
            lastfm_user_playcount = :userPlaycount,
            lastfm_listeners = :listeners,
            lastfm_user_loved = :userLoved
        WHERE id = :trackId
        """
    )
    suspend fun setLastfmEnrichment(
        trackId: Long,
        mbid: String?,
        userPlaycount: Int?,
        listeners: Long?,
        userLoved: Boolean,
    )

    /** Find a downloaded track by canonical identity (for auto-reconciliation). */
    @Query("""
        SELECT * FROM tracks
        WHERE is_downloaded = 1
          AND LOWER(canonical_title) = LOWER(:canonicalTitle)
          AND LOWER(canonical_artist) = LOWER(:canonicalArtist)
        LIMIT 1
    """)
    suspend fun findDownloadedByCanonical(canonicalTitle: String, canonicalArtist: String): TrackEntity?

    /**
     * Returns the canonical-key set ("$canonicalArtist|$canonicalTitle")
     * for every downloaded track. Used by [com.stash.core.data.sync.workers.StashMixRefreshWorker]'s
     * discovery pre-filter to drop Last.fm candidates that would dedup
     * to library content downstream — keeping discovery_queue PENDING
     * rows representative of genuinely-new music instead of "rediscovery"
     * hits.
     *
     * `is_downloaded = 1` restricts to playable content. Stub TrackEntities
     * (created by StashDiscoveryWorker before their files land) are excluded
     * so we don't double-filter against in-flight discoveries.
     */
    @Query(
        """
        SELECT DISTINCT (canonical_artist || '|' || canonical_title) AS k
        FROM tracks
        WHERE is_downloaded = 1
        """
    )
    suspend fun getLibraryCanonicalKeys(): List<String>

    // ── Wrong-match flagging (user-initiated) ───────────────────────────

    /**
     * Flag a track as wrongly-matched, or unflag it. Used from the Now
     * Playing overflow menu when the user realises the downloaded audio
     * doesn't match the Spotify metadata. Flagged tracks surface in the
     * Failed Matches screen so the resync flow can offer alternatives.
     */
    @Query("UPDATE tracks SET match_flagged = :flagged WHERE id = :trackId")
    suspend fun updateMatchFlagged(trackId: Long, flagged: Boolean)

    /**
     * All currently-flagged tracks. Used by the Failed Matches screen to
     * render flagged rows alongside unmatched ones. Ordered by title so
     * the list is stable across flagged-flag toggles.
     */
    @Query("SELECT * FROM tracks WHERE match_flagged = 1 ORDER BY title ASC")
    fun getFlaggedTracks(): Flow<List<TrackEntity>>

    /** Count of flagged tracks. Drives the Sync-tab warning card. */
    @Query("SELECT COUNT(*) FROM tracks WHERE match_flagged = 1")
    fun getFlaggedCount(): Flow<Int>

    /**
     * v0.9.15: Snapshot every tracks row (with its stored canonicals) for
     * the [com.stash.core.data.sync.workers.BlocklistIntegrityWorker]
     * one-shot cleanup. The worker iterates this list, checks each row's
     * canonical_artist|canonical_title against `track_blocklist`, and
     * tears down the rows whose identity matches — i.e. the v0.9.13/14-
     * era tracks that leaked back during the broken-flag-era. Returns the
     * full list (no LIMIT) because we need a stable snapshot to iterate
     * while the worker mutates the table.
     */
    @Query("SELECT * FROM tracks")
    suspend fun getAllForIntegrityScan(): List<TrackEntity>

    // ── Library candidate pool ──────────────────────────────────────────

    /**
     * v0.9.15: All downloaded tracks — the candidate pool for Stash Mix
     * recipes. The blocklist is now its own identity-keyed table
     * (`track_blocklist`); blocked tracks have their tracks-row hard
     * deleted by [com.stash.core.data.blocklist.BlocklistGuard.block], so
     * this query needs no AND clause to exclude them. (Renamed from
     * `getAllDownloadedNonBlacklisted` after the column drop in v20.)
     */
    @Query("SELECT * FROM tracks WHERE is_downloaded = 1")
    suspend fun getAllDownloaded(): List<TrackEntity>

    // ── Protected-playlist cascade helpers ───────────────────────────────

    /**
     * Returns `true` if [trackId] belongs to at least one playlist that
     * counts as "protected" — Liked Songs (either source) or an in-app
     * Stash custom playlist (type=CUSTOM AND source=BOTH). Used by the
     * cascade-delete algorithm to decide whether deleting another playlist
     * is allowed to also delete the audio file.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM playlist_tracks pt
            INNER JOIN playlists p ON p.id = pt.playlist_id
            WHERE pt.track_id = :trackId
              AND pt.removed_at IS NULL
              AND (
                  p.type = 'LIKED_SONGS'
                  OR p.type = 'STASH_LIKED'
                  OR (p.type = 'CUSTOM' AND p.source = 'BOTH')
              )
        )
        """
    )
    suspend fun isTrackInProtectedPlaylist(trackId: Long): Boolean

    /**
     * Same as [isTrackInProtectedPlaylist] but excludes [excludePlaylistId]
     * from the search. Used for delete-preview: when the user asks to
     * delete a playlist, we need to know whether each track would still
     * have a protected home *after* that playlist is gone.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM playlist_tracks pt
            INNER JOIN playlists p ON p.id = pt.playlist_id
            WHERE pt.track_id = :trackId
              AND pt.playlist_id != :excludePlaylistId
              AND pt.removed_at IS NULL
              AND (
                  p.type = 'LIKED_SONGS'
                  OR p.type = 'STASH_LIKED'
                  OR (p.type = 'CUSTOM' AND p.source = 'BOTH')
              )
        )
        """
    )
    suspend fun isTrackInProtectedPlaylistExcluding(
        trackId: Long,
        excludePlaylistId: Long,
    ): Boolean

    /**
     * Returns the count of *other* playlists (any type, any source) that
     * still claim [trackId] after excluding [excludePlaylistId]. Used as
     * the second step of the cascade algorithm — if this is zero, no
     * playlist references the track any more and its file can be removed.
     */
    @Query(
        """
        SELECT COUNT(*) FROM playlist_tracks pt
        WHERE pt.track_id = :trackId
          AND pt.playlist_id != :excludePlaylistId
          AND pt.removed_at IS NULL
        """
    )
    suspend fun countOtherPlaylistsClaimingTrack(trackId: Long, excludePlaylistId: Long): Int
}
