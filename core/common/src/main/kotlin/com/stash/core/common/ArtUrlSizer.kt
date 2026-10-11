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
 * [forSize] is applied per image request (see `ArtSizeInterceptor` in `:core:ui`), so a 48dp row
 * keeps its stored URL and only a surface that is really big, Now Playing first of all, asks for a
 * big variant. Nothing stored in the database changes.
 *
 * **Upgrade only.** A URL is rewritten only when its own size is under 90% of the target
 * ([BIG_ENOUGH_TENTHS]). A stored URL that is about big enough passes through untouched, so list
 * thumbnails keep exactly the URL (and the disk-cache entry) they have today, a stored `=w1024`
 * is not re-fetched as `=w1280` for a 1078px request, and no surface ever asks for a smaller
 * image than the stored one.
 *
 * **Never a broken cover.** Where a bigger variant is not guaranteed to exist (Qobuz `_max`,
 * Spotify's original, Last.fm's original, YouTube `maxresdefault`, iTunes above 600), the shared
 * OkHttp client's `ArtFallbackInterceptor` walks a 404 back down through
 * [ArtUrlUpgrader.artFallback], ending on a variant that always exists; and `ArtSizeInterceptor`
 * draws the stored URL when the sized-up one fails for any other reason (offline, 5xx, timeout).
 *
 * **Data Saver.** [forSize]'s `maxVariantPx` caps the variants it may pick ([maxVariantPx]): with
 * Android's Data Saver on over a metered network, nothing above [DATA_SAVER_MAX_PX], so Qobuz stays
 * `_600`, Spotify 640 and Last.fm `770x0` (their next size up is an unbounded original, up to
 * 2.2 MB measured for a Qobuz `_max`).
 *
 * Host by host, measured 2026-10-10 with the app's own headers:
 *
 * | Host                         | Variants served                         | Largest            |
 * |------------------------------|-----------------------------------------|--------------------|
 * | lh3/yt3.googleusercontent, ggpht | any `=wN-hN` / `=sN`, capped at the original; 512 with no token | original (1400–1448) |
 * | i.ytimg.com                  | default, mq, hq, sd, hq720/maxres (1280x720) | maxres, 82% of videos |
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

    /**
     * A stored URL at least this many tenths of the target is left alone: 90%. A 1024px cover for
     * a 1078px request is not worth another download; Last.fm's 770 for a 948px cover (81%) is.
     */
    private const val BIG_ENOUGH_TENTHS = 9

    /** The largest variant [forSize] may pick under Data Saver on a metered network. */
    const val DATA_SAVER_MAX_PX = 1200

    /** No cap: any variant a host serves may be picked. */
    const val NO_CAP = Int.MAX_VALUE

    /**
     * Stand-in long side for a host's "original" variant, whose real size is unknown until it is
     * fetched (Qobuz `_max`, Spotify `82c1`, Last.fm `ar0`): above any Data Saver cap, so a capped
     * request never picks one.
     */
    private const val UNBOUNDED_PX = 4000

    // ── Google (YouTube Music album art, channel and playlist art) ─────────────────────────────
    // Any size is served, capped at the original, so the size we ask for is a free choice.
    // Bucketing keeps the number of distinct URLs (and disk-cache entries) per cover small.
    private val GOOGLE_HOST = Regex("""^(?:lh\d|yt\d)\.(?:googleusercontent|ggpht)\.com$""")
    private val GOOGLE_TIERS = intArrayOf(256, 512, 1024, 1280, 1600)
    private val GOOGLE_WH = Regex("""=w(\d+)-h(\d+)""")
    private val GOOGLE_S = Regex("""=s(\d+)""")
    private const val GOOGLE_JPEG = "-l90-rj"

    /** What Google serves for a URL with no size token (probed: 512x512). */
    private const val GOOGLE_DEFAULT_PX = 512

    // ── YouTube video thumbnails ───────────────────────────────────────────────────────────────
    // The app crops these square, so the useful size is the HEIGHT. A `?sqp=` query is Google's
    // server-side downscale: `hqdefault.jpg?sqp=…` arrives 400x225, `hq720.jpg?sqp=…` ~686x386.
    // 720 is the tallest YouTube serves (`hq720` and `maxresdefault`, the same 1280x720 frame;
    // probed over 20 videos, either both exist or neither does).
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
    private const val YTIMG_MAX_HEIGHT = 720
    private const val YTIMG_HQ720 = "hq720"

    // ── Spotify ────────────────────────────────────────────────────────────────────────────────
    private const val SPOTIFY_ALBUM_PREFIX = "ab67616d0000"
    private val SPOTIFY_TIERS = linkedMapOf("4851" to 64, "1e02" to 300, "b273" to 640, "82c1" to UNBOUNDED_PX)

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
     * The cap [forSize] should use: [DATA_SAVER_MAX_PX] when Android's Data Saver applies to this
     * app ([dataSaverRestricting]: `RESTRICT_BACKGROUND_STATUS_ENABLED`) on a metered network,
     * else [NO_CAP]. Data Saver off, the app allow-listed, or unmetered Wi-Fi: no cap.
     */
    fun maxVariantPx(dataSaverRestricting: Boolean, metered: Boolean): Int =
        if (dataSaverRestricting && metered) DATA_SAVER_MAX_PX else NO_CAP

    /**
     * [url] rewritten to the smallest variant its host serves that is at least [px] on its long
     * side (or the largest one allowed, when none is that big), or [url] itself when it is already
     * about that big (90%), the best allowed variant is no bigger than it, it is not from a host
     * we know, or [px] is not a real size.
     *
     * @param url a cover URL as stored; null and blank pass through.
     * @param px the target's long side in pixels, e.g. the cover's measured on-screen width.
     * @param maxVariantPx never pick a variant whose long side is above this ([maxVariantPx]).
     */
    fun forSize(url: String?, px: Int, maxVariantPx: Int = NO_CAP): String? {
        if (url.isNullOrBlank() || px <= 0) return url
        // Only rewrite web URLs: file paths and content:// URIs are local covers, served as-is.
        if (!url.startsWith("https://") && !url.startsWith("http://")) return url
        val afterScheme = url.substringAfter("://")
        // No path means nothing to size (and nothing we could rewrite safely).
        if ('/' !in afterScheme) return url
        val host = afterScheme.substringBefore('/').lowercase()
        val cap = maxVariantPx.coerceAtLeast(1)
        return when {
            GOOGLE_HOST.matches(host) -> google(url, px, cap)
            host == "i.ytimg.com" || host.endsWith(".ytimg.com") -> youtube(url, px)
            host == "i.scdn.co" || host.endsWith(".spotifycdn.com") -> spotify(url, px, cap)
            host == "static.qobuz.com" -> qobuz(url, px, cap)
            host.endsWith("freetls.fastly.net") && "lastfm" in host -> lastFm(url, px, cap)
            host.endsWith(".mzstatic.com") -> itunes(url, px, cap)
            host.endsWith("saavncdn.com") -> saavn(url, px, cap)
            host.endsWith(".dzcdn.net") -> deezer(url, px, cap)
            else -> url
        }
    }

    /**
     * [forSize], limited to variants that EVERY cover on its host has, for a consumer that can't
     * fall back when a bigger variant is missing: Android Auto's browse tiles, which the car fetches
     * itself from the item's artwork URI. A pick that [ArtUrlUpgrader.artFallback] would have to
     * walk down from on a 404 (YouTube maxres, Last.fm 770/originals, Qobuz `_max`, Spotify's
     * original, iTunes above 600) is not guaranteed, so [url] is kept. Google sizes, Qobuz up to
     * `_600`, Spotify up to 640, iTunes up to 600 and JioSaavn 500 always exist.
     */
    fun forSizeGuaranteed(url: String?, px: Int, maxVariantPx: Int = NO_CAP): String? {
        val sized = forSize(url, px, maxVariantPx)
        if (sized == url || sized == null) return sized
        return if (ArtUrlUpgrader.artFallback(sized) == null) sized else url
    }

    /**
     * How many pixels [url] itself is known to deliver on its long side, for the one host where
     * the bigger variant [forSize] picks can come back SMALLER: Last.fm. Its `770x0` is the CDN's
     * own rendering, upscaled when the original is smaller (46% of a real library's covers), and
     * `ar0` is that original, as small as 350px. When the original turns out smaller than this,
     * the stored URL draws better (the CDN resamples it more cleanly than a phone stretches it),
     * so `ArtSizeInterceptor` draws that instead. Null for every other host: there, a bigger
     * variant is never smaller than the stored one (Google and iTunes cap at the original, and
     * Qobuz `_max`, Spotify's original, YouTube maxres are at least the stored tier).
     */
    fun storedPx(url: String?): Int? {
        if (url.isNullOrBlank() || !url.startsWith("http")) return null
        val host = url.substringAfter("://").substringBefore('/').lowercase()
        if (!(host.endsWith("freetls.fastly.net") && "lastfm" in host)) return null
        val token = LASTFM_PATH.find(url)?.groupValues?.get(2) ?: return null
        return lastFmTokenPx(token)?.takeIf { it < UNBOUNDED_PX }
    }

    /** A size digit-run as an Int; null when it overflows (a malformed URL, left untouched). */
    private fun String.px(): Int? = toIntOrNull()

    /** True when [stored] is at least 90% of [px]: close enough that a bigger one isn't worth it. */
    private fun bigEnough(stored: Int, px: Int): Boolean =
        stored.toLong() * 10 >= px.toLong() * BIG_ENOUGH_TENTHS

    /**
     * The first of [tiers] that is at least [px], else the largest; never above [cap] (the
     * smallest tier when even that is above it).
     */
    private fun tierFor(px: Int, tiers: IntArray, cap: Int): Int {
        val allowed = tiers.filter { it <= cap }.ifEmpty { listOf(tiers.first()) }
        return allowed.firstOrNull { it >= px } ?: allowed.last()
    }

    private fun google(url: String, px: Int, cap: Int): String {
        GOOGLE_WH.find(url)?.let { m ->
            val w = m.groupValues[1].px() ?: return url
            val h = m.groupValues[2].px() ?: return url
            val stored = maxOf(w, h)
            if (stored <= 0 || bigEnough(stored, px)) return url
            val t = tierFor(px, GOOGLE_TIERS, cap)
            if (t <= stored) return url
            // Scale both sides: a non-square box (a banner with `-p`, an exact crop) keeps its
            // shape rather than turning into a square crop.
            val nw = (w.toLong() * t / stored).toInt()
            val nh = (h.toLong() * t / stored).toInt()
            return url.replaceRange(m.range, "=w$nw-h$nh")
        }
        GOOGLE_S.find(url)?.let { m ->
            val stored = m.groupValues[1].px() ?: return url
            // `=s0` is Google's "original size": nothing is bigger.
            if (stored == 0 || bigEnough(stored, px)) return url
            val t = tierFor(px, GOOGLE_TIERS, cap)
            if (t <= stored) return url
            return url.replaceRange(m.range, "=s$t")
        }
        // No size token: Google serves 512. Ask for a size explicitly only when that is bigger
        // (upgrade only), replacing any other options (as ArtUrlUpgrader.upgrade does), and for a
        // JPEG: without `-rj` a PNG original is served as PNG, ~6x the bytes (3 MB vs 0.5 MB at
        // 1200px).
        if (bigEnough(GOOGLE_DEFAULT_PX, px)) return url
        val t = tierFor(px, GOOGLE_TIERS, cap)
        if (t <= GOOGLE_DEFAULT_PX) return url
        return url.substringBefore('=') + "=w$t-h$t$GOOGLE_JPEG"
    }

    private fun youtube(url: String, px: Int): String {
        val m = YTIMG_FILE.find(url) ?: return url
        val file = m.groupValues[1]
        val nominal = YTIMG_HEIGHT[file] ?: return url
        val downscaled = '?' in url
        val stored = if (downscaled) minOf(nominal, YTIMG_SQP_HEIGHT) else nominal
        // A clean 720 frame is YouTube's largest: nothing to gain.
        if (stored >= YTIMG_MAX_HEIGHT || bigEnough(stored, px)) return url
        // `hq720` behind a downscale query: the full 1280x720 is the same file without it, and it
        // certainly exists (the URL came from YouTube), where maxres is only likely to.
        if (file == YTIMG_HQ720) return url.substringBefore('?')
        // maxresdefault is the only other variant that is not letterboxed and bigger than hq; a
        // video without one walks maxres -> sd -> hq in ArtFallbackInterceptor.
        return ArtUrlUpgrader.upgrade(url) ?: url
    }

    private fun spotify(url: String, px: Int, cap: Int): String {
        val i = url.indexOf("/image/$SPOTIFY_ALBUM_PREFIX")
        if (i < 0) return url
        val codeStart = i + "/image/$SPOTIFY_ALBUM_PREFIX".length
        if (url.length < codeStart + 4) return url
        val code = url.substring(codeStart, codeStart + 4)
        val stored = SPOTIFY_TIERS[code] ?: return url
        if (bigEnough(stored, px)) return url
        val allowed = SPOTIFY_TIERS.entries.filter { it.value <= cap }
        val target = (allowed.firstOrNull { it.value >= px } ?: allowed.lastOrNull()) ?: return url
        if (target.value <= stored) return url
        return url.replaceRange(codeStart, codeStart + 4, target.key)
    }

    private fun qobuz(url: String, px: Int, cap: Int): String {
        val m = QOBUZ_FILE.find(url) ?: return url
        val stored = m.groupValues[2].px() ?: return url // `_max` / `_org` are already the largest
        if (bigEnough(stored, px)) return url
        val maxAllowed = UNBOUNDED_PX <= cap
        val target = when {
            px <= QOBUZ_TIERS.last() -> tierFor(px, QOBUZ_TIERS, cap).toString()
            maxAllowed -> QOBUZ_MAX
            else -> tierFor(px, QOBUZ_TIERS, cap).toString()
        }
        if (target != QOBUZ_MAX && target.toInt() <= stored) return url
        return url.replaceRange(m.groups[2]!!.range, target)
    }

    private fun lastFm(url: String, px: Int, cap: Int): String {
        val m = LASTFM_PATH.find(url) ?: return url
        val stored = lastFmTokenPx(m.groupValues[2]) ?: return url
        if (bigEnough(stored, px)) return url
        val size = when {
            px > LASTFM_770 && UNBOUNDED_PX <= cap -> "ar0"
            LASTFM_770 > stored && LASTFM_770 <= cap -> "770x0"
            else -> return url
        }
        return url.replaceRange(m.range, "${m.groupValues[1]}$size/${m.groupValues[3]}.jpg")
    }

    /** The long side a Last.fm size token stands for: `300x300` 300, `174s` 174, `ar0` original. */
    private fun lastFmTokenPx(token: String): Int? = when (token) {
        "ar0" -> UNBOUNDED_PX
        else -> LASTFM_TOKEN.find(token)?.let { m ->
            maxOf(m.groupValues[1].px() ?: return null, m.groupValues[2].px() ?: 0)
        }
    }

    private fun itunes(url: String, px: Int, cap: Int): String {
        val m = ITUNES_FILE.find(url) ?: return url
        val stored = maxOf(m.groupValues[1].px() ?: return url, m.groupValues[2].px() ?: return url)
        if (bigEnough(stored, px)) return url
        val t = tierFor(px, ITUNES_TIERS, cap)
        if (t <= stored) return url
        return url.replaceRange(m.range, "/${t}x$t${m.groupValues[3]}.${m.groupValues[4]}")
    }

    private fun saavn(url: String, px: Int, cap: Int): String {
        val m = SAAVN_FILE.find(url) ?: return url
        val stored = maxOf(m.groupValues[1].px() ?: return url, m.groupValues[2].px() ?: return url)
        if (bigEnough(stored, px)) return url
        val t = tierFor(px, SAAVN_TIERS, cap)
        if (t <= stored) return url
        return url.replaceRange(m.range, "-${t}x$t.${m.groupValues[3]}")
    }

    private fun deezer(url: String, px: Int, cap: Int): String {
        val m = DEEZER_FILE.find(url) ?: return url
        val stored = maxOf(m.groupValues[2].px() ?: return url, m.groupValues[3].px() ?: return url)
        if (bigEnough(stored, px)) return url
        val t = tierFor(px, DEEZER_TIERS, cap)
        if (t <= stored) return url
        return url.replaceRange(m.range, "${m.groupValues[1]}${t}x$t${m.groupValues[4]}")
    }
}
