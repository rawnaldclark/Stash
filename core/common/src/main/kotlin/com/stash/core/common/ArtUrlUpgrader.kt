package com.stash.core.common

/**
 * Upgrades album art URLs to request the highest reasonable quality from
 * each CDN. Called wherever an art URL is stored to ensure the UI always
 * has a crisp image regardless of what the API originally returned.
 *
 * **YouTube Music (`lh3.googleusercontent.com`):**
 * InnerTube returns thumbnails as small as 60x60. The CDN supports
 * arbitrary sizes via the `=wN-hN` URL suffix. We upgrade to 1024x1024
 * — large enough for the 260dp NowPlaying surface on a 3x display
 * (780px) with headroom. Coil downsamples the in-memory bitmap to
 * view size, so the only cost is download bandwidth; the on-CDN file
 * is cached.
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

    // lh3.googleusercontent.com (YT Music album art) and
    // yt3.googleusercontent.com (YT channel art, playlist art) share the
    // same `=wN-hN-…` size-token format. Both upgrade to 1024x1024.
    private val LH3_SIZE_REGEX = Regex("""=w\d+-h\d+""")
    private const val LH3_TARGET_SIZE = "=w1024-h1024"

    // yt3.ggpht.com uses a single-dimension square token `=sN`. Bump to
    // 1080 — fits anything we'd render at 3x density.
    private val GGPHT_SIZE_REGEX = Regex("""=s\d+""")
    private const val GGPHT_TARGET_SIZE = "=s1080"

    private const val SPOTIFY_64 = "ab67616d00004851"
    private const val SPOTIFY_300 = "ab67616d00001e02"
    private const val SPOTIFY_640 = "ab67616d0000b273"

    // Last.fm `/i/u/<size>/<hash>` — capture the `/i/u/` prefix so we can
    // swap just the <size> segment (e.g. 300x300, 174s) for a larger one.
    private val LASTFM_SIZE_REGEX = Regex("""(/i/u/)[^/]+/""")
    private const val LASTFM_TARGET_SIZE = "770x0"

    // i.ytimg.com filenames in increasing order of quality.
    //   `default`      → 120x90
    //   `mqdefault`    → 320x180
    //   `hqdefault`    → 480x360
    //   `sddefault`    → 640x480
    //   `maxresdefault`→ 1280x720 (404s for low-popularity uploads)
    //
    // We upgrade everything < sddefault to `sddefault`. 640x480 is sharp
    // enough for the mosaic tile / playlist-cover surfaces at typical
    // high-DPI sizes and is available for ~99% of YT videos. We don't
    // chase `maxresdefault` because the 404 rate is non-trivial and
    // Coil doesn't fall back on broken URLs.
    //
    // We ALSO strip `?sqp=…&rs=…` query parameters: those are Google's
    // server-side downscale tokens that shrink the served image even
    // when the URL points at a high-res `*default.jpg`. Without
    // stripping, an `hqdefault.jpg?sqp=…` URL arrives at ~320px wide.
    private val YTIMG_LOW_RES = listOf("default", "mqdefault", "hqdefault")
    private const val YTIMG_TARGET = "sddefault"
    private val YTIMG_PATH_REGEX = Regex(
        """(/vi[a-z_]*/[^/]+/)(?:default|mqdefault|hqdefault)(\.(?:jpg|webp))""",
    )

    /**
     * Returns true when [url] is a YouTube *video* thumbnail
     * (`i.ytimg.com/vi/.../sddefault.jpg` etc). These are uploader-chosen
     * images — music-video frames, "Topic" channel placeholders, hand-
     * picked photos — and rarely match the actual studio album cover.
     *
     * Used by the streaming + download pipelines to decide whether to
     * overwrite a stored art URL with a fresh catalog match (Qobuz, etc).
     * Proper YT Music catalog art lives on `lh3.googleusercontent.com`
     * and is treated as good — not a video thumbnail.
     *
     * Note: this stays true after [upgrade] rewrites `sddefault` →
     * `maxresdefault` or similar; the host is the load-bearing signal.
     */
    fun isYouTubeVideoThumbnail(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return "i.ytimg.com" in url
    }

    fun upgrade(url: String?): String? {
        if (url == null) return null

        return when {
            // YouTube Music album art (lh3.googleusercontent.com) AND
            // YouTube channel/playlist art (yt3.googleusercontent.com).
            // Same `=wN-hN-…` token format; same upgrade target.
            "lh3.googleusercontent.com" in url ||
                "yt3.googleusercontent.com" in url -> {
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

            // YouTube channel art (yt3.ggpht.com) uses `=sN` single-dim
            // square sizing. Upgrade to 1080.
            "yt3.ggpht.com" in url -> {
                if (GGPHT_SIZE_REGEX.containsMatchIn(url)) {
                    GGPHT_SIZE_REGEX.replace(url, GGPHT_TARGET_SIZE)
                } else if (url.contains("=")) {
                    url.substringBefore("=") + GGPHT_TARGET_SIZE
                } else {
                    "$url$GGPHT_TARGET_SIZE"
                }
            }

            // Spotify album art (i.scdn.co)
            "i.scdn.co/image/" in url -> {
                url.replace(SPOTIFY_64, SPOTIFY_640)
                    .replace(SPOTIFY_300, SPOTIFY_640)
            }

            // Last.fm art (lastfm.freetls.fastly.net) is served under
            // `/i/u/<size>/<hash>.<ext>` — e.g. `/i/u/300x300/…`. The API
            // hands back 300x300 (or smaller `NNNs` avatars), which is
            // badly upscaled on large surfaces like the Home hero. Bump the
            // size segment to `770x0` (770px wide, proportional) for a crisp
            // image. Coil downsamples to view size, so the only cost is
            // bandwidth on a CDN-cached file.
            "lastfm." in url && LASTFM_SIZE_REGEX.containsMatchIn(url) -> {
                LASTFM_SIZE_REGEX.replace(url, "$1$LASTFM_TARGET_SIZE/")
            }

            // YouTube video thumbnails (i.ytimg.com). Two upgrades:
            //   1. Strip `?sqp=…&rs=…` query — Google's server-side
            //      downscale tokens; they shrink the served image even
            //      when the URL already points at `hqdefault.jpg`.
            //   2. Rewrite `default.jpg` (120x90) and `mqdefault.jpg`
            //      (320x180) up to `hqdefault.jpg` (480x360).
            // `sddefault` / `maxresdefault` pass through filename-wise
            // since they're already good — but they still get the query
            // strip.
            "i.ytimg.com" in url -> {
                val stripped = url.substringBefore("?")
                if (YTIMG_LOW_RES.any { "/$it." in stripped }) {
                    YTIMG_PATH_REGEX.replace(stripped) { match ->
                        "${match.groupValues[1]}$YTIMG_TARGET${match.groupValues[2]}"
                    }
                } else {
                    stripped
                }
            }

            // Everything else — leave as-is
            else -> url
        }
    }
}
