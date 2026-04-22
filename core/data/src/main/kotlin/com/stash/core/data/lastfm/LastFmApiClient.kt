package com.stash.core.data.lastfm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigInteger
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal Last.fm 2.0 API client: the three endpoints we need for
 * authenticating + scrobbling from a non-desktop app.
 *
 * Auth flow (web-auth style):
 *   1. [getAuthToken] — returns a one-shot token (expires after ~1 hour
 *      if not authorised).
 *   2. User visits `https://www.last.fm/api/auth/?api_key=X&token=Y` in
 *      their browser and clicks "Yes, allow access."
 *   3. [getSession] — exchanges the authorised token for a persistent
 *      session key. Session keys do not expire; store and reuse.
 *
 * Once the session key is stored, [scrobble] submits play events. Every
 * WRITE call is signed: md5(concat of sorted params + shared secret).
 *
 * This client needs a valid Last.fm API key + shared secret. They're
 * injected as strings so the app layer can source them from BuildConfig
 * (which in turn reads `local.properties` at build time). See the
 * Stash-level README section "Last.fm API setup" for how to obtain one.
 */
@Singleton
class LastFmApiClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val credentials: LastFmCredentials,
) {
    /** Step 1 of web-auth: request a token. User then authorises in browser. */
    suspend fun getAuthToken(): Result<String> = runCatching {
        val params = sortedMapOf(
            "method" to "auth.getToken",
            "api_key" to credentials.apiKey,
        )
        val response = signedGet(params)
        response["token"]!!.jsonPrimitive.content
    }

    /**
     * Step 3 of web-auth: exchange an authorised token for a session key.
     * Returns ([username], [sessionKey]) on success. Returns
     * `Result.failure` if the token hasn't been authorised yet — in that
     * case the user hasn't finished the browser step.
     */
    suspend fun getSession(token: String): Result<Pair<String, String>> = runCatching {
        val params = sortedMapOf(
            "method" to "auth.getSession",
            "api_key" to credentials.apiKey,
            "token" to token,
        )
        val response = signedGet(params)
        val session = response["session"]?.jsonObject ?: error("No session object in response")
        val name = session["name"]!!.jsonPrimitive.content
        val key = session["key"]!!.jsonPrimitive.content
        name to key
    }

    /**
     * Submit a completed play to Last.fm. [timestampEpochSeconds] is the
     * UNIX time when the track *started* playing, not when the scrobble
     * is submitted. Last.fm accepts events submitted hours/days late.
     */
    suspend fun scrobble(
        sessionKey: String,
        artist: String,
        track: String,
        timestampEpochSeconds: Long,
        album: String? = null,
    ): Result<Unit> = runCatching {
        val params = sortedMapOf(
            "method" to "track.scrobble",
            "api_key" to credentials.apiKey,
            "sk" to sessionKey,
            "artist" to artist,
            "track" to track,
            "timestamp" to timestampEpochSeconds.toString(),
        )
        if (!album.isNullOrBlank()) params["album"] = album
        signedPost(params)
        Unit
    }

    // ── Public (API-key-only) read endpoints ──────────────────────────

    /**
     * Top tags for a single track. Returns `emptyList()` when the track
     * isn't in Last.fm's index at all (extremely common for deep cuts).
     * Caller then falls back to [getArtistTopTags] — every listed artist
     * has tags, so we always get *something*.
     */
    suspend fun getTrackTopTags(
        artist: String,
        track: String,
    ): Result<List<LastFmTag>> = runCatching {
        val params = sortedMapOf(
            "method" to "track.getTopTags",
            "api_key" to credentials.apiKey,
            "artist" to artist,
            "track" to track,
            "autocorrect" to "1",
        )
        val response = unsignedGet(params)
        parseTopTags(response["toptags"]?.jsonObject)
    }

    /** Top tags for an artist. Used as fallback + as a coarse genre signal. */
    suspend fun getArtistTopTags(artist: String): Result<List<LastFmTag>> = runCatching {
        val params = sortedMapOf(
            "method" to "artist.getTopTags",
            "api_key" to credentials.apiKey,
            "artist" to artist,
            "autocorrect" to "1",
        )
        val response = unsignedGet(params)
        parseTopTags(response["toptags"]?.jsonObject)
    }

    /**
     * Artists similar to [artist]. [limit] is the Last.fm page size — the
     * API caps around 100 regardless of what you send. Used by the
     * discovery engine to seed new candidate artists from the user's
     * favorites.
     */
    suspend fun getSimilarArtists(
        artist: String,
        limit: Int = 30,
    ): Result<List<LastFmSimilarArtist>> = runCatching {
        val params = sortedMapOf(
            "method" to "artist.getSimilar",
            "api_key" to credentials.apiKey,
            "artist" to artist,
            "autocorrect" to "1",
            "limit" to limit.toString(),
        )
        val response = unsignedGet(params)
        parseSimilarArtists(response["similarartists"]?.jsonObject)
    }

    /** Top tracks for an artist — fuel for discovery downloads. */
    suspend fun getArtistTopTracks(
        artist: String,
        limit: Int = 10,
    ): Result<List<LastFmTopTrack>> = runCatching {
        val params = sortedMapOf(
            "method" to "artist.getTopTracks",
            "api_key" to credentials.apiKey,
            "artist" to artist,
            "autocorrect" to "1",
            "limit" to limit.toString(),
        )
        val response = unsignedGet(params)
        parseTopTracks(response["toptracks"]?.jsonObject)
    }

    // ── Public user-history endpoints (API-key-only for public profiles) ──

    /**
     * User's all-time top artists. Used for Last.fm cold-start — when a
     * user connects an account with existing scrobble history, we seed
     * affinity from this call so their mixes are personal from day one.
     */
    suspend fun getUserTopArtists(
        username: String,
        period: String = "overall",
        limit: Int = 100,
    ): Result<List<LastFmTopArtist>> = runCatching {
        val params = sortedMapOf(
            "method" to "user.getTopArtists",
            "api_key" to credentials.apiKey,
            "user" to username,
            "period" to period,
            "limit" to limit.toString(),
        )
        val response = unsignedGet(params)
        parseTopArtists(response["topartists"]?.jsonObject)
    }

    /**
     * `track.getInfo` — album art + canonical album/title/duration for a
     * track. Returns the best non-placeholder image URL, or null if the
     * track isn't in Last.fm's catalog or has no art.
     *
     * Used by the art-backfill pipeline and as the preferred fallback when
     * a YouTube match returns no thumbnail (niche genres, compilation
     * uploads, UGC tracks often surface without music-renderer thumbnails).
     * Higher-quality than YouTube's hqdefault.jpg when Last.fm has the
     * track at all — Last.fm images are album art, not video stills.
     */
    suspend fun getTrackInfo(
        artist: String,
        title: String,
    ): Result<String?> = runCatching {
        val params = sortedMapOf(
            "method" to "track.getInfo",
            "api_key" to credentials.apiKey,
            "artist" to artist,
            "track" to title,
            "autocorrect" to "1",
        )
        val response = unsignedGet(params)
        parseTrackImageUrl(response["track"]?.jsonObject)
    }

    /** User's loved tracks. Strong affinity signal for cold-start. */
    suspend fun getUserLovedTracks(
        username: String,
        limit: Int = 200,
    ): Result<List<LastFmTopTrack>> = runCatching {
        val params = sortedMapOf(
            "method" to "user.getLovedTracks",
            "api_key" to credentials.apiKey,
            "user" to username,
            "limit" to limit.toString(),
        )
        val response = unsignedGet(params)
        parseTopTracks(response["lovedtracks"]?.jsonObject)
    }

    // ── Internal: signed request helpers ──────────────────────────────

    private fun sign(params: Map<String, String>): String {
        val sigBase = buildString {
            // Last.fm: params sorted alphabetically by key, concatenated
            // as key1value1key2value2..., then shared secret appended.
            // `format` and `callback` are excluded from the signature.
            params.toSortedMap()
                .filterKeys { it != "format" && it != "callback" }
                .forEach { (k, v) ->
                    append(k)
                    append(v)
                }
            append(credentials.apiSecret)
        }
        val md5 = MessageDigest.getInstance("MD5").digest(sigBase.toByteArray(Charsets.UTF_8))
        return BigInteger(1, md5).toString(16).padStart(32, '0')
    }

    // All HTTP helpers dispatch to Dispatchers.IO so callers can be
    // suspend functions invoked from anywhere (including VM scopes on
    // the Main dispatcher) without hitting NetworkOnMainThreadException.
    // OkHttp's execute() is blocking; wrapping here keeps the rest of
    // the client straightforward and consistent.
    private suspend fun signedGet(params: Map<String, String>): JsonObject =
        withContext(Dispatchers.IO) {
            val signed = params.toMutableMap().apply {
                put("api_sig", sign(this))
                put("format", "json")
            }
            val url = API_URL.toHttpUrl().newBuilder().apply {
                signed.forEach { (k, v) -> addQueryParameter(k, v) }
            }.build()
            val request = Request.Builder().url(url).get().build()
            val body = okHttpClient.newCall(request).execute().use {
                check(it.isSuccessful) { "Last.fm GET failed: HTTP ${it.code}" }
                it.body?.string() ?: error("Empty Last.fm response")
            }
            json.parseToJsonElement(body).jsonObject
        }

    private suspend fun signedPost(params: Map<String, String>): JsonObject =
        withContext(Dispatchers.IO) {
            val signed = params.toMutableMap().apply {
                put("api_sig", sign(this))
                put("format", "json")
            }
            val form = FormBody.Builder().apply {
                signed.forEach { (k, v) -> add(k, v) }
            }.build()
            val request = Request.Builder().url(API_URL).post(form).build()
            val body = okHttpClient.newCall(request).execute().use {
                check(it.isSuccessful) { "Last.fm POST failed: HTTP ${it.code}" }
                it.body?.string() ?: error("Empty Last.fm response")
            }
            json.parseToJsonElement(body).jsonObject
        }

    /**
     * Unsigned GET — used for read-only public API endpoints. No session
     * key, no signature, just an API key. Returns the parsed root JSON
     * object (callers drill into the response-specific subtree).
     */
    private suspend fun unsignedGet(params: Map<String, String>): JsonObject =
        withContext(Dispatchers.IO) {
            val paramsWithFormat = params + ("format" to "json")
            val url = API_URL.toHttpUrl().newBuilder().apply {
                paramsWithFormat.forEach { (k, v) -> addQueryParameter(k, v) }
            }.build()
            val request = Request.Builder().url(url).get().build()
            val body = okHttpClient.newCall(request).execute().use {
                check(it.isSuccessful) { "Last.fm GET failed: HTTP ${it.code}" }
                it.body?.string() ?: error("Empty Last.fm response")
            }
            val root = json.parseToJsonElement(body).jsonObject
            // Last.fm returns HTTP 200 with { error, message } on some
            // kinds of failures (invalid artist, rate-limited, etc).
            // Surface those so callers don't treat garbage as success.
            root["error"]?.jsonPrimitive?.content?.let { err ->
                val msg = root["message"]?.jsonPrimitive?.content ?: "unknown"
                error("Last.fm API error $err: $msg")
            }
            root
        }

    // ── Response parsers ──────────────────────────────────────────────

    private fun parseTopTags(root: JsonObject?): List<LastFmTag> {
        if (root == null) return emptyList()
        val tagsRaw = root["tag"] ?: return emptyList()
        val arr = tagsRaw.asArrayOrSingletonArray() ?: return emptyList()
        // Last.fm counts: usually a small integer in the 0..100 range for
        // top tags, but occasionally very large for artist-level. Normalize
        // by the max within this response so tag weights are comparable
        // across tracks even when absolute counts aren't.
        val pairs = arr.mapNotNull { elem ->
            val obj = elem.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val count = obj["count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            name.lowercase() to count
        }.filter { it.first.isNotBlank() && it.second > 0 }
        val maxCount = pairs.maxOfOrNull { it.second } ?: return emptyList()
        return pairs.map { LastFmTag(name = it.first, weight = it.second.toFloat() / maxCount) }
    }

    private fun parseSimilarArtists(root: JsonObject?): List<LastFmSimilarArtist> {
        if (root == null) return emptyList()
        val arr = root["artist"]?.asArrayOrSingletonArray() ?: return emptyList()
        return arr.mapNotNull { elem ->
            val obj = elem.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val match = obj["match"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
            LastFmSimilarArtist(name = name, match = match)
        }
    }

    private fun parseTopTracks(root: JsonObject?): List<LastFmTopTrack> {
        if (root == null) return emptyList()
        val arr = root["track"]?.asArrayOrSingletonArray() ?: return emptyList()
        return arr.mapNotNull { elem ->
            val obj = elem.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val artistNode = obj["artist"]
            val artist = when {
                artistNode is JsonObject -> artistNode["name"]?.jsonPrimitive?.content
                else -> artistNode?.jsonPrimitive?.content
            } ?: return@mapNotNull null
            val playcount = obj["playcount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            LastFmTopTrack(artist = artist, title = name, playcount = playcount)
        }
    }

    /**
     * Extracts the best album-art URL from a `track.getInfo` response's
     * `track.album.image[]` array. Prefers "mega" → "extralarge" → "large"
     * sizes. Filters Last.fm's well-known "no image" placeholder (the star
     * logo at `.../2a96cbd8b46e442fc41c2b86b821562f.*`) which otherwise
     * masquerades as a populated URL.
     */
    private fun parseTrackImageUrl(trackRoot: JsonObject?): String? {
        if (trackRoot == null) return null
        val album = trackRoot["album"]?.jsonObject ?: return null
        val images = album["image"]?.asArrayOrSingletonArray() ?: return null
        // Build a size → url map, filtering placeholder + empty entries.
        val bySize = HashMap<String, String>(images.size)
        for (img in images) {
            val obj = img.jsonObject
            val size = obj["size"]?.jsonPrimitive?.content ?: continue
            val url = obj["#text"]?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() && !it.contains(LASTFM_PLACEHOLDER_HASH) }
                ?: continue
            bySize[size] = url
        }
        // Preference order: highest quality first.
        return bySize["mega"] ?: bySize["extralarge"] ?: bySize["large"]
    }

    private fun parseTopArtists(root: JsonObject?): List<LastFmTopArtist> {
        if (root == null) return emptyList()
        val arr = root["artist"]?.asArrayOrSingletonArray() ?: return emptyList()
        return arr.mapNotNull { elem ->
            val obj = elem.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val playcount = obj["playcount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            LastFmTopArtist(name = name, playcount = playcount)
        }
    }

    /**
     * Last.fm quirk: when a list has a single element the API returns a
     * JSON object directly instead of a 1-element array. Normalize both
     * shapes into an array so parsers can iterate uniformly.
     */
    private fun kotlinx.serialization.json.JsonElement.asArrayOrSingletonArray():
        JsonArray? = when (this) {
        is JsonArray -> this
        is JsonObject -> JsonArray(listOf(this))
        else -> null
    }

    companion object {
        private const val API_URL = "https://ws.audioscrobbler.com/2.0/"
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Last.fm's global "no image" placeholder filename — the star logo
         * that Last.fm serves when an album has no uploaded art. The same
         * hash appears across every image size so a single substring match
         * filters every flavour (34s, 64s, 174s, 300x300).
         */
        private const val LASTFM_PLACEHOLDER_HASH = "2a96cbd8b46e442fc41c2b86b821562f"
    }
}

/** Parsed tag row from track/artist top-tag endpoints. [weight] in 0..1. */
data class LastFmTag(val name: String, val weight: Float)

/** Parsed similar-artist from `artist.getSimilar`. [match] in 0..1. */
data class LastFmSimilarArtist(val name: String, val match: Float)

/** Track projection used by top-tracks, loved-tracks, artist top-tracks. */
data class LastFmTopTrack(val artist: String, val title: String, val playcount: Int)

/** Artist projection used by user.getTopArtists. */
data class LastFmTopArtist(val name: String, val playcount: Int)

/**
 * Last.fm API credentials. Provided by the app layer (typically from
 * BuildConfig, which reads `local.properties` at build time — see the
 * README for the developer setup).
 *
 * Both values must be non-empty for Last.fm features to function; when
 * empty, the UI should disable the "Connect Last.fm" button. The client
 * itself doesn't check emptiness — that's an app-level concern.
 */
data class LastFmCredentials(
    val apiKey: String,
    val apiSecret: String,
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank() && apiSecret.isNotBlank()
}
