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
    /**
     * The titles of the artist's top tracks (`discography.topTracks`), in Spotify's order: the
     * artist page checks them against YouTube's Popular shelf before trusting [monthlyListeners]
     * (a namesake shares the name, not the songs).
     */
    val topTrackTitles: List<String> = emptyList(),
)
