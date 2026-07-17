package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.flow.Flow

/**
 * Projection holding a playlist together with its associated tracks.
 *
 * @property playlist  The playlist metadata.
 * @property tracks    Ordered list of tracks belonging to the playlist.
 */
data class PlaylistWithTracks(
    val playlist: PlaylistEntity,
    val tracks: List<TrackEntity>,
)

/**
 * Data-access object for [PlaylistEntity] and the
 * [PlaylistTrackCrossRef] join table.
 */
@Dao
interface PlaylistDao {

    // ── Inserts ─────────────────────────────────────────────────────────

    /** Insert a playlist, replacing on conflict (e.g. same source_id). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(playlist: PlaylistEntity): Long

    /** Insert a cross-reference linking a track to a playlist. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrossRef(crossRef: PlaylistTrackCrossRef)

    /**
     * Returns the existing cross-reference for (playlistId, trackId) if any,
     * so the caller can preserve `addedAt` when re-inserting (otherwise
     * REPLACE would reset it every sync, breaking chronological ordering in
     * ACCUMULATE mode where we want the newest additions on top).
     */
    @Query("""
        SELECT * FROM playlist_tracks
        WHERE playlist_id = :playlistId AND track_id = :trackId
    """)
    suspend fun getCrossRef(playlistId: Long, trackId: Long): PlaylistTrackCrossRef?

    // ── Update / Delete ─────────────────────────────────────────────────

    /** Update an existing playlist entity. */
    @Update
    suspend fun update(playlist: PlaylistEntity)

    /** Delete a playlist entity. Cascades to playlist_tracks rows. */
    @Delete
    suspend fun delete(playlist: PlaylistEntity)

    /** Delete a playlist by id. Cascades to playlist_tracks rows. */
    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deleteById(playlistId: Long)

    /**
     * One-time cleanup: removes playlists created by the original
     * DatabaseSeeder. The seeder used very specific source IDs that do
     * not collide with real Spotify or YouTube Music playlists created
     * by the sync pipeline (real YouTube liked uses `youtube_liked_songs`,
     * real YouTube mixes use `VLRDTMAK5uy_*`). These five source IDs are
     * only ever present when the seeder ran on a fresh install.
     *
     * @return The number of playlist rows deleted.
     */
    @Query(
        """
        DELETE FROM playlists
        WHERE source_id IN (
            'spotify:playlist:dailymix1',
            'spotify:playlist:dailymix2',
            'spotify:collection:tracks',
            'RDMM',
            'LM'
        )
        """
    )
    suspend fun deleteSeederPlaylists(): Int

    // ── List queries ────────────────────────────────────────────────────

    /** All active (non-hidden) playlists ordered alphabetically.
     *
     *  Unfiltered by `sync_enabled` — for UI consumers use
     *  [getAllVisible] instead. This variant exists for maintenance
     *  passes (dedup, migrations) that legitimately need every active
     *  row regardless of the user's Sync Preferences toggles. */
    @Query("SELECT * FROM playlists WHERE is_active = 1 ORDER BY name ASC")
    fun getAllActive(): Flow<List<PlaylistEntity>>

    /**
     * All playlists eligible to render on Home/Library.
     *
     * Visibility is now coupled to the per-playlist `sync_enabled`
     * toggle: turning sync off in Sync Preferences hides the playlist
     * from Home + Library. The escape hatch is downloaded content —
     * if the playlist has at least one downloaded, non-blacklisted
     * track, it stays visible regardless of `sync_enabled`. This
     * preserves user investment in playlists they previously synced.
     *
     * The Sync tab continues to show every playlist via dedicated DAO
     * methods ([getSpotifyPlaylistsForPreferences],
     * [getYouTubePlaylistsForPreferences]) so the user can flip sync
     * back on for any playlist they want.
     *
     * Pre-v0.9.9 this was decoupled — `sync_enabled = 0` only meant
     * "skip on the next sync," and once-imported playlists stayed
     * forever. The change addresses the long-standing complaint that
     * upstream Spotify/YouTube libraries flooded Home with mosaics
     * for playlists the user never opted into.
     */
    /**
     * v0.9.27 — the escape-hatch EXISTS clause now considers streamable
     * tracks too when `includeStreamable = true`. Without this, a
     * sync_enabled = 0 playlist whose tracks are all stream-only would
     * disappear in Online mode even though the user can still play
     * every track in it. The `sync_enabled = 1` arm and the unconditional
     * `is_active = 1` arm are unchanged. Callers MUST pass the flag
     * explicitly — see TrackDao.getByPlaylist for rationale.
     */
    @Query("""
        SELECT p.* FROM playlists p
        WHERE p.is_active = 1
          AND (
              p.sync_enabled = 1
              OR EXISTS (
                  SELECT 1 FROM playlist_tracks pt
                  JOIN tracks t ON pt.track_id = t.id
                  WHERE pt.playlist_id = p.id
                    AND pt.removed_at IS NULL
                    AND (t.is_downloaded = 1 OR (:includeStreamable AND t.is_streamable = 1))
              )
          )
        ORDER BY p.name ASC
    """)
    fun getAllVisible(includeStreamable: Boolean): Flow<List<PlaylistEntity>>

    /** All playlists from a specific music source. */
    @Query("SELECT * FROM playlists WHERE source = :source ORDER BY name ASC")
    fun getBySource(source: MusicSource): Flow<List<PlaylistEntity>>

    /** All active playlists of a specific type, ordered alphabetically. */
    @Query("SELECT * FROM playlists WHERE type = :type AND is_active = 1 ORDER BY name ASC")
    fun getByType(type: PlaylistType): Flow<List<PlaylistEntity>>

    // ── Single-item lookups ─────────────────────────────────────────────

    /** Find a playlist by primary key. */
    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PlaylistEntity?

    /** Find a playlist by its remote source ID (e.g. Spotify playlist ID). */
    @Query("SELECT * FROM playlists WHERE source_id = :sourceId LIMIT 1")
    suspend fun findBySourceId(sourceId: String): PlaylistEntity?

    // ── Playlist with tracks ────────────────────────────────────────────

    /**
     * Load a playlist alongside all its non-removed tracks.
     *
     * Runs inside a single transaction to guarantee a consistent snapshot.
     */
    @Transaction
    suspend fun getPlaylistWithTracks(playlistId: Long): PlaylistWithTracks? {
        val playlist = getById(playlistId) ?: return null
        val tracks = getTracksForPlaylist(playlistId)
        return PlaylistWithTracks(playlist, tracks)
    }

    /** Internal helper: fetch ordered tracks for a playlist. */
    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN playlist_tracks pt ON t.id = pt.track_id
        WHERE pt.playlist_id = :playlistId AND pt.removed_at IS NULL
        ORDER BY pt.position ASC
        """
    )
    suspend fun getTracksForPlaylist(playlistId: Long): List<TrackEntity>

    /**
     * True when [trackId] appears in any active Stash Mix playlist —
     * i.e. a playlist with `type = STASH_MIX`. Stash Mixes are the
     * locally-curated rotating playlists; downloads from those should
     * always go through the lossless source if one's available, even
     * when the global lossless toggle is off.
     *
     * Returns false for tracks that exist in the library only via
     * other playlist types (custom, daily mix, liked songs, etc.).
     * The `removed_at IS NULL` clause excludes soft-deleted tracks
     * so a track that was once in a mix but isn't any more no longer
     * forces lossless mode.
     *
     * Stored as TEXT via [com.stash.core.data.db.converter.Converters.playlistTypeToString]
     * — comparing against the literal "STASH_MIX" string is correct.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM playlists p
            INNER JOIN playlist_tracks pt ON p.id = pt.playlist_id
            WHERE pt.track_id = :trackId
              AND pt.removed_at IS NULL
              AND p.type = 'STASH_MIX'
              AND p.is_active = 1
        )
        """
    )
    suspend fun isTrackInStashMix(trackId: Long): Boolean

    // ── Metadata updates ────────────────────────────────────────────────

    /** Update the cached track count for a playlist. */
    @Query("UPDATE playlists SET track_count = :count WHERE id = :playlistId")
    suspend fun updateTrackCount(playlistId: Long, count: Int)

    /** Mark a playlist as last synced at the given epoch-millis timestamp. */
    @Query("UPDATE playlists SET last_synced = :timestamp WHERE id = :playlistId")
    suspend fun updateLastSynced(playlistId: Long, timestamp: Long)

    // ── Snapshot queries ─────────────────────────────────────────────────

    /** Retrieve the snapshot ID for a playlist, used for change detection. */
    @Query("SELECT snapshot_id FROM playlists WHERE id = :playlistId LIMIT 1")
    suspend fun getSnapshotId(playlistId: Long): String?

    /** Update the snapshot ID for a playlist after a successful sync. */
    @Query("UPDATE playlists SET snapshot_id = :snapshotId WHERE id = :playlistId")
    suspend fun updateSnapshotId(playlistId: Long, snapshotId: String?)

    /** All active playlists from a specific music source. */
    @Query("SELECT * FROM playlists WHERE source = :source AND is_active = 1 ORDER BY name ASC")
    suspend fun getActivePlaylistsBySource(source: MusicSource): List<PlaylistEntity>

    /**
     * Hard-delete all track associations for a playlist. Called by
     * StashMixRefreshWorker on each refresh — the mix's tracks are
     * regenerated from scratch every time, so wiping locally-added rows
     * along with sync-added ones is intentional.
     *
     * **NOT used by DiffWorker (sync) anymore** — use
     * [clearSyncedPlaylistTracks] instead so user-added tracks survive
     * REFRESH-mode re-sync of an imported playlist.
     */
    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :playlistId")
    suspend fun clearPlaylistTracks(playlistId: Long)

    /**
     * v0.9.23 — hard-delete only the SYNC-added playlist_tracks rows.
     * User-added rows (locally_added = 1) are preserved across REFRESH
     * so manual additions to imported Spotify/YT Music playlists persist.
     * See issue #42.
     */
    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :playlistId AND locally_added = 0")
    suspend fun clearSyncedPlaylistTracks(playlistId: Long)

    /**
     * Remove a single track's membership from a specific playlist. Used by
     * the cascade-delete flow so unlinking (a) a track being removed from
     * one playlist while protected by another, and (b) a track being hard-
     * deleted, both go through the same primitive.
     */
    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :playlistId AND track_id = :trackId")
    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long)

    /**
     * v0.9.15: Hard-delete every cross-ref for [trackId] across all
     * playlists. Used by [com.stash.core.data.blocklist.BlocklistGuard.block]
     * so a blocked track stops appearing in any playlist UI immediately,
     * and so the next sync's `getUnqueuedTrackIds` doesn't see a sync-
     * enabled-playlist membership that would re-queue the download.
     */
    @Query("DELETE FROM playlist_tracks WHERE track_id = :trackId")
    suspend fun deleteAllCrossRefsForTrack(trackId: Long)

    /**
     * One-time cleanup: hard-delete all soft-deleted playlist_tracks entries.
     * These accumulate from daily mix rotations and serve no purpose after
     * the soft-delete marker is set. Reduces table bloat.
     */
    @Query("DELETE FROM playlist_tracks WHERE removed_at IS NOT NULL")
    suspend fun purgeRemovedPlaylistTracks(): Int

    // ── Sync preference queries ─────────────────────────────────────────

    /** Toggle sync_enabled for a specific playlist. */
    @Query("UPDATE playlists SET sync_enabled = :enabled WHERE id = :playlistId")
    suspend fun updateSyncEnabled(playlistId: Long, enabled: Boolean)

    /** Toggle whether a playlist is hidden from the Home rails. */
    @Query("UPDATE playlists SET hide_from_home = :hidden WHERE id = :playlistId")
    suspend fun setHideFromHome(playlistId: Long, hidden: Boolean)

    /**
     * v0.9.26 — flip `is_active` on every playlist materialized by a
     * built-in Stash Mix recipe. Used by the Stash-Mixes opt-out toggle
     * so the Daily Discover / Deep Cuts / First Listen surfaces hide from
     * Home and Library without being hard-deleted. Re-enabling restores
     * them with their existing track lists intact.
     */
    @Query(
        """
        UPDATE playlists
        SET is_active = :active
        WHERE id IN (
            SELECT playlist_id FROM stash_mix_recipes
            WHERE is_builtin = 1 AND playlist_id IS NOT NULL
        )
        """
    )
    suspend fun setActiveForBuiltinMixes(active: Boolean): Int

    /** Update the cover art URL (local file path or remote URL) for a playlist. */
    @Query("UPDATE playlists SET art_url = :artUrl WHERE id = :playlistId")
    suspend fun updateArtUrl(playlistId: Long, artUrl: String?)

    /**
     * Distinct album-art URLs of a playlist's tracks, ordered by first
     * appearance, capped at [limit]. Used to build the Stash Mix cover mosaic
     * from ALL its tracks (library + stream-only discovery survivors), so a
     * 100%-streaming mix still gets a cover. Excludes null/blank art.
     */
    @Query(
        """
        SELECT t.album_art_url FROM tracks t
        INNER JOIN playlist_tracks pt ON t.id = pt.track_id
        WHERE pt.playlist_id = :playlistId
          AND pt.removed_at IS NULL
          AND t.album_art_url IS NOT NULL
          AND t.album_art_url != ''
        GROUP BY t.album_art_url
        ORDER BY MIN(pt.position) ASC
        LIMIT :limit
        """
    )
    suspend fun getCoverArtUrlsForPlaylist(playlistId: Long, limit: Int): List<String>

    /**
     * Refreshes the user-facing name for a synced playlist. Spotify changes
     * the display name for its generated mixes over time (e.g. "Your Daily
     * Mix 1" → "Daily Mix 1" or back), so sync runs update the name to
     * whatever the remote source currently reports.
     */
    @Query("UPDATE playlists SET name = :name WHERE id = :playlistId")
    suspend fun updateName(playlistId: Long, name: String)

    /** All Spotify playlists ordered by type (liked first, then mixes, then custom) and name. */
    @Query("""
        SELECT * FROM playlists
        WHERE source = 'SPOTIFY' AND is_active = 1
        ORDER BY
            CASE type
                WHEN 'LIKED_SONGS' THEN 0
                WHEN 'CUSTOM' THEN 1
                WHEN 'DAILY_MIX' THEN 2
            END,
            name ASC
    """)
    fun getSpotifyPlaylistsForPreferences(): Flow<List<PlaylistEntity>>

    /** All YouTube Music playlists ordered by type (liked first, then mixes) and name. */
    @Query("""
        SELECT * FROM playlists
        WHERE source = 'YOUTUBE' AND is_active = 1
        ORDER BY
            CASE type
                WHEN 'LIKED_SONGS' THEN 0
                WHEN 'DAILY_MIX' THEN 1
                ELSE 2
            END,
            name ASC
    """)
    fun getYouTubePlaylistsForPreferences(): Flow<List<PlaylistEntity>>

    /**
     * One-shot data migration: flip sync_enabled on every YouTube playlist
     * that's still false. Fixes the Option A gap where playlists discovered
     * before the Sync-preference UI was extended to YouTube got stuck at
     * sync_enabled = 0 and silently skipped by DiffWorker. Called once per
     * install from [com.stash.app.StashApplication].
     *
     * @return the number of rows updated.
     */
    @Query("UPDATE playlists SET sync_enabled = 1 WHERE source = 'YOUTUBE' AND sync_enabled = 0")
    suspend fun enableAllYouTubePlaylistSync(): Int

    /**
     * One-shot data migration: hide every YouTube playlist that currently
     * has zero linked tracks. Cleans up stale "My Mix N" rows left over
     * from syncs that ran while sync_enabled was false — they were created
     * as playlist shells but never populated with tracks, then kept
     * cluttering the Home screen indefinitely because the feed rotated
     * past them. Future DiffWorker runs re-activate any such row if the
     * same mix reappears in a later snapshot.
     *
     * @return the number of rows hidden.
     */
    @Query(
        """
        UPDATE playlists SET is_active = 0
        WHERE source = 'YOUTUBE' AND is_active = 1
          AND id NOT IN (
              SELECT playlist_id FROM playlist_tracks
              WHERE removed_at IS NULL
          )
        """
    )
    suspend fun hideEmptyYouTubePlaylists(): Int

    /** All sync-enabled playlists for a given source. Used by the sync
     *  pipeline to skip disabled playlists. */
    @Query("SELECT * FROM playlists WHERE source = :source AND is_active = 1 AND sync_enabled = 1")
    suspend fun getSyncEnabledPlaylists(source: MusicSource): List<PlaylistEntity>

    /**
     * Soft-deactivate playlists from [source] whose [source_id] isn't in
     * [currentSourceIds]. Used after a sync to hide playlists that rotated
     * off the remote's home feed (e.g. a YouTube Music Home Mix that isn't
     * surfaced today). The rows stay in the DB with their track links
     * intact, so the playlist can be cheaply revived if it reappears
     * later — see [reactivateById]. Returns the number of rows flipped.
     */
    @Query(
        "UPDATE playlists SET is_active = 0 " +
            "WHERE source = :source AND is_active = 1 " +
            "AND source_id NOT IN (:currentSourceIds)"
    )
    suspend fun deactivateMissingForSource(
        source: MusicSource,
        currentSourceIds: List<String>,
    ): Int

    /** Flip [is_active] back to 1 for a specific playlist id. Paired with
     *  [deactivateMissingForSource] so a rotating home-feed mix that
     *  returns tomorrow re-surfaces on the Home screen instead of
     *  remaining silently hidden. */
    @Query("UPDATE playlists SET is_active = 1 WHERE id = :playlistId AND is_active = 0")
    suspend fun reactivateById(playlistId: Long): Int

    // ── Custom playlist management ──────────────────────────────────────

    /** Get the next available position for appending a track to a playlist. */
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_tracks WHERE playlist_id = :playlistId AND removed_at IS NULL")
    suspend fun getNextPosition(playlistId: Long): Int

    /** All user-created custom playlists (source = BOTH means local). */
    @Query("SELECT * FROM playlists WHERE type = 'CUSTOM' AND source = 'BOTH' AND is_active = 1 ORDER BY name ASC")
    fun getUserCreatedPlaylists(): Flow<List<PlaylistEntity>>

    /**
     * v0.9.23 — playlists the user can pick as a destination for
     * "Save to Playlist." Includes custom playlists AND imported
     * Spotify / YT Music CUSTOM playlists (issue #42), excludes
     * system surfaces the user shouldn't add tracks to directly
     * (Stash Mix recipes, Downloads Mix, daily mixes/liked songs).
     * LIKED_SONGS is intentionally not pickable here — Like is a
     * separate first-class action.
     *
     * v0.9.25 fix — visibility mirrors [getAllVisible]'s Home/Library
     * rule: a playlist is pickable if either (a) its sync toggle is
     * on, OR (b) it already has at least one downloaded track. This
     * matches the established invariant that "playlists you've invested
     * in stay visible regardless of sync_enabled." `source = 'BOTH'`
     * (Stash-native customs) is included as a defensive third arm so
     * brand-new empty custom playlists are always pickable as a save
     * destination, even before they have tracks.
     */
    @Query(
        """
        SELECT * FROM playlists
        WHERE is_active = 1
          AND type IN ('CUSTOM')
          AND (
              source = 'BOTH'
              OR sync_enabled = 1
              OR EXISTS (
                  SELECT 1 FROM playlist_tracks pt
                  JOIN tracks t ON pt.track_id = t.id
                  WHERE pt.playlist_id = playlists.id
                    AND pt.removed_at IS NULL
                    AND t.is_downloaded = 1
              )
          )
        ORDER BY
          CASE WHEN source = 'BOTH' THEN 0 ELSE 1 END,
          name ASC
        """
    )
    fun getPickablePlaylists(): Flow<List<PlaylistEntity>>

    /** Soft-delete a single track from a playlist. */
    @Query("UPDATE playlist_tracks SET removed_at = CURRENT_TIMESTAMP WHERE playlist_id = :playlistId AND track_id = :trackId AND removed_at IS NULL")
    suspend fun softDeleteTrackFromPlaylist(playlistId: Long, trackId: Long)

    /**
     * Returns DISTINCT track ids that appear in any of the given playlists.
     * Used by [com.stash.core.data.sync.workers.StashMixRefreshWorker]'s
     * single-recipe refresh path to seed `excludeIds` from the user's
     * currently-materialized OTHER mixes — without this, a manual refresh
     * of one mix sees an empty exclude set and naturally produces overlap
     * with the others (the very symptom PR 3's batch-mode dedup was meant
     * to fix).
     */
    @Query("SELECT DISTINCT track_id FROM playlist_tracks WHERE playlist_id IN (:playlistIds)")
    suspend fun getTrackIdsForPlaylists(playlistIds: List<Long>): List<Long>

    /**
     * The CURRENT ordered (by position) track ids of a single playlist,
     * excluding soft-removed rows. Used by
     * [com.stash.core.data.sync.workers.StashMixRefreshWorker.materializeMix]
     * to detect a no-op refresh: if the ordered list it is about to write
     * equals this, the destructive clear + reinsert (and the visible track
     * flash it causes) is skipped. Order matters — the comparison is
     * element-wise.
     */
    @Query(
        "SELECT track_id FROM playlist_tracks " +
            "WHERE playlist_id = :playlistId AND removed_at IS NULL " +
            "ORDER BY position ASC",
    )
    suspend fun getOrderedTrackIdsForPlaylist(playlistId: Long): List<Long>

    /**
     * Like [DiscoveryQueueDao.getDoneTrackIdsForRecipe] but also returns
     * ids whose track row is stream-only (`is_streamable = 1`, no
     * `is_downloaded`). The Stash Mix streaming-first rollout (v0.9.37)
     * inserts stream-only stubs from `StashDiscoveryWorker`;
     * [com.stash.core.data.sync.workers.StashMixRefreshWorker.materializeMix]
     * must surface both downloaded and streamable tracks when assembling
     * the Mix, so the rotating playlists keep filling even when new
     * discoveries never land on disk.
     *
     * Mirrors the existing query's INNER JOIN + status / track_id filters
     * exactly — the only difference is the OR-relaxed track predicate
     * (`is_downloaded = 1 OR is_streamable = 1`). The original method is
     * preserved for non-Mix callers; this one is materializer-only.
     */
    @Query(
        """
        SELECT dq.track_id FROM discovery_queue dq
        INNER JOIN tracks t ON t.id = dq.track_id
        WHERE dq.recipe_id = :recipeId
          AND dq.status = 'DONE'
          AND dq.track_id IS NOT NULL
          AND (t.is_downloaded = 1 OR t.is_streamable = 1)
        ORDER BY dq.completed_at DESC
        """
    )
    suspend fun getStreamableOrDoneTrackIdsForRecipe(recipeId: Long): List<Long>
}
