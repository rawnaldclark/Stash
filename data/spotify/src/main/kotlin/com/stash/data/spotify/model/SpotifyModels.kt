package com.stash.data.spotify.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Spotify Web API data transfer objects.
 *
 * These classes map directly to the JSON shapes returned by the Spotify REST API.
 * Field names use snake_case to match the API response without requiring
 * [kotlinx.serialization.SerialName] annotations on every property.
 */

@Serializable
data class SpotifyUser(
    val id: String,
    val display_name: String? = null,
    val images: List<SpotifyImage>? = null,
)

@Serializable
data class SpotifyImage(
    val url: String,
    val height: Int? = null,
    val width: Int? = null,
)

@Serializable
data class SpotifyPlaylistsResponse(
    val items: List<SpotifyPlaylistItem> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
)

@Serializable
data class SpotifyPlaylistItem(
    val id: String,
    val name: String,
    val owner: SpotifyOwner,
    val images: List<SpotifyImage>? = null,
    val tracks: SpotifyTracksRef? = null,
    /**
     * Spotify's version of this playlist, as the GraphQL library listing
     * (`libraryV3`, `item.data.revisionId`) reports it: an opaque string that
     * moves whenever the playlist changes on Spotify. The sync uses it to skip
     * re-reading a playlist it already mirrored at this version.
     *
     * Null when the listing carries none (the Liked Songs pseudo-playlist,
     * home-feed mixes, an older response shape): such a playlist is always
     * read in full.
     */
    val revisionId: String? = null,
)

@Serializable
data class SpotifyOwner(
    val id: String,
    val display_name: String? = null,
)

@Serializable
data class SpotifyTracksRef(
    val total: Int? = null,
)

@Serializable
data class SpotifyTracksResponse(
    val items: List<SpotifyTrackItem> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class SpotifyTrackItem(
    val track: SpotifyTrackObject? = null,

    /**
     * ISO-8601 instant the track was saved to the library, as Spotify reports
     * it on the *item wrapper* (not the track). Liked Songs order is this value
     * descending; without it the sync can only mirror whatever order the
     * endpoint happened to return, which is how tracks saved years ago surfaced
     * at the top of Liked Songs (issue #410).
     *
     * Null when the response carries no add date (playlist pages, older shapes).
     */
    @SerialName("added_at")
    val addedAt: String? = null,
)

@Serializable
data class SpotifyTrackObject(
    val id: String,
    val name: String,
    val artists: List<SpotifyArtist> = emptyList(),
    val album: SpotifyAlbum? = null,
    val duration_ms: Long = 0,
    val uri: String = "",
    /**
     * Parental-advisory flag from Spotify: the Web API's `explicit`, the web player's GraphQL
     * `contentRating.label` ([contentRatingExplicit]). Null when the response doesn't say: not
     * known, so nothing is steered toward the clean version. It used to default to false, and the
     * GraphQL parsers (every playlist and Liked Songs sync since 2026-04) never set it, so every
     * synced song read as clean and the matcher preferred clean versions (2026-10-26). Threaded
     * through to domain [Track] so the matcher can tell clean from explicit.
     */
    val explicit: Boolean? = null,
    /**
     * International Standard Recording Code — a per-master unique identifier
     * Spotify exposes via `external_ids.isrc`. Populated only when the Web
     * API request explicitly asks for `external_ids` in its `fields=`
     * parameter. Used as the highest-precision signal when matching a
     * Spotify track to its canonical YouTube upload.
     */
    val isrc: String? = null,
)

@Serializable
data class SpotifyArtist(
    val id: String,
    val name: String,
)

@Serializable
data class SpotifyAlbum(
    val id: String,
    val name: String,
    val images: List<SpotifyImage>? = null,
)
