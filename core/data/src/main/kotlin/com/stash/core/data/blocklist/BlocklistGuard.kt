package com.stash.core.data.blocklist

import android.util.Log
import androidx.room.withTransaction
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackBlocklistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackBlocklistEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.sync.TrackMatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v0.9.15: Where a block originated. Used for telemetry attribution and
 * the Settings UI's "blocked from N" label.
 */
enum class BlockSource {
    NOW_PLAYING, CONTEXT_MENU, PLAYLIST_DELETE,
    MIGRATION_V19, INTEGRITY_WORKER, FAILED_DOWNLOADS, OTHER
}

/**
 * v0.9.15: Single chokepoint for blocklist enforcement. Every track-source
 * flow (sync, mix, discovery, search, download, swap, failed-match) consults
 * this guard via [isBlocked] before inserting / queueing / linking. The
 * block action [block] runs atomically inside a Room transaction so the
 * user can't end up with an orphaned file or stale playlist_tracks rows.
 *
 * Identity-keyed: a block survives `tracks` row churn, source-switches,
 * and canonical-normaliser disagreements that produce duplicate rows.
 */
@Singleton
class BlocklistGuard @Inject constructor(
    private val database: StashDatabase,
    private val blocklistDao: TrackBlocklistDao,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val downloadQueueDao: DownloadQueueDao,
    private val fileDeleter: FileDeleter,
    private val matcher: TrackMatcher,
) {

    /** Identity check by raw fields. Used by sync workers reading from snapshots. */
    suspend fun isBlocked(
        artist: String,
        title: String,
        spotifyUri: String?,
        youtubeId: String?,
    ): Boolean {
        val key = BlocklistKey.of(artist, title, matcher)
        return blocklistDao.isBlocked(key, spotifyUri, youtubeId)
    }

    /** Identity check by trackId (looks up the row, then forwards). */
    suspend fun isBlockedByTrackId(trackId: Long): Boolean {
        val track = trackDao.getById(trackId) ?: return false
        return isBlocked(track.artist, track.title, track.spotifyUri, track.youtubeId)
    }

    /**
     * Atomic block. Inserts a blocklist entry, hard-deletes every
     * `playlist_tracks` row referencing this track id, deletes any
     * `download_queue` rows, deletes the audio + art files, and deletes
     * the `tracks` row itself. Row deletes happen in one Room transaction.
     *
     * File deletes happen OUTSIDE the transaction to avoid holding a write
     * lock across slow I/O. If a file delete fails (e.g. storage unmounted),
     * row deletes still proceed and we accept an orphaned file on disk —
     * the current integrity worker does NOT walk the music directory
     * looking for orphans, so add a periodic file-orphan sweep separately
     * if that ever becomes a real issue.
     */
    suspend fun block(track: TrackEntity, source: BlockSource) {
        val key = BlocklistKey.of(track.artist, track.title, matcher)
        val entry = TrackBlocklistEntity(
            canonicalKey = key,
            artist = track.artist,
            title = track.title,
            spotifyUri = track.spotifyUri,
            youtubeId = track.youtubeId,
            blockedAt = System.currentTimeMillis(),
            blockedFrom = source.name,
        )
        fileDeleter.delete(track.filePath)
        fileDeleter.delete(track.albumArtPath)

        val playlistIds = database.withTransaction {
            val heldBy = playlistDao.playlistIdsForTrack(track.id)
            blocklistDao.insert(entry)
            playlistDao.deleteAllCrossRefsForTrack(track.id)
            downloadQueueDao.deleteByTrackId(track.id)
            trackDao.deleteById(track.id)
            heldBy
        }
        // The Library cards show a stored count; every playlist that held the song loses one.
        // After the commit, so the recount reads the rows without it.
        playlistIds.forEach { id ->
            playlistDao.updateTrackCount(id, trackDao.getByPlaylist(id, includeStreamable = true).first().size)
        }
        Log.d(TAG, "Blocked '${track.artist} - ${track.title}' (id=${track.id}, source=$source)")
    }

    /** Remove the block. Used by the Settings unblock action. */
    suspend fun unblock(canonicalKey: String) {
        blocklistDao.deleteByKey(canonicalKey)
    }

    /** UI feed for the Blocked Songs viewer. */
    fun observeBlocklist(): Flow<List<TrackBlocklistEntity>> = blocklistDao.observeAll()

    /** Reactive count for the Sync-tab "Blocked Songs" badge. */
    fun observeCount(): Flow<Int> = blocklistDao.observeCount()

    private companion object {
        const val TAG = "BlocklistGuard"
    }
}
