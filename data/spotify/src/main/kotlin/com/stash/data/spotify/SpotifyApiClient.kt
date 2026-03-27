package com.stash.data.spotify

import android.util.Base64
import android.util.Log
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.UserInfo
import com.stash.data.spotify.model.SpotifyAlbum
import com.stash.data.spotify.model.SpotifyArtist
import com.stash.data.spotify.model.SpotifyImage
import com.stash.data.spotify.model.SpotifyOwner
import com.stash.data.spotify.model.SpotifyPlaylistItem
import com.stash.data.spotify.model.SpotifyPlaylistsResponse
import com.stash.data.spotify.model.SpotifyTrackItem
import com.stash.data.spotify.model.SpotifyTrackObject
import com.stash.data.spotify.model.SpotifyTracksRef
import com.stash.data.spotify.model.SpotifyTracksResponse
import com.stash.data.spotify.model.SpotifyUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thrown when the Spotify API returns an error that exhausts all retry attempts.
 *
 * @property httpCode The HTTP status code from the last failed attempt.
 * @property url      The request URL that failed.
 */
class SpotifyApiException(
    val httpCode: Int,
    val url: String,
    message: String,
) : Exception(message)

/**
 * High-level client for the Spotify Web API.
 *
 * Uses Spotify's internal spclient endpoints (resolved via apresolve.spotify.com)
 * for playlist and track operations, since sp_dc-derived web player tokens are
 * blocked from the public api.spotify.com/v1 playlist endpoints with persistent
 * HTTP 429 errors (rate limit by design, not by usage).
 *
 * The spclient endpoints are the same ones used by the official Spotify web player
 * and are designed to work with sp_dc-derived access tokens. They return JSON when
 * the Accept: application/json header is set.
 *
 * Falls back to api.spotify.com/v1 only for the user profile endpoint (/me) which
 * is lighter and less restricted.
 *
 * All methods automatically retrieve a valid access token from [TokenManager]
 * and attach it as a Bearer token. Requests that receive HTTP 429 are retried
 * up to [MAX_RETRIES] times respecting the Retry-After header. A small delay is
 * inserted between consecutive requests to avoid triggering rate limits.
 */
@Singleton
class SpotifyApiClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val tokenManager: TokenManager,
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "StashSync"
        private const val BASE_URL = "https://api.spotify.com/v1"
        private const val DEFAULT_LIMIT = 50

        /** Maximum number of retry attempts for 429 responses. */
        private const val MAX_RETRIES = 3

        /** Default wait time (ms) when 429 has no Retry-After header. */
        private const val DEFAULT_RETRY_AFTER_MS = 5000L

        /** Minimum delay (ms) between consecutive API requests to avoid bursts. */
        private const val INTER_REQUEST_DELAY_MS = 500L

        /**
         * Extra padding (ms) added on top of the Retry-After value.
         */
        private const val RETRY_AFTER_PADDING_MS = 3000L

        /**
         * URL for resolving spclient access points. Returns JSON with a list
         * of hostnames:ports that serve the internal Spotify API.
         */
        private const val AP_RESOLVE_URL = "https://apresolve.spotify.com/?type=spclient"

        private val DAILY_MIX_REGEX = Regex("""Daily Mix \d+""")
    }

    /**
     * Timestamp of the last completed API request. Used to enforce
     * [INTER_REQUEST_DELAY_MS] spacing between calls.
     */
    private var lastRequestTimeMs = 0L

    /**
     * Cached spclient base URL, resolved from apresolve.spotify.com.
     * Lazily populated on first use and reused thereafter.
     */
    @Volatile
    private var spClientBaseUrl: String? = null

    // ── Profile ──────────────────────────────────────────────────────────

    /**
     * Returns the current user's Spotify profile.
     *
     * IMPORTANT: Does NOT call api.spotify.com/v1/me, which permanently
     * 429-blocks sp_dc tokens. Instead resolves the user ID from:
     * 1. Stored username in token metadata (extracted from JWT during auth)
     * 2. JWT decode of the current access token
     * 3. spclient identity endpoints as a last resort
     *
     * @return A [UserInfo] with the user's ID, or null if resolution fails.
     */
    suspend fun getCurrentUserProfile(): UserInfo? = withContext(Dispatchers.IO) {
        val userId = resolveUserId()
        if (userId != null) {
            Log.d(TAG, "getCurrentUserProfile: resolved userId='$userId'")
            return@withContext UserInfo(
                id = userId,
                displayName = userId,
                imageUrl = null,
            )
        }
        Log.e(TAG, "getCurrentUserProfile: could not resolve user ID")
        null
    }

    // ── Playlists ────────────────────────────────────────────────────────

    /**
     * Fetches the current user's playlists via the spclient rootlist endpoint.
     *
     * The spclient endpoint at /playlist/v2/user/{userId}/rootlist returns the
     * user's playlist library in JSON format. We parse it and convert to the
     * same [SpotifyPlaylistItem] model used by the rest of the app.
     *
     * @param limit  Maximum number of playlists to return (default 50).
     * @param offset Zero-based index of the first playlist to return.
     * @return List of [SpotifyPlaylistItem].
     * @throws SpotifyApiException if the API request fails after retries.
     */
    suspend fun getUserPlaylists(
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
    ): List<SpotifyPlaylistItem> = withContext(Dispatchers.IO) {
        val spClientBase = getSpClientBaseUrl()
            ?: throw SpotifyApiException(0, "", "Cannot resolve spclient host")

        // Try to resolve user ID for the rootlist endpoint.
        val userId = resolveUserId()
        if (userId != null) {
            val url = "$spClientBase/playlist/v2/user/$userId/rootlist" +
                "?decorate=revision%2Clength%2Cattributes%2Ctimestamp%2Cowner"
            Log.d(TAG, "Fetching playlists via spclient rootlist: $url")

            val body = executeGet(url)
                ?: throw SpotifyApiException(0, url, "No access token available for Spotify")

            Log.d(TAG, "spclient rootlist response (first 2000 chars): ${body.take(2000)}")
            return@withContext parseRootlistResponse(body, limit, offset)
        }

        // Fallback: Try the spclient library endpoint which may not need a user ID.
        // Also try the spclient equivalent of /v1/me/playlists with different paths.
        val fallbackUrls = listOf(
            "$spClientBase/playlist/v2/me/rootlist?decorate=revision%2Clength%2Cattributes%2Ctimestamp%2Cowner",
            "$spClientBase/your-library/v2/me/playlists?limit=$limit&offset=$offset",
        )

        for (url in fallbackUrls) {
            try {
                Log.d(TAG, "Trying fallback playlist URL: $url")
                val body = executeGet(url) ?: continue
                Log.d(TAG, "Fallback response (first 2000 chars): ${body.take(2000)}")

                // Try rootlist format first, then raw list.
                val rootlistResult = parseRootlistResponse(body, limit, offset)
                if (rootlistResult.isNotEmpty()) return@withContext rootlistResult

                // Try standard API format.
                val parsed = json.decodeFromString<SpotifyPlaylistsResponse>(body)
                if (parsed.items.isNotEmpty()) return@withContext parsed.items
            } catch (e: Exception) {
                Log.d(TAG, "Fallback URL $url failed: ${e.message}")
            }
        }

        throw SpotifyApiException(0, "", "Cannot fetch playlists: user ID unknown and fallback endpoints failed")
    }

    /**
     * Fetches all tracks in a specific playlist via the spclient endpoint.
     *
     * @param playlistId The Spotify playlist ID.
     * @return List of [SpotifyTrackItem].
     * @throws SpotifyApiException if any page request fails after retries.
     */
    suspend fun getPlaylistTracks(playlistId: String): List<SpotifyTrackItem> {
        return withContext(Dispatchers.IO) {
            val spClientBase = getSpClientBaseUrl()
                ?: throw SpotifyApiException(0, "", "Cannot resolve spclient host")

            val uri = "spotify:playlist:$playlistId"
            val url = "$spClientBase/playlist/v2/playlist/$uri"
            Log.d(TAG, "Fetching playlist tracks via spclient: $url")

            val body = executeGet(url)
                ?: throw SpotifyApiException(0, url, "No access token available for Spotify")

            Log.d(TAG, "spclient playlist response (first 2000 chars): ${body.take(2000)}")

            parsePlaylistTracksResponse(body)
        }
    }

    /**
     * Finds the user's Spotify-generated Daily Mix playlists.
     *
     * Daily Mixes are owned by the "spotify" user and have names matching
     * the pattern "Daily Mix N".
     *
     * @return List of [SpotifyPlaylistItem] representing Daily Mixes, or empty
     *         if none are found or the request fails.
     */
    suspend fun getDailyMixes(): List<SpotifyPlaylistItem> {
        return getUserPlaylists().filter { playlist ->
            playlist.owner.id == "spotify" && DAILY_MIX_REGEX.matches(playlist.name)
        }
    }

    // ── Liked Songs ──────────────────────────────────────────────────────

    /**
     * Fetches a page of the user's Liked Songs (saved tracks).
     *
     * Uses the spclient collection endpoint for liked songs.
     *
     * @param limit  Maximum number of tracks to return (1..50, default 50).
     * @param offset Zero-based index of the first track to return.
     * @return List of [SpotifyTrackItem].
     * @throws SpotifyApiException if the API request fails after retries.
     */
    suspend fun getLikedSongs(
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
    ): List<SpotifyTrackItem> = withContext(Dispatchers.IO) {
        val spClientBase = getSpClientBaseUrl()
            ?: throw SpotifyApiException(0, "", "Cannot resolve spclient host")

        val url = "$spClientBase/collection/v2/paging?folderUri=spotify%3Acollection%3Atracks" +
            "&limit=$limit&offset=$offset"
        Log.d(TAG, "Fetching liked songs via spclient: $url")

        val body = executeGet(url)
            ?: throw SpotifyApiException(0, url, "No access token available for Spotify")

        Log.d(TAG, "spclient liked songs response (first 2000 chars): ${body.take(2000)}")

        parseLikedSongsResponse(body)
    }

    // ── spclient resolution ──────────────────────────────────────────────

    /**
     * Resolves an spclient hostname from Spotify's access-point resolver.
     *
     * @return The base URL (e.g. "https://gue1-spclient.spotify.com"), or null on failure.
     */
    private fun resolveSpClientHost(): String? {
        return try {
            Log.d(TAG, "Resolving spclient host from $AP_RESOLVE_URL")
            val request = Request.Builder()
                .url(AP_RESOLVE_URL)
                .get()
                .header("Accept", "application/json")
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "apresolve returned HTTP ${response.code}")
                response.body?.close()
                return null
            }
            val body = response.body?.string() ?: return null
            Log.d(TAG, "apresolve response: ${body.take(500)}")

            val jsonObj = json.parseToJsonElement(body)
            val hosts = jsonObj.jsonObject["spclient"]?.jsonArray
            val firstHost = hosts?.firstOrNull()?.jsonPrimitive?.content ?: return null

            // The host comes as "hostname:port"; strip the :443 for HTTPS.
            val hostname = firstHost.substringBefore(":")
            val baseUrl = "https://$hostname"
            Log.d(TAG, "Resolved spclient base URL: $baseUrl")
            baseUrl
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve spclient host", e)
            null
        }
    }

    /**
     * Returns the cached spclient base URL, resolving it on first call.
     */
    private fun getSpClientBaseUrl(): String? {
        spClientBaseUrl?.let { return it }
        val resolved = resolveSpClientHost()
        spClientBaseUrl = resolved
        return resolved
    }

    // ── User ID resolution ──────────────────────────────────────────────

    /**
     * Cached Spotify user ID, resolved on first use.
     */
    @Volatile
    private var cachedUserId: String? = null

    /**
     * Resolves the current Spotify user ID by trying multiple approaches:
     * 1. Return cached value if available
     * 2. Read the stored user ID from the encrypted token store
     * 3. Decode the JWT access token payload (no network call)
     * 4. Try the token endpoint for the username field
     * 5. Try spclient identity endpoints
     *
     * IMPORTANT: We NEVER call api.spotify.com/v1/me here. That endpoint
     * permanently 429-blocks sp_dc tokens (not rate limiting -- policy).
     *
     * @return The Spotify user ID, or null if it cannot be determined.
     */
    private suspend fun resolveUserId(): String? {
        cachedUserId?.let {
            Log.d(TAG, "resolveUserId: returning cached='$it'")
            return it
        }

        // Approach 1: Read from stored token metadata (set during connectSpotifyWithCookie).
        val username = tokenManager.getSpotifyUsername()
        if (!username.isNullOrEmpty()) {
            Log.d(TAG, "resolveUserId: from stored metadata='$username'")
            cachedUserId = username
            return username
        }

        // Approach 2: Decode the JWT access token (zero network calls).
        val accessToken = tokenManager.getSpotifyAccessToken()
        if (accessToken != null) {
            val jwtUsername = extractUsernameFromJwt(accessToken)
            if (jwtUsername != null) {
                Log.d(TAG, "resolveUserId: from JWT='$jwtUsername'")
                cachedUserId = jwtUsername
                return jwtUsername
            }
        }

        // Approach 3: Fetch the token endpoint and look for username field.
        try {
            val userId = fetchUserIdFromTokenEndpoint()
            if (userId != null) {
                Log.d(TAG, "resolveUserId: from token endpoint='$userId'")
                cachedUserId = userId
                return userId
            }
        } catch (e: Exception) {
            Log.w(TAG, "Token endpoint user ID extraction failed: ${e.message}")
        }

        // Approach 4: Try spclient identity endpoints as a last resort.
        val spClientBase = getSpClientBaseUrl()
        if (spClientBase != null) {
            val identityUrls = listOf(
                "$spClientBase/v1/me",
                "$spClientBase/identity/v1/user",
            )
            for (url in identityUrls) {
                try {
                    val body = executeGet(url) ?: continue
                    Log.d(TAG, "Identity response from $url: ${body.take(500)}")
                    val jsonObj = json.parseToJsonElement(body).jsonObject
                    val userId = jsonObj["id"]?.jsonPrimitive?.content
                        ?: jsonObj["username"]?.jsonPrimitive?.content
                        ?: jsonObj["user"]?.jsonPrimitive?.content
                        ?: jsonObj["uri"]?.jsonPrimitive?.content
                            ?.takeIf { it.startsWith("spotify:user:") }
                            ?.removePrefix("spotify:user:")
                    if (!userId.isNullOrEmpty()) {
                        Log.d(TAG, "resolveUserId: from spclient identity='$userId'")
                        cachedUserId = userId
                        return userId
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Identity endpoint $url failed: ${e.message}")
                }
            }
        }

        Log.e(TAG, "resolveUserId: ALL approaches failed")
        return null
    }

    /**
     * Fetches the Spotify token endpoint directly with the sp_dc cookie
     * to get the full token response, which may include additional user info.
     *
     * We call the same endpoint as SpotifyAuthManager but parse the full
     * JSON response including any extra fields not in our serialized model.
     */
    private suspend fun fetchUserIdFromTokenEndpoint(): String? {
        val spDcCookie = tokenManager.getSpDcCookie() ?: return null

        val request = Request.Builder()
            .url("https://open.spotify.com/api/token?reason=transport&productType=web-player")
            .get()
            .header("Cookie", "sp_dc=$spDcCookie")
            .header("Accept", "application/json")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36",
            )
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.w(TAG, "Token endpoint fetch for user ID failed: HTTP ${response.code}")
            response.body?.close()
            return null
        }

        val body = response.body?.string() ?: return null
        Log.d(TAG, "Token endpoint full response: $body")

        // Parse the full JSON and look for any user-identifying field.
        try {
            val jsonObj = json.parseToJsonElement(body).jsonObject
            val allKeys = jsonObj.keys.joinToString(", ")
            Log.d(TAG, "Token response keys: $allKeys")

            // Check common fields for user identity.
            return jsonObj["username"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
                ?: jsonObj["user"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
                ?: jsonObj["userId"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
                ?: jsonObj["displayName"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse token response for user ID", e)
            return null
        }
    }

    // ── JWT utility ────────────────────────────────────────────────────────

    /**
     * Extracts the Spotify username from a JWT access token without any network call.
     *
     * Spotify sp_dc-derived access tokens are JWTs whose base64url-encoded payload
     * contains a "sub" or "username" field with the Spotify user ID.
     *
     * @param accessToken A JWT access token.
     * @return The Spotify username/user ID, or null if extraction fails.
     */
    private fun extractUsernameFromJwt(accessToken: String): String? {
        return try {
            val parts = accessToken.split(".")
            if (parts.size < 2) {
                Log.w(TAG, "extractUsernameFromJwt: not a JWT (${parts.size} parts)")
                return null
            }
            val payload = String(
                Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            )
            Log.d(TAG, "JWT payload (first 500 chars): ${payload.take(500)}")
            val jsonObj = json.parseToJsonElement(payload).jsonObject
            val username = jsonObj["sub"]?.jsonPrimitive?.content
                ?: jsonObj["username"]?.jsonPrimitive?.content
            Log.d(TAG, "JWT extracted username: '$username'")
            username?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "extractUsernameFromJwt failed", e)
            null
        }
    }

    // ── spclient response parsing ────────────────────────────────────────

    /**
     * Parses the spclient rootlist response into [SpotifyPlaylistItem] objects.
     *
     * The rootlist response has a structure like:
     * ```json
     * {
     *   "revision": "...",
     *   "contents": {
     *     "items": [
     *       {
     *         "uri": "spotify:playlist:xxx",
     *         "attributes": {
     *           "name": "...",
     *           "picture": "...",
     *           "timestamp": ...
     *         },
     *         "ownerUsername": "...",
     *         "length": 42
     *       }
     *     ],
     *     "metaItems": [...]
     *   }
     * }
     * ```
     */
    private fun parseRootlistResponse(
        responseBody: String,
        limit: Int,
        offset: Int,
    ): List<SpotifyPlaylistItem> {
        return try {
            val root = json.parseToJsonElement(responseBody).jsonObject
            val contents = root["contents"]?.jsonObject ?: return emptyList()
            val items = contents["items"]?.jsonArray ?: return emptyList()

            items.drop(offset).take(limit).mapNotNull { element ->
                val item = element.jsonObject
                val uri = item["uri"]?.jsonPrimitive?.content ?: return@mapNotNull null

                // Only include playlists, not folders or other items.
                if (!uri.startsWith("spotify:playlist:")) return@mapNotNull null

                val playlistId = uri.removePrefix("spotify:playlist:")
                val attributes = item["attributes"]?.jsonObject
                val name = attributes?.get("name")?.jsonPrimitive?.content ?: "Untitled"
                val ownerUsername = item["ownerUsername"]?.jsonPrimitive?.content ?: ""
                val length = item["length"]?.jsonPrimitive?.intOrNull ?: 0

                // Construct an image URL from the picture field if present.
                // spclient may return a Spotify image ID rather than a full URL.
                val picture = attributes?.get("picture")?.jsonPrimitive?.content
                val images = if (picture != null) {
                    val imageUrl = if (picture.startsWith("http")) {
                        picture
                    } else {
                        "https://i.scdn.co/image/$picture"
                    }
                    listOf(SpotifyImage(url = imageUrl))
                } else {
                    null
                }

                SpotifyPlaylistItem(
                    id = playlistId,
                    name = name,
                    owner = SpotifyOwner(id = ownerUsername),
                    images = images,
                    tracks = SpotifyTracksRef(total = length),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse rootlist response", e)
            emptyList()
        }
    }

    /**
     * Parses the spclient playlist response into [SpotifyTrackItem] objects.
     *
     * The playlist response has a structure like:
     * ```json
     * {
     *   "revision": "...",
     *   "contents": {
     *     "items": [
     *       {
     *         "uri": "spotify:track:xxx",
     *         "attributes": {
     *           "added_by": "...",
     *           "timestamp": ...
     *         }
     *       }
     *     ],
     *     "metaItems": [
     *       {
     *         "uri": "spotify:track:xxx",
     *         "attributes": {
     *           "title": "...",
     *           "artist_name": "...",
     *           "album_title": "...",
     *           "duration": ...,
     *           "album_cover": "..."
     *         }
     *       }
     *     ]
     *   }
     * }
     * ```
     *
     * Note: The exact metadata fields may vary. If metaItems is not populated,
     * we construct minimal track objects from the URIs alone.
     */
    private fun parsePlaylistTracksResponse(responseBody: String): List<SpotifyTrackItem> {
        return try {
            val root = json.parseToJsonElement(responseBody).jsonObject
            val contents = root["contents"]?.jsonObject ?: return emptyList()
            val items = contents["items"]?.jsonArray ?: return emptyList()

            // Try to build a metadata lookup from metaItems if available.
            val metaItems = contents["metaItems"]?.jsonArray
            val metaMap = mutableMapOf<String, JsonObject>()
            metaItems?.forEach { meta ->
                val metaObj = meta.jsonObject
                val uri = metaObj["uri"]?.jsonPrimitive?.content
                if (uri != null) {
                    metaMap[uri] = metaObj
                }
            }

            items.mapNotNull { element ->
                val item = element.jsonObject
                val uri = item["uri"]?.jsonPrimitive?.content ?: return@mapNotNull null

                // Only include tracks.
                if (!uri.startsWith("spotify:track:")) return@mapNotNull null

                val trackId = uri.removePrefix("spotify:track:")
                val attributes = item["attributes"]?.jsonObject
                val meta = metaMap[uri]?.get("attributes")?.jsonObject

                // Extract track metadata from attributes or metaItems.
                val title = meta?.get("title")?.jsonPrimitive?.content
                    ?: attributes?.get("title")?.jsonPrimitive?.content
                    ?: "Unknown"
                val artistName = meta?.get("artist_name")?.jsonPrimitive?.content
                    ?: attributes?.get("artist_name")?.jsonPrimitive?.content
                    ?: ""
                val albumTitle = meta?.get("album_title")?.jsonPrimitive?.content
                    ?: attributes?.get("album_title")?.jsonPrimitive?.content
                val durationMs = meta?.get("duration")?.jsonPrimitive?.longOrNull
                    ?: attributes?.get("duration")?.jsonPrimitive?.longOrNull
                    ?: 0L
                val albumCover = meta?.get("album_cover")?.jsonPrimitive?.content
                    ?: attributes?.get("album_cover")?.jsonPrimitive?.content

                val albumImages = if (albumCover != null) {
                    val imageUrl = if (albumCover.startsWith("http")) {
                        albumCover
                    } else {
                        "https://i.scdn.co/image/$albumCover"
                    }
                    listOf(SpotifyImage(url = imageUrl))
                } else {
                    null
                }

                SpotifyTrackItem(
                    track = SpotifyTrackObject(
                        id = trackId,
                        name = title,
                        artists = if (artistName.isNotEmpty()) {
                            listOf(SpotifyArtist(id = "", name = artistName))
                        } else {
                            emptyList()
                        },
                        album = if (albumTitle != null || albumImages != null) {
                            SpotifyAlbum(
                                id = "",
                                name = albumTitle ?: "",
                                images = albumImages,
                            )
                        } else {
                            null
                        },
                        duration_ms = durationMs,
                        uri = uri,
                    ),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse playlist tracks response", e)
            emptyList()
        }
    }

    /**
     * Parses the spclient liked songs / collection response.
     *
     * This is a best-effort parser; the exact format of the collection
     * endpoint may differ. We log the response and parse what we can.
     */
    private fun parseLikedSongsResponse(responseBody: String): List<SpotifyTrackItem> {
        return try {
            val root = json.parseToJsonElement(responseBody).jsonObject

            // The collection endpoint may return items directly or nested.
            val items = root["items"]?.jsonArray
                ?: root["contents"]?.jsonObject?.get("items")?.jsonArray
                ?: return emptyList()

            items.mapNotNull { element ->
                val item = element.jsonObject
                val uri = item["uri"]?.jsonPrimitive?.content
                    ?: item["trackUri"]?.jsonPrimitive?.content
                    ?: return@mapNotNull null

                if (!uri.startsWith("spotify:track:")) return@mapNotNull null

                val trackId = uri.removePrefix("spotify:track:")
                val attributes = item["attributes"]?.jsonObject
                val title = attributes?.get("title")?.jsonPrimitive?.content
                    ?: item["name"]?.jsonPrimitive?.content
                    ?: "Unknown"
                val artistName = attributes?.get("artist_name")?.jsonPrimitive?.content ?: ""
                val albumTitle = attributes?.get("album_title")?.jsonPrimitive?.content
                val durationMs = attributes?.get("duration")?.jsonPrimitive?.longOrNull ?: 0L

                SpotifyTrackItem(
                    track = SpotifyTrackObject(
                        id = trackId,
                        name = title,
                        artists = if (artistName.isNotEmpty()) {
                            listOf(SpotifyArtist(id = "", name = artistName))
                        } else {
                            emptyList()
                        },
                        album = if (albumTitle != null) {
                            SpotifyAlbum(id = "", name = albumTitle, images = null)
                        } else {
                            null
                        },
                        duration_ms = durationMs,
                        uri = uri,
                    ),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse liked songs response", e)
            emptyList()
        }
    }

    // ── Internal HTTP ────────────────────────────────────────────────────

    /**
     * Builds a GET request with all required Spotify Web Player headers.
     *
     * @param url         The full request URL including query parameters.
     * @param accessToken A valid Bearer access token obtained from the sp_dc cookie.
     * @return A fully constructed [Request] ready for execution.
     */
    private fun buildRequest(url: String, accessToken: String): Request {
        return Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US")
            .header("App-Platform", "WebPlayer")
            .header("Origin", "https://open.spotify.com")
            .header("Referer", "https://open.spotify.com/")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36",
            )
            .header("spotify-app-version", "1.2.52.442.g0e1a5ca5")
            .build()
    }

    /**
     * Executes an authenticated GET request with automatic retry handling
     * for HTTP 429 (Too Many Requests) and token refresh on HTTP 401.
     *
     * @param url The full request URL including query parameters.
     * @return The response body as a String, or null if the access token is unavailable.
     * @throws SpotifyApiException if the request fails with a non-recoverable status
     *         code or if all retry attempts for a 429 are exhausted.
     */
    private suspend fun executeGet(url: String): String? {
        var accessToken = tokenManager.getSpotifyAccessToken() ?: run {
            Log.w(TAG, "executeGet: no access token available for $url")
            return null
        }
        var hasRetriedAuth = false

        // Enforce minimum spacing between consecutive requests.
        val elapsed = System.currentTimeMillis() - lastRequestTimeMs
        if (elapsed < INTER_REQUEST_DELAY_MS) {
            delay(INTER_REQUEST_DELAY_MS - elapsed)
        }

        var lastResponseCode = 0
        for (attempt in 0..MAX_RETRIES) {
            Log.d(TAG, "executeGet: $url (attempt ${attempt + 1}/${MAX_RETRIES + 1})")
            val request = buildRequest(url, accessToken)

            val response = okHttpClient.newCall(request).execute()
            lastRequestTimeMs = System.currentTimeMillis()
            lastResponseCode = response.code

            if (response.isSuccessful) {
                val body = response.body?.string()
                Log.d(TAG, "executeGet: $url -> HTTP ${response.code}, body length=${body?.length ?: 0}")
                return body
            }

            // Handle 401 by forcing a token refresh (once).
            if (response.code == 401 && !hasRetriedAuth) {
                hasRetriedAuth = true
                response.body?.close()
                Log.w(TAG, "401 Unauthorized on $url, forcing token refresh")
                val refreshedToken = tokenManager.forceRefreshSpotifyAccessToken()
                if (refreshedToken != null && refreshedToken != accessToken) {
                    accessToken = refreshedToken
                    continue
                }
            }

            // Handle 429 rate limiting with Retry-After header + padding.
            if (response.code == 429 && attempt < MAX_RETRIES) {
                val errorBody = response.body?.string() ?: "no body"
                val retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()
                Log.w(
                    TAG,
                    "429 rate limited on $url (attempt ${attempt + 1}/$MAX_RETRIES), " +
                        "Retry-After: ${retryAfterSeconds}s, body: $errorBody",
                )

                val waitMs = if (retryAfterSeconds != null && retryAfterSeconds > 0) {
                    (retryAfterSeconds * 1000) + RETRY_AFTER_PADDING_MS
                } else {
                    DEFAULT_RETRY_AFTER_MS * (attempt + 1)
                }
                Log.i(TAG, "Waiting ${waitMs / 1000}s before retry")
                delay(waitMs)
                continue
            }

            // Non-recoverable error or final retry attempt.
            val finalBody = response.body?.string() ?: "no body"
            Log.e(TAG, "Spotify error HTTP ${response.code} on $url, body: $finalBody")
            throw SpotifyApiException(
                httpCode = response.code,
                url = url,
                message = "Spotify API request failed: HTTP ${response.code} on $url" +
                    if (attempt > 0) " (after ${attempt + 1} attempts)" else "",
            )
        }

        throw SpotifyApiException(
            httpCode = lastResponseCode,
            url = url,
            message = "Spotify API request failed after ${MAX_RETRIES + 1} attempts: HTTP $lastResponseCode on $url",
        )
    }
}
