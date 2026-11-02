package com.stash.data.ytmusic.model

import kotlinx.serialization.Serializable

/** One external link. [kind] is a free string (e.g. "instagram","x","youtube",
 *  "website") — NOT an enum, so a value written by a newer app build never
 *  crashes an older build's cache decode. UI maps known kinds to icons. */
@Serializable
data class SocialLink(val kind: String, val url: String)

/** Spotify-style "About" data. All fields optional; the enricher returns `null`
 *  (not an empty instance) when nothing was found. The About section shows only
 *  when [hasSection]: a listener count alone is the hero's, not a section's. */
@Serializable
data class ArtistAbout(
    val bio: String? = null,
    val socials: List<SocialLink> = emptyList(),
    val photoUrl: String? = null, // upgrade only; UI coalesces with avatarUrl
    /** The artist's photo gallery (Spotify), shown as a row in About. */
    val gallery: List<String> = emptyList(),
    /** A wide header photo (Spotify): the banner when YouTube's page has none. */
    val headerUrl: String? = null,
    /**
     * Spotify's monthly listeners (unique listeners over the last 28 days), from the same
     * `queryArtistOverview` answer as [gallery]. Shown in the hero, not in About; it wins over
     * YouTube's [ArtistProfile.monthlyAudience]. Null when Spotify is not connected, has no single
     * exact match, or the profile was cached before this field existed.
     */
    val monthlyListeners: Long? = null,
    /**
     * The titles of the Spotify artist's top tracks, from the same answer: [monthlyListeners]
     * counts only when one of them is on YouTube's Popular shelf (a namesake shares the name,
     * not the songs). Empty in profiles cached before this field existed.
     */
    val topTrackTitles: List<String> = emptyList(),
) {
    /** Whether the About section has anything of its own to show (everything but [monthlyListeners]). */
    val hasSection: Boolean
        get() = bio != null || socials.isNotEmpty() || photoUrl != null || gallery.isNotEmpty() || headerUrl != null
}
