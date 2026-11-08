package com.stash.data.lyrics.source

import android.util.Log
import com.stash.core.common.Clock
import com.stash.core.common.SystemClock
import com.stash.data.lyrics.parser.TtmlParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * Spicy Lyrics (api.spicylyrics.org): best available sync for a track.
 * Syllable -> word-synced TTML (built here, parsed like Apple's), Line -> synced LRC.
 * Static (plain text) is a miss: taking it would stop the walk before LRCLIB and KuGou, which
 * often have the song line-synced.
 * Looks the track up by [LyricsQuery.spotifyTrackId], else its [LyricsQuery.isrc], else an ISRC from
 * lrc.red's search, else an Apple Music id found through iTunes; with none it is a clean miss.
 * Blank [apiKey] + default [baseUrl] -> off. Blank [apiKey] + custom [baseUrl] (a proxy that adds the key) -> no auth header.
 *
 * [onDemandOnly]: asked only for a track being played or just downloaded (in its place right after
 * Apple, see `LyricsModule`), and by the word-synced upgrades that ask the on-demand sources (Now
 * Playing's action, "Re-check library", the follow-up after a line-synced answer).
 *
 * Failures: 404 is a plain miss. A 429, 401/403, any other failed status, a transport failure or a
 * 200 that isn't JSON (a Cloudflare challenge page, say) opens a [SKIP_WINDOW_MS] window, so a host in
 * trouble isn't asked again on every song. A 429 and an open window THROW ([AppleTtmlLyricsSource.HttpStatusException] /
 * [SkippedException]): a walk reads that as a miss (an on-demand source can't fail it), an upgrade pass
 * as RATE_LIMITED / PAUSED instead of moving past tracks Spicy never answered. Anything else is a miss.
 */
class SpicyLyricsSource(
    client: OkHttpClient,
    appVersionName: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val apiKey: String = "",
    private val clock: Clock = SystemClock(),
    /** lrc.red's title/artist -> ISRC search, shared with the lrc.red/Binimum sources and their breaker. */
    private val isrcResolver: IsrcResolver? = null,
    /** iTunes title/artist -> Apple Music id, shared with [AppleTtmlLyricsSource]. */
    private val appleMusicIds: AppleMusicIdResolver? = null,
    /** [LyricsDns] in the app; tests pass [Dns.SYSTEM] so they never reach Cloudflare. */
    lyricsDns: Dns = LyricsDns(),
) : LyricsSource {

    override val id = SOURCE_ID
    override val displayName = DISPLAY_NAME
    override val onDemandOnly = true
    /** Thrown while the skip window is open: the request wasn't sent. */
    class SkippedException : IOException("Spicy Lyrics skipped: the service keeps failing or rate-limiting")

    private val userAgent = "Stash/$appVersionName (Android)"
    private val interactiveHttp = client.newBuilder().dns(lyricsDns).callTimeout(4, TimeUnit.SECONDS).build()
    private val backgroundHttp = client.newBuilder().dns(lyricsDns).callTimeout(10, TimeUnit.SECONDS).build()

    private var skipUntilMs = 0L

    override suspend fun resolve(query: LyricsQuery): LyricsResult? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() && baseUrl == DEFAULT_BASE_URL) return@withContext null
        if (skipWindowOpen()) throw SkippedException()
        // Spotify id first (it finds community syncs the other ids can't), then the track's own ISRC,
        // then an ISRC from lrc.red's search, then an Apple Music id from iTunes. Only the last two cost
        // a request, and whichever source asks first pays for it: IsrcResolver is shared with lrc.red and
        // Binimum (which run after this), AppleMusicIdResolver with Apple's source (which runs before it).
        val trackId = query.spotifyTrackId
            ?: normalizeIsrc(query.isrc)
            ?: isrcFromTitleArtist(query)
            ?: appleMusicIds?.resolve(query)
            ?: return@withContext null

        val json = try {
            fetch(trackId, query.interactive)
        } catch (e: AppleTtmlLyricsSource.HttpStatusException) {
            throw e // a 429: the skip window is open already, and an upgrade pass must hear about it
        } catch (e: IOException) {
            Log.d(TAG, "spicy transport failure for \"${query.title}\": ${e.message}")
            openSkipWindow("transport failure")
            null
        }
        if (json == null) return@withContext null

        // Not JSON at all: the host (or something in front of it) is in trouble, not the song.
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            openSkipWindow("a 200 that isn't JSON (${json.length} chars)")
            return@withContext null
        }
        val result = runCatching { parse(root, trackId) }
            .onFailure { Log.w(TAG, "spicy response for $trackId didn't parse", it) }
            .getOrNull()
        Log.d(TAG, "spicy $trackId (\"${query.title}\") -> ${result?.let { if (it.ttml != null) "syllable" else "line" } ?: "miss"}")
        result
    }

    /** The shared [IsrcResolver]: any failure, including its breaker being open, just means "no ISRC from here". */
    private suspend fun isrcFromTitleArtist(query: LyricsQuery): String? {
        val resolver = isrcResolver ?: return null
        return try {
            normalizeIsrc(resolver.resolve(query))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "ISRC search unavailable for \"${query.title}\": ${e.message}")
            null
        }
    }

    /** Body on 200, null on 404 or a skip-worthy status, throws IOException on other failures. */
    private fun fetch(trackId: String, interactive: Boolean): String? {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("v1/lyrics")
            .addPathSegment(trackId)
            .build()
        val request = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .build()
        val http = if (interactive) interactiveHttp else backgroundHttp
        return http.newCall(request).execute().use { r ->
            when {
                r.code == 404 -> null
                r.code == 429 -> {
                    openSkipWindow("HTTP 429")
                    throw AppleTtmlLyricsSource.HttpStatusException(429, url.host)
                }
                r.code == 401 || r.code == 403 -> {
                    openSkipWindow("HTTP ${r.code}")
                    null
                }
                !r.isSuccessful -> throw IOException("HTTP ${r.code} from ${url.host}")
                else -> r.body?.string()
            }
        }
    }

    private fun parse(root: JSONObject, trackId: String): LyricsResult? {
        if (root.optInt("Status", 200) != 200) return null
        val body = root.optJSONObject("Body") ?: return null
        val lyricsId = body.str("id") ?: trackId
        val credits = credits(body)
        return when (body.str("Type")) {
            "Syllable" -> {
                val ttml = buildTtml(body) ?: return null
                val parsed = TtmlParser.parse(ttml)?.takeIf { it.lines.isNotEmpty() } ?: return null
                LyricsResult(
                    sourceId = id, plainText = parsed.toPlainText(), syncedLrc = parsed.toLrc(),
                    instrumental = false, language = null, sourceLyricsId = lyricsId,
                    ttml = ttml, credits = credits,
                )
            }
            "Line" -> {
                val content = body.optJSONArray("Content") ?: return null
                val lrc = StringBuilder()
                val plain = StringBuilder()
                for (i in 0 until content.length()) {
                    val o = content.optJSONObject(i) ?: continue
                    val text = o.str("Text")?.trim().orEmpty()
                    if (text.isEmpty()) continue
                    lrc.append(lrcStamp(o.optDouble("StartTime", 0.0))).append(text).append('\n')
                    plain.append(text).append('\n')
                }
                if (plain.isEmpty()) return null
                LyricsResult(
                    sourceId = id, plainText = plain.toString().trimEnd(), syncedLrc = lrc.toString().trimEnd(),
                    instrumental = false, language = null, sourceLyricsId = lyricsId, credits = credits,
                )
            }
            // "Static" (plain text) and anything new: a miss, so the sources after this one get
            // their turn (see the class KDoc).
            else -> null
        }
    }

    /** Only when Spicy's own catalogue answered; other sources carry no uploader/maker. */
    private fun credits(body: JSONObject): LyricsCredits? {
        if (body.str("source") != "spicy_lyrics") return null
        val attr = body.optJSONObject("UploadAttribution") ?: return null
        fun person(key: String): Pair<String?, String?> {
            val o = attr.optJSONObject(key) ?: return null to null
            val name = o.str("username")
            val url = o.str("url")?.takeIf { it.startsWith("https://") }
            return name to url
        }
        val (uName, uUrl) = person("Uploader")
        val (mName, mUrl) = person("Maker")
        if (uName == null && mName == null) return null
        return LyricsCredits(uName, uUrl, mName, mUrl)
    }

    /** Apple-style TTML (the shape [TtmlParser] reads): one <p> per vocal line, bg vocals as x-bg spans. */
    private fun buildTtml(body: JSONObject): String? {
        val content = body.optJSONArray("Content") ?: return null
        val ps = StringBuilder()
        var n = 0
        for (i in 0 until content.length()) {
            val v = content.optJSONObject(i) ?: continue
            if (v.str("Type") != "Vocal") continue
            val lead = v.optJSONObject("Lead") ?: continue
            val leadSpans = spans(lead.optJSONArray("Syllables"))
            if (leadSpans.isEmpty()) continue
            n++
            val begin = lead.optDouble("StartTime", 0.0)
            var end = lead.optDouble("EndTime", begin)
            val bgXml = StringBuilder()
            v.optJSONArray("Background")?.let { bgs ->
                for (b in 0 until bgs.length()) {
                    val bg = bgs.optJSONObject(b) ?: continue
                    val bgSpans = spans(bg.optJSONArray("Syllables"))
                    if (bgSpans.isEmpty()) continue
                    val bgBegin = bg.optDouble("StartTime", begin)
                    val bgEnd = bg.optDouble("EndTime", bgBegin)
                    if (bgEnd > end) end = bgEnd
                    bgXml.append(" <span ttm:role=\"x-bg\" begin=\"${ts(bgBegin)}\" end=\"${ts(bgEnd)}\">")
                        .append(bgSpans).append("</span>")
                }
            }
            val agent = if (v.optBoolean("OppositeAligned")) "v2" else "v1"
            ps.append("<p begin=\"${ts(begin)}\" end=\"${ts(end)}\" ttm:agent=\"$agent\" itunes:key=\"L$n\">")
                .append(leadSpans).append(bgXml).append("</p>")
        }
        if (n == 0) return null
        val start = body.optDouble("StartTime", 0.0)
        val end = body.optDouble("EndTime", 0.0)
        return "<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\" " +
            "xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\" itunes:timing=\"Word\">" +
            "<head><metadata><ttm:agent type=\"person\" xml:id=\"v1\"/><ttm:agent type=\"person\" xml:id=\"v2\"/></metadata></head>" +
            "<body dur=\"${ts(end)}\"><div begin=\"${ts(start)}\" end=\"${ts(end)}\">$ps</div></body></tt>"
    }

    private fun spans(syllables: JSONArray?): String {
        if (syllables == null) return ""
        val sb = StringBuilder()
        for (i in 0 until syllables.length()) {
            val s = syllables.optJSONObject(i) ?: continue
            val text = escape(s.str("Text").orEmpty()).trim()
            if (text.isEmpty()) continue
            val b = s.optDouble("StartTime", 0.0)
            val e = s.optDouble("EndTime", b)
            sb.append("<span begin=\"${ts(b)}\" end=\"${ts(e)}\">").append(text).append("</span>")
            if (!s.optBoolean("IsPartOfWord") && i < syllables.length() - 1) sb.append(' ')
        }
        return sb.toString().trimEnd()
    }

    /**
     * Text for the TTML: the control characters XML 1.0 doesn't allow are dropped (one would make
     * the whole document unparseable, losing every line), then `& < > "` are escaped.
     */
    private fun escape(s: String) = XML_INVALID_CHARS.replace(s, "")
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Apple TTML clock: `12.345` under a minute, `m:ss.mmm` above. */
    private fun ts(sec: Double): String {
        val ms = (sec * 1000).roundToLong().coerceAtLeast(0L)
        val m = ms / 60_000
        val s = (ms % 60_000) / 1000
        val milli = ms % 1000
        return if (m == 0L) String.format(Locale.ROOT, "%d.%03d", s, milli)
        else String.format(Locale.ROOT, "%d:%02d.%03d", m, s, milli)
    }

    private fun lrcStamp(sec: Double): String {
        val cs = (sec * 100).roundToLong().coerceAtLeast(0L)
        return String.format(Locale.ROOT, "[%02d:%02d.%02d]", cs / 6000, (cs % 6000) / 100, cs % 100)
    }

    /** The string at [key], or null when it's missing, JSON null (which `optString` turns into "null") or blank. */
    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).ifBlank { null }

    @Synchronized
    private fun skipWindowOpen() = clock.now() < skipUntilMs

    @Synchronized
    private fun openSkipWindow(reason: String) {
        skipUntilMs = clock.now() + SKIP_WINDOW_MS
        Log.w(TAG, "Skipping Spicy Lyrics for ${SKIP_WINDOW_MS / 60_000} min: $reason")
    }

    companion object {
        const val SOURCE_ID = "spicy-lyrics"
        /** Also the provider named in the lyrics credit ("Lyrics by Spicy Lyrics"). */
        const val DISPLAY_NAME = "Spicy Lyrics"
        const val DEFAULT_BASE_URL = "https://api.spicylyrics.org"
        private const val TAG = "SpicyLyricsSource"
        internal const val SKIP_WINDOW_MS = 10 * 60_000L

        /** Characters XML 1.0 forbids: C0 controls other than tab, LF and CR, and U+FFFE/U+FFFF. */
        private val XML_INVALID_CHARS = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\uFFFE\\uFFFF]")

        private val ISRC_REGEX = Regex("^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$")

        /** An ISRC as Spicy's `id` takes it: 12 chars, upper-case, no dashes ("us-aaa-00-00001" -> "USAAA0000001"); null if it isn't one. */
        internal fun normalizeIsrc(raw: String?): String? {
            val s = raw?.filter { it.isLetterOrDigit() }?.uppercase(Locale.ROOT) ?: return null
            return s.takeIf { ISRC_REGEX.matches(it) }
        }
    }
}
