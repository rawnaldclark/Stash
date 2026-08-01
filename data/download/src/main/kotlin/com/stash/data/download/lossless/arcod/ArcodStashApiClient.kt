package com.stash.data.download.lossless.arcod

import android.util.Log
import com.stash.data.download.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for ARCOD's `/v2/stash/…` API — the routes the operator issued specifically
 * for Stash (spec supplied 2026-07-04, implemented 2026-07-31).
 *
 * ## Why this is a new class rather than a change to [ArcodClient]
 *
 * The existing client targets an API that no longer exists in that form. It calls
 * `arcod.xyz/api/get-music` for search and runs an asynchronous render job —
 * `POST /v2/downloads`, then polling `GET /v2/downloads/<id>` until `completed` —
 * and it never sends `X-Stash-Key` at all, which the current API rejects outright.
 * That mismatch is the likeliest reason ARCOD was parked: not a dead source, a dead
 * client.
 *
 * The replacement is markedly simpler and shares qbdlx's profile: one GET returns a
 * direct, Range-seekable FLAC URL. No job queue, no polling, no client-side decrypt.
 *
 * ## Contract
 *
 * Every route requires both headers:
 *  - `Authorization: Bearer <arcod user access token>` — per user.
 *  - `X-Stash-Key: <private integration key>` — per build, from
 *    [BuildConfig.ARCOD_STASH_KEY]. Shared on condition it stays private, so it is
 *    injected from `local.properties` / a CI secret and never committed.
 *  - `Token-Country` is optional (e.g. `FR`).
 *
 * Routes (the generic `/v2/search`, `/v2/albums` routes are explicitly NOT for us):
 *  - `GET /v2/stash/search?q=&limit=&offset=`
 *  - `GET /v2/stash/albums/:albumId` (optional `title=`, `artist=`)
 *  - `GET /v2/stash/stream/:trackId?quality=27` →
 *    `{ url, mimeType, quality, trackId }`
 *
 * ## Status: unverified against the live server
 *
 * As of writing every `/v2/stash/…` call returns `403 {"error":"Forbidden"}` from
 * ARCOD's own middleware — identical with and without the key, and never reaching
 * the 401 the spec defines for a bad user token, so the request is refused before
 * auth is evaluated. Awaiting the operator: either the key has rotated (the spec is
 * four weeks old) or the routes are IP-allowlisted.
 *
 * This client is therefore written to the documented contract and covered by tests
 * against a MockWebServer, but is deliberately NOT yet wired into
 * `LosslessSourceRegistry` or `StreamSourceRegistry`. Wiring a chain onto an API
 * that has never returned 200 is how you ship something that silently does nothing.
 */
@Singleton
class ArcodStashApiClient @Inject constructor(
    private val client: OkHttpClient,
) {

    /**
     * Test seam: tests point this at a MockWebServer. Off the constructor because
     * mixing `@Inject` with a default-valued parameter generates two JVM
     * constructors and Hilt rejects the ambiguous injection site — same reasoning as
     * [com.stash.data.download.lossless.amz.AmzApiClient].
     */
    internal var baseUrl: String = BuildConfig.ARCOD_API_BASE

    /** True when this build carries the private integration key. */
    val isConfigured: Boolean get() = BuildConfig.ARCOD_STASH_KEY.isNotBlank()

    /** One catalog hit. `id` is what [streamUrl] wants. */
    data class SearchResult(
        val id: String,
        val title: String,
        val artist: String,
        val album: String?,
        val durationMs: Long,
    )

    /** A resolved, directly-playable stream. Supports HTTP Range. */
    data class Stream(val url: String, val mimeType: String, val quality: Int)

    /**
     * Outcomes mapped from the documented status codes. The distinction matters
     * downstream: only [Refused] means "do not retry this request unchanged".
     */
    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        /** 401 — the user's arcod token is missing or invalid; they must reconnect. */
        data object Unauthorized : Result<Nothing>
        /** 403 — bad integration key, blocked user, or blocked IP. Not user-fixable. */
        data object Forbidden : Result<Nothing>
        /** 429 — honour [retryAfterSeconds] when present. */
        data class RateLimited(val retryAfterSeconds: Long?) : Result<Nothing>
        /** 400 or an unparseable body. */
        data class BadRequest(val message: String?) : Result<Nothing>
        /** Transport failure or 5xx — the request is fine, the service is not. */
        data class Unavailable(val message: String?) : Result<Nothing>
    }

    suspend fun search(
        token: String,
        query: String,
        limit: Int = 12,
        offset: Int = 0,
        country: String? = null,
    ): Result<List<SearchResult>> {
        val q = URLEncoder.encode(query, "UTF-8")
        return get("$baseUrl/v2/stash/search?q=$q&limit=$limit&offset=$offset", token, country) { root ->
            // The catalog shape isn't pinned by the spec beyond the example, so read
            // defensively: take whichever array the payload carries and skip entries
            // without an id rather than failing the whole page.
            val items = root["items"]?.jsonArray
                ?: root["results"]?.jsonArray
                ?: root["tracks"]?.jsonArray
                ?: return@get emptyList()
            items.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = o.str("id") ?: o.str("trackId") ?: return@mapNotNull null
                SearchResult(
                    id = id,
                    title = o.str("title").orEmpty(),
                    artist = o.str("artist") ?: o["artist"]?.jsonObjectOrNull()?.str("name").orEmpty(),
                    album = o.str("album") ?: o["album"]?.jsonObjectOrNull()?.str("title"),
                    durationMs = o.str("duration")?.toLongOrNull()?.let { secondsOrMillis(it) } ?: 0L,
                )
            }
        }
    }

    /**
     * Resolves a directly-playable FLAC URL. `quality=27` is the hi-res tier, matching
     * the Qobuz format code the rest of the app already speaks.
     */
    suspend fun streamUrl(
        token: String,
        trackId: String,
        quality: Int = 27,
        country: String? = null,
    ): Result<Stream> =
        get("$baseUrl/v2/stash/stream/$trackId?quality=$quality", token, country) { root ->
            Stream(
                url = root.str("url").orEmpty(),
                mimeType = root.str("mimeType") ?: "audio/flac",
                quality = root.str("quality")?.toIntOrNull() ?: quality,
            )
        }

    private suspend fun <T> get(
        url: String,
        token: String,
        country: String?,
        parse: (JsonObject) -> T,
    ): Result<T> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${token.trim()}")
            .header("X-Stash-Key", BuildConfig.ARCOD_STASH_KEY)
            .apply { country?.takeIf { it.isNotBlank() }?.let { header("Token-Country", it) } }
            .get()
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    in 200..299 -> {
                        val body = response.body?.string().orEmpty()
                        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                            ?: return@use Result.BadRequest("unparseable response body")
                        Result.Ok(parse(root))
                    }
                    401 -> Result.Unauthorized
                    403 -> Result.Forbidden
                    429 -> Result.RateLimited(
                        response.header("Retry-After")?.trim()?.toLongOrNull(),
                    )
                    400 -> Result.BadRequest(response.body?.string()?.take(200))
                    else -> Result.Unavailable("HTTP ${response.code}")
                }
            }
        }.getOrElse { t ->
            if (t is CancellationException) throw t
            Log.d(TAG, "arcod request failed: ${t.message}")
            Result.Unavailable(t.message)
        }
    }

    /** Durations arrive as seconds in the examples; tolerate millis without inventing hours. */
    private fun secondsOrMillis(v: Long): Long = if (v > 10_000L) v else v * 1000L

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it != "null" }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
        runCatching { jsonObject }.getOrNull()

    private companion object {
        private const val TAG = "ArcodStash"
    }
}
