package com.stash.core.data.social

import android.util.Log
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.model.Track
import com.stash.data.spotify.SpotifyApiClient
import com.stash.data.spotify.SpotifyTrackCandidate
import com.stash.data.ytmusic.YTMusicApiClient
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Fills in a track's MISSING platform id at like time so a heart can reach
 * both Spotify and YouTube even when the track was only ever known to one of
 * them (a YouTube-search find has no Spotify URI; a freshly-synced Spotify
 * track may not have a YouTube id yet).
 *
 * Correctness: a wrong match would save the WRONG song to the user's library.
 * The YouTube→Spotify direction gates candidates through the shared
 * [TrackMatcher.isFuzzyMatch] (canonical title ≥0.92, artist ≥0.90, duration
 * within 10 s) and takes the closest-duration passer — for a like, any master
 * of the RIGHT song is fine to save, so we don't need the streaming resolver's
 * album-vs-single abstention. The Spotify→YouTube direction reuses
 * [YTMusicApiClient.searchCanonicalVideoId], the same songs-filtered canonical
 * matcher the download flow trusts.
 *
 * ponytail: doesn't reuse data:download's SpotifySearchScorer — it lives a
 * layer above core:data (depends on TrackQuery), so it isn't importable here;
 * TrackMatcher is the shared correctness primitive both build on. Upgrade to
 * the full scorer only if a wrong-master save is ever reported.
 *
 * A found id is persisted best-effort (the `spotify_uri`/`youtube_id` UNIQUE
 * indexes can reject a dup); the resolved id is returned regardless so the
 * like still fires with it in-memory.
 */
@Singleton
class CrossPlatformLikeResolver @Inject constructor(
    private val spotifyApiClient: SpotifyApiClient,
    private val ytMusicApiClient: YTMusicApiClient,
    private val matcher: TrackMatcher,
    private val trackDao: TrackDao,
    private val trackIdentityEvents: TrackIdentityEvents,
) {
    /** The track's Spotify URI, resolving + persisting it if missing. Null when no safe match. */
    suspend fun ensureSpotifyUri(track: Track): String? {
        track.spotifyUri?.let { return it }
        if (track.title.isBlank() || track.artist.isBlank()) return null

        val candidates = try {
            spotifyApiClient.searchTracksGraphQL("${track.artist} ${track.title}")
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "ensureSpotifyUri: search failed for ${track.id}: ${e.message}")
            return null
        }

        val match = pickSpotify(track, candidates) ?: run {
            Log.d(TAG, "ensureSpotifyUri: no confident match for '${track.artist} - ${track.title}'")
            return null
        }
        val uri = "spotify:track:${match.id}"
        runCatching { trackDao.updateSpotifyUri(track.id, uri) }
            .onFailure { Log.d(TAG, "ensureSpotifyUri: persist skipped for ${track.id}: ${it.message}") }
        Log.i(TAG, "ensureSpotifyUri: matched ${track.id} → $uri")
        return uri
    }

    /** The track's YouTube video id, resolving + persisting it if missing. Null when unresolved. */
    suspend fun ensureYoutubeId(track: Track): String? {
        track.youtubeId?.let { return it }
        if (track.title.isBlank() || track.artist.isBlank()) return null

        val videoId = try {
            // The advisory picks the explicit copy of the song unless the track is known clean.
            ytMusicApiClient.searchCanonicalVideoId(track.artist, track.title, track.explicit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "ensureYoutubeId: search failed for ${track.id}: ${e.message}")
            return null
        } ?: run {
            Log.d(TAG, "ensureYoutubeId: no match for '${track.artist} - ${track.title}'")
            return null
        }

        runCatching { trackDao.updateYoutubeId(track.id, videoId) }
            .onFailure { Log.d(TAG, "ensureYoutubeId: persist skipped for ${track.id}: ${it.message}") }
        // The track's youtubeId identity just changed — drop any StreamUrl
        // cached for this track id so the next play resolves fresh instead
        // of reusing a URL that was (or wasn't) resolved against this id.
        trackIdentityEvents.emitIdentityChanged(track.id)
        Log.i(TAG, "ensureYoutubeId: matched ${track.id} → $videoId")
        return videoId
    }

    /**
     * The closest-duration candidate that clears [TrackMatcher.isFuzzyMatch],
     * or null when none passes. Spotify returns candidates in relevance order;
     * the strict fuzzy gate is what actually protects against a wrong recording.
     */
    private fun pickSpotify(track: Track, candidates: List<SpotifyTrackCandidate>): SpotifyTrackCandidate? =
        candidates
            .filter {
                matcher.isFuzzyMatch(
                    track.title, track.artist, track.durationMs,
                    it.name, it.artists.firstOrNull().orEmpty(), it.durationMs,
                )
            }
            .minByOrNull { abs(it.durationMs - track.durationMs) }

    companion object {
        private const val TAG = "CrossPlatformLike"
    }
}
