package com.stash.core.common

/**
 * Upgrades album art URLs to request the highest reasonable quality from
 * each CDN. Called wherever an art URL is stored to ensure the UI always
 * has a crisp image regardless of what the API originally returned.
 *
 * **YouTube Music (`lh3.googleusercontent.com`):**
 * InnerTube returns thumbnails as small as 60x60. The CDN supports
 * arbitrary sizes via the `=wN-hN` URL suffix. We upgrade to 544x544
 * which matches Spotify's standard quality and is sharp on any screen.
 *
 * **YouTube video thumbnails (`i.ytimg.com`):**
 * `sddefault.jpg` is 640x480. We upgrade to `hqdefault.jpg` (480x360)
 * or `maxresdefault.jpg` (1280x720) if the source is `sddefault`.
 * Actually `sddefault` is already decent — the main issue is the
 * `lh3` URLs, not `ytimg`.
 *
 * **Spotify (`i.scdn.co`):**
 * The URL path contains a size prefix:
 * - `ab67616d00004851` → 64x64
 * - `ab67616d00001e02` → 300x300
 * - `ab67616d0000b273` → 640x640
 * We upgrade any smaller variant to 640x640.
 *
 * Returns null if the input is null, preserving nullable semantics.
 */
object ArtUrlUpgrader {

    private val LH3_SIZE_REGEX = Regex("""=w\d+-h\d+""")
    private const val LH3_TARGET_SIZE = "=w544-h544"

    private const val SPOTIFY_64 = "ab67616d00004851"
    private const val SPOTIFY_300 = "ab67616d00001e02"
    private const val SPOTIFY_640 = "ab67616d0000b273"

    fun upgrade(url: String?): String? {
        if (url == null) return null

        return when {
            // YouTube Music album art (lh3.googleusercontent.com)
            "lh3.googleusercontent.com" in url -> {
                if (LH3_SIZE_REGEX.containsMatchIn(url)) {
                    LH3_SIZE_REGEX.replace(url, LH3_TARGET_SIZE)
                } else if (url.contains("=")) {
                    // Has other CDN params but no explicit size — strip and set size
                    url.substringBefore("=") + LH3_TARGET_SIZE
                } else {
                    // No params at all — append size directly
                    "$url$LH3_TARGET_SIZE"
                }
            }

            // Spotify album art (i.scdn.co)
            "i.scdn.co/image/" in url -> {
                url.replace(SPOTIFY_64, SPOTIFY_640)
                    .replace(SPOTIFY_300, SPOTIFY_640)
            }

            // Everything else (ytimg, other CDNs) — leave as-is
            else -> url
        }
    }
}
