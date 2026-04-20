package com.stash.data.download.matching

import android.util.Log
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.model.Track
import com.stash.data.ytmusic.model.MusicVideoType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Canonicalizes YouTube-library-imported tracks to their studio-audio
 * equivalents.
 *
 * YouTube Music's "Archive Mix" / "Daily Mix" / user playlists sometimes
 * reference music-video uploads (OMV) rather than the Topic-channel
 * studio master (ATV). When those get imported via
 * [com.stash.core.data.sync.workers.PlaylistFetchWorker] the user ends
 * up downloading MV audio (with intros, alternate mixes, spoken bits)
 * instead of the album cut. Pre-Phase 5 there was no hook in the
 * pipeline to catch this — [com.stash.data.download.DownloadManager.resolveUrl]
 * short-circuits when `track.youtubeId` is already set, skipping all
 * scoring and verification.
 *
 * This class provides the hook. Call
 * [canonicalize] for any YT-source track before building its download
 * URL. If the videoId's actual [MusicVideoType] is OMV / UGC /
 * PODCAST_EPISODE, we run a YouTube Music search for the same song
 * and swap in the first ATV (or OFFICIAL_SOURCE_MUSIC) match that
 * clears the scorer's auto-accept threshold. When no better candidate
 * exists we keep the original — we never fail a track because its only
 * available upload happens to be a music video.
 */
@Singleton
class YtLibraryCanonicalizer @Inject constructor(
    private val searchExecutor: InnerTubeSearchExecutor,
    private val matchScorer: MatchScorer,
    private val trackDao: TrackDao,
    private val trackMatcher: TrackMatcher,
) {
    companion object {
        private const val TAG = "YtLibCanonicalizer"
    }

    /**
     * Returns either the original URL or a canonicalized replacement.
     * Safe to call on any track — unknown types, ATV originals, and
     * search misses all short-circuit back to the original URL.
     */
    suspend fun canonicalize(track: Track, originalVideoId: String): String {
        val originalUrl = "https://www.youtube.com/watch?v=$originalVideoId"

        val verification = searchExecutor.verifyVideo(originalVideoId)
        val type = verification?.musicVideoType

        // Only non-canonical types trigger reconciliation. ATV and
        // OFFICIAL_SOURCE_MUSIC are already the right answer;
        // null (unknown) is trusted — some legitimate entries (old
        // uploads, region-specific edits) simply lack the enum and we
        // don't want to rewrite them blindly.
        if (type == null ||
            type == MusicVideoType.ATV ||
            type == MusicVideoType.OFFICIAL_SOURCE_MUSIC
        ) {
            return originalUrl
        }

        val query = buildQuery(track.title, track.artist)
        val candidates = searchExecutor.search(query, maxResults = 10)
        if (candidates.isEmpty()) {
            Log.d(TAG, "canonicalize: no candidates for '$query' ($type=$originalVideoId), keeping original")
            return originalUrl
        }

        // targetDurationMs=0 because the imported video's duration is
        // the wrong reference. An OMV typically runs longer than its
        // ATV counterpart (spoken intros, outros, alternate mixes), so
        // handing the MV's duration to the scorer would reject every
        // ATV candidate via the soft duration weight. Passing 0 tells
        // [MatchScorer] to score duration neutrally.
        val scored = matchScorer.scoreResults(
            targetTitle = track.title,
            targetArtist = track.artist,
            targetDurationMs = 0L,
            results = candidates,
            targetAlbum = track.album,
            targetExplicit = track.explicit,
        )
        val best = matchScorer.bestMatch(scored) ?: run {
            Log.d(
                TAG,
                "canonicalize: no candidate above threshold for '${track.title}' " +
                    "($type/$originalVideoId), keeping original",
            )
            return originalUrl
        }

        if (best.videoId == originalVideoId) {
            // Scorer reached for the same videoId — nothing to swap.
            return originalUrl
        }

        // Persist the swap on [tracks.youtube_id] so future lookups are
        // consistent with the file on disk. Without this, the row still
        // claims the OMV videoId even after we download ATV audio — which
        // breaks queries that join by youtube_id (e.g. Failed Matches'
        // exclude-current-wrong guard, duplicate detection). If the
        // subsequent download fails, next resolveUrl will short-circuit
        // on the new (correct) videoId and try again without re-running
        // canonicalization — strictly better than the pre-persist state.
        trackDao.updateYoutubeId(track.id, best.videoId)

        // Also refresh the display/dedup metadata so the UI stops showing
        // the OMV's title ("Smooth Criminal (Official Video)") and its
        // music-video thumbnail once the ATV audio lands on disk. Without
        // this, Stash would render the MV's metadata against the studio
        // audio — visually and semantically wrong. duration_ms gets
        // refreshed too since OMV and ATV often differ by intros/outros
        // and an inaccurate duration breaks the seek bar.
        val atvTitle = best.title.ifBlank { track.title }
        val atvArtist = best.uploader.ifBlank { track.artist }
        val atvDurationMs = if (best.durationSeconds > 0) best.durationSeconds * 1000L else 0L
        trackDao.updateCanonicalMetadata(
            trackId = track.id,
            title = atvTitle,
            canonicalTitle = trackMatcher.canonicalTitle(atvTitle),
            canonicalArtist = trackMatcher.canonicalArtist(atvArtist),
            album = best.album,
            albumArtUrl = best.thumbnailUrl,
            durationMs = atvDurationMs,
        )

        Log.i(
            TAG,
            "canonicalize: '${track.artist} - ${track.title}' " +
                "$type/$originalVideoId → ${best.videoId} " +
                "(score=${"%.2f".format(best.matchScore)}) " +
                "newTitle='$atvTitle'",
        )
        return best.youtubeUrl
    }

    /**
     * Strips parenthetical content (e.g. "(Official Video)") so the
     * search isn't biased toward another OMV. The imported title often
     * includes such a marker — matching it verbatim on YouTube Music
     * would surface more MV uploads.
     */
    private fun buildQuery(title: String, artist: String): String {
        val cleanTitle = title
            .replace(Regex("""\s*[\(\[][^)\]]*[\)\]]"""), "")
            .trim()
        return "$artist $cleanTitle".trim()
    }

    /**
     * Metadata-refresh path for tracks whose audio is already
     * canonicalized (ATV) but whose display fields are still stale —
     * legacy tracks from the first Phase 6 backfill run where only
     * [trackDao.updateYoutubeId] was persisted. Does NOT re-download;
     * only updates the DB.
     *
     * Returns true if new metadata was persisted, false if no match
     * could be confidently identified (leaves the track unchanged
     * rather than writing partial data).
     *
     * Looks up the track's current videoId in a fresh search and
     * persists the search-shelf title/album/art/duration. Falls back
     * to the single best ATV match for the query if the current
     * videoId doesn't appear in search results (which can happen for
     * long-tail tracks whose popularity ranking has shifted since
     * canonicalization).
     */
    suspend fun refreshMetadata(track: Track): Boolean {
        val currentVideoId = track.youtubeId ?: return false
        val query = buildQuery(track.title, track.artist)
        val candidates = searchExecutor.search(query, maxResults = 10)
        if (candidates.isEmpty()) {
            Log.d(TAG, "refreshMetadata: no candidates for '$query', skipping")
            return false
        }

        // Prefer the candidate that matches the current videoId —
        // that's the canonical one we already selected in a prior run.
        // Fall back to the best-scoring ATV if the exact videoId
        // doesn't show up (search rankings drift over time).
        val direct = candidates.firstOrNull { it.id == currentVideoId }
        val chosen = if (direct != null) {
            direct
        } else {
            val scored = matchScorer.scoreResults(
                targetTitle = track.title,
                targetArtist = track.artist,
                targetDurationMs = 0L,
                results = candidates,
                targetAlbum = track.album,
                targetExplicit = track.explicit,
            )
            matchScorer.bestMatch(scored) ?: run {
                Log.d(TAG, "refreshMetadata: no candidate above threshold for '${track.title}', skipping")
                return false
            }
            // bestMatch returns MatchResult; find the underlying YtDlpSearchResult
            // so we have album + thumbnail + duration.
            candidates.firstOrNull { it.id == matchScorer.bestMatch(scored)?.videoId }
                ?: return false
        }

        val newTitle = chosen.title.ifBlank { track.title }
        val newArtist = chosen.uploader.ifBlank { track.artist }
        val newDurationMs =
            if (chosen.duration > 0) (chosen.duration * 1000).toLong() else 0L
        trackDao.updateCanonicalMetadata(
            trackId = track.id,
            title = newTitle,
            canonicalTitle = trackMatcher.canonicalTitle(newTitle),
            canonicalArtist = trackMatcher.canonicalArtist(newArtist),
            album = chosen.album,
            albumArtUrl = chosen.thumbnail,
            durationMs = newDurationMs,
        )
        Log.i(
            TAG,
            "refreshMetadata: trackId=${track.id} '${track.title}' → '$newTitle' " +
                "(videoId=${chosen.id} album=${chosen.album ?: "?"})",
        )
        return true
    }
}
