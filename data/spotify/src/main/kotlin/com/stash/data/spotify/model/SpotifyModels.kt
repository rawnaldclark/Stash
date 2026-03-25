package com.stash.data.spotify.model

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
)

@Serializable
data class SpotifyTrackObject(
    val id: String,
    val name: String,
    val artists: List<SpotifyArtist> = emptyList(),
    val album: SpotifyAlbum? = null,
    val duration_ms: Long = 0,
    val uri: String = "",
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
