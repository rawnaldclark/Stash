package com.stash.data.lyrics.source

import android.util.Log
import com.stash.core.common.Clock
import com.stash.core.common.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * title/artist -> Apple Music song id, through iTunes Search. The one place that lookup happens:
 * [AppleTtmlLyricsSource] and [SpicyLyricsSource] both ask here, so a song is searched once between them.
 *
 * - [find]: blocking, THROWS on a failed request (as Apple's source needs: a failure keeps the track
 *   retryable, where a null would read as a clean miss). Same timeout/retry profile as before:
 *   interactive = 4 s, no retry; background = 10 s, one retry on a transport failure; an HTTP
 *   failure is never retried.
 * - [resolve]: [find] for callers where a failure is just "no id".
 *
 * A finished search is remembered per song: an id for the process (bounded), a definitive
 * "no match" for [MISS_TTL_MS]. A failed request is never remembered.
 */
class AppleMusicIdResolver(
    client: OkHttpClient,
    appVersionName: String,
    private val searchBaseUrl: String = DEFAULT_SEARCH_BASE_URL,
    lyricsDns: Dns = LyricsDns(),
    private val clock: Clock = SystemClock(),
) {
    private val userAgent = "Stash/$appVersionName (Android)"
    private val interactiveHttp = client.newBuilder().dns(lyricsDns).callTimeout(INTERACTIVE_TIMEOUT_S, TimeUnit.SECONDS).build()
    private val backgroundHttp = client.newBuilder().dns(lyricsDns).callTimeout(BACKGROUND_TIMEOUT_S, TimeUnit.SECONDS).build()

    private class Entry(val id: String?, val atMs: Long)

    /** Song key -> answer, least-recently-used first. Guarded by itself. */
    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > CACHE_MAX
    }

    /** Blocking: call off the main thread. Null = no match. Throws on a failed request. */
    fun find(query: LyricsQuery): String? {
        val key = cacheKey(query)
        synchronized(cache) {
            val hit = cache[key]
            if (hit != null) {
                if (hit.id != null || clock.now() - hit.atMs < MISS_TTL_MS) return hit.id
                cache.remove(key)
            }
        }
        val id = search(query)
        synchronized(cache) { cache[key] = Entry(id, clock.now()) }
        return id
    }

    /**
     * [find] with a failed request read as "no id" (not remembered, so the next ask tries again).
     * Except a 429: that is thrown, so an upgrade pass stops (RATE_LIMITED) instead of moving past
     * the song as if iTunes had no match for it.
     */
    suspend fun resolve(query: LyricsQuery): String? = withContext(Dispatchers.IO) {
        try {
            find(query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppleTtmlLyricsSource.HttpStatusException) {
            if (e.code == 429) throw e
            Log.d(TAG, "iTunes search failed for \"${query.title}\": ${e.message}")
            null
        } catch (e: Exception) {
            Log.d(TAG, "iTunes search failed for \"${query.title}\": ${e.message}")
            null
        }
    }

    /**
     * The id an earlier search already found for this song, without asking iTunes: null when it
     * hasn't been searched, or the search found nothing. In a lookup, Apple's source has just
     * searched the song, so Spicy gets its id here for free.
     */
    fun cached(query: LyricsQuery): String? = synchronized(cache) { cache[cacheKey(query)]?.id }

    private fun cacheKey(q: LyricsQuery): String = listOf(
        q.title.trim(), q.artist.trim(), q.albumArtist.orEmpty().trim(), ((q.durationMs ?: 0L) / 1000).toString(),
    ).joinToString("\u0000").lowercase(Locale.ROOT)

    private fun search(query: LyricsQuery): String? {
        // A title that is all brackets ("(Intro)") normalises to nothing, and "" is in every title:
        // any song by the artist would match. Not worth a request.
        if (normalize(query.title).isEmpty()) return null
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
                candidates.joinToString { "${it.id}:${it.title}/${it.artist}(${it.durationMs}ms)" }.ifEmpty { "0 results" },
        )
        return pickBest(query, candidates)?.id
    }

    /** Body on 2xx, null on 404, throws [AppleTtmlLyricsSource.HttpStatusException] on any other status. */
    private fun get(url: HttpUrl, interactive: Boolean): String? {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        val http = if (interactive) interactiveHttp else backgroundHttp
        val maxAttempts = if (interactive) 1 else 2
        var lastError: IOException? = null
        repeat(maxAttempts) { attempt ->
            try {
                http.newCall(request).execute().use { response ->
                    if (response.code == 404) return null
                    if (!response.isSuccessful) throw AppleTtmlLyricsSource.HttpStatusException(response.code, url.host)
                    return response.body?.string()
                }
            } catch (e: AppleTtmlLyricsSource.HttpStatusException) {
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
        const val DEFAULT_SEARCH_BASE_URL = "https://itunes.apple.com"
        private const val TAG = "AppleMusicIdResolver"
        private const val INTERACTIVE_TIMEOUT_S = 4L
        private const val BACKGROUND_TIMEOUT_S = 10L
        private const val CACHE_MAX = 256

        /** How long a definitive "no match" is remembered, so a Retry tap isn't answered from a stale miss for the whole session. */
        internal const val MISS_TTL_MS = 10 * 60_000L
    }
}
