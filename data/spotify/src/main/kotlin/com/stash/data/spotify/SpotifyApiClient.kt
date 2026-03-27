package com.stash.data.spotify

import android.util.Log
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.UserInfo
import com.stash.core.auth.spotify.SpotifyAuthConfig
import com.stash.core.auth.spotify.SpotifyAuthManager
import com.stash.data.spotify.model.SpotifyAlbum
import com.stash.data.spotify.model.SpotifyArtist
import com.stash.data.spotify.model.SpotifyImage
import com.stash.data.spotify.model.SpotifyOwner
import com.stash.data.spotify.model.SpotifyPlaylistItem
import com.stash.data.spotify.model.SpotifyTrackItem
import com.stash.data.spotify.model.SpotifyTrackObject
import com.stash.data.spotify.model.SpotifyTracksRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
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
 * High-level client for Spotify data access using a TWO-PRONGED approach:
 *
 * **Prong 1 -- Client Credentials (public data):**
 * Uses Spotify's official client_credentials OAuth2 flow with well-known credentials
 * to access the standard Web API (api.spotify.com/v1). This works reliably for
 * public playlists and track metadata without 429 blocks.
 *
 * **Prong 2 -- sp_dc token (user-specific data):**
 * Uses the sp_dc cookie-derived access token with the GraphQL Partner API
 * (api-partner.spotify.com) for user-specific data like the user's library
 * (playlist enumeration), Daily Mixes, and Liked Songs.
 *
 * The key insight: once we know a playlist's ID (from the user's library via sp_dc),
 * we fetch its actual track data via client_credentials -- which never gets 429'd.
 *
 * If sp_dc-based operations fail (e.g., GraphQL schema changes, client token issues),
 * the client gracefully returns empty results for user-specific data while all
 * playlist track fetching continues to work via client_credentials.
 */
@Singleton
class SpotifyApiClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val tokenManager: TokenManager,
    private val spotifyAuthManager: SpotifyAuthManager,
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "StashSync"
        private const val DEFAULT_LIMIT = 50

        /** Base URL for the Spotify Web API v1 (used with client_credentials tokens). */
        private const val WEB_API_BASE = "https://api.spotify.com/v1"

        /** Regex pattern for identifying Spotify-generated Daily Mix playlists. */
        private val DAILY_MIX_REGEX = Regex("""Daily Mix \d+""")
    }

    // ── Client Credentials Token Cache ──────────────────────────────────

    /**
     * Cached client_credentials access token for the public Web API.
     * Valid for ~1 hour; refreshed automatically when expired.
     */
    @Volatile
    private var clientCredentialsToken: String? = null

    /** Epoch seconds when the cached client_credentials token expires. */
    @Volatile
    private var clientCredentialsExpiry: Long = 0

    /**
     * Returns a valid client_credentials token, refreshing if expired.
     * The token is cached for 1 hour minus a 60-second safety margin.
     *
     * @return A valid Bearer token for api.spotify.com/v1, or null on failure.
     */
    private suspend fun getClientCredentialsToken(): String? {
        val now = System.currentTimeMillis() / 1000
        val cached = clientCredentialsToken
        if (cached != null && now < clientCredentialsExpiry - 60) {
            return cached
        }

        Log.d(TAG, "getClientCredentialsToken: cache expired or empty, acquiring new token")
        val token = spotifyAuthManager.getClientCredentialsToken()
        if (token != null) {
            clientCredentialsToken = token
            clientCredentialsExpiry = now + 3600 // 1 hour
            Log.d(TAG, "getClientCredentialsToken: cached new token, expires at ${clientCredentialsExpiry}")
        } else {
            Log.e(TAG, "getClientCredentialsToken: failed to acquire token")
        }
        return token
    }

    // ── GraphQL Client Token Cache (for sp_dc operations) ───────────────

    /**
     * Cached client token for the GraphQL Partner API (sp_dc prong).
     * Lazily acquired on first GraphQL call and reused until it fails.
     */
    @Volatile
    private var cachedClientToken: String? = null

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Returns the current user's Spotify profile.
     *
     * Resolves the user ID from stored token metadata (username extracted
     * from the JWT during authentication).
     *
     * @return A [UserInfo] with the user's ID, or null if resolution fails.
     */
    suspend fun getCurrentUserProfile(): UserInfo? = withContext(Dispatchers.IO) {
        val username = tokenManager.getSpotifyUsername()
        if (username != null) {
            Log.d(TAG, "getCurrentUserProfile: resolved userId='$username'")
            return@withContext UserInfo(
                id = username,
                displayName = username,
                imageUrl = null,
            )
        }
        Log.e(TAG, "getCurrentUserProfile: could not resolve user ID")
        null
    }

    /**
     * Fetches the current user's playlists via the GraphQL `libraryV3` operation.
     *
     * This is a sp_dc-dependent operation (Prong 2). If the GraphQL request fails,
     * returns an empty list rather than throwing -- the user can still manually
     * sync individual playlists by ID.
     *
     * @param limit  Maximum number of playlists to return (default 50).
     * @param offset Zero-based index of the first playlist to return.
     * @return List of [SpotifyPlaylistItem].
     */
    suspend fun getUserPlaylists(
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
    ): List<SpotifyPlaylistItem> = withContext(Dispatchers.IO) {
        Log.d(TAG, "getUserPlaylists: limit=$limit, offset=$offset")

        // Use client_credentials + public Web API to fetch user's playlists
        val token = getClientCredentialsToken()
        if (token == null) {
            Log.e(TAG, "getUserPlaylists: no client_credentials token available")
            return@withContext emptyList()
        }

        val username = tokenManager.getSpotifyUsername()
        if (username.isNullOrEmpty()) {
            Log.e(TAG, "getUserPlaylists: no Spotify username stored, cannot fetch playlists")
            return@withContext emptyList()
        }

        try {
            val url = "$WEB_API_BASE/users/$username/playlists?limit=$limit&offset=$offset"
            Log.d(TAG, "getUserPlaylists: GET $url")

            val request = Request.Builder()
                .url(url)
                .get()
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string()
            Log.d(TAG, "getUserPlaylists: HTTP ${response.code}, body length=${responseBody?.length ?: 0}")

            if (!response.isSuccessful || responseBody == null) {
                Log.e(TAG, "getUserPlaylists: failed HTTP ${response.code}, body=${responseBody?.take(300)}")
                return@withContext emptyList()
            }

            val root = json.parseToJsonElement(responseBody).jsonObject
            val items = root["items"]?.jsonArray ?: return@withContext emptyList()

            val playlists = items.mapNotNull { element ->
                try {
                    val obj = element.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "Unknown"
                    val ownerObj = obj["owner"]?.jsonObject
                    val ownerId = ownerObj?.get("id")?.jsonPrimitive?.contentOrNull ?: ""
                    val ownerName = ownerObj?.get("display_name")?.jsonPrimitive?.contentOrNull
                    val images = obj["images"]?.jsonArray?.mapNotNull { imgEl ->
                        imgEl.jsonObject["url"]?.jsonPrimitive?.contentOrNull?.let { SpotifyImage(url = it) }
                    }
                    val trackCount = obj["tracks"]?.jsonObject?.get("total")?.jsonPrimitive?.intOrNull

                    SpotifyPlaylistItem(
                        id = id,
                        name = name,
                        owner = SpotifyOwner(id = ownerId, display_name = ownerName),
                        images = images,
                        tracks = trackCount?.let { SpotifyTracksRef(total = it) },
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "getUserPlaylists: failed to parse playlist item", e)
                    null
                }
            }

            Log.d(TAG, "getUserPlaylists: parsed ${playlists.size} playlists")
            playlists
        } catch (e: Exception) {
            Log.e(TAG, "getUserPlaylists: failed", e)
            emptyList()
        }
    }

    /**
     * Fetches all tracks in a specific playlist via the PUBLIC Web API.
     *
     * This is the core of Prong 1: uses a client_credentials token to call
     * api.spotify.com/v1/playlists/{id}/tracks. This works reliably for any
     * public or collaborative playlist without 429 blocks.
     *
     * Falls back to the sp_dc GraphQL approach if client_credentials fails.
     *
     * @param playlistId The Spotify playlist ID (without the "spotify:playlist:" prefix).
     * @return List of [SpotifyTrackItem].
     * @throws SpotifyApiException if all approaches fail.
     */
    suspend fun getPlaylistTracks(playlistId: String): List<SpotifyTrackItem> {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "getPlaylistTracks: playlistId=$playlistId (trying client_credentials first)")

            // Prong 1: Try client_credentials + public Web API
            val clientCredsTracks = tryGetPlaylistTracksViaWebApi(playlistId)
            if (clientCredsTracks != null) {
                Log.d(TAG, "getPlaylistTracks: got ${clientCredsTracks.size} tracks via Web API")
                return@withContext clientCredsTracks
            }

            // Prong 2: Fall back to sp_dc GraphQL
            Log.w(TAG, "getPlaylistTracks: Web API failed, falling back to GraphQL for $playlistId")
            val graphqlTracks = tryGetPlaylistTracksViaGraphQL(playlistId)
            if (graphqlTracks != null) {
                Log.d(TAG, "getPlaylistTracks: got ${graphqlTracks.size} tracks via GraphQL fallback")
                return@withContext graphqlTracks
            }

            Log.e(TAG, "getPlaylistTracks: both Web API and GraphQL failed for $playlistId")
            throw SpotifyApiException(0, "$WEB_API_BASE/playlists/$playlistId/tracks",
                "Failed to fetch tracks for playlist $playlistId via both Web API and GraphQL")
        }
    }

    /**
     * Fetches the user's Liked Songs from the library.
     *
     * Liked Songs are private and require the sp_dc token (Prong 2).
     * If the GraphQL request fails, returns an empty list gracefully.
     *
     * @param limit  Maximum number of tracks to return (default 50).
     * @param offset Zero-based index of the first track to return.
     * @return List of [SpotifyTrackItem].
     */
    suspend fun getLikedSongs(
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
    ): List<SpotifyTrackItem> = withContext(Dispatchers.IO) {
        Log.d(TAG, "getLikedSongs: limit=$limit, offset=$offset (via sp_dc GraphQL)")

        try {
            val variables = """
                {
                    "uri": "spotify:collection:tracks",
                    "offset": $offset,
                    "limit": $limit
                }
            """.trimIndent()

            val responseJson = executeGraphQL(
                operationName = "fetchPlaylist",
                variables = variables,
                hash = SpotifyAuthConfig.HASH_FETCH_PLAYLIST,
            )

            if (responseJson != null) {
                val tracks = parsePlaylistTracksGraphQLResponse(responseJson)
                Log.d(TAG, "getLikedSongs: got ${tracks.size} liked songs")
                return@withContext tracks
            }

            Log.w(TAG, "getLikedSongs: GraphQL returned null, returning empty list")
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "getLikedSongs: sp_dc/GraphQL failed, returning empty list", e)
            emptyList()
        }
    }

    /**
     * Finds the user's Spotify-generated Daily Mix playlists.
     *
     * Daily Mixes are owned by the "spotify" user and have names matching
     * the pattern "Daily Mix N". They are identified from the library response.
     *
     * If the library fetch fails (sp_dc issues), returns an empty list gracefully.
     *
     * @return List of [SpotifyPlaylistItem] representing Daily Mixes.
     */
    suspend fun getDailyMixes(): List<SpotifyPlaylistItem> {
        return try {
            getUserPlaylists().filter { playlist ->
                playlist.owner.id == "spotify" && DAILY_MIX_REGEX.matches(playlist.name)
            }.also { mixes ->
                Log.d(TAG, "getDailyMixes: found ${mixes.size} daily mixes: ${mixes.map { it.name }}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "getDailyMixes: failed to enumerate daily mixes, returning empty list", e)
            emptyList()
        }
    }

    // ── Prong 1: Client Credentials + Public Web API ────────────────────

    /**
     * Fetches playlist tracks via the public Spotify Web API using a client_credentials token.
     *
     * Paginates through all tracks (50 per page) and returns the complete list.
     * Returns null if the token cannot be acquired or the request fails.
     *
     * @param playlistId The Spotify playlist ID.
     * @return List of tracks, or null on failure.
     */
    private suspend fun tryGetPlaylistTracksViaWebApi(playlistId: String): List<SpotifyTrackItem>? {
        val token = getClientCredentialsToken()
        if (token == null) {
            Log.w(TAG, "tryGetPlaylistTracksViaWebApi: no client_credentials token")
            return null
        }

        val allTracks = mutableListOf<SpotifyTrackItem>()
        var url: String? = "$WEB_API_BASE/playlists/$playlistId/tracks?limit=50&offset=0" +
            "&fields=items(track(id,name,uri,duration_ms,artists(id,name),album(id,name,images))),next"

        while (url != null) {
            Log.d(TAG, "tryGetPlaylistTracksViaWebApi: GET $url")

            val request = Request.Builder()
                .url(url)
                .get()
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseCode = response.code
            val responseBody = response.body?.string()

            Log.d(TAG, "tryGetPlaylistTracksViaWebApi: HTTP $responseCode, " +
                "body length=${responseBody?.length ?: 0}")

            if (!response.isSuccessful) {
                Log.e(TAG, "tryGetPlaylistTracksViaWebApi: HTTP $responseCode for $playlistId")
                Log.e(TAG, "tryGetPlaylistTracksViaWebApi: body (first 500): ${responseBody?.take(500)}")

                // If 404, the playlist might be private -- return null to try GraphQL
                if (responseCode == 404) return null

                // If 401, token might have expired mid-pagination -- try refreshing once
                if (responseCode == 401) {
                    clientCredentialsToken = null
                    val refreshedToken = getClientCredentialsToken() ?: return null
                    Log.d(TAG, "tryGetPlaylistTracksViaWebApi: retrying with refreshed token")
                    val retryRequest = Request.Builder()
                        .url(url!!)
                        .get()
                        .header("Authorization", "Bearer $refreshedToken")
                        .header("Accept", "application/json")
                        .build()
                    val retryResponse = okHttpClient.newCall(retryRequest).execute()
                    if (!retryResponse.isSuccessful) {
                        Log.e(TAG, "tryGetPlaylistTracksViaWebApi: retry also failed HTTP ${retryResponse.code}")
                        return null
                    }
                    val retryBody = retryResponse.body?.string() ?: return null
                    val parsed = parseWebApiPlaylistPage(retryBody)
                    allTracks.addAll(parsed.first)
                    url = parsed.second
                    continue
                }

                // For 429 or other errors, return null to try fallback
                return null
            }

            if (responseBody == null) return null

            val parsed = parseWebApiPlaylistPage(responseBody)
            allTracks.addAll(parsed.first)
            url = parsed.second

            Log.d(TAG, "tryGetPlaylistTracksViaWebApi: page returned ${parsed.first.size} tracks, " +
                "total so far=${allTracks.size}, hasNext=${url != null}")
        }

        return allTracks
    }

    /**
     * Parses a single page of the Web API playlist tracks response.
     *
     * @param responseBody The raw JSON response body.
     * @return Pair of (tracks on this page, next page URL or null).
     */
    private fun parseWebApiPlaylistPage(responseBody: String): Pair<List<SpotifyTrackItem>, String?> {
        return try {
            val root = json.parseToJsonElement(responseBody).jsonObject
            val nextUrl = root["next"]?.jsonPrimitive?.contentOrNull
            val items = root["items"]?.jsonArray ?: return Pair(emptyList(), null)

            val tracks = items.mapNotNull { element ->
                try {
                    val wrapper = element.jsonObject
                    val trackObj = wrapper["track"]?.jsonObject ?: return@mapNotNull null

                    val id = trackObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val name = trackObj["name"]?.jsonPrimitive?.contentOrNull ?: "Unknown"
                    val uri = trackObj["uri"]?.jsonPrimitive?.contentOrNull ?: "spotify:track:$id"
                    val durationMs = trackObj["duration_ms"]?.jsonPrimitive?.longOrNull ?: 0L

                    // Parse artists
                    val artists = trackObj["artists"]?.jsonArray?.mapNotNull { artistEl ->
                        val artistObj = artistEl.jsonObject
                        val artistId = artistObj["id"]?.jsonPrimitive?.contentOrNull ?: ""
                        val artistName = artistObj["name"]?.jsonPrimitive?.contentOrNull
                            ?: return@mapNotNull null
                        SpotifyArtist(id = artistId, name = artistName)
                    } ?: emptyList()

                    // Parse album
                    val albumObj = trackObj["album"]?.jsonObject
                    val album = if (albumObj != null) {
                        val albumId = albumObj["id"]?.jsonPrimitive?.contentOrNull ?: ""
                        val albumName = albumObj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                        val albumImages = albumObj["images"]?.jsonArray?.mapNotNull { imgEl ->
                            val imgUrl = imgEl.jsonObject["url"]?.jsonPrimitive?.contentOrNull
                            if (imgUrl != null) SpotifyImage(url = imgUrl) else null
                        }
                        SpotifyAlbum(id = albumId, name = albumName, images = albumImages)
                    } else {
                        null
                    }

                    SpotifyTrackItem(
                        track = SpotifyTrackObject(
                            id = id,
                            name = name,
                            artists = artists,
                            album = album,
                            duration_ms = durationMs,
                            uri = uri,
                        ),
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "parseWebApiPlaylistPage: failed to parse track item", e)
                    null
                }
            }

            Pair(tracks, nextUrl)
        } catch (e: Exception) {
            Log.e(TAG, "parseWebApiPlaylistPage: failed to parse response", e)
            Pair(emptyList(), null)
        }
    }

    // ── Prong 2: sp_dc + GraphQL Partner API (fallback) ─────────────────

    /**
     * Fetches playlist tracks via the GraphQL Partner API using the sp_dc token.
     *
     * This is the fallback for private playlists that aren't accessible via
     * the public Web API. Returns null if the GraphQL approach fails entirely.
     *
     * @param playlistId The Spotify playlist ID.
     * @return List of tracks, or null on failure.
     */
    private suspend fun tryGetPlaylistTracksViaGraphQL(playlistId: String): List<SpotifyTrackItem>? {
        val uri = "spotify:playlist:$playlistId"
        val allTracks = mutableListOf<SpotifyTrackItem>()
        var currentOffset = 0
        val pageSize = 100

        while (true) {
            val variables = """
                {
                    "uri": "$uri",
                    "offset": $currentOffset,
                    "limit": $pageSize
                }
            """.trimIndent()

            val responseJson = executeGraphQL(
                operationName = "fetchPlaylist",
                variables = variables,
                hash = SpotifyAuthConfig.HASH_FETCH_PLAYLIST,
            ) ?: break

            val tracks = parsePlaylistTracksGraphQLResponse(responseJson)
            Log.d(TAG, "tryGetPlaylistTracksViaGraphQL: offset=$currentOffset, got ${tracks.size} tracks")

            if (tracks.isEmpty()) break
            allTracks.addAll(tracks)

            if (tracks.size < pageSize) break
            currentOffset += pageSize
        }

        return if (allTracks.isNotEmpty()) allTracks else null
    }

    // ── GraphQL execution ──────────────────────────────────────────────

    /**
     * Executes a GraphQL persisted query against the Partner API.
     *
     * Builds the full URL with query parameters (operationName, variables,
     * extensions with the persisted query hash) and sends a GET request with
     * all required headers (Authorization, Client-Token, App-Platform, etc.).
     *
     * Handles token refresh on 401 and client token re-acquisition on 400/401.
     *
     * @param operationName The GraphQL operation name (e.g., "libraryV3").
     * @param variables     JSON string of query variables.
     * @param hash          The sha256 persisted query hash.
     * @return Parsed [JsonObject] of the response, or null on failure.
     */
    private suspend fun executeGraphQL(
        operationName: String,
        variables: String,
        hash: String,
    ): JsonObject? {
        val accessToken = tokenManager.getSpotifyAccessToken()
        if (accessToken == null) {
            Log.e(TAG, "executeGraphQL: no access token available")
            return null
        }

        val clientToken = ensureClientToken()
        if (clientToken == null) {
            Log.e(TAG, "executeGraphQL: could not acquire client token")
            return null
        }

        val encodedVariables = URLEncoder.encode(variables, "UTF-8")
        val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"$hash"}}"""
        val encodedExtensions = URLEncoder.encode(extensions, "UTF-8")

        val url = "${SpotifyAuthConfig.GRAPHQL_ENDPOINT}" +
            "?operationName=$operationName" +
            "&variables=$encodedVariables" +
            "&extensions=$encodedExtensions"

        Log.d(TAG, "executeGraphQL: $operationName (hash=${hash.take(16)}...)")

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $accessToken")
            .header("Client-Token", clientToken)
            .header("Accept", "application/json")
            .header("App-Platform", "WebPlayer")
            .header("Spotify-App-Version", SpotifyAuthConfig.CLIENT_VERSION)
            .header("Origin", "https://open.spotify.com")
            .header("Referer", "https://open.spotify.com/")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36",
            )
            .build()

        val response = okHttpClient.newCall(request).execute()
        val responseCode = response.code
        val responseBody = response.body?.string()

        Log.d(TAG, "executeGraphQL: $operationName -> HTTP $responseCode, " +
            "body length=${responseBody?.length ?: 0}")

        if (!response.isSuccessful) {
            Log.e(TAG, "executeGraphQL: HTTP $responseCode for $operationName")
            Log.e(TAG, "executeGraphQL: response body (first 1000 chars): ${responseBody?.take(1000)}")

            // If we get 401, try refreshing the access token and retrying once
            if (responseCode == 401) {
                Log.w(TAG, "executeGraphQL: 401, attempting token refresh and retry")
                val refreshedToken = tokenManager.forceRefreshSpotifyAccessToken()
                if (refreshedToken != null) {
                    cachedClientToken = null
                    val newClientToken = ensureClientToken()
                    if (newClientToken != null) {
                        return retryGraphQL(operationName, refreshedToken, newClientToken, url)
                    }
                }
            }

            // If we get 400, the client token may be stale; try re-acquiring
            if (responseCode == 400) {
                Log.w(TAG, "executeGraphQL: 400, attempting client token refresh and retry")
                cachedClientToken = null
                val newClientToken = ensureClientToken()
                if (newClientToken != null) {
                    return retryGraphQL(operationName, accessToken, newClientToken, url)
                }
            }

            return null
        }

        if (responseBody == null) {
            Log.e(TAG, "executeGraphQL: null response body for $operationName")
            return null
        }

        Log.d(TAG, "executeGraphQL: $operationName response (first 2000 chars): ${responseBody.take(2000)}")

        return try {
            val parsed = json.parseToJsonElement(responseBody).jsonObject
            val errors = parsed["errors"]
            if (errors != null) {
                Log.w(TAG, "executeGraphQL: GraphQL errors in $operationName: $errors")
            }
            parsed
        } catch (e: Exception) {
            Log.e(TAG, "executeGraphQL: failed to parse response for $operationName", e)
            null
        }
    }

    /**
     * Retries a GraphQL request with updated tokens.
     * Used after token refresh or client token re-acquisition.
     */
    private fun retryGraphQL(
        operationName: String,
        accessToken: String,
        clientToken: String,
        url: String,
    ): JsonObject? {
        Log.d(TAG, "retryGraphQL: retrying $operationName with refreshed tokens")

        val retryRequest = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $accessToken")
            .header("Client-Token", clientToken)
            .header("Accept", "application/json")
            .header("App-Platform", "WebPlayer")
            .header("Spotify-App-Version", SpotifyAuthConfig.CLIENT_VERSION)
            .header("Origin", "https://open.spotify.com")
            .header("Referer", "https://open.spotify.com/")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36",
            )
            .build()

        val retryResponse = okHttpClient.newCall(retryRequest).execute()
        val retryBody = retryResponse.body?.string()
        Log.d(TAG, "retryGraphQL: $operationName -> HTTP ${retryResponse.code}, " +
            "body length=${retryBody?.length ?: 0}")

        if (!retryResponse.isSuccessful || retryBody == null) {
            Log.e(TAG, "retryGraphQL: retry also failed for $operationName: " +
                "HTTP ${retryResponse.code}, body=${retryBody?.take(500)}")
            return null
        }

        return try {
            json.parseToJsonElement(retryBody).jsonObject
        } catch (e: Exception) {
            Log.e(TAG, "retryGraphQL: parse failed for $operationName", e)
            null
        }
    }

    /**
     * Ensures a valid client token is available for the GraphQL Partner API.
     *
     * @return The client token string, or null if acquisition fails.
     */
    private suspend fun ensureClientToken(): String? {
        cachedClientToken?.let { return it }

        var clientId = tokenManager.getSpotifyClientId()

        if (clientId == null) {
            Log.w(TAG, "ensureClientToken: no clientId in storage, forcing token refresh to populate it")
            tokenManager.forceRefreshSpotifyAccessToken()
            clientId = tokenManager.getSpotifyClientId()
        }

        if (clientId == null) {
            Log.e(TAG, "ensureClientToken: clientId still null after token refresh")
            return null
        }

        Log.d(TAG, "ensureClientToken: acquiring client token for clientId='$clientId'")
        val token = spotifyAuthManager.getClientToken(clientId)
        if (token != null) {
            Log.d(TAG, "ensureClientToken: acquired client token (${token.take(20)}...)")
            cachedClientToken = token
        } else {
            Log.e(TAG, "ensureClientToken: failed to acquire client token")
        }
        return token
    }

    // ── Response parsing (GraphQL) ──────────────────────────────────────

    /**
     * Parses the `libraryV3` GraphQL response into [SpotifyPlaylistItem] objects.
     */
    private fun parseLibraryResponse(responseJson: JsonObject): List<SpotifyPlaylistItem> {
        return try {
            val items = responseJson["data"]
                ?.jsonObject?.get("me")
                ?.jsonObject?.get("libraryV3")
                ?.jsonObject?.get("items")
                ?.jsonArray

            if (items == null) {
                Log.w(TAG, "parseLibraryResponse: could not find data.me.libraryV3.items")
                Log.d(TAG, "parseLibraryResponse: top-level keys: ${responseJson.keys}")
                val dataKeys = responseJson["data"]?.jsonObject?.keys
                Log.d(TAG, "parseLibraryResponse: data keys: $dataKeys")
                return emptyList()
            }

            Log.d(TAG, "parseLibraryResponse: found ${items.size} library items")

            items.mapNotNull { element ->
                try {
                    val wrapper = element.jsonObject
                    val item = wrapper["item"]?.jsonObject ?: return@mapNotNull null
                    val typeName = item["__typename"]?.jsonPrimitive?.contentOrNull
                    val data = item["data"]?.jsonObject ?: return@mapNotNull null

                    val dataTypeName = data["__typename"]?.jsonPrimitive?.contentOrNull
                    if (dataTypeName != "Playlist") {
                        Log.d(TAG, "parseLibraryResponse: skipping item type: $typeName/$dataTypeName")
                        return@mapNotNull null
                    }

                    val uri = data["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    if (!uri.startsWith("spotify:playlist:")) return@mapNotNull null

                    val playlistId = uri.removePrefix("spotify:playlist:")
                    val name = data["name"]?.jsonPrimitive?.contentOrNull ?: "Untitled"

                    val ownerUsername = data["ownerV2"]
                        ?.jsonObject?.get("data")
                        ?.jsonObject?.get("username")
                        ?.jsonPrimitive?.contentOrNull ?: ""

                    val imageUrl = data["images"]
                        ?.jsonObject?.get("items")
                        ?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("sources")
                        ?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("url")
                        ?.jsonPrimitive?.contentOrNull

                    val images = if (imageUrl != null) {
                        listOf(SpotifyImage(url = imageUrl))
                    } else {
                        null
                    }

                    val totalCount = data["content"]
                        ?.jsonObject?.get("totalCount")
                        ?.jsonPrimitive?.intOrNull ?: 0

                    SpotifyPlaylistItem(
                        id = playlistId,
                        name = name,
                        owner = SpotifyOwner(id = ownerUsername),
                        images = images,
                        tracks = SpotifyTracksRef(total = totalCount),
                    ).also {
                        Log.d(TAG, "parseLibraryResponse: playlist '${it.name}' " +
                            "(id=${it.id}, owner=${it.owner.id}, tracks=$totalCount)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "parseLibraryResponse: failed to parse item", e)
                    null
                }
            }.also { playlists ->
                Log.d(TAG, "parseLibraryResponse: parsed ${playlists.size} playlists total")
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseLibraryResponse: failed to parse response", e)
            emptyList()
        }
    }

    /**
     * Parses the `fetchPlaylist` GraphQL response into [SpotifyTrackItem] objects.
     */
    private fun parsePlaylistTracksGraphQLResponse(responseJson: JsonObject): List<SpotifyTrackItem> {
        return try {
            val items = responseJson["data"]
                ?.jsonObject?.get("playlistV2")
                ?.jsonObject?.get("content")
                ?.jsonObject?.get("items")
                ?.jsonArray

            if (items == null) {
                Log.w(TAG, "parsePlaylistTracksGraphQLResponse: could not find data.playlistV2.content.items")
                val dataKeys = responseJson["data"]?.jsonObject?.keys
                Log.d(TAG, "parsePlaylistTracksGraphQLResponse: data keys: $dataKeys")
                return emptyList()
            }

            Log.d(TAG, "parsePlaylistTracksGraphQLResponse: found ${items.size} items")

            items.mapNotNull { element ->
                try {
                    val wrapper = element.jsonObject
                    val itemV2 = wrapper["itemV2"]?.jsonObject
                    val data = itemV2?.get("data")?.jsonObject ?: return@mapNotNull null

                    val typeName = data["__typename"]?.jsonPrimitive?.contentOrNull
                    if (typeName != null && typeName != "Track" && typeName != "TrackResponseWrapper") {
                        Log.d(TAG, "parsePlaylistTracksGraphQLResponse: skipping type: $typeName")
                        return@mapNotNull null
                    }

                    val uri = data["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    if (!uri.startsWith("spotify:track:")) return@mapNotNull null

                    val trackId = uri.removePrefix("spotify:track:")
                    val name = data["name"]?.jsonPrimitive?.contentOrNull ?: "Unknown"

                    val durationMs = data["trackDuration"]
                        ?.jsonObject?.get("totalMilliseconds")
                        ?.jsonPrimitive?.longOrNull
                        ?: data["duration"]
                            ?.jsonObject?.get("totalMilliseconds")
                            ?.jsonPrimitive?.longOrNull
                        ?: 0L

                    val artistItems = data["artists"]
                        ?.jsonObject?.get("items")
                        ?.jsonArray

                    val artists = artistItems?.mapNotNull { artistElement ->
                        val artistObj = artistElement.jsonObject
                        val artistName = artistObj["profile"]
                            ?.jsonObject?.get("name")
                            ?.jsonPrimitive?.contentOrNull
                            ?: return@mapNotNull null
                        val artistUri = artistObj["uri"]?.jsonPrimitive?.contentOrNull ?: ""
                        val artistId = if (artistUri.startsWith("spotify:artist:")) {
                            artistUri.removePrefix("spotify:artist:")
                        } else {
                            ""
                        }
                        SpotifyArtist(id = artistId, name = artistName)
                    } ?: emptyList()

                    val albumData = data["albumOfTrack"]?.jsonObject
                    val album = if (albumData != null) {
                        val albumUri = albumData["uri"]?.jsonPrimitive?.contentOrNull ?: ""
                        val albumId = if (albumUri.startsWith("spotify:album:")) {
                            albumUri.removePrefix("spotify:album:")
                        } else {
                            ""
                        }
                        val albumName = albumData["name"]?.jsonPrimitive?.contentOrNull ?: ""
                        val coverArtUrl = albumData["coverArt"]
                            ?.jsonObject?.get("sources")
                            ?.jsonArray?.firstOrNull()
                            ?.jsonObject?.get("url")
                            ?.jsonPrimitive?.contentOrNull
                        val albumImages = if (coverArtUrl != null) {
                            listOf(SpotifyImage(url = coverArtUrl))
                        } else {
                            null
                        }
                        SpotifyAlbum(id = albumId, name = albumName, images = albumImages)
                    } else {
                        null
                    }

                    SpotifyTrackItem(
                        track = SpotifyTrackObject(
                            id = trackId,
                            name = name,
                            artists = artists,
                            album = album,
                            duration_ms = durationMs,
                            uri = uri,
                        ),
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "parsePlaylistTracksGraphQLResponse: failed to parse track item", e)
                    null
                }
            }.also { tracks ->
                Log.d(TAG, "parsePlaylistTracksGraphQLResponse: parsed ${tracks.size} tracks")
            }
        } catch (e: Exception) {
            Log.e(TAG, "parsePlaylistTracksGraphQLResponse: failed to parse response", e)
            emptyList()
        }
    }
}
