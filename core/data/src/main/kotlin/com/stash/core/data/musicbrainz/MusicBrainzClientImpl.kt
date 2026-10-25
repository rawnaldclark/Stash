package com.stash.core.data.musicbrainz

import com.stash.core.data.share.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client-direct MusicBrainz client. No proxy: MusicBrainz rate-limits per IP
 * with no API key, so per-device residential IPs are naturally compliant. A
 * simple on-device gate keeps requests ~1s apart (their published limit).
 */
@Singleton
class MusicBrainzClientImpl @Inject constructor(
    okHttpClient: OkHttpClient,
) : MusicBrainzClient {
    // MusicBrainz answers in 1-7 s on a good day. A coroutine timeout cannot
    // interrupt a blocking call, so the bound lives on the call itself.
    private val http: OkHttpClient = okHttpClient.newBuilder()
        .callTimeout(CALL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // The back cover lookup's calls: a search measured up to 8 s (2026-10-24), past [http]'s bound.
    private val lookupHttp: OkHttpClient = okHttpClient.newBuilder()
        .callTimeout(LOOKUP_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val rateGate = Mutex()
    @Volatile private var lastCallMs = 0L

    /** Test seams: the server, the gap between calls, and the wait before retrying a 503. */
    internal var base: String = BASE
    internal var minIntervalMs: Long = MIN_INTERVAL_MS
    internal var busyRetryMs: Long = BUSY_RETRY_MS

    override suspend fun lookupUrlRels(mbid: String): JsonObject? = runCatching {
        get("artist/$mbid?inc=url-rels&fmt=json")
    }.getOrNull()

    override suspend fun searchByName(name: String): JsonObject? = runCatching {
        val query = URLEncoder.encode("artist:\"${escapeLucene(name)}\"", "UTF-8")
        val body = get("artist?query=$query&fmt=json") ?: return null
        val first = body["artists"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val score = first["score"]?.jsonPrimitive?.int ?: return null
        val type = first["type"]?.jsonPrimitive?.content
        if (score < 95 || type !in setOf("Person", "Group")) return null
        val mbid = first["id"]?.jsonPrimitive?.content ?: return null
        lookupUrlRels(mbid)
    }.getOrNull()

    override suspend fun searchRecordingsByIsrc(isrc: String): JsonObject? =
        getOrNull("recording?query=${URLEncoder.encode("isrc:${escapeLucene(isrc)}", "UTF-8")}&limit=$SEARCH_LIMIT&fmt=json")

    override suspend fun searchReleaseGroups(artist: String, album: String): JsonObject? {
        val query = "releasegroup:\"${escapeLucene(album)}\" AND artist:\"${escapeLucene(artist)}\""
        return getOrNull("release-group?query=${URLEncoder.encode(query, "UTF-8")}&limit=$SEARCH_LIMIT&fmt=json")
    }

    override suspend fun searchReleases(artist: String, album: String): JsonObject? {
        val query = "release:\"${escapeLucene(album)}\" AND artist:\"${escapeLucene(artist)}\""
        return getOrNull("release?query=${URLEncoder.encode(query, "UTF-8")}&limit=$SEARCH_LIMIT&fmt=json")
    }

    override suspend fun browseReleases(releaseGroupId: String): JsonObject? =
        getOrNull("release?release-group=$releaseGroupId&status=official&inc=media+url-rels&limit=100&fmt=json")

    override suspend fun releaseGroupUrlRels(releaseGroupId: String): JsonObject? =
        getOrNull("release-group/$releaseGroupId?inc=url-rels&fmt=json")

    override suspend fun releaseUrlRels(releaseId: String): JsonObject? =
        getOrNull("release/$releaseId?inc=url-rels&fmt=json")

    /**
     * GET [path] for the back cover lookup, null on failure.
     *
     * - A 503 is MusicBrainz over its per-IP rate limit, or busy: retried after [busyRetryMs], up to
     *   [BUSY_RETRIES] times, instead of failing the whole lookup.
     * - The call cancels with its caller ([await]): the lookup is cancelled on every skip to another
     *   album, and a blocking call would hold a thread until it timed out.
     * - A cancellation is rethrown, never read as a failure, which would hold the album back.
     */
    private suspend fun getOrNull(path: String): JsonObject? {
        repeat(BUSY_RETRIES + 1) { attempt ->
            throttle()
            val req = Request.Builder().url("$base$path").header("User-Agent", USER_AGENT).build()
            val (code, body) = try {
                lookupHttp.newCall(req).await { code, body -> code to body }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return null
            }
            when {
                code == 503 -> if (attempt < BUSY_RETRIES) delay(busyRetryMs)
                code !in 200..299 -> return null
                else -> return runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            }
        }
        return null
    }

    /** GET [path] under the MB base with our UA + rate gate; null on non-2xx. */
    private suspend fun get(path: String): JsonObject? {
        throttle()
        val req = Request.Builder()
            .url("$base$path")
            .header("User-Agent", USER_AGENT)
            .build()
        return withContext(Dispatchers.IO) {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()?.let { json.parseToJsonElement(it).jsonObject }
            }
        }
    }

    /** Serialize + space calls ≥ MIN_INTERVAL_MS apart. */
    private suspend fun throttle() = rateGate.withLock {
        val wait = minIntervalMs - (System.currentTimeMillis() - lastCallMs)
        if (wait > 0) delay(wait)
        lastCallMs = System.currentTimeMillis()
    }

    private companion object {
        const val BASE = "https://musicbrainz.org/ws/2/"
        private const val CALL_TIMEOUT_SECONDS = 5L
        private const val LOOKUP_TIMEOUT_SECONDS = 15L
        /** 503s retried before a back cover lookup call gives up, and the wait before each. */
        const val BUSY_RETRIES = 2
        const val BUSY_RETRY_MS = 2_000L
        const val USER_AGENT = "Stash/1.0 ( https://github.com/rawnaldclark/Stash )"
        const val MIN_INTERVAL_MS = 1000L
        /**
         * Results per search. An album named like its artist is crowded: "The Beatles" by The Beatles
         * ranks the White Album 14th, behind a dozen one-release compilations all scored 100.
         */
        const val SEARCH_LIMIT = 50
    }
}
