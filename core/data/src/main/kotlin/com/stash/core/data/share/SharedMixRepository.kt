package com.stash.core.data.share

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.room.withTransaction
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.SharedMixDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.SharedMixEntity
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import com.stash.core.model.share.ShareConfig
import com.stash.core.model.share.ShareLinks
import com.stash.core.model.share.SharedTrack
import com.stash.core.model.share.toSharedTrack
import com.stash.core.model.share.toSharedTrackWithArt
import com.stash.core.model.share.toTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class FollowCheck { UpToDate, Updated, Removed, Unreachable }

/** Only [Failed] is retried by the publish worker. */
enum class PublishOutcome { Unchanged, Published, Removed, Forbidden, Rejected, Failed }

/** Clips a descriptor to the Worker's limits (worker src/validate.js), so one odd row can't fail a whole mix or post. */
internal fun SharedTrack.withinLimits() = copy(
    title = title.cut(500),
    artist = artist.cut(500),
    album = album?.cut(500),
    isrc = isrc?.takeIf { it.length <= 20 },
    spotifyId = spotifyId?.takeIf { it.length <= 40 },
    youtubeId = youtubeId?.takeIf { it.length <= 20 },
    artUrl = artUrl?.takeIf { it.length <= 1000 },
)

/** [take] that never ends on half an emoji: UTF-8 would send the lone half as "?". */
internal fun String.cut(n: Int) = take(n).let { if (it.isNotEmpty() && it.length == n && it.last().isHighSurrogate()) it.dropLast(1) else it }

/** Every share and follow operation (spec §5-6). */
@Singleton
class SharedMixRepository @Inject constructor(
    private val database: StashDatabase,
    private val sharedMixDao: SharedMixDao,
    private val playlistDao: PlaylistDao,
    private val trackDao: TrackDao,
    private val musicRepository: MusicRepository,
    private val api: ShareApiClient,
    @ApplicationContext private val context: Context,
) {
    fun observe(playlistId: Long): Flow<SharedMixEntity?> = sharedMixDao.observeForPlaylist(playlistId)

    /** Playlists that are an active follow, i.e. read-only in the Library (spec §6). */
    fun observeActiveFollowedIds(): Flow<List<Long>> = sharedMixDao.observeActiveFollowedIds()
    suspend fun forPlaylist(playlistId: Long): SharedMixEntity? = sharedMixDao.forPlaylist(playlistId)

    // ── Owner ──────────────────────────────────────────────────────────────────────────────

    /**
     * Build the document from the playlist's live members, in order (removed ones excluded).
     * Every field is clipped to the Worker's limits (worker src/validate.js) so one odd library
     * row can never make the whole mix unpublishable.
     * [withArt] keeps each song's cover link (Community posts; a shared mix never sends it).
     */
    suspend fun buildDocument(playlistId: Long, name: String, sharedBy: String?, withArt: Boolean = false): SharedMixDocument {
        // Untagged local files are kept: toSharedTrack() gives them placeholder title/artist.
        val tracks = playlistDao.getTracksForPlaylist(playlistId).map { it.toDomain() }
        return SharedMixDocument(
            name = name.trim().cut(100),
            sharedBy = sharedBy?.trim()?.cut(40)?.ifBlank { null },
            covers = tracks.mapNotNull { it.albumArtUrl?.takeIf { u -> u.length <= 1000 && ShareConfig.isAllowedCover(u) } }.distinct().take(4),
            tracks = tracks.take(MAX_TRACKS).map { (if (withArt) it.toSharedTrackWithArt() else it.toSharedTrack()).withinLimits() },
        )
    }

    /** Create a link for [playlistId]; returns the https URL. */
    suspend fun share(playlistId: Long, name: String, sharedBy: String?, autoUpdate: Boolean): ShareResult<String> {
        val doc = buildDocument(playlistId, name, sharedBy)
        if (doc.tracks.isEmpty()) return ShareResult.Failed("This playlist has no songs to share.")
        val key = newEditKey()
        return when (val r = api.create(doc, key)) {
            is ShareResult.Ok -> {
                sharedMixDao.delete(playlistId) // a stale REMOVED row from an earlier share
                sharedMixDao.insert(
                    SharedMixEntity(
                        playlistId = playlistId, shareId = r.value.id, role = SharedMixEntity.ROLE_OWNER,
                        editKey = key, name = doc.name, version = r.value.version, contentHash = doc.contentHash(),
                        autoUpdate = autoUpdate, sharedBy = doc.sharedBy,
                    ),
                )
                ShareResult.Ok(ShareLinks.mixUrl(r.value.id))
            }
            is ShareResult.Failed -> r
            else -> ShareResult.Failed("Couldn't create the link.")
        }
    }

    /** Send a new version only if what followers would see changed. */
    suspend fun publishIfChanged(row: SharedMixEntity): PublishOutcome {
        val key = row.editKey ?: return PublishOutcome.Forbidden
        val doc = buildDocument(row.playlistId, row.name, row.sharedBy)
        if (doc.tracks.isEmpty()) return PublishOutcome.Unchanged // an emptied playlist isn't published
        val hash = doc.contentHash()
        if (hash == row.contentHash) return PublishOutcome.Unchanged
        return when (val r = api.update(row.shareId, doc, key, row.version)) {
            is ShareResult.Ok -> { sharedMixDao.markPublished(row.playlistId, r.value, hash); PublishOutcome.Published }
            ShareResult.Gone -> { sharedMixDao.markRemoved(row.playlistId, noticePending = false); PublishOutcome.Removed }
            // KV can briefly 404 a just-created mix: retry, only a 410 means it's gone.
            ShareResult.NotFound -> PublishOutcome.Failed
            ShareResult.Forbidden -> { Log.w(TAG, "edit key rejected for ${ShareLinks.logId(row.shareId)}"); PublishOutcome.Forbidden }
            is ShareResult.Rejected -> { Log.w(TAG, "server rejected ${ShareLinks.logId(row.shareId)}: HTTP ${r.code}"); PublishOutcome.Rejected }
            is ShareResult.Failed -> PublishOutcome.Failed
        }
    }

    suspend fun setAutoUpdate(playlistId: Long, on: Boolean) {
        sharedMixDao.setAutoUpdate(playlistId, on)
        if (on) SharedMixPublishWorker.enqueue(context) // edits made while it was off go out now
    }

    /** Remove the link. A remote 404/410 still clears the local row. */
    suspend fun stopSharing(playlistId: Long): Boolean {
        val row = sharedMixDao.forPlaylist(playlistId) ?: return true
        val r = api.delete(row.shareId, row.editKey.orEmpty())
        if (r is ShareResult.Failed) return false
        sharedMixDao.delete(playlistId)
        return true
    }

    private fun newEditKey(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }


    // ── Follower ───────────────────────────────────────────────────────────────────────────

    suspend fun fetch(shareId: String): ShareResult<SharedMixDocument> = api.get(shareId)
    suspend fun byShareId(shareId: String): SharedMixEntity? = sharedMixDao.byShareId(shareId)
    fun observeByShareId(shareId: String): Flow<SharedMixEntity?> = sharedMixDao.observeByShareId(shareId)

    /** Persist every descriptor (deduped against the library), keeping document order and dropping repeats. */
    suspend fun persistTracks(doc: SharedMixDocument): List<Long> =
        doc.tracks.map { musicRepository.ensureTrackPersisted(it.toTrack()) }.distinct()

    suspend fun tracksFor(doc: SharedMixDocument): List<Track> =
        persistTracks(doc).mapNotNull { trackDao.getById(it)?.toDomain() }

    /**
     * Spec §6 Follow: a CUSTOM/BOTH playlist, source_id share:<id>, download off. Idempotent per share id
     * and atomic: a double tap returns the first follow's playlist, and a `share:<id>` playlist left
     * without its row (a crash mid-follow; source_id is UNIQUE) is adopted instead of re-inserted.
     */
    suspend fun follow(doc: SharedMixDocument): Long {
        sharedMixDao.byShareId(doc.id)?.let { return it.playlistId }
        val ids = persistTracks(doc) // slow; kept out of the write transaction
        val now = Instant.now()
        return database.withTransaction {
            sharedMixDao.byShareId(doc.id)?.let { return@withTransaction it.playlistId }
            val orphan = playlistDao.findBySourceId("share:${doc.id}")
            val playlistId = if (orphan != null) {
                playlistDao.replaceMixMembership(orphan.id, ids, doc.name, now)
                orphan.id
            } else {
                playlistDao.createMixWithMembership(
                    PlaylistEntity(
                        name = doc.name, source = MusicSource.BOTH, sourceId = "share:${doc.id}",
                        type = PlaylistType.CUSTOM, isActive = true, syncEnabled = false,
                    ),
                    ids, now,
                )
            }
            // insert (ABORT), not upsert: a clash must throw and roll the whole follow back.
            sharedMixDao.insert(
                SharedMixEntity(
                    playlistId = playlistId, shareId = doc.id, role = SharedMixEntity.ROLE_FOLLOWER,
                    name = doc.name, version = doc.version, sharedBy = doc.sharedBy, lastCheckedAt = now.toEpochMilli(),
                ),
            )
            playlistId
        }
    }

    /** Spec §6 Save a copy: an ordinary editable playlist, no link back. */
    suspend fun saveCopy(doc: SharedMixDocument): Long {
        val ids = persistTracks(doc)
        val playlistId = musicRepository.createPlaylist(doc.name)
        // Streams until the user switches downloads on (spec §6); set before any track joins.
        playlistDao.setSyncEnabled(playlistId, false)
        playlistDao.replaceMixMembership(playlistId, ids, doc.name, Instant.now())
        return playlistId
    }

    /**
     * Remove a followed mix and the tracks it brought in, unless something else claims them
     * (a like, a download, another playlist, listening history; see [TrackDao.deleteUnclaimedTracks]).
     */
    suspend fun unfollow(playlistId: Long) {
        val playlist = playlistDao.getById(playlistId)?.toDomain() ?: return
        val memberIds = playlistDao.getTracksForPlaylist(playlistId).map { it.id }
        sharedMixDao.delete(playlistId)
        musicRepository.removePlaylist(playlist)
        trackDao.deleteUnclaimedTracks(memberIds)
    }

    /**
     * "Download this mix" is the playlist's sync_enabled (spec §6). On also starts downloading now.
     * Off cancels the songs still waiting, exactly as a playlist's Download button does
     * ([com.stash.core.data.db.dao.DownloadQueueDao.cancelWaitingForPlaylist]): a song downloading
     * now finishes, downloaded songs stay, and a song another kept playlist or followed mix still
     * wants keeps its row. The flag lands at once; the queue work runs one change at a time, so an
     * off right after an on waits for the on's songs to be queued, then cancels them.
     *
     * ponytail: a follow update's background queueing ([applyUpdate]) doesn't take [downloadLock],
     * so an off landing mid-update can let that update's new songs through. Take the lock there too,
     * re-reading sync_enabled inside it, if that ever shows up.
     */
    suspend fun setDownload(playlistId: Long, on: Boolean) {
        playlistDao.setSyncEnabled(playlistId, on)
        downloadLock.withLock {
            if (on) musicRepository.queueDownloadsForPlaylist(playlistId)
            else database.downloadQueueDao().cancelWaitingForPlaylist(playlistId)
        }
    }

    private val downloadLock = Mutex()

    suspend fun checkForUpdate(row: SharedMixEntity, now: Long): FollowCheck {
        return when (val v = api.version(row.shareId)) {
            is ShareResult.Ok -> {
                if (v.value <= row.version) {
                    sharedMixDao.markChecked(row.playlistId, missingCount = 0, lastCheckedAt = now); FollowCheck.UpToDate
                } else when (val d = api.get(row.shareId)) {
                    // A newer format this build can't read is never applied; keep the copy we have.
                    is ShareResult.Ok -> if (d.value.v > 1) FollowCheck.Unreachable else { applyUpdate(row, d.value, now); FollowCheck.Updated }
                    ShareResult.Gone -> removed(row)
                    else -> FollowCheck.Unreachable
                }
            }
            ShareResult.Gone -> removed(row)
            // Only a 410 tombstone means "stopped sharing". A 404 is KV lag or a lost record, never proof, so the
            // follow stays live; missing_count just records it for diagnostics.
            ShareResult.NotFound -> {
                sharedMixDao.markChecked(row.playlistId, row.missingCount + 1, now); FollowCheck.Unreachable
            }
            else -> FollowCheck.Unreachable
        }
    }

    private suspend fun applyUpdate(row: SharedMixEntity, doc: SharedMixDocument, now: Long) {
        val ids = persistTracks(doc)
        val download = database.withTransaction {
            // Unfollowed mid-check: nothing to update (replaceMixMembership would hit an FK error).
            val playlist = playlistDao.getById(row.playlistId) ?: return@withTransaction null
            val dropped = playlistDao.getOrderedTrackIdsForPlaylist(row.playlistId) - ids.toSet()
            playlistDao.replaceMixMembership(row.playlistId, ids, doc.name, Instant.ofEpochMilli(now))
            // Songs the owner took out go the way unfollow's do, unless something else claims them.
            trackDao.deleteUnclaimedTracks(dropped)
            sharedMixDao.markApplied(row.playlistId, doc.version, doc.name, doc.sharedBy, now)
            playlist.syncEnabled
        }
        // Nobody tapped anything: the new songs follow the Sync tab's "Wi-Fi only", like a sync's.
        if (download == true) musicRepository.queueDownloadsForPlaylist(row.playlistId, background = true)
    }

    private suspend fun removed(row: SharedMixEntity): FollowCheck {
        sharedMixDao.markRemoved(row.playlistId, noticePending = true)
        return FollowCheck.Removed
    }

    /** The one-time "stopped sharing" message (spec §6), or null. Clears the flag. */
    suspend fun consumeRemovedNotice(playlistId: Long): String? {
        val row = sharedMixDao.forPlaylist(playlistId)?.takeIf { it.noticePending } ?: return null
        sharedMixDao.clearNotice(playlistId)
        return "${row.sharedBy ?: "The owner"} stopped sharing this mix. You keep your copy."
    }

    companion object {
        private const val TAG = "SharedMix"
        const val MAX_TRACKS = 2000
    }
}
