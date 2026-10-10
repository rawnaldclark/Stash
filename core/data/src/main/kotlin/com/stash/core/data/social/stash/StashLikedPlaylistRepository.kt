package com.stash.core.data.social.stash

import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import javax.inject.Singleton

/**
 * v0.9.13: Manages the local "Liked Songs" playlist (one per install,
 * [PlaylistType.STASH_LIKED]). Lazy-seeded on the first heart-tap so a
 * fresh install carries no orphan playlist row until the user actually
 * uses the feature.
 *
 * Mirrors [com.stash.core.data.repository.MusicRepositoryImpl.linkTrackToDownloadsMix]
 * exactly — uses [MusicRepository.addTrackToPlaylist] so the playlist's
 * cached `track_count` and the cross-ref's `position` stay consistent
 * with the rest of the codebase. Raw [PlaylistDao.insertCrossRef] calls
 * skip both, so we go through the helper.
 *
 * Idempotent: tapping the heart twice on the same track is a no-op
 * after the first call (cross-ref existence check + early return), and
 * the `tracks.stash_liked_at` timestamp is only written once.
 */
@Singleton
class StashLikedPlaylistRepository @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val trackDao: TrackDao,
    private val musicRepository: MusicRepository,
) {
    /**
     * Add [trackId] to the Stash Liked Songs playlist. Seeds the
     * playlist on first call. Sets `tracks.stash_liked_at` only on the
     * first successful add — re-tapping a track that's already liked
     * leaves the existing timestamp untouched.
     */
    suspend fun add(trackId: Long) {
        val trackArt = trackDao.getById(trackId)?.let { track ->
            sequenceOf(track.albumArtPath, track.albumArtUrl)
                .firstOrNull { !it.isNullOrBlank() }
        }
        val playlistId = ensureSeeded(trackArt)
        val existing = playlistDao.getCrossRef(playlistId, trackId)
        if (existing != null && existing.removedAt == null) return
        // Order matters: mark the timestamp FIRST so the Room observation
        // that fires after `insertCrossRef` already sees `stashLikedAt`
        // set. Without this, the observer emits the row twice — once with
        // stashLikedAt = null (right after the cross-ref insert), then
        // again after `markStashLiked` — and the heart visual flickers
        // un-red between the two emissions. The recount inside
        // `addTrackToPlaylist` widens that gap noticeably (issue #105 follow-up).
        trackDao.markStashLiked(trackId, System.currentTimeMillis())
        // Reuse existing helper for trackCount + position handling.
        // Mirrors linkTrackToDownloadsMix at MusicRepositoryImpl.kt:294.
        musicRepository.addTrackToPlaylist(trackId = trackId, playlistId = playlistId)
    }

    /**
     * Likes from another device or a library file (link-sync spec §2.3, §7.1): each `(trackId, likedAt)` becomes a Stash like
     * at its own time, added to Liked Songs, never sent to Spotify or YouTube Music (nothing here fans out). A track already in
     * Liked Songs is left as it is, and an existing like keeps its time. One recount at the end, not one per song.
     *
     * @return how many were newly liked.
     */
    suspend fun addAllFrom(likes: List<Pair<Long, Long>>): Int {
        if (likes.isEmpty()) return 0
        val firstArt = likes.firstNotNullOfOrNull { (id, _) ->
            trackDao.getById(id)?.let { t -> sequenceOf(t.albumArtPath, t.albumArtUrl).firstOrNull { !it.isNullOrBlank() } }
        }
        val playlistId = ensureSeeded(firstArt)
        var position = playlistDao.getNextPosition(playlistId)
        var added = 0
        for ((trackId, likedAt) in likes.distinctBy { it.first }) {
            val existing = playlistDao.getCrossRef(playlistId, trackId)
            if (existing != null && existing.removedAt == null) continue
            if (trackDao.getById(trackId) == null) continue
            trackDao.likeIfNotAlreadyLiked(trackId, likedAt)
            playlistDao.insertCrossRef(
                com.stash.core.data.db.entity.PlaylistTrackCrossRef(
                    playlistId = playlistId,
                    trackId = trackId,
                    position = position++,
                    addedAt = java.time.Instant.ofEpochMilli(likedAt),
                    locallyAdded = true,
                ),
            )
            added++
        }
        val count = trackDao.getByPlaylist(playlistId, includeStreamable = true).first().size
        playlistDao.updateTrackCount(playlistId, count)
        return added
    }

    /**
     * Remove a track from the Stash Liked Songs playlist. Companion to
     * [add] — used by the heart-toggle on Now Playing. Idempotent: a
     * second call when the track is already gone is a no-op. Clears
     * `tracks.stash_liked_at` so the heart icon flips back to outline.
     *
     * Note: this does NOT propagate to Spotify/YT Music. Per v0.9.13
     * design, the auto-save flow to Spotify is one-way — un-liking
     * locally does not unsave externally.
     */
    suspend fun remove(trackId: Long) {
        val playlistId = playlistDao.findBySourceId(STASH_LIKED_SOURCE_ID)?.id ?: run {
            // No Stash Liked playlist exists yet → nothing to remove.
            // Still clear the timestamp in case it's somehow set.
            trackDao.clearStashLiked(trackId)
            return
        }
        // Clear FIRST for the same Room-emission-ordering reason as `add` —
        // observers see one update with stashLikedAt = null, not a brief
        // un-red→red flicker if cross-ref removal is observed first.
        trackDao.clearStashLiked(trackId)
        musicRepository.removeTrackFromPlaylist(trackId = trackId, playlistId = playlistId)
    }

    /**
     * Lazily create the "Liked Songs" playlist if it doesn't exist.
     * Fixed `sourceId` so the unique `source_id` index keeps us at one
     * row per install. `syncEnabled = true` is intentional: unlike
     * DOWNLOADS_MIX (which is always populated by downloaded tracks
     * and so passes the "has-downloaded-track" branch of
     * `getAllVisible`), STASH_LIKED can hold streamed/preview-only
     * tracks. Without `sync_enabled = 1` the playlist is invisible in
     * Library/Home until the user happens to download one of the
     * liked tracks. Sync pipeline ignores `MusicSource.BOTH`, so this
     * flag only affects visibility, not actual sync behavior.
     */
    private suspend fun ensureSeeded(trackArt: String?): Long {
        playlistDao.findBySourceId(STASH_LIKED_SOURCE_ID)?.let { existing ->
            if (existing.artUrl.isNullOrBlank() && !trackArt.isNullOrBlank()) {
                playlistDao.updateArtUrl(existing.id, trackArt)
            }
            return existing.id
        }
        val entity = PlaylistEntity(
            name = "Liked Songs",
            source = MusicSource.BOTH,
            sourceId = STASH_LIKED_SOURCE_ID,
            type = PlaylistType.STASH_LIKED,
            artUrl = trackArt?.takeUnless { it.isBlank() },
            syncEnabled = true,
        )
        return playlistDao.insert(entity)
    }

    companion object {
        private const val STASH_LIKED_SOURCE_ID = "stash_liked_songs"
    }
}
