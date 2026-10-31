package com.stash.feature.record.mixes

import com.stash.core.data.blocklist.BlocklistKey
import com.stash.core.data.db.entity.TrackBlocklistEntity
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.model.weblink.SongKey

/**
 * The songs liked in Stash now (`stash_liked_at`: the heart Now Playing shows). A song of the record is liked when one of these
 * rows is it: by its words (`sameSong`, the web's `L.isLiked`), or by the Spotify id the row carries. A Like by the history's
 * Spotify id can land on a row a Spotify sync made, which names the song otherwise ("A, B" where the history says "A"): that row
 * is the song all the same, for its heart, for "Not in your Stash yet" and for an unlike.
 */
class Likes(val rows: List<LibrarySong>) {
    private val index = HistoryMixes.haveIndex(rows)
    private val bySpotifyId: Set<String> = rows.mapNotNullTo(HashSet()) { it.spotifyId }

    /** The Spotify ids [s] goes by: the history's, and your own row's. */
    private fun spotifyIdsOf(s: MixSong): Set<String> = setOfNotNull(s.sid, s.lib?.spotifyId)

    /** [s] is liked in Stash. */
    fun has(s: MixSong): Boolean = index.has(s.identity()) || spotifyIdsOf(s).any { it in bySpotifyId }

    /** Every row liked in Stash that is [s]: the same song by its words, or by its Spotify id (an unlike takes them all out). */
    fun rowsOf(s: MixSong): List<Long> {
        val ref = s.identity()
        val ids = spotifyIdsOf(s)
        return rows.filter { SongKey.sameSong(it, ref) || (it.spotifyId != null && it.spotifyId in ids) }.map { it.trackId }.distinct()
    }

    companion object {
        val NONE = Likes(emptyList())
    }
}

/**
 * The blocklist as the record's lists read it (BlocklistGuard.isBlocked's rule over a song's words and its Spotify id: a history
 * song has no YouTube id): a blocked song is never in Top songs, "Not in your Stash yet" or a mix ([without]).
 */
fun interface BlockedSongs {
    fun has(title: String, artist: String, sid: String?): Boolean

    companion object {
        val NONE = BlockedSongs { _, _, _ -> false }

        /** The blocklist's [entries], read once, matched as [com.stash.core.data.blocklist.BlocklistGuard.isBlocked] matches. */
        fun of(entries: List<TrackBlocklistEntity>, matcher: TrackMatcher): BlockedSongs {
            if (entries.isEmpty()) return NONE
            val keys = entries.mapTo(HashSet()) { it.canonicalKey }
            val uris = entries.mapNotNullTo(HashSet()) { it.spotifyUri }
            return BlockedSongs { title, artist, sid ->
                BlocklistKey.of(artist, title, matcher) in keys || (sid != null && "spotify:track:$sid" in uris)
            }
        }
    }
}

/** This mix without its blocked songs, or null when every one of them is blocked (the app never shows a blocked song). */
fun HistoryMix.without(blocked: BlockedSongs): HistoryMix? {
    if (blocked === BlockedSongs.NONE) return this
    val kept = items.filterNot { blocked.has(it.title, it.artist, it.sid) }
    return when {
        kept.isEmpty() -> null
        kept.size == items.size -> this
        else -> copy(items = kept)
    }
}
