package com.stash.data.ytmusic.model

import kotlinx.serialization.Serializable

/**
 * One tile of YouTube Music's "Moods & moments" grid (`FEmusic_moods_and_genres`): "Chill", "Focus", "Workout"...
 * [params] opens its page (`browse FEmusic_moods_and_genres_category` with these params). [color] is YouTube's own
 * stripe colour for the tile, ARGB, or null when it sent none.
 */
@Serializable
data class MoodTile(val title: String, val params: String, val color: Long? = null)

/**
 * One of YouTube Music's playlists on a mood's page. [id] is its browse id (`VL…`), which the album page opens as
 * a playlist ([AlbumSource.YOUTUBE_PLAYLIST]); [subtitle] is YouTube's line under it ("YouTube Music", a curator).
 */
@Serializable
data class MoodPlaylist(val id: String, val title: String, val subtitle: String, val thumbnailUrl: String?)

/** A row of a mood's page: YouTube's own title ("Coffee shop blends") and its playlists. */
@Serializable
data class MoodShelf(val title: String, val playlists: List<MoodPlaylist>)

/** A mood's page: its name and its rows of playlists, in YouTube's order. */
@Serializable
data class MoodPage(val title: String, val shelves: List<MoodShelf>)
