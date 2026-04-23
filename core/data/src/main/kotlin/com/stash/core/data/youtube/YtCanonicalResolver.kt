package com.stash.core.data.youtube

import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.ytmusic.InnerTubeClient
import com.stash.data.ytmusic.model.MusicVideoType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the best canonical YouTube Music video id for scrobbling a
 * track. Three-tier decision:
 *
 *  1. If `musicVideoType` is ATV or OMV, use the stored `youtubeId`
 *     directly — Stash already downloaded from a canonical source.
 *  2. Else, if `ytCanonicalVideoId` is cached, use it — a previous
 *     resolver pass already did the search.
 *  3. Else, call `InnerTubeClient.searchCanonical`. On hit, persist the
 *     id via `TrackDao.updateYtCanonicalVideoId` and return it. On miss,
 *     return null — caller should mark the event as "handled" and skip
 *     submission (don't pollute Recap with UGC).
 */
@Singleton
class YtCanonicalResolver @Inject constructor(
    private val trackDao: TrackDao,
    private val innerTubeClient: InnerTubeClient,
) {
    suspend fun resolve(track: TrackEntity): String? {
        // Tier 1: ATV/OMV already downloaded — use stored id.
        val mvType = track.musicVideoType
        if (
            (mvType == MusicVideoType.ATV.name || mvType == MusicVideoType.OMV.name)
            && !track.youtubeId.isNullOrBlank()
        ) {
            return track.youtubeId
        }

        // Tier 2: cache hit from a prior search.
        if (!track.ytCanonicalVideoId.isNullOrBlank()) {
            return track.ytCanonicalVideoId
        }

        // Tier 3: search and cache.
        val resolved = innerTubeClient.searchCanonical(track.artist, track.title)
        if (!resolved.isNullOrBlank()) {
            trackDao.updateYtCanonicalVideoId(track.id, resolved)
        }
        return resolved
    }
}
