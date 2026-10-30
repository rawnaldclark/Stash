package com.stash.data.spotify

/**
 * An artist's photos on Spotify ([SpotifyApiClient.getArtistVisuals]): the gallery, largest size
 * of each, and the wide header image.
 */
data class SpotifyArtistVisuals(
    val gallery: List<String>,
    val headerUrl: String?,
)
