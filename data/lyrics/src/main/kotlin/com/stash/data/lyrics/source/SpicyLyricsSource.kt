package com.stash.data.lyrics.source

import android.util.Log
import com.stash.core.common.Clock
import com.stash.core.common.SystemClock
import com.stash.data.lyrics.parser.TtmlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * Spicy Lyrics (api.spicylyrics.org): best available sync for a Spotify track id.
 * Syllable -> word-synced TTML (built here, parsed like Apple's), Line -> synced LRC, Static -> plain.
 * Needs [LyricsQuery.spotifyTrackId]; without one it is a clean miss.
 * Blank [apiKey] + default [baseUrl] -> off. Blank [apiKey] + custom [baseUrl] (a proxy that adds the key) -> no auth header.
 */
class SpicyLyricsSource(
    client: OkHttpClient,
    appVersionName: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val apiKey: String = "",
    private val clock: Clock = SystemClock(),
) : LyricsSource {

    override val id = SOURCE_ID
    override val displayName = "Spicy Lyrics"

    private val userAgent = "Stash/$appVersionName (Android)"
    private val interactiveHttp = client.newBuilder().callTimeout(4, TimeUnit.SECONDS).build()
    private val backgroundHttp = client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()

    private var skipUntilMs = 0L

    override suspend fun resolve(query: LyricsQuery): LyricsResult? = withContext(Dispatchers.IO) {
        val trackId = query.spotifyTrackId ?: return@withContext null
        if (apiKey.isBlank() && baseUrl == DEFAULT_BASE_URL) return@withContext null
        if (skipWindowOpen()) return@withContext null

        val json = try {
            fetch(trackId, query.interactive)
        } catch (e: IOException) {
            Log.d(TAG, "spicy transport failure for \"${query.title}\": ${e.message}")
            openSkipWindow("transport failure")
            null
        }
        if (json == null) return@withContext null

        val result = runCatching { parse(json, trackId) }
            .onFailure { Log.w(TAG, "spicy response for $trackId didn't parse", it) }
            .getOrNull()
        Log.d(TAG, "spicy $trackId (\"${query.title}\") -> ${result?.let { if (it.ttml != null) "syllable" else "line/static" } ?: "miss"}")
        result
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
                r.code == 429 || r.code == 401 || r.code == 403 -> {
                    openSkipWindow("HTTP ${r.code}")
                    null
                }
                !r.isSuccessful -> throw IOException("HTTP ${r.code} from ${url.host}")
                else -> r.body?.string()
            }
        }
    }

    private fun parse(json: String, trackId: String): LyricsResult? {
        val root = JSONObject(json)
        if (root.optInt("Status", 200) != 200) return null
        val body = root.optJSONObject("Body") ?: return null
        val lyricsId = body.optString("id").ifBlank { trackId }
        val credits = credits(body)
        return when (body.optString("Type")) {
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
                    val text = o.optString("Text").trim()
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
            "Static" -> {
                val lines = body.optJSONArray("Lines") ?: return null
                val plain = (0 until lines.length())
                    .mapNotNull { lines.optJSONObject(it)?.optString("Text") }
                    .joinToString("\n").trim()
                if (plain.isEmpty()) return null
                LyricsResult(
                    sourceId = id, plainText = plain, syncedLrc = null,
                    instrumental = false, language = null, sourceLyricsId = lyricsId, credits = credits,
                )
            }
            else -> null
        }
    }

    /** Only when Spicy's own catalogue answered; other sources carry no uploader/maker. */
    private fun credits(body: JSONObject): LyricsCredits? {
        if (body.optString("source") != "spicy_lyrics") return null
        val attr = body.optJSONObject("UploadAttribution") ?: return null
        fun person(key: String): Pair<String?, String?> {
            val o = attr.optJSONObject(key) ?: return null to null
            val name = o.optString("username").ifBlank { null }
            val url = o.optString("url").takeIf { it.startsWith("https://") }
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
            if (v.optString("Type") != "Vocal") continue
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
            val text = s.optString("Text").trim()
            if (text.isEmpty()) continue
            val b = s.optDouble("StartTime", 0.0)
            val e = s.optDouble("EndTime", b)
            sb.append("<span begin=\"${ts(b)}\" end=\"${ts(e)}\">").append(escape(text)).append("</span>")
            if (!s.optBoolean("IsPartOfWord") && i < syllables.length() - 1) sb.append(' ')
        }
        return sb.toString().trimEnd()
    }

    private fun escape(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

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

    @Synchronized
    private fun skipWindowOpen() = clock.now() < skipUntilMs

    @Synchronized
    private fun openSkipWindow(reason: String) {
        skipUntilMs = clock.now() + SKIP_WINDOW_MS
        Log.w(TAG, "Skipping Spicy Lyrics for ${SKIP_WINDOW_MS / 60_000} min: $reason")
    }

    companion object {
        const val SOURCE_ID = "spicy-lyrics"
        const val DEFAULT_BASE_URL = "https://api.spicylyrics.org"
        private const val TAG = "SpicyLyricsSource"
        internal const val SKIP_WINDOW_MS = 10 * 60_000L
    }
}