package com.stash.core.data.library.duplicates

import android.util.Log
import androidx.room.withTransaction
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DuplicateDismissalEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackAliasEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.db.entity.TrackMergeLogEntity
import javax.inject.Inject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Merges one library row into another row of the same song, in one transaction: everything that
 * pointed at the merged-away row ("the loser") points at the row that stays ("the keeper"), the
 * loser's ids become aliases, a line goes into the merge log, and the loser row is deleted. Its file
 * is never touched here: deleting a file is the caller's step, after this commits, and only for
 * Possible duplicates' "Merge" after its one confirmation.
 *
 * What moves (every table with a track id, schema 76):
 *  - **Playlists** (`playlist_tracks`): a membership the keeper doesn't have moves as it is (its
 *    place, its date, "added by the user"). Where both are in a playlist the keeper's membership
 *    stays, so the song is never in it twice, and it is marked the user's own if either was; where
 *    the keeper's was removed and the loser's is live, the keeper's comes back with the loser's
 *    place. Custom orders move unless the keeper has its own entry.
 *  - **Plays and history**: listening events (Your record, History, scrobble queue), skips, play
 *    counts, last played; Stash Mix discovery rows.
 *  - **Likes**: the like columns ([DuplicateRules.mergedRow]); Liked Songs memberships move with
 *    the playlists.
 *  - **Downloads**: a waiting download request of the loser moves to a keeper that has no file and
 *    no request of its own; any other queue row (finished, failed) goes with the loser.
 *  - **Caches**: lyrics, tags, Spotify resolution, cloud upload state and sync-undo snapshots move
 *    unless the keeper has its own.
 *  - **Aliases and "Not a duplicate"** marks of the loser move to the keeper.
 *
 * Refused (returns null, nothing written): the same row twice, a missing row, a pair
 * [DuplicateRules.mayMergePair] rejects (a flagged wrong match, explicit against clean, a local
 * file, a cloud song, a SoundCloud upload), a cloud item on either side, a download running for the
 * loser right now (its file would land on a deleted row), or a loser holding a file the keeper
 * doesn't have (the song's only file would be lost; the caller picks that row as the keeper).
 *
 * No undo: a merge is reversible only by hand, from its [TrackMergeLogEntity] line (the loser's
 * columns, its memberships and the counts that moved). An undo replayed days later would have to
 * guess around everything that happened to the keeper since, and a wrong guess loses data.
 */
class TrackMerger @Inject constructor(
    private val database: StashDatabase,
) {

    /** What one merge did. */
    data class Merged(
        /** The row that stays, as written. */
        val kept: TrackEntity,
        /** The row that is gone, as it was. */
        val removed: TrackEntity,
        /** Playlists the loser was in (moved, folded into the keeper's, or brought back). */
        val memberships: Int,
        val plays: Int,
        val skips: Int,
    )

    /**
     * Merges row [loserId] into row [keeperId] (see [TrackMerger]).
     *
     * @param reason the merge log's reason ([TrackMergeLogEntity.REASON_AUTO_MERGE] or
     *   [TrackMergeLogEntity.REASON_USER_MERGE]).
     * @return what was merged, or null when the merge was refused and nothing changed.
     */
    suspend fun merge(keeperId: Long, loserId: Long, reason: String, now: Long = System.currentTimeMillis()): Merged? =
        database.withTransaction { mergeInTransaction(keeperId, loserId, reason, now) }

    /** [merge]'s body; the caller holds the transaction (the cleanup batches many merges into one). */
    internal suspend fun mergeInTransaction(keeperId: Long, loserId: Long, reason: String, now: Long): Merged? {
        val trackDao = database.trackDao()
        val dao = database.duplicateDao()
        if (keeperId == loserId) return null
        val keeper = trackDao.getById(keeperId) ?: return null
        val loser = trackDao.getById(loserId) ?: return null
        if (!DuplicateRules.mayMergePair(keeper, loser)) return refused(keeper, loser, "not mergeable")
        if (DuplicateRules.holdsFile(loser) && !DuplicateRules.holdsFile(keeper)) {
            return refused(keeper, loser, "the loser holds the only file")
        }
        if (dao.cloudItemCount(keeperId) + dao.cloudItemCount(loserId) > 0) return refused(keeper, loser, "cloud song")
        if (dao.runningDownloadCount(loserId) > 0) return refused(keeper, loser, "a download is running for the loser")

        // Playlists first, while both rows exist.
        val loserMemberships = dao.membershipsOf(loserId)
        val folded = HashSet<Long>()
        for (m in loserMemberships) {
            val k = database.playlistDao().getCrossRef(m.playlistId, keeperId)
            when {
                k == null -> dao.moveMembership(m.playlistId, loserId, keeperId)
                k.removedAt != null && m.removedAt == null -> {
                    dao.dropMembership(m.playlistId, loserId)
                    dao.reviveMembership(m.playlistId, keeperId, m.position, m.addedAt.toEpochMilli(), m.locallyAdded)
                }
                else -> {
                    if (k.removedAt == null && m.removedAt == null) {
                        if (m.locallyAdded && !k.locallyAdded) dao.markUserAdded(m.playlistId, keeperId)
                        folded += m.playlistId
                    }
                    dao.dropMembership(m.playlistId, loserId)
                }
            }
        }
        dao.moveOrderEntries(loserId, keeperId)

        val plays = dao.moveListeningEvents(loserId, keeperId)
        val skips = dao.moveSkipEvents(loserId, keeperId)
        val discovery = dao.moveDiscoveryRows(loserId, keeperId)
        dao.moveLyrics(loserId, keeperId)
        dao.moveTags(loserId, keeperId)
        dao.moveCloudUploads(loserId, keeperId)
        dao.moveSpotifyResolution(loserId, keeperId)
        dao.dropSpotifyResolution(loserId)
        dao.moveUndoMemberships(loserId, keeperId)
        dao.dropUndoMemberships(loserId)

        val downloads = if (!DuplicateRules.holdsFile(keeper) && dao.downloadRowCount(keeperId) == 0) {
            dao.moveWaitingDownloads(loserId, keeperId, keeper.youtubeId?.takeIf { it.isNotBlank() }?.let(::watchUrl))
        } else {
            0
        }

        val dismissals = dao.dismissalsOf(loserId).mapNotNull { d ->
            val other = if (d.trackA == loserId) d.trackB else d.trackA
            if (other == keeperId) null else DuplicateDismissalEntity.of(keeperId, other, d.dismissedAt)
        }
        if (dismissals.isNotEmpty()) dao.dismiss(dismissals)
        dao.repointAliases(loserId, keeperId)

        val merged = DuplicateRules.mergedRow(keeper, loser)
        val aliases = DuplicateRules.aliasesFor(loser, merged, now)
        dao.log(
            TrackMergeLogEntity(
                at = now,
                reason = reason,
                keptTrackId = keeperId,
                removedTrackId = loserId,
                title = loser.title,
                artist = loser.artist,
                detail = mergeDetail(keeper, loser, loserMemberships, plays, skips, discovery, downloads, aliases).toString(),
            ),
        )
        // The loser goes before the keeper takes its ids: the video and Spotify columns are UNIQUE.
        trackDao.delete(loser)
        trackDao.update(merged)
        dao.putAliases(aliases)
        for (playlistId in folded) {
            database.playlistDao().updateTrackCount(playlistId, trackDao.countByPlaylist(playlistId, includeStreamable = true))
        }
        return Merged(kept = merged, removed = loser, memberships = loserMemberships.size, plays = plays, skips = skips)
    }

    private fun refused(keeper: TrackEntity, loser: TrackEntity, why: String): Merged? {
        Log.i(TAG, "not merging ${loser.id} into ${keeper.id}: $why")
        return null
    }

    companion object {
        private const val TAG = "TrackMerger"

        private fun watchUrl(videoId: String) = "https://music.youtube.com/watch?v=$videoId"

        /** The merge log's JSON for a merge: enough to answer "where did my song go" and to undo it by hand. */
        internal fun mergeDetail(
            keeper: TrackEntity,
            loser: TrackEntity,
            memberships: List<PlaylistTrackCrossRef>,
            plays: Int,
            skips: Int,
            discovery: Int,
            downloads: Int,
            aliases: List<TrackAliasEntity>,
        ): JsonObject = buildJsonObject {
            put("removed", rowDetail(loser))
            put(
                "kept",
                buildJsonObject {
                    put("id", keeper.id)
                    put("youtube_id", keeper.youtubeId)
                    put("spotify_uri", keeper.spotifyUri)
                    put("source", keeper.source.name)
                    put("play_count", keeper.playCount)
                    put("file_path", keeper.filePath)
                },
            )
            put(
                "memberships",
                buildJsonArray {
                    memberships.forEach { m ->
                        add(
                            buildJsonObject {
                                put("playlist_id", m.playlistId)
                                put("position", m.position)
                                put("added_at", m.addedAt.toEpochMilli())
                                put("removed_at", m.removedAt?.toEpochMilli())
                                put("locally_added", m.locallyAdded)
                            },
                        )
                    }
                },
            )
            put(
                "moved",
                buildJsonObject {
                    put("plays", plays)
                    put("skips", skips)
                    put("discovery", discovery)
                    put("downloads", downloads)
                },
            )
            put("aliases", buildJsonArray { aliases.forEach { add(kotlinx.serialization.json.JsonPrimitive("${it.kind}:${it.value}")) } })
        }

        /** The columns of [row] the merge log keeps: who it was, what identified it, its file, its plays and likes. */
        internal fun rowDetail(row: TrackEntity): JsonObject = buildJsonObject {
            put("id", row.id)
            put("title", row.title)
            put("artist", row.artist)
            put("album", row.album)
            put("duration_ms", row.durationMs)
            put("source", row.source.name)
            put("youtube_id", row.youtubeId)
            put("spotify_uri", row.spotifyUri)
            put("isrc", row.isrc)
            put("explicit", row.explicit)
            put("file_path", row.filePath)
            put("file_format", row.fileFormat)
            put("is_downloaded", row.isDownloaded)
            put("play_count", row.playCount)
            put("stash_liked_at", row.stashLikedAt)
            put("spotify_saved_at", row.spotifySavedAt)
            put("ytmusic_saved_at", row.ytMusicSavedAt)
            put("date_added", row.dateAdded.toEpochMilli())
            put("album_art_url", row.albumArtUrl)
        }
    }
}
