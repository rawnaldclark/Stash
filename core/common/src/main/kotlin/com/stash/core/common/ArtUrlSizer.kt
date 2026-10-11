package com.stash.core.common

/**
 * Picks the variant of a cover URL that is big enough for a given on-screen size.
 *
 * Covers are stored at whatever size their source handed back, and for several sources that is
 * far smaller than the Now Playing cover, which is ~950px wide on a Pixel 5 and ~1270px on a
 * Pixel 6 Pro. Measured on a real library (2026-10-10): Qobuz covers are stored `_600`, some
 * YouTube Music covers `=w544` or even `=w60`, YouTube thumbnails `hqdefault?sqp=…` (400x225),
 * and Last.fm `770x0`. Every one of those hosts serves the same image larger.
 *
 * [forSize] is applied per image request (see `ArtSizeMapper` in `:core:ui`), so a 48dp row asks
 * for a small variant and only a surface that is really big, Now Playing first of all, asks for a
 * big one. Nothing stored in the database changes.
 *
 * **Upgrade only.** A URL is rewritten only when its own size is SMALLER than [forSize]'s target.
 * A stored URL that is already big enough passes through untouched, so list thumbnails keep
 * exactly the URL (and the disk-cache entry) they have today, and no surface ever gets a smaller
 * image than before this existed.
 *
 * **Never a broken cover.** Where a bigger variant is not guaranteed to exist (Qobuz `_max`,
 * Spotify's original, Last.fm's original, YouTube `maxresdefault`, iTunes above 600), the shared
 * OkHttp client's `ArtFallbackInterceptor` walks a 404 back down through
 * [ArtUrlUpgrader.artFallback], ending on a variant that always exists.
 *
 * Host by host, measured 2026-10-10 with the app's own headers:
 *
 * | Host                         | Variants served                         | Largest            |
 * |------------------------------|-----------------------------------------|--------------------|
 * | lh3/yt3.googleusercontent, ggpht | any `=wN-hN` / `=sN`, capped at the original | original (1400–1448) |
 * | i.ytimg.com                  | default, mq, hq, sd, maxres (1280x720)  | maxres, 82% of videos |
 * | i.scdn.co / spotifycdn       | 64, 300, 640, `82c1` original            | 1500–2000          |
 * | static.qobuz.com             | 50,100,150,230,300,600, `_max`           | 1300–4000 (`_max`) |
 * | Last.fm (freetls.fastly.net) | 300x300, 770x0, `ar0` original           | 350–3000 (`ar0`)   |
 * | mzstatic.com (iTunes)        | any `NxNbb`                              | 3000               |
 * | c.saavncdn.com (JioSaavn)    | 50x50, 150x150, 500x500                  | 500                |
 * | dzcdn.net (Deezer)           | any `NxN-…`; 1000 is cover_xl            | 1000               |
 *
 * Unknown hosts, unparseable URLs, local paths and `content://` URIs pass through unchanged.
 */
object ArtUrlSizer {

    // ── Google (YouTube Music album art, channel and playlist art) ─────────────────────────────
    // Any size is served, capped at the original, so the size we ask for is a free choice.
    // Bucketing keeps the number of distinct URLs (and disk-cache entries) per cover small.
    private val GOOGLE_TIERS = intArrayOf(256, 512, 1024, 1280, 1600)
    private val GOOGLE_HOST = Regex("""^(?:lh\d|yt\d)\.(?:googleusercontent|ggpht)\.com$""")
    private val GOOGLE_WH = Regex("""=w(\d+)-h(\d+)""")
    private val GOOGLE_S = Regex("""=s(\d+)""")
    private const val GOOGLE_JPEG = "-l90-rj"

    // ── YouTube video thumbnails ───────────────────────────────────────────────────────────────
    // The app crops these square, so the useful size is the HEIGHT. A `?sqp=` query is Google's
    // server-side downscale: `hqdefault.jpg?sqp=…` arrives 400x225, `hq720.jpg?sqp=…` ~686x386.
    private val YTIMG_FILE = Regex("""/vi[a-z_]*/[^/]+/([a-z0-9]+)\.(?:jpg|webp)""")
    private val YTIMG_HEIGHT = mapOf(
        "default" to 90,
        "mqdefault" to 180,
        "hqdefault" to 360,
        "sddefault" to 480,
        "hq720" to 720,
        "maxresdefault" to 720,
    )
    private const val YTIMG_SQP_HEIGHT = 225

    // ── Spotify ────────────────────────────────────────────────────────────────────────────────
    private const val SPOTIFY_ALBUM_PREFIX = "ab67616d0000"
    private val SPOTIFY_TIERS = linkedMapOf("4851" to 64, "1e02" to 300, "b273" to 640, "82c1" to Int.MAX_VALUE)

    // ── Qobuz ──────────────────────────────────────────────────────────────────────────────────
    // Nothing between 600 and `_max` exists (_700.._1400 all 404). `_org` is never used: the same
    // pixels as `_max` at up to 5x the bytes (11 MB measured).
    private val QOBUZ_FILE = Regex("""(/images/covers/.+_)(\d+|max|org)(\.jpg)$""")
    private val QOBUZ_TIERS = intArrayOf(50, 100, 150, 230, 300, 600)
    private const val QOBUZ_MAX = "max"

    // ── Last.fm ────────────────────────────────────────────────────────────────────────────────
    // `770x0` is upscaled by the CDN when the original is smaller (46% of a real library's
    // covers), and downscaled when it is larger (32% are over 1024). `ar0` is the original. Every
    // other token (1024x1024, 1200x0, …) silently returns the original too, so there is no
    // bounded size above 770. Always JPEG: `ar0.png` of a 3000px cover is 11 MB.
    private val LASTFM_PATH = Regex("""(/i/u/)([^/]+)/([0-9a-f]+)\.[A-Za-z]+""")
    private val LASTFM_TOKEN = Regex("""^(\d+)(?:x(\d+))?s?$""")
    private const val LASTFM_770 = 770

    // ── iTunes / Apple Music ───────────────────────────────────────────────────────────────────
    private val ITUNES_FILE = Regex("""/(\d+)x(\d+)([a-z]{2}(?:-\d+)?)\.(jpg|png|webp)$""")
    private val ITUNES_TIERS = intArrayOf(300, 600, 1200, 1600)

    // ── JioSaavn ───────────────────────────────────────────────────────────────────────────────
    private val SAAVN_FILE = Regex("""-(\d+)x(\d+)\.(jpg|png|webp)$""")
    private val SAAVN_TIERS = intArrayOf(50, 150, 500)

    // ── Deezer ─────────────────────────────────────────────────────────────────────────────────
    private val DEEZER_FILE = Regex("""(/images/(?:cover|artist)/[0-9a-f]+/)(\d+)x(\d+)(-[^/]*)$""")
    private val DEEZER_TIERS = intArrayOf(250, 500, 1000)

    /**
     * [url] rewritten to the smallest variant its host serves that is at least [px] on its long
     * side (or the host's largest, when none is that big), or [url] itself when it is already at
     * least that big, is not from a host we know, or [px] is not a real size.
     *
     * @param url a cover URL as stored; null and blank pass through.
     * @param px the target's long side in pixels, e.g. the cover's measured on-screen width.
     */
    fun forSize(url: String?, px: Int): String? {
        if (url.isNullOrBlank() || px <= 0) return url
        // Only rewrite web URLs: file paths and content:// URIs are local covers, served as-is.
        if (!url.startsWith("https://") && !url.startsWith("http://")) return url
        val afterScheme = url.substringAfter("://")
        // No path means nothing to size (and nothing we could rewrite safely).
        if ('/' !in afterScheme) return url
        val host = afterScheme.substringBefore('/').lowercase()
        return when {
            GOOGLE_HOST.matches(host) -> google(url, px)
            host == "i.ytimg.com" || host.endsWith(".ytimg.com") -> youtube(url, px)
            host == "i.scdn.co" || host.endsWith(".spotifycdn.com") -> spotify(url, px)
            host == "static.qobuz.com" -> qobuz(url, px)
            host.endsWith("freetls.fastly.net") && "lastfm" in host -> lastFm(url, px)
            host.endsWith(".mzstatic.com") -> itunes(url, px)
            host.endsWith("saavncdn.com") -> saavn(url, px)
            host.endsWith(".dzcdn.net") -> deezer(url, px)
            else -> url
        }
    }

    /** A size digit-run as an Int; null when it overflows (a malformed URL, left untouched). */
    private fun String.px(): Int? = toIntOrNull()

    /** The first of [tiers] that is at least [px], else the largest. */
    private fun tierFor(px: Int, tiers: IntArray): Int = tiers.firstOrNull { it >= px } ?: tiers.last()

    private fun google(url: String, px: Int): String {
        GOOGLE_WH.find(url)?.let { m ->
            val stored = maxOf(m.groupValues[1].px() ?: return url, m.groupValues[2].px() ?: return url)
            if (stored >= px) return url
            val t = tierFor(px, GOOGLE_TIERS)
            return url.replaceRange(m.range, "=w$t-h$t")
        }
        GOOGLE_S.find(url)?.let { m ->
            val stored = m.groupValues[1].px() ?: return url
            // `=s0` is Google's "original size": nothing is bigger.
            if (stored == 0 || stored >= px) return url
            return url.replaceRange(m.range, "=s${tierFor(px, GOOGLE_TIERS)}")
        }
        // No size token: Google serves a small default (512 at most). Ask for a size explicitly,
        // replacing any other options (as ArtUrlUpgrader.upgrade does), and for a JPEG: without
        // `-rj` a PNG original is served as PNG, ~6x the bytes (3 MB vs 0.5 MB at 1200px).
        val t = tierFor(px, GOOGLE_TIERS)
        return url.substringBefore('=') + "=w$t-h$t$GOOGLE_JPEG"
    }

    private fun youtube(url: String, px: Int): String {
        val m = YTIMG_FILE.find(url) ?: return url
        val nominal = YTIMG_HEIGHT[m.groupValues[1]] ?: return url
        val stored = if ('?' in url) minOf(nominal, YTIMG_SQP_HEIGHT) else nominal
        if (stored >= px) return url
        // maxresdefault is the only variant that is not letterboxed and bigger than hq; a video
        // without one walks maxres -> sd -> hq in ArtFallbackInterceptor.
        return ArtUrlUpgrader.upgrade(url) ?: url
    }

    private fun spotify(url: String, px: Int): String {
        val i = url.indexOf("/image/$SPOTIFY_ALBUM_PREFIX")
        if (i < 0) return url
        val codeStart = i + "/image/$SPOTIFY_ALBUM_PREFIX".length
        if (url.length < codeStart + 4) return url
        val code = url.substring(codeStart, codeStart + 4)
        val stored = SPOTIFY_TIERS[code] ?: return url
        if (stored >= px) return url
        val target = SPOTIFY_TIERS.entries.first { it.value >= px }.key
        return url.replaceRange(codeStart, codeStart + 4, target)
    }

    private fun qobuz(url: String, px: Int): String {
        val m = QOBUZ_FILE.find(url) ?: return url
        val token = m.groupValues[2]
        val stored = token.px() ?: return url // `_max` / `_org` are already the largest
        if (stored >= px) return url
        val target = if (px > QOBUZ_TIERS.last()) QOBUZ_MAX else tierFor(px, QOBUZ_TIERS).toString()
        return url.replaceRange(m.groups[2]!!.range, target)
    }

    private fun lastFm(url: String, px: Int): String {
        val m = LASTFM_PATH.find(url) ?: return url
        val token = m.groupValues[2]
        val stored = lastFmTokenPx(token) ?: return url
        if (stored >= px) return url
        val (size, ext) = if (px <= LASTFM_770) "770x0" to "jpg" else "ar0" to "jpg"
        return url.replaceRange(m.range, "${m.groupValues[1]}$size/${m.groupValues[3]}.$ext")
    }

    /** The long side a Last.fm size token stands for: `300x300` 300, `174s` 174, `ar0` original. */
    private fun lastFmTokenPx(token: String): Int? = when {
        token == "ar0" -> Int.MAX_VALUE
        else -> LASTFM_TOKEN.find(token)?.let { m ->
            maxOf(m.groupValues[1].px() ?: return null, m.groupValues[2].px() ?: 0)
        }
    }

    private fun itunes(url: String, px: Int): String {
        val m = ITUNES_FILE.find(url) ?: return url
        val stored = maxOf(m.groupValues[1].px() ?: return url, m.groupValues[2].px() ?: return url)
        if (stored >= px) return url
        val t = tierFor(px, ITUNES_TIERS)
        return url.replaceRange(m.range, "/${t}x$t${m.groupValues[3]}.${m.groupValues[4]}")
    }

    private fun saavn(url: String, px: Int): String {
        val m = SAAVN_FILE.find(url) ?: return url
        val stored = maxOf(m.groupValues[1].px() ?: return url, m.groupValues[2].px() ?: return url)
        if (stored >= px) return url
        val t = tierFor(px, SAAVN_TIERS)
        if (t <= stored) return url
        return url.replaceRange(m.range, "-${t}x$t.${m.groupValues[3]}")
    }

    private fun deezer(url: String, px: Int): String {
        val m = DEEZER_FILE.find(url) ?: return url
        val stored = maxOf(m.groupValues[2].px() ?: return url, m.groupValues[3].px() ?: return url)
        if (stored >= px) return url
        val t = tierFor(px, DEEZER_TIERS)
        if (t <= stored) return url
        return url.replaceRange(m.range, "${m.groupValues[1]}${t}x$t${m.groupValues[4]}")
    }
}
