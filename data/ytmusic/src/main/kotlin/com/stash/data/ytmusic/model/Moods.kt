package com.stash.data.ytmusic.model

import kotlinx.serialization.Serializable

/**
 * One tile of YouTube Music's "Moods & moments" or "Genres" grid (`FEmusic_moods_and_genres`): "Chill", "Focus",
 * "Rock"... [params] opens its page (`browse FEmusic_moods_and_genres_category` with these params). [color] is YouTube's
 * own stripe colour for the tile, ARGB, or null when it sent none.
 */
@Serializable
data class MoodTile(val title: String, val params: String, val color: Long? = null)

/**
 * The two grids of YouTube Music's Moods & genres page, each in YouTube's order: [moods] ("Moods & moments": Chill,
 * Commute...) and [genres] (Pop, Rock, Hip-hop...). Either is empty when the page came without it.
 */
data class MoodGrids(val moods: List<MoodTile>, val genres: List<MoodTile>) {
    fun isEmpty(): Boolean = moods.isEmpty() && genres.isEmpty()

    companion object {
        val None = MoodGrids(emptyList(), emptyList())
    }
}

/**
 * One of YouTube Music's playlists on a mood's page. [id] is its browse id (`VL…`), which the album page opens as
 * a playlist ([AlbumSource.YOUTUBE_PLAYLIST]); [subtitle] is YouTube's line under it ("YouTube Music", a curator).
 */
@Serializable
data class MoodPlaylist(val id: String, val title: String, val subtitle: String, val thumbnailUrl: String?)

/**
 * A row of a mood's or a genre's page: YouTube's own title ("Coffee shop blends", "Songs", "Albums") and what it
 * holds, in YouTube's order. A row holds one kind: [songs] (a genre's "Songs"), [playlists] (a mood's rows, a genre's
 * "Featured playlists" and "Community playlists") or [albums] (a genre's "Albums"); the other two are empty.
 */
@Serializable
data class MoodShelf(
    val title: String,
    val playlists: List<MoodPlaylist> = emptyList(),
    val songs: List<TrackSummary> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
)

/** A mood's or a genre's page: its name and its rows, in YouTube's order. */
@Serializable
data class MoodPage(val title: String, val shelves: List<MoodShelf>) {

    /**
     * The first [count] different covers on the page, row after row in YouTube's order (Mochy Music's rule for a
     * tile's covers): a genre's are its first songs' album art (the Songs row comes first), a mood's its first
     * playlists' covers.
     */
    fun coverUrls(count: Int): List<String> =
        shelves.asSequence()
            .flatMap { shelf ->
                shelf.songs.asSequence().map { it.thumbnailUrl } +
                    shelf.playlists.asSequence().map { it.thumbnailUrl } +
                    shelf.albums.asSequence().map { it.thumbnailUrl }
            }
            .filterNotNull()
            .filter { it.isNotBlank() }
            .distinct()
            .take(count)
            .toList()
}
