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
 * Every variant is normalised to `maxresdefault.jpg` (1280x720, no
 * letterbox) and the `?sqp=` downscale query is stripped. `maxresdefault`
 * is missing for some uploads; `ArtFallbackInterceptor` walks a 404 down
 * to `sddefault` and then `hqdefault`, which every video has (see
 * [artFallback]).
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
        // `hq720` is left out on purpose: it is already the full 1280x720 frame (search results
        // carry it behind a `?sqp=` downscale, which the query strip below removes), and it
        // certainly exists, where maxres is only likely to.
        """(/vi[a-z_]*/[^/]+/)(?:default|mqdefault|hqdefault|sddefault|maxresdefault)(\.(?:jpg|webp))""",
    )

    // Large variants [ArtUrlSizer] asks for that are not guaranteed to exist, and the rung each
    // steps down to (one that always exists). See [artFallback].
    private const val QOBUZ_HOST = "static.qobuz.com"
    private const val QOBUZ_LARGEST = "_max.jpg"
    private const val QOBUZ_LARGEST_FALLBACK = "_600.jpg"
    private const val SPOTIFY_ORIGINAL = "/image/ab67616d000082c1"
    private const val SPOTIFY_ORIGINAL_FALLBACK = "/image/ab67616d0000b273"
    private const val LASTFM_ORIGINAL_SIZE = "ar0"
    private const val ITUNES_HOST = "mzstatic.com"
    private const val ITUNES_SAFE_PX = 600
    private val ITUNES_FILE_REGEX = Regex("""/(\d+)x(\d+)([a-z]{2}(?:-\d+)?\.(?:jpg|png|webp))$""")

    /**
     * The best thumbnail of YouTube video [videoId], the cover of last resort. For an auto-generated
     * YouTube Music upload it IS the album cover, centred in the 16:9 frame the UI crops square. The
     * shared client's [artFallback] walk covers the 18% of videos without a `maxresdefault`.
     */
    fun youTubeThumbnail(videoId: String): String = "https://i.ytimg.com/vi/$videoId/$YTIMG_TARGET.jpg"

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
     *
     * The large variants [ArtUrlSizer] asks for on a big surface step down to
     * the size that was stored before: Qobuz `_max` -> `_600`, Spotify's
     * original (`82c1`) -> 640 (`b273`), Last.fm's original (`ar0`) ->
     * `770x0.jpg` (and on down the walk above), iTunes above 600 -> 600.
     */
    fun artFallback(url: String?): String? {
        if (url == null) return null
        if (QOBUZ_HOST in url && url.endsWith(QOBUZ_LARGEST)) {
            return url.removeSuffix(QOBUZ_LARGEST) + QOBUZ_LARGEST_FALLBACK
        }
        if (SPOTIFY_ORIGINAL in url) {
            return url.replace(SPOTIFY_ORIGINAL, SPOTIFY_ORIGINAL_FALLBACK)
        }
        if (ITUNES_HOST in url) {
            val m = ITUNES_FILE_REGEX.find(url) ?: return null
            val w = m.groupValues[1].toIntOrNull() ?: return null
            val h = m.groupValues[2].toIntOrNull() ?: return null
            if (maxOf(w, h) <= ITUNES_SAFE_PX) return null
            return url.replaceRange(m.range, "/${ITUNES_SAFE_PX}x$ITUNES_SAFE_PX${m.groupValues[3]}")
        }
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
            "/$LASTFM_ORIGINAL_SIZE/$hash." in url ->
                "$prefix$LASTFM_TARGET_SIZE/$hash.$LASTFM_TARGET_EXT"
            "/$LASTFM_TARGET_SIZE/$hash.$LASTFM_TARGET_EXT" in url ->
                "$prefix$LASTFM_TARGET_SIZE/$hash.$LASTFM_SOURCE_EXT"
            "/$LASTFM_TARGET_SIZE/$hash.$LASTFM_SOURCE_EXT" in url ->
                "$prefix$LASTFM_SOURCE_SIZE/$hash.$LASTFM_SOURCE_EXT"
            else -> return null
        }
        return LASTFM_PATH_REGEX.replace(url) { next }
    }

    /**
     * A full-width banner of [url]: YouTube's artist header photos are wide (about 16:9,
     * 5120 px at full size), and [upgrade]'s square `=w1024-h1024-p` crops them to a
     * square. Asking for the width alone keeps the photo's own shape, 1440 px wide.
     * Other hosts get [upgrade].
     */
    fun banner(url: String?): String? {
        if (url == null) return null
        if ("lh3.googleusercontent.com" in url || "yt3.googleusercontent.com" in url) {
            return url.substringBefore("=") + BANNER_SIZE
        }
        return upgrade(url)
    }

    private const val BANNER_SIZE = "=w1440-l90-rj"

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
