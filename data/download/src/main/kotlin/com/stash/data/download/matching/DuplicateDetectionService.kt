package com.stash.data.download.matching

import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.sync.TrackMatcher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks whether a track already exists in the local library before
 * downloading, preventing wasted bandwidth and duplicate files.
 *
 * Uses a three-tier lookup strategy, from most specific to broadest:
 * 1. Exact Spotify URI match.
 * 2. Exact YouTube video ID match.
 * 3. Canonical title + artist match (cross-source deduplication).
 */
@Singleton
class DuplicateDetectionService @Inject constructor(
    private val trackDao: TrackDao,
    private val trackMatcher: TrackMatcher,
) {
    /**
     * Search for an existing track that matches the given identifiers.
     *
     * @param spotifyUri Optional Spotify URI (e.g. "spotify:track:abc123").
     * @param youtubeId  Optional YouTube video ID (e.g. "dQw4w9WgXcQ").
     * @param title      Track title for canonical comparison.
     * @param artist     Track artist for canonical comparison.
     * @return The database ID of the existing track if a duplicate is found,
     *         null if no duplicate exists.
     */
    suspend fun findDuplicate(
        spotifyUri: String?,
        youtubeId: String?,
        title: String,
        artist: String,
    ): Long? {
        // 1. Exact Spotify URI match (highest confidence)
        spotifyUri?.let { uri ->
            trackDao.findBySpotifyUri(uri)?.let { return it.id }
        }

        // 2. Exact YouTube ID match
        youtubeId?.let { id ->
            trackDao.findByYoutubeId(id)?.let { return it.id }
        }

        // 3. Canonical title + artist match (cross-source deduplication)
        val canonTitle = trackMatcher.canonicalTitle(title)
        val canonArtist = trackMatcher.canonicalArtist(artist)
        if (canonTitle.isNotBlank() && canonArtist.isNotBlank()) {
            trackDao.findByCanonicalIdentity(canonTitle, canonArtist)?.let { return it.id }
        }

        return null
    }
}
