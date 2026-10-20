package com.stash.core.data.lastfm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Negative caching for Last.fm reads: Last.fm's final "there is nothing here"
 * answers, remembered in the same response cache (and under the same key) as
 * the successes, so asking again within [TTL_MS] costs no request.
 *
 * Why: the art backfill and the enrichment workers re-asked about rows
 * Last.fm can never fill (voice notes, "Unknown Artist" files, deep cuts) on
 * every run, and only successes were cached, so every one of those misses was
 * a fresh proxy request every time. They were most of the proxy's traffic.
 *
 * What counts as a miss, and nothing else:
 *  - API error 6 ("not found") and 7 ("invalid resource"): Last.fm's own,
 *    final answer about this artist/track. The proxy already treats them as
 *    final (no direct retry).
 *  - a `track.getInfo` answer with no `track` object (it parses to an empty
 *    info: no art, no duration).
 * Transient failures are never cached: network errors, HTTP 5xx, 429 and
 * error 29 (rate limit), 11/16 (service offline / temporarily unavailable),
 * and the proxy's own string errors ("upstream_error", …).
 *
 * A miss is stored as a small marker object so a read can tell it from a
 * cached success: `{"stash_miss":true,"error":"6","message":"…"}`, or just
 * `{"stash_miss":true}` for an empty `track.getInfo`.
 */
internal object LastFmCachedMiss {

    /**
     * How long a miss is believed: 14 days. Long enough that a row Last.fm
     * can't fill is asked about twice a month instead of on every run (the
     * backfill ran on every cold start and after every mix refresh), short
     * enough that a new release, which Last.fm usually lists within days of
     * its first scrobbles, gets its art within two weeks at worst. 30 days
     * would save little more: after the first miss the remaining traffic is
     * already one request per row per window.
     */
    const val TTL_MS = 14L * 24 * 60 * 60 * 1000

    /** Error codes that are Last.fm's final "nothing here" answer to a lookup. */
    val FINAL_ERROR_CODES: Set<Int> = setOf(6, 7)

    private const val MARKER = "stash_miss"

    /** True when [errorCode] is a final answer worth remembering. */
    fun isFinalError(errorCode: Int): Boolean = errorCode in FINAL_ERROR_CODES

    /** True for a `track.getInfo` success that names no track (parses to an empty info). */
    fun isEmptyTrackInfo(params: Map<String, String>, root: JsonObject): Boolean =
        params["method"].equals("track.getInfo", ignoreCase = true) && root["track"] !is JsonObject

    /** The cached body for a final API error. */
    fun encodeError(code: String, message: String): String =
        JsonObject(
            mapOf(
                MARKER to JsonPrimitive(true),
                "error" to JsonPrimitive(code),
                "message" to JsonPrimitive(message),
            ),
        ).toString()

    /** The cached body for an empty `track.getInfo`. */
    fun encodeEmpty(): String = JsonObject(mapOf(MARKER to JsonPrimitive(true))).toString()

    /** True when a cached body is a miss rather than a success. */
    fun isMiss(root: JsonObject): Boolean = root[MARKER]?.jsonPrimitive?.content == "true"

    /** How long a cached body may be served: [TTL_MS] for a miss, [successTtlMs] otherwise. */
    fun ttlFor(root: JsonObject, successTtlMs: Long): Long = if (isMiss(root)) TTL_MS else successTtlMs

    /**
     * What a cached miss answers: the remembered error (thrown as a
     * [LastFmApiException] with `fromCache = true`, exactly the type a fresh
     * "not found" throws) or, for an empty `track.getInfo`, an empty root.
     */
    fun replay(root: JsonObject): JsonObject {
        val code = root["error"]?.jsonPrimitive?.content
        if (code != null) {
            val message = root["message"]?.jsonPrimitive?.content ?: "not found"
            throw LastFmApiException(code, "Last.fm API error $code: $message (cached)", fromCache = true)
        }
        return JsonObject(emptyMap())
    }
}
