package com.stash.core.data.youtube

import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.ytmusic.CanonicalLookup
import com.stash.data.ytmusic.CanonicalMatch
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
 *  3. Else, call `InnerTubeClient.searchCanonicalLookup`. On hit, persist
 *     the id via `TrackDao.updateYtCanonicalVideoId` and return it. On
 *     [CanonicalLookup.NoMatch] the caller should mark the event as
 *     "handled" and skip submission (don't pollute Recap with UGC). On
 *     [CanonicalLookup.Failed] nothing is known yet: the caller keeps the
 *     event queued, since an offline search is no evidence of "no match".
 */
@Singleton
class YtCanonicalResolver @Inject constructor(
    private val trackDao: TrackDao,
    private val innerTubeClient: InnerTubeClient,
) {
    suspend fun resolve(track: TrackEntity): CanonicalLookup {
        // Tier 1: ATV/OMV already downloaded — use stored id.
        val mvType = track.musicVideoType
        val youtubeId = track.youtubeId
        if (
            (mvType == MusicVideoType.ATV.name || mvType == MusicVideoType.OMV.name)
            && !youtubeId.isNullOrBlank()
        ) {
            return CanonicalLookup.Found(CanonicalMatch(youtubeId, thumbnailUrl = null))
        }

        // Tier 2: cache hit from a prior search.
        val cached = track.ytCanonicalVideoId
        if (!cached.isNullOrBlank()) {
            return CanonicalLookup.Found(CanonicalMatch(cached, thumbnailUrl = null))
        }

        // Tier 3: search and cache. The advisory picks the explicit or clean copy
        // by the same rule as playback's YouTube search.
        val lookup = innerTubeClient.searchCanonicalLookup(track.artist, track.title, track.explicit)
        if (lookup !is CanonicalLookup.Found) return lookup
        if (lookup.match.videoId.isBlank()) return CanonicalLookup.NoMatch
        trackDao.updateYtCanonicalVideoId(track.id, lookup.match.videoId)
        return lookup
    }
}
