package com.stash.core.data.community

import com.stash.core.common.TrackCanonical
import com.stash.core.data.db.SQLITE_BIND_LIMIT
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackAliasEntity
import com.stash.core.data.library.SameSongRule
import com.stash.core.model.share.SharedTrack
import javax.inject.Inject

/**
 * Community's "heard" from this phone's listening history: has one of a post's songs been played anywhere in Stash
 * since the post went up? Then it counts as heard, as 30 s played from the post does (CommunityListenTracker), which
 * misses a song played from the Library, Liked, Search, a radio or a queue that came back after a restart.
 *
 * A song is found by its YouTube id, then its Spotify id (either one also where a duplicate merge moved it,
 * `track_aliases`), then its title and artist ([TrackCanonical] words). Every library row any of those finds counts,
 * since one song can be several rows (a Spotify row next to a YouTube one, another upload of the same song). By the
 * words, a row must also be the same version ([SameSongRule.isSameSong] with the ids left out, as a library copy is
 * judged): "Song (Live)" doesn't count for "Song", and a cover by another artist has other artist words.
 *
 * Read-only: it never adds a row. A handful of index searches for the songs ([TrackDao.findExistingForBatch], chunked)
 * and one for their listens ([ListeningEventDao.anyListenSince]).
 */
class CommunityListenHistory @Inject constructor(
    private val trackDao: TrackDao,
    private val listeningEventDao: ListeningEventDao,
) {
    /**
     * Whether this phone has a counted listen (a `listening_events` row of its own: half the song, 30 s to 4 min) of
     * any of [songs] that started at or after [sinceMs], the post's own time. False for no songs.
     */
    suspend fun heardSince(songs: List<SharedTrack>, sinceMs: Long): Boolean {
        val rows = rowsFor(songs)
        return rows.isNotEmpty() && listeningEventDao.anyListenSince(rows, sinceMs)
    }

    /** Every library row that stands for one of [songs] (see the class comment), by id. */
    internal suspend fun rowsFor(songs: List<SharedTrack>): List<Long> {
        if (songs.isEmpty()) return emptyList()
        val videoIds = songs.mapNotNull { it.youtubeId?.trim()?.takeIf(String::isNotEmpty) }.distinct()
        val spotifyUris = songs.mapNotNull { s -> s.spotifyId?.trim()?.takeIf(String::isNotEmpty)?.let { "spotify:track:$it" } }.distinct()
        // Each song's words, with its raw title for the version check. A blank pair (a title that's only a bracket, no
        // artist) would match every other blank row, so it isn't looked up by words.
        val byWords = songs
            .map { (TrackCanonical.title(it.title) to TrackCanonical.artist(it.artist)) to it.title }
            .filterNot { (words, _) -> TrackCanonical.isBlankKey(words.first, words.second) }
            .groupBy({ it.first }, { it.second })

        val found = LinkedHashSet<Long>()
        val videoSet = videoIds.toHashSet()
        val uriSet = spotifyUris.toHashSet()
        // Never an empty IN (): findExistingForBatch pads only the words.
        trackDao.findExistingForBatch(spotifyUris.ifEmpty { NONE }, videoIds.ifEmpty { NONE }, byWords.keys.toList())
            .forEach { row ->
                val words = byWords[row.canonicalTitle to row.canonicalArtist]
                val same = row.youtubeId in videoSet || row.spotifyUri in uriSet ||
                    words?.any { title -> SameSongRule.isSameSong(row, title, youtubeId = null, spotifyUri = null) } == true
                if (same) found += row.id
            }
        aliases(TrackAliasEntity.KIND_YOUTUBE_ID, videoIds).forEach { found += it }
        aliases(TrackAliasEntity.KIND_SPOTIFY_URI, spotifyUris).forEach { found += it }
        return found.toList()
    }

    /** The rows a duplicate merge pointed these merged-away ids of [kind] at. */
    private suspend fun aliases(kind: String, values: List<String>): List<Long> =
        values.chunked(SQLITE_BIND_LIMIT).flatMap { chunk -> trackDao.aliasRowsRaw(kind, chunk).map { it.trackId } }

    private companion object {
        /** Stands in for an empty id list: no row holds it. */
        val NONE = listOf("\u0000__community_no_id__")
    }
}
