package com.stash.data.spotify

/**
 * What the artist page takes from Spotify's `queryArtistOverview` answer
 * ([SpotifyApiClient.getArtistVisuals]): the photo gallery (largest size of each), the wide header
 * image, and the monthly listeners. One request carries all three.
 */
data class SpotifyArtistVisuals(
    val gallery: List<String>,
    val headerUrl: String?,
    /** `data.artistUnion.stats.monthlyListeners`: unique listeners over the last 28 days. Null when absent or 0. */
    val monthlyListeners: Long? = null,
)
