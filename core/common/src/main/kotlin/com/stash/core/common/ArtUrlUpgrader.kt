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
 * Every variant is normalised to `hqdefault.jpg` (480x360), the only
 * one YouTube generates for EVERY video. `sddefault` / `maxresdefault`
 * exist for some uploads and 404 for others, and a 404 is drawn as
 * nothing — black album art at random (see [upgrade]).
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

    // Last.fm covers live at `<host>/i/u/<size>/<hash>.<ext>` on TWO hosts:
    // `lastfm.freetls.fastly.net` AND `lastfm-img.freetls.fastly.net`. Match
    // the shared CDN domain, never the host prefix — the old check was
    // `"lastfm." in url`, which `lastfm-img.` does not contain, so 56% of a
    // real library's Last.fm covers were never upgraded at all.
    private const val LASTFM_HOST = "freetls.fastly.net"
    private val LASTFM_PATH_REGEX = Regex("""(/i/u/)[^/]+/([0-9a-f]+)\.[A-Za-z]+""")
    private const val LASTFM_TARGET_SIZE = "770x0"

    // The API only ever advertises 300x300, and it serves it as a PNG. The
    // same hash is available 770 wide, and as a JPEG it is LIGHTER than the
    // 300px PNG it replaces — probed over 200 covers of a real library on
    // 2026-09-09: 300x300.png median 152 KB, 770x0.png median 739 KB,
    // 770x0.jpg median 91 KB. So this is 2.5x the pixels for 60% of the
    // bytes. The 770 variants are rendered on demand and a few percent of
    // hashes miss one of the two formats (never both), which is what
    // `LastFmArtFallbackInterceptor` exists to catch.
    //
    // Re-probing this: Fastly varies these on Accept-Encoding. The SAME url
    // 404s without `Accept-Encoding: gzip` and serves 200 with it, so a
    // curl/urllib probe invents misses the app never sees — OkHttp always
    // sends gzip. Measured with the app's own headers, all 191 covers
    // reachable in a real library's playlists serve the 770 JPEG, and a
    // miss can also be transient: one that 404'd three times from a PC
    // served 200 to the phone a minute later.
    private const val LASTFM_TARGET_EXT = "jpg"

    // What the API itself hands back, and therefore the last rung of the
    // fallback: whatever else is missing, this is the URL that worked before.
    private const val LASTFM_SOURCE_SIZE = "300x300"
    private const val LASTFM_SOURCE_EXT = "png"

    // i.ytimg.com filenames in increasing order of quality:
    //   `default`      → 120x90
    //   `mqdefault`    → 320x180   (16:9, no bars)
    //   `hqdefault`    → 480x360   the only one EVERY video has
    //   `sddefault`    → 640x480   present for 89% of a real library
    //   `maxresdefault`→ 1280x720  present for 82%
    //
    // This aimed at `hqdefault` because bigger variants 404 for some videos
    // and a 404 draws nothing — black album art at random (MIGRATION_42_43
    // repaired the rows an earlier `sddefault` guess had broken).
    // `ArtFallbackInterceptor` removes that constraint: a miss now walks
    // back down instead of rendering nothing, so the stored URL can aim at
    // the best variant.
    //
    // And it should, because `hqdefault` is not just small, it is the wrong
    // SHAPE. YouTube pads the 16:9 frame into 4:3, which on a measured
    // thumbnail is 45 black rows top and bottom — 24% of the square this
    // app crops to is black. `maxresdefault` is a clean 1280x720. Probed
    // over 150 covers on 2026-09-10 it was never a stock placeholder
    // either: every hit was a real 1280x720 frame, 24 KB at the smallest,
    // no repeated images. Videos without one simply 404.
    //
    // We ALSO strip `?sqp=…&rs=…` query parameters: those are Google's
    // server-side downscale tokens that shrink the served image even when
    // the URL points at a high-res `*default.jpg`. Without stripping, an
    // `hqdefault.jpg?sqp=…` URL arrives at ~320px wide.
    private const val YTIMG_TARGET = "maxresdefault"
    private val YTIMG_PATH_REGEX = Regex(
        """(/vi[a-z_]*/[^/]+/)(?:default|mqdefault|hqdefault|sddefault|maxresdefault)(\.(?:jpg|webp))""",
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
     * Note: this stays true whatever filename [upgrade] normalises to;
     * the host is the load-bearing signal.
     */
    fun isYouTubeVideoThumbnail(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return "i.ytimg.com" in url
    }

    /**
     * The next URL to try when [url] came back 404, or null when there is
     * nothing left to try. Both upgraded hosts aim high and step down.
     *
     * **YouTube (`i.ytimg.com`):** `maxresdefault` -> `sddefault` ->
     * `hqdefault`, which every video has. Each step gives up resolution
     * rather than showing nothing.
     *
     * Last.fm renders the `770x0` variants on demand and a small share of
     * hashes are missing one of the two formats — measured over 200 covers of
     * a real library on 2026-09-09, `770x0.jpg` missed 6 and `770x0.png`
     * missed 5, and no hash missed both. The rungs therefore run
     * `770x0.jpg` -> `770x0.png` -> `300x300.png` (what the API itself
     * advertises), so an upgraded cover can never render worse than the URL
     * it replaced. Applied by the OkHttp interceptor, which covers every
     * consumer of the shared client: Coil for the in-app surfaces and
     * media3's bitmap loader for the notification and lock screen.
     */
    fun artFallback(url: String?): String? {
        if (url == null) return null
        if ("i.ytimg.com" in url) {
            val next = when {
                "/$YTIMG_TARGET." in url -> url.replace("/$YTIMG_TARGET.", "/sddefault.")
                "/sddefault." in url -> url.replace("/sddefault.", "/hqdefault.")
                else -> return null
            }
            return next
        }
        if (LASTFM_HOST !in url) return null
        val match = LASTFM_PATH_REGEX.find(url) ?: return null
        val (prefix, hash) = match.groupValues[1] to match.groupValues[2]
        val next = when {
            "/$LASTFM_TARGET_SIZE/$hash.$LASTFM_TARGET_EXT" in url ->
                "$prefix$LASTFM_TARGET_SIZE/$hash.$LASTFM_SOURCE_EXT"
            "/$LASTFM_TARGET_SIZE/$hash.$LASTFM_SOURCE_EXT" in url ->
                "$prefix$LASTFM_SOURCE_SIZE/$hash.$LASTFM_SOURCE_EXT"
            else -> return null
        }
        return LASTFM_PATH_REGEX.replace(url) { next }
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
            // hands back 300x300 (or smaller `NNNs` avatars), which is badly
            // upscaled on the 300dp Now Playing hero (~1000px on a 3.5x
            // screen) and on the Home mosaics. Take the same hash 770 wide
            // and as a JPEG: sharper AND smaller than what it replaces.
            LASTFM_HOST in url && LASTFM_PATH_REGEX.containsMatchIn(url) -> {
                LASTFM_PATH_REGEX.replace(url) { match ->
                    "${match.groupValues[1]}$LASTFM_TARGET_SIZE/" +
                        "${match.groupValues[2]}.$LASTFM_TARGET_EXT"
                }
            }

            // YouTube video thumbnails (i.ytimg.com): strip the `?sqp=…&rs=…`
            // downscale query and normalise every variant to `hqdefault` —
            // the one filename that exists for every video (see YTIMG_TARGET).
            "i.ytimg.com" in url -> {
                YTIMG_PATH_REGEX.replace(url.substringBefore("?")) { match ->
                    "${match.groupValues[1]}$YTIMG_TARGET${match.groupValues[2]}"
                }
            }

            // Everything else — leave as-is
            else -> url
        }
    }
}
