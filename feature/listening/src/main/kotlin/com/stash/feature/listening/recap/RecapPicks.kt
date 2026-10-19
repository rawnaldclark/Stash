package com.stash.feature.listening.recap

import com.stash.core.data.db.dao.TopArtistRow
import com.stash.core.data.db.dao.TopTrackRow
import com.stash.core.data.genre.ArtistGenreIndex
import com.stash.core.data.genre.TopGenres
import com.stash.feature.listening.stats.ListeningInsights
import com.stash.feature.listening.stats.TopArtistUi

/**
 * The songs the artist and genre cards play, picked the same way whatever the recap is built
 * from (Stash's own history or Last.fm scrobbles): an artist's most played song in the period,
 * never the #1 song — those cards come before the quiz and must not give its answer away.
 */
internal object RecapPicks {

    /** [songs] most played first; null when the artist has no song other than the #1. */
    fun topSongOf(songs: List<TopTrackRow>, artistName: String): TopTrackRow? {
        val no1Key = songs.firstOrNull()?.let { ListeningInsights.songKey(it.track.title, it.track.artist) }
        val key = ListeningInsights.artistKey(artistName)
        return songs.firstOrNull {
            ListeningInsights.artistKey(it.track.artist) == key &&
                ListeningInsights.songKey(it.track.title, it.track.artist) != no1Key
        }
    }

    /** Each top artist's song, by [TopArtistUi.key] (artists without one are absent). */
    fun artistTopSongs(songs: List<TopTrackRow>, topArtists: List<TopArtistUi>): Map<String, TopTrackRow> =
        topArtists.mapNotNull { a -> topSongOf(songs, a.name)?.let { a.key to it } }.toMap()

    /** The top genre's leading artist's song (the genres card's music), or null. */
    fun genreSong(
        songs: List<TopTrackRow>,
        credits: List<TopArtistRow>,
        index: ArtistGenreIndex,
        genres: TopGenres,
    ): TopTrackRow? {
        val top = genres.items.firstOrNull() ?: return null
        val artist = ListeningInsights.genreTopArtists(credits, index, listOf(top.key), perGenre = 1)[top.key]?.firstOrNull()
            ?: return null
        return topSongOf(songs, artist)
    }
}
