package com.stash.data.lyrics.source

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * title/artist -> ISRC via the lrc.red search API (`lrc.red/api/v1`, which is where
 * `lyrics-api.binimum.org` redirects; asked directly to save the redirect hop).
 *
 * For [IsrcTtmlLyricsSource] (the ISRC found here picks which TTML file to fetch and is kept in
 * `lyrics.source_lyrics_id`) and for [SpicyLyricsSource]'s background upgrade passes, which look a
 * song without a Spotify id up by it. It is NEVER written to `tracks.isrc` — that column
 * decides which version of a song streams and downloads, and a fuzzy title/artist search is not
 * evidence enough to change it.
 *
 * Only word-timed entries (`timing_type == "word"`) are candidates: a line-timed file would
 * replace LRCLIB's line sync with fake per-word timing.
 *
 * Answers (an ISRC or a clean "no match") are remembered for the process, bounded to
 * [CACHE_MAX]. Failures are not remembered, so they stay retryable, and all requests go through
 * the shared [IsrcLyricsHostBreaker], which keeps a dead or rate-limiting host to one failure per
 * window. Same HTTP setup as the sources: [LyricsDns] and the `Stash/<version>` User-Agent.
 */
class IsrcResolver(
    client: OkHttpClient,
    appVersionName: String,
    private val breaker: IsrcLyricsHostBreaker,
    private val searchUrl: String = DEFAULT_SEARCH_URL,
    /** [LyricsDns] in the app; tests pass [Dns.SYSTEM] so they never reach Cloudflare. */
    lyricsDns: Dns = LyricsDns(),
) {
    private val userAgent = "Stash/$appVersionName (Android)"
    private val interactiveHttp = client.newBuilder().dns(lyricsDns)
        .callTimeout(INTERACTIVE_TIMEOUT_S, TimeUnit.SECONDS).build()
    private val backgroundHttp = client.newBuilder().dns(lyricsDns)
        .callTimeout(BACKGROUND_TIMEOUT_S, TimeUnit.SECONDS).build()

    /** Keyed by [cacheKey]; a null value is a remembered "no match". Guarded by itself. */
    private val cache = object : LinkedHashMap<String, String?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?) = size > CACHE_MAX
    }

    /**
     * The ISRC of the best word-timed match, or null when there is no confident one (including a
     * 200 that isn't JSON, e.g. a Cloudflare challenge page: that also opens the breaker and is
     * not remembered). Throws on a transport/HTTP failure or while the breaker is open.
     */
    suspend fun resolve(query: LyricsQuery): String? {
        val wantTitle = normalize(query.title)
        val wantArtist = normalize(query.artist)
        // Nothing to match on: an empty needle would "contain"-match every result.
        if (wantTitle.isEmpty() || wantArtist.isEmpty()) return null

        val key = cacheKey(query)
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }

        val url = searchUrl.toHttpUrl().newBuilder()
            .addQueryParameter("track", query.title)
            .addQueryParameter("artist", query.artist)
            .build()
        val http = if (query.interactive) interactiveHttp else backgroundHttp
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        val body = withContext(Dispatchers.IO) {
            breaker.call {
                http.newCall(request).execute().use { r ->
                    when {
                        r.code == 404 -> null
                        !r.isSuccessful -> throw AppleTtmlLyricsSource.HttpStatusException(r.code, url.host)
                        else -> r.body?.string().orEmpty()
                    }
                }
            }
        }
        val results = if (body == null) {
            null
        } else {
            try {
                JSONObject(body).optJSONArray("results")
            } catch (e: JSONException) {
                // A 200 that isn't JSON: a challenge or error page in front of the API. Not an
                // answer about this song, so not remembered; the breaker stops asking for a while.
                Log.w(TAG, "ISRC search answered non-JSON (${body.take(80).replace('\n', ' ')})")
                breaker.onBlocked("ISRC search returned a non-JSON body")
                return null
            }
        }
        Log.d(TAG, "ISRC search got ${results?.length() ?: 0} result(s) for \"${query.title}\"")
        val isrc = results?.let { pickBest(query, it) }
        Log.d(TAG, "ISRC search \"${query.title}\" - ${query.artist}: ${isrc ?: "no word-timed match"}")
        synchronized(cache) { cache[key] = isrc }
        return isrc
    }

    companion object {
        private const val TAG = "IsrcResolver"
        const val DEFAULT_SEARCH_URL = "https://lrc.red/api/v1"
        private const val INTERACTIVE_TIMEOUT_S = 3L
        private const val BACKGROUND_TIMEOUT_S = 8L
        private const val CACHE_MAX = 64

        /** A candidate whose duration is further than this from the track's is another version. */
        private const val MAX_DURATION_DIFF_S = 5L

        private val PAREN = Regex("""\s*[(\[][^)\]]*[)\]]""")
        private val DASH_SUFFIX = Regex("""\s+-\s+.*$""")
        private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

        private fun cacheKey(query: LyricsQuery) =
            "${query.title}|${query.artist}|${query.durationMs?.div(1000)}".lowercase(Locale.ROOT)

        /** Lower-case, drop "(feat. …)"/"[Remix]" and " - Remastered" suffixes, collapse punctuation. */
        internal fun normalize(s: String) = s.lowercase(Locale.ROOT)
            .replace(PAREN, "")
            .replace(DASH_SUFFIX, "")
            .replace(NON_WORD, " ")
            .trim()

        /**
         * Scores word-timed results: exact title 3 / partial 1 (0 = skip), artist must overlap,
         * duration within [MAX_DURATION_DIFF_S] (+2 within 2 s, +1 otherwise; unknown = no bonus),
         * +1 for the same album. Highest score wins; the API's order breaks ties.
         */
        internal fun pickBest(query: LyricsQuery, results: JSONArray): String? {
            val wantTitle = normalize(query.title)
            val wantArtists = listOfNotNull(query.artist, query.albumArtist)
                .map(::normalize).filter { it.isNotEmpty() }
            val wantAlbum = query.album?.let(::normalize).orEmpty()
            val wantSec = (query.durationMs ?: 0L) / 1000
            if (wantTitle.isEmpty() || wantArtists.isEmpty()) return null

            var best: Pair<String, Int>? = null
            for (i in 0 until results.length()) {
                val o = results.optJSONObject(i) ?: continue
                if (!o.optString("timing_type").equals("word", ignoreCase = true)) continue
                val isrc = o.optString("isrc").trim().uppercase(Locale.ROOT)
                    .takeIf { it.length == 12 && it.all(Char::isLetterOrDigit) } ?: continue
                val t = normalize(o.optString("track_name"))
                val titleScore = when {
                    t == wantTitle -> 3
                    t.isNotEmpty() && (t.contains(wantTitle) || wantTitle.contains(t)) -> 1
                    else -> 0
                }
                if (titleScore == 0) continue
                val a = normalize(o.optString("artist_name"))
                if (a.isEmpty() || wantArtists.none { a.contains(it) || it.contains(a) }) continue
                var score = titleScore
                val dur = o.optLong("duration", 0L)
                if (wantSec > 0 && dur > 0) {
                    val diff = abs(wantSec - dur)
                    if (diff > MAX_DURATION_DIFF_S) continue
                    score += if (diff <= 2) 2 else 1
                }
                if (wantAlbum.isNotEmpty() && normalize(o.optString("album_name")) == wantAlbum) score += 1
                if (best == null || score > best.second) best = isrc to score
            }
            return best?.first
        }
    }
}
