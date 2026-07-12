package com.stash.data.ytmusic.model

import kotlinx.serialization.Serializable

/** One external link. [kind] is a free string (e.g. "instagram","x","youtube",
 *  "website") — NOT an enum, so a value written by a newer app build never
 *  crashes an older build's cache decode. UI maps known kinds to icons. */
@Serializable
data class SocialLink(val kind: String, val url: String)

/** Spotify-style "About" data. All fields optional; the enricher returns `null`
 *  (not an empty instance) when nothing was found, so the UI gate is `about != null`. */
@Serializable
data class ArtistAbout(
    val bio: String? = null,
    val socials: List<SocialLink> = emptyList(),
    val photoUrl: String? = null, // upgrade only; UI coalesces with avatarUrl
)
