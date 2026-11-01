package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.stash.core.data.db.chunkedForBind
import com.stash.core.data.db.entity.DuplicateDismissalEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackAliasEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.db.entity.TrackMergeLogEntity

/** A canonical (title, artist) key that more than one row holds. */
data class SharedCanonicalKey(val title: String, val artist: String)

/**
 * The statements behind merging two rows of one song (schema 76): moving everything that points at
 * the merged-away row to the row that stays, the aliases that keep its ids findable, the merge log,
 * and the "Not a duplicate" marks. Only [com.stash.core.data.library.duplicates.TrackMerger] and the
 * cleanup write through it, always inside one transaction per merge.
 *
 * "Moves" use `UPDATE OR IGNORE` where the table's key includes the track id (lyrics, tags, the
 * custom order, undo snapshots, cloud uploads): the row that stays keeps its own entry, and the
 * merged-away row's leftover goes with it (CASCADE, or an explicit delete where there is no foreign
 * key).
 */
@Dao
interface DuplicateDao {

    // ── Aliases ─────────────────────────────────────────────────────────

    /** Writes [aliases]; an id already aliased is pointed at the new row (an id names one song). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAliases(aliases: List<TrackAliasEntity>)

    @Query("SELECT * FROM track_aliases WHERE kind = :kind AND value IN (:values)")
    suspend fun aliasesRaw(kind: String, values: List<String>): List<TrackAliasEntity>

    /** The aliases of [kind] among [values], chunked for the bind cap. */
    suspend fun aliases(kind: String, values: List<String>): List<TrackAliasEntity> =
        values.distinct().chunkedForBind { aliasesRaw(kind, it) }

    @Query("SELECT * FROM track_aliases WHERE track_id = :trackId")
    suspend fun aliasesOf(trackId: Long): List<TrackAliasEntity>

    /** Aliases that pointed at [loserId] point at [keeperId]: an id merged twice still finds its song. */
    @Query("UPDATE track_aliases SET track_id = :keeperId WHERE track_id = :loserId")
    suspend fun repointAliases(loserId: Long, keeperId: Long)

    // ── Playlist memberships ────────────────────────────────────────────

    @Query("SELECT * FROM playlist_tracks WHERE track_id = :trackId")
    suspend fun membershipsOf(trackId: Long): List<PlaylistTrackCrossRef>

    @Query("UPDATE playlist_tracks SET track_id = :keeperId WHERE playlist_id = :playlistId AND track_id = :loserId")
    suspend fun moveMembership(playlistId: Long, loserId: Long, keeperId: Long)

    /** The kept row's removed membership takes the merged-away row's live one: its place, date and who added it. */
    @Query(
        """
        UPDATE playlist_tracks
        SET position = :position, added_at = :addedAt, removed_at = NULL, locally_added = :locallyAdded
        WHERE playlist_id = :playlistId AND track_id = :trackId
        """,
    )
    suspend fun reviveMembership(playlistId: Long, trackId: Long, position: Int, addedAt: Long, locallyAdded: Boolean)

    @Query("UPDATE playlist_tracks SET locally_added = 1 WHERE playlist_id = :playlistId AND track_id = :trackId")
    suspend fun markUserAdded(playlistId: Long, trackId: Long)

    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :playlistId AND track_id = :trackId")
    suspend fun dropMembership(playlistId: Long, trackId: Long)

    @Query("UPDATE OR IGNORE playlist_order_entries SET track_id = :keeperId WHERE track_id = :loserId")
    suspend fun moveOrderEntries(loserId: Long, keeperId: Long)

    // ── History and caches ──────────────────────────────────────────────

    @Query("UPDATE listening_events SET track_id = :keeperId WHERE track_id = :loserId")
    suspend fun moveListeningEvents(loserId: Long, keeperId: Long): Int

    @Query("UPDATE track_skip_events SET track_id = :keeperId WHERE track_id = :loserId")
    suspend fun moveSkipEvents(loserId: Long, keeperId: Long): Int

    @Query("UPDATE discovery_queue SET track_id = :keeperId WHERE track_id = :loserId")
    suspend fun moveDiscoveryRows(loserId: Long, keeperId: Long): Int

    @Query("UPDATE OR IGNORE lyrics SET track_id = :keeperId WHERE track_id = :loserId")
    suspend fun moveLyrics(loserId: Long, keeperId: Long)

    @Query("UPDATE OR IGNORE track_tags SET track_id = :keeperId WHERE track_id = :loserId")
    suspend fun moveTags(loserId: Long, keeperId: Long)

    @Query("UPDATE OR IGNORE cloud_uploads SET track_id = :keeperId WHERE track_id = :loserId")
    suspend fun moveCloudUploads(loserId: Long, keeperId: Long)

    @Query("UPDATE OR IGNORE spotify_resolution SET trackId = :keeperId WHERE trackId = :loserId")
    suspend fun moveSpotifyResolution(loserId: Long, keeperId: Long)

    /** No foreign key on `spotify_resolution`: the merged-away row's leftover is deleted here. */
    @Query("DELETE FROM spotify_resolution WHERE trackId = :trackId")
    suspend fun dropSpotifyResolution(trackId: Long)

    @Query("UPDATE OR IGNORE sync_undo_memberships SET track_id = :keeperId WHERE track_id = :loserId")
    suspend fun moveUndoMemberships(loserId: Long, keeperId: Long)

    /** No foreign key on `sync_undo_memberships`: the merged-away row's leftovers are deleted here. */
    @Query("DELETE FROM sync_undo_memberships WHERE track_id = :trackId")
    suspend fun dropUndoMemberships(trackId: Long)

    // ── Downloads and files ─────────────────────────────────────────────

    @Query("SELECT COUNT(*) FROM download_queue WHERE track_id = :trackId")
    suspend fun downloadRowCount(trackId: Long): Int

    /** Download rows that are waiting or running: a file may still land on [trackId]. */
    @Query(
        """
        SELECT COUNT(*) FROM download_queue
        WHERE track_id = :trackId AND status IN ('PENDING', 'IN_PROGRESS', 'WAITING_FOR_LOSSLESS')
        """,
    )
    suspend fun activeDownloadCount(trackId: Long): Int

    @Query("SELECT COUNT(*) FROM download_queue WHERE track_id = :trackId AND status = 'IN_PROGRESS'")
    suspend fun runningDownloadCount(trackId: Long): Int

    /**
     * The merged-away row's waiting download request moves to the kept row (which has no file and
     * no queue row of its own), aimed at the kept row's video, or at none.
     */
    @Query(
        """
        UPDATE download_queue SET track_id = :keeperId, youtube_url = :youtubeUrl, rejected_video_id = NULL
        WHERE track_id = :loserId AND status IN ('PENDING', 'WAITING_FOR_LOSSLESS')
        """,
    )
    suspend fun moveWaitingDownloads(loserId: Long, keeperId: Long, youtubeUrl: String?): Int

    /** A cloud song's own row (a Dropbox / OneDrive file): never merged. */
    @Query("SELECT COUNT(*) FROM cloud_items WHERE track_id = :trackId")
    suspend fun cloudItemCount(trackId: Long): Int

    // ── "Not a duplicate" ───────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun dismiss(dismissals: List<DuplicateDismissalEntity>)

    @Query("SELECT * FROM duplicate_dismissals WHERE track_a = :trackId OR track_b = :trackId")
    suspend fun dismissalsOf(trackId: Long): List<DuplicateDismissalEntity>

    @Query("SELECT * FROM duplicate_dismissals")
    suspend fun allDismissals(): List<DuplicateDismissalEntity>

    // ── Merge log ───────────────────────────────────────────────────────

    @Insert
    suspend fun log(entry: TrackMergeLogEntity): Long

    @Query("SELECT * FROM track_merge_log ORDER BY at DESC, id DESC LIMIT :limit")
    suspend fun recentLog(limit: Int): List<TrackMergeLogEntity>

    @Query("SELECT COUNT(*) FROM track_merge_log WHERE reason = :reason")
    suspend fun logCount(reason: String): Int

    // ── The one-time cleanup ────────────────────────────────────────────

    /**
     * Id-less YouTube stubs after [afterId], oldest first: rows with no video id, no Spotify id and
     * no SoundCloud id, saved as YouTube (Stash Mix discoveries, and a like or listen that had no
     * video id). What the cleanup reads, [limit] at a time.
     */
    @Query(
        """
        SELECT * FROM tracks
        WHERE id > :afterId
          AND (youtube_id IS NULL OR youtube_id = '')
          AND (spotify_uri IS NULL OR spotify_uri = '')
          AND soundcloud_id IS NULL
          AND source = 'YOUTUBE'
        ORDER BY id ASC
        LIMIT :limit
        """,
    )
    suspend fun stubsAfter(afterId: Long, limit: Int): List<TrackEntity>

    /** How many id-less YouTube stubs the library holds ([stubsAfter]'s rows): the cleanup's dry-run count. */
    @Query(
        """
        SELECT COUNT(*) FROM tracks
        WHERE (youtube_id IS NULL OR youtube_id = '')
          AND (spotify_uri IS NULL OR spotify_uri = '')
          AND soundcloud_id IS NULL
          AND source = 'YOUTUBE'
        """,
    )
    suspend fun stubCount(): Int

    /**
     * Deletes the id-less stub [trackId] only if nothing uses it, checked in this one statement so a
     * sync or a mix that links it a moment earlier keeps it:
     *  - no live playlist membership (a removal mark is not a use);
     *  - never played or skipped, no play count;
     *  - not liked anywhere (Stash, Spotify, YouTube Music, Last.fm);
     *  - no file, and no waiting or running download, no lossless upgrade;
     *  - not a cloud song, not flagged as a wrong match;
     *  - not in a Stash Mix's pool: no discovery row other than a failed one points at it (the mix
     *    refresh rotates its finds in from those rows, so a stub sitting out this week's mix is
     *    still one of its songs);
     *  - no merged-away id points at it.
     *
     * @return 1 when it was deleted.
     */
    @Query(
        """
        DELETE FROM tracks
        WHERE id = :trackId
          AND (youtube_id IS NULL OR youtube_id = '')
          AND (spotify_uri IS NULL OR spotify_uri = '')
          AND soundcloud_id IS NULL
          AND source = 'YOUTUBE'
          AND match_flagged = 0
          AND is_downloaded = 0 AND (file_path IS NULL OR file_path = '')
          AND play_count = 0
          AND stash_liked_at IS NULL AND spotify_saved_at IS NULL AND ytmusic_saved_at IS NULL
          AND lastfm_loved_at IS NULL AND lastfm_user_loved = 0
          AND NOT EXISTS (SELECT 1 FROM playlist_tracks pt WHERE pt.track_id = :trackId AND pt.removed_at IS NULL)
          AND NOT EXISTS (SELECT 1 FROM listening_events le WHERE le.track_id = :trackId)
          AND NOT EXISTS (SELECT 1 FROM track_skip_events se WHERE se.track_id = :trackId)
          AND NOT EXISTS (
              SELECT 1 FROM download_queue dq WHERE dq.track_id = :trackId
                AND dq.status IN ('PENDING', 'IN_PROGRESS', 'WAITING_FOR_LOSSLESS')
          )
          AND NOT EXISTS (SELECT 1 FROM flac_upgrade_queue fq WHERE fq.track_id = :trackId)
          AND NOT EXISTS (SELECT 1 FROM cloud_items ci WHERE ci.track_id = :trackId)
          AND NOT EXISTS (SELECT 1 FROM discovery_queue d WHERE d.track_id = :trackId AND d.status != 'FAILED')
          AND NOT EXISTS (SELECT 1 FROM track_aliases ta WHERE ta.track_id = :trackId)
        """,
    )
    suspend fun deleteIfUnusedStub(trackId: Long): Int

    // ── Possible duplicates ─────────────────────────────────────────────

    /**
     * Canonical keys more than one mergeable row holds: where Possible duplicates looks. Blank keys,
     * local files, cloud songs and SoundCloud uploads are left out (they are never merged).
     * Index-backed by `(canonical_title, canonical_artist)`.
     */
    @Query(
        """
        SELECT canonical_title AS title, canonical_artist AS artist FROM tracks
        WHERE canonical_title != '' AND canonical_artist != ''
          AND source NOT IN ('LOCAL', 'CLOUD', 'SOUNDCLOUD')
          AND soundcloud_id IS NULL
        GROUP BY canonical_title, canonical_artist
        HAVING COUNT(*) > 1
        """,
    )
    suspend fun sharedCanonicalKeys(): List<SharedCanonicalKey>

    @Query("SELECT * FROM tracks WHERE canonical_title IN (:titles)")
    suspend fun rowsWithCanonicalTitlesRaw(titles: List<String>): List<TrackEntity>

    /** Every row whose canonical (title, artist) is one of [keys], chunked for the bind cap. */
    suspend fun rowsWithCanonicalKeys(keys: List<SharedCanonicalKey>): List<TrackEntity> {
        val wanted = keys.mapTo(HashSet()) { it.title to it.artist }
        return keys.map { it.title }.distinct()
            .chunkedForBind { rowsWithCanonicalTitlesRaw(it) }
            .filter { (it.canonicalTitle to it.canonicalArtist) in wanted }
    }
}
