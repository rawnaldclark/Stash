package com.stash.core.data.listening

import com.stash.core.common.foldCase
import com.stash.core.common.matchesArtistCredits
import com.stash.core.common.primaryArtist
import com.stash.core.data.db.dao.TopArtistRow

/**
 * Artist credits grouped by main act, the one way Your listening's Top artists, the Recap and Home's "New from your
 * artists" count an artist: "Drake, Future" counts for Drake, as the Library's Artists tab and artist pages group.
 */
object MainArtists {

    /** The key of an artist: one per main act, folded like `equals(ignoreCase = true)`. */
    fun key(name: String): String = foldCase(name.primaryArtist().trim())

    /**
     * Top artists by main act. Plays, songs and time add up; the cover and spelling are the busiest credit's. A main
     * act its own credit doesn't name ("Earth" from "Earth, Wind & Fire, Chicago") or a blank one is left out, as the
     * Library does: its artist page would be empty. Most played first, then the one heard last.
     */
    fun byMainArtist(rows: List<TopArtistRow>): List<TopArtistRow> =
        rows.groupBy { key(it.artist) }
            .mapNotNull { (_, group) ->
                val lead = group.maxWith(compareBy<TopArtistRow> { it.plays }.thenBy { it.lastPlayedAt })
                val name = lead.artist.primaryArtist().trim()
                if (name.isEmpty() || group.none { matchesArtistCredits(it.artist, "", name) }) return@mapNotNull null
                lead.copy(
                    artist = name,
                    plays = group.sumOf { it.plays },
                    songs = group.sumOf { it.songs },
                    lastPlayedAt = group.maxOf { it.lastPlayedAt },
                    listenedMs = group.sumOf { it.listenedMs },
                )
            }
            .sortedWith(compareByDescending<TopArtistRow> { it.plays }.thenByDescending { it.lastPlayedAt })
}
