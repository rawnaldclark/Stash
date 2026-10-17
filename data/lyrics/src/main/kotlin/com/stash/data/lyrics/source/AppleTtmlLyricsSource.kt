package com.stash.data.lyrics.source

import android.util.Log
import com.stash.core.common.Clock
import com.stash.core.common.SystemClock
import com.stash.data.lyrics.parser.TtmlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Dns
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Word-synced lyrics: iTunes Search (title/artist -> Apple Music song id) then
 * lyrics.paxsenix.org `apple-music/lyrics?ttml=true`.
 *
 * [LyricsQuery.interactive] picks a timeout/retry profile:
 *  - interactive (lyrics sheet's inline on-open fetch, a Retry tap — someone is watching "Loading"):
 *    a short per-request timeout, no retry, so an unreachable host fails fast and the chain moves
 *    on to LRCLIB instead of stalling the UI.
 *  - background (backfill worker, manual library-wide fetch): a longer timeout, one retry.
 * Either way, an HTTP-level failure (429 / 5xx) is NEVER retried: the server answered, just badly,
 * and hitting it again immediately only makes rate limiting worse. Only a transport failure
 * (timeout, refused connection, a TLS handshake dying on a pooled connection some VPNs silently
 * drop) is eligible for the background profile's single retry.
 *
 * The live endpoint is slow (measured 2026-09-24: over 60 s per id, so both timeout profiles
 * always lose), but the server keeps working after we hang up and caches the result, so
 * `apple-music/cache/{id}` is asked first. It is fast either way, and it is how a slow first
 * lookup turns into lyrics on the next try.
 *
 * Skip window: after [SKIP_AFTER_TIMEOUTS] live lookups in a row fail at the transport level
 * (time out, or a TLS/connect error), or on any HTTP 429,
 * the live call is skipped for [SKIP_WINDOW_MS] and [resolve] throws [AppleSkippedException], so the
 * chain moves straight on to LRCLIB (and, being a failure rather than a miss, the track stays
 * retryable). The cache is still asked during the window. Any live response resets the count.
 * Instance state, which is process-wide because the source is a Hilt singleton.
 */
class AppleTtmlLyricsSource(
    client: OkHttpClient,
    private val appVersionName: String,
    private val lyricsBaseUrl: String = DEFAULT_LYRICS_BASE_URL,
    private val searchBaseUrl: String = DEFAULT_SEARCH_BASE_URL,
    private val clock: Clock = SystemClock(),
    /** [LyricsDns] in the app; tests pass [Dns.SYSTEM] so they never reach Cloudflare. */
    lyricsDns: Dns = LyricsDns(),
) : LyricsSource {

    override val id = SOURCE_ID
    override val displayName = "Apple Music (word-synced)"

    private val userAgent = "Stash/$appVersionName (Android)"
    private val interactiveHttp = client.newBuilder()
        .dns(lyricsDns)
        .callTimeout(INTERACTIVE_TIMEOUT_S, TimeUnit.SECONDS).build()
    private val backgroundHttp = client.newBuilder()
        .dns(lyricsDns)
        .callTimeout(BACKGROUND_TIMEOUT_S, TimeUnit.SECONDS).build()

    private var consecutiveTimeouts = 0
    private var skipUntilMs = 0L

    /** Thrown while the skip window is open: a failure, so the source walk moves on and retries later. */
    class AppleSkippedException : IOException("Apple lyrics skipped: the service keeps failing or rate-limiting")

    override suspend fun resolve(query: LyricsQuery): LyricsResult? = withContext(Dispatchers.IO) {
        val songId = try {
            findSongId(query)
        } catch (e: HttpStatusException) {
            if (e.code == 429) openSkipWindow()
            throw e
        }
        if (songId == null) {
            Log.d(TAG, "no Apple match for \"${query.title}\" - ${query.artist}")
            return@withContext null
        }
        val ttml = fetchCachedTtml(songId) ?: fetchLiveTracked(songId, query.interactive)
        if (ttml == null) {
            Log.d(TAG, "apple id $songId (\"${query.title}\") has no TTML")
            return@withContext null
        }
        val parsed = TtmlParser.parse(ttml)?.takeIf { it.lines.isNotEmpty() }
        if (parsed == null) {
            Log.w(TAG, "apple id $songId (\"${query.title}\") returned TTML but it didn't parse")
            return@withContext null
        }
        Log.d(TAG, "apple id $songId (\"${query.title}\") -> ${parsed.lines.size} lines")
        LyricsResult(
            sourceId = id,
            plainText = parsed.toPlainText(),
            syncedLrc = parsed.toLrc(),
            instrumental = false,
            language = null,
            sourceLyricsId = songId,
            ttml = ttml,
        )
    }

    private fun findSongId(query: LyricsQuery): String? {
        val url = searchBaseUrl.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("term", "${query.title} ${query.artist}")
            .addQueryParameter("media", "music")
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "10")
            .build()
        val body = get(url, query.interactive) ?: return null
        val results = JSONObject(body).optJSONArray("results") ?: return null
        val candidates = (0 until results.length()).mapNotNull { i ->
            val o = results.optJSONObject(i) ?: return@mapNotNull null
            val trackId = o.optLong("trackId", 0L).takeIf { it > 0 } ?: return@mapNotNull null
            Candidate(
                id = trackId.toString(),
                title = o.optString("trackName"),
                artist = o.optString("artistName"),
                durationMs = o.optLong("trackTimeMillis", 0L),
            )
        }
        Log.d(
            TAG,
            "iTunes search \"${query.title}\" - ${query.artist}: " +
                candidates.joinToString { "${it.id}:${it.title}/${it.artist}(${it.durationMs}ms)" }
                    .ifEmpty { "0 results" },
        )
        return pickBest(query, candidates)?.id
    }

    /**
     * `apple-music/cache/{id}`: 200 + raw TTML XML on a hit. A miss is HTTP 404 with a JSON body
     * `{"detail":"Track '<id>' is not in the Google Drive cache."}` (observed 2026-09-24), which
     * [get] maps to null. Any error or a non-TTML body is also a miss: the live call decides.
     */
    private fun fetchCachedTtml(songId: String): String? {
        val url = lyricsBaseUrl.toHttpUrl().newBuilder()
            .addPathSegments("apple-music/cache")
            .addPathSegment(songId)
            .build()
        val body = try {
            get(url, interactive = true)   // short timeout, no retry
        } catch (e: IOException) {
            Log.d(TAG, "cache lookup for apple id $songId failed: ${e.message}")
            null
        }?.trim()?.removePrefix(BOM_CHAR)
        return body?.takeIf { it.startsWith("<tt") }
            ?.also { Log.d(TAG, "cache hit for apple id $songId") }
    }

    @Synchronized
    private fun skipWindowOpen(): Boolean = clock.now() < skipUntilMs

    @Synchronized
    private fun openSkipWindow() {
        consecutiveTimeouts = 0
        skipUntilMs = clock.now() + SKIP_WINDOW_MS
        Log.w(TAG, "Skipping Apple lyrics for ${SKIP_WINDOW_MS / 60_000} min")
    }

    /** The live lookup, gated and counted by the skip window (see class KDoc). */
    private fun fetchLiveTracked(songId: String, interactive: Boolean): String? {
        if (skipWindowOpen()) throw AppleSkippedException()
        return try {
            fetchTtml(songId, interactive).also { synchronized(this) { consecutiveTimeouts = 0 } }
        } catch (e: HttpStatusException) {
            if (e.code == 429) openSkipWindow()
            throw e
        } catch (e: IOException) {
            // Any transport failure, not only timeouts (InterruptedIOException): a TLS or connect error
            // fails fast, and without counting it the background worker kept retrying a dead host.
            val n = synchronized(this) { ++consecutiveTimeouts }
            if (n >= SKIP_AFTER_TIMEOUTS) openSkipWindow()
            throw e
        }
    }

    private fun fetchTtml(songId: String, interactive: Boolean): String? {
        val url = lyricsBaseUrl.toHttpUrl().newBuilder()
            .addPathSegments("apple-music/lyrics")
            .addQueryParameter("id", songId)
            .addQueryParameter("ttml", "true")
            .build()
        val raw = get(url, interactive) ?: return null
        val body = raw.trim().removePrefix(BOM_CHAR)
        if (body.startsWith("<")) return body
        extractTtmlFromJson(body)?.let { return it }
        Log.d(
            TAG,
            "Non-XML, non-JSON-wrapped response for apple id $songId " +
                "(${body.length} chars): \"${body.take(200)}\"",
        )
        return null
    }

    private fun extractTtmlFromJson(body: String): String? {
        val root = runCatching { JSONTokener(body).nextValue() }.getOrNull() ?: return null

        fun fromObject(o: JSONObject): String? {
            val type = (o.opt("type") as? String)?.trim()?.uppercase()
            if (type != null && type != "TTML") return null
            for (key in TTML_JSON_KEYS) {
                val v = o.opt(key) as? String ?: continue
                val trimmed = v.trim()
                if (trimmed.startsWith("<")) return trimmed
            }
            return null
        }

        return when (root) {
            is JSONObject -> fromObject(root)
                ?: (root.opt("data") as? JSONObject)?.let(::fromObject)
                ?: (root.opt("result") as? JSONObject)?.let(::fromObject)
            is org.json.JSONArray -> (0 until root.length())
                .mapNotNull { root.opt(it) as? JSONObject }
                .firstNotNullOfOrNull(::fromObject)
            else -> null
        }
    }

    /**
     * Thrown for a non-2xx, non-404 HTTP response — the server answered, just badly. Never retried.
     * Public so the upgrade pass can tell a 429 (stop asking) from any other failure.
     */
    class HttpStatusException(val code: Int, host: String) : IOException("HTTP $code from $host")

    /**
     * Body on 2xx, null on 404, throws on anything else.
     *
     * [interactive] picks the timeout/retry profile (see class KDoc). A transport failure gets one
     * retry in the background profile only; an [HttpStatusException] (429/5xx) never retries.
     */
    private fun get(url: HttpUrl, interactive: Boolean): String? {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        val httpClient = if (interactive) interactiveHttp else backgroundHttp
        val maxAttempts = if (interactive) 1 else 2
        var lastError: IOException? = null
        repeat(maxAttempts) { attempt ->
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (response.code == 404) return null
                    if (!response.isSuccessful) throw HttpStatusException(response.code, url.host)
                    return response.body?.string()
                }
            } catch (e: HttpStatusException) {
                throw e
            } catch (e: IOException) {
                lastError = e
                if (attempt < maxAttempts - 1) {
                    Log.d(TAG, "Transport failure on ${url.host}, retrying once: ${e.message}")
                    Thread.sleep(250)
                }
            }
        }
        throw lastError!!
    }

    private data class Candidate(val id: String, val title: String, val artist: String, val durationMs: Long)

    private fun normalize(s: String): String = s.lowercase(Locale.ROOT)
        .replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "")
        .replace(Regex("""\s+-\s+.*$"""), "")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()

    private fun pickBest(query: LyricsQuery, candidates: List<Candidate>): Candidate? {
        val wantTitle = normalize(query.title)
        val wantArtists = listOfNotNull(query.albumArtist, query.artist).map(::normalize).filter { it.isNotEmpty() }
        val wantMs = query.durationMs ?: 0L
        return candidates.mapNotNull { c ->
            val t = normalize(c.title)
            val titleScore = when {
                t == wantTitle -> 3
                t.contains(wantTitle) || wantTitle.contains(t) -> 1
                else -> 0
            }
            if (titleScore == 0) return@mapNotNull null
            val a = normalize(c.artist)
            if (wantArtists.none { a.contains(it) || it.contains(a) }) return@mapNotNull null
            var score = titleScore
            if (wantMs > 0 && c.durationMs > 0) {
                val diff = abs(wantMs - c.durationMs)
                if (diff > 8_000) return@mapNotNull null
                score += if (diff <= 3_000) 2 else 1
            }
            c to score
        }.maxByOrNull { it.second }?.first
    }

    companion object {
        const val SOURCE_ID = "apple-ttml"
        const val DEFAULT_LYRICS_BASE_URL = "https://lyrics.paxsenix.org"
        const val DEFAULT_SEARCH_BASE_URL = "https://itunes.apple.com"
        private const val TAG = "AppleTtmlLyricsSource"
        private const val BOM_CHAR = "\uFEFF"
        private val TTML_JSON_KEYS = listOf("ttml", "data", "lyrics", "content", "xml")
        private const val INTERACTIVE_TIMEOUT_S = 4L
        private const val BACKGROUND_TIMEOUT_S = 10L
        internal const val SKIP_AFTER_TIMEOUTS = 3
        internal const val SKIP_WINDOW_MS = 10 * 60_000L
    }
}