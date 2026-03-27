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
 * High-level client for the Spotify GraphQL Partner API.
 *
 * Uses Spotify's internal GraphQL Partner API (api-partner.spotify.com/pathfinder/v1/query)
 * which is the same backend used by the official Spotify web player. This is the ONLY
 * confirmed working approach for sp_dc-derived access tokens.
 *
 * The spclient approach (apresolve.spotify.com -> spclient endpoints) was abandoned
 * because ALL endpoints return HTTP 404.
 *
 * The public REST API (api.spotify.com/v1) was abandoned because it permanently
 * 429-blocks sp_dc tokens on playlist endpoints.
 *
 * Required credentials:
 * 1. Access token -- obtained from the sp_dc cookie via [TokenManager]
 * 2. Client token -- obtained from clienttoken.spotify.com via [SpotifyAuthManager]
 *
 * All GraphQL queries use persisted query hashes (sha256) which are scraped from
 * the Spotify web player JS bundles. These hashes are stable for weeks but will
 * need updating when Spotify deploys new web player versions.
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

        /** Regex pattern for identifying Spotify-generated Daily Mix playlists. */
        private val DAILY_MIX_REGEX = Regex("""Daily Mix \d+""")
    }

    /**
     * Cached client token for the GraphQL Partner API.
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
     * Returns all playlists from the user's library including Spotify-generated
     * playlists (Daily Mixes, Discover Weekly, etc.).
     *
     * @param limit  Maximum number of playlists to return (default 50).
     * @param offset Zero-based index of the first playlist to return.
     * @return List of [SpotifyPlaylistItem].
     * @throws SpotifyApiException if the GraphQL request fails.
     */
    suspend fun getUserPlaylists(
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
    ): List<SpotifyPlaylistItem> = withContext(Dispatchers.IO) {
        Log.d(TAG, "getUserPlaylists: limit=$limit, offset=$offset")

        val variables = """
            {
                "filters": [],
                "order": null,
                "textFilter": "",
                "features": ["LIKED_SONGS", "YOUR_EPISODES"],
                "limit": $limit,
                "offset": $offset,
                "flatten": false,
                "expandedFolders": [],
                "folderUri": null,
                "includeFoldersWhenFlattening": true
            }
        """.trimIndent()

        val responseJson = executeGraphQL(
            operationName = "libraryV3",
            variables = variables,
            hash = SpotifyAuthConfig.HASH_LIBRARY_V3,
        ) ?: throw SpotifyApiException(0, SpotifyAuthConfig.GRAPHQL_ENDPOINT, "GraphQL libraryV3 returned null")

        parseLibraryResponse(responseJson)
    }

    /**
     * Fetches all tracks in a specific playlist via the GraphQL `fetchPlaylist` operation.
     *
     * @param playlistId The Spotify playlist ID (without the "spotify:playlist:" prefix).
     * @return List of [SpotifyTrackItem].
     * @throws SpotifyApiException if the GraphQL request fails.
     */
    suspend fun getPlaylistTracks(playlistId: String): List<SpotifyTrackItem> {
        return withContext(Dispatchers.IO) {
            val uri = "spotify:playlist:$playlistId"
            Log.d(TAG, "getPlaylistTracks: uri=$uri")

            val allTracks = mutableListOf<SpotifyTrackItem>()
            var currentOffset = 0
            val pageSize = 100

            // Paginate through all tracks in the playlist
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

                val tracks = parsePlaylistTracksResponse(responseJson)
                Log.d(TAG, "getPlaylistTracks: offset=$currentOffset, got ${tracks.size} tracks")

                if (tracks.isEmpty()) break
                allTracks.addAll(tracks)

                // If we got fewer than the page size, we've reached the end
                if (tracks.size < pageSize) break
                currentOffset += pageSize
            }

            Log.d(TAG, "getPlaylistTracks: total ${allTracks.size} tracks for $playlistId")
            allTracks
        }
    }

    /**
     * Fetches the user's Liked Songs from the library.
     *
     * Liked Songs appear in the libraryV3 response as a "CollectionItem" with
     * a special URI. This method fetches them by looking for the collection
     * item in the library response and then fetching its contents.
     *
     * Note: The libraryV3 operation returns a reference to Liked Songs but not
     * the actual tracks. We use the fetchPlaylist hash with the collection URI
     * as a fallback, or parse inline track data if available.
     *
     * @param limit  Maximum number of tracks to return (default 50).
     * @param offset Zero-based index of the first track to return.
     * @return List of [SpotifyTrackItem].
     */
    suspend fun getLikedSongs(
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
    ): List<SpotifyTrackItem> = withContext(Dispatchers.IO) {
        Log.d(TAG, "getLikedSongs: limit=$limit, offset=$offset")

        // The liked songs collection URI uses a special format
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
            val tracks = parsePlaylistTracksResponse(responseJson)
            Log.d(TAG, "getLikedSongs: got ${tracks.size} liked songs")
            return@withContext tracks
        }

        Log.w(TAG, "getLikedSongs: GraphQL returned null, returning empty list")
        emptyList()
    }

    /**
     * Finds the user's Spotify-generated Daily Mix playlists.
     *
     * Daily Mixes are owned by the "spotify" user and have names matching
     * the pattern "Daily Mix N". They are identified from the library response.
     *
     * @return List of [SpotifyPlaylistItem] representing Daily Mixes, or empty
     *         if none are found or the request fails.
     */
    suspend fun getDailyMixes(): List<SpotifyPlaylistItem> {
        return getUserPlaylists().filter { playlist ->
            playlist.owner.id == "spotify" && DAILY_MIX_REGEX.matches(playlist.name)
        }.also { mixes ->
            Log.d(TAG, "getDailyMixes: found ${mixes.size} daily mixes: ${mixes.map { it.name }}")
        }
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
                    // Also refresh the client token since it may be tied to the old access token
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

        // Log first 2000 chars for debugging
        Log.d(TAG, "executeGraphQL: $operationName response (first 2000 chars): ${responseBody.take(2000)}")

        return try {
            val parsed = json.parseToJsonElement(responseBody).jsonObject
            // Check for GraphQL-level errors
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
     * Ensures a valid client token is available, acquiring one if needed.
     *
     * The client token is obtained from Spotify's clienttoken endpoint using the
     * client ID that was returned in the original sp_dc token response.
     *
     * If no clientId is in storage (e.g., token was saved by older code before
     * the GraphQL migration), forces a token refresh which will populate the
     * clientId in the scope field.
     *
     * @return The client token string, or null if acquisition fails.
     */
    private suspend fun ensureClientToken(): String? {
        cachedClientToken?.let { return it }

        var clientId = tokenManager.getSpotifyClientId()

        // If no clientId in storage, the token was saved by older code.
        // Force a refresh which will re-pack the scope with "username|clientId".
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

    // ── Response parsing ────────────────────────────────────────────────

    /**
     * Parses the `libraryV3` GraphQL response into [SpotifyPlaylistItem] objects.
     *
     * Response structure:
     * ```json
     * {
     *   "data": {
     *     "me": {
     *       "libraryV3": {
     *         "items": [
     *           {
     *             "item": {
     *               "__typename": "PlaylistResponseWrapper",
     *               "data": {
     *                 "__typename": "Playlist",
     *                 "uri": "spotify:playlist:xxx",
     *                 "name": "...",
     *                 "ownerV2": { "data": { "username": "..." } },
     *                 "images": { "items": [{ "sources": [{ "url": "..." }] }] },
     *                 "content": { "totalCount": 42 }
     *               }
     *             }
     *           }
     *         ],
     *         "totalCount": 10
     *       }
     *     }
     *   }
     * }
     * ```
     *
     * All field access is null-safe; if Spotify changes the schema, parsing
     * returns empty lists rather than crashing.
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

                    // We only want playlists, not albums, artists, etc.
                    val dataTypeName = data["__typename"]?.jsonPrimitive?.contentOrNull
                    if (dataTypeName != "Playlist") {
                        Log.d(TAG, "parseLibraryResponse: skipping item type: $typeName/$dataTypeName")
                        return@mapNotNull null
                    }

                    val uri = data["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    if (!uri.startsWith("spotify:playlist:")) return@mapNotNull null

                    val playlistId = uri.removePrefix("spotify:playlist:")
                    val name = data["name"]?.jsonPrimitive?.contentOrNull ?: "Untitled"

                    // Extract owner username
                    val ownerUsername = data["ownerV2"]
                        ?.jsonObject?.get("data")
                        ?.jsonObject?.get("username")
                        ?.jsonPrimitive?.contentOrNull ?: ""

                    // Extract first image URL
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

                    // Extract track count
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
     *
     * Response structure:
     * ```json
     * {
     *   "data": {
     *     "playlistV2": {
     *       "content": {
     *         "items": [
     *           {
     *             "itemV2": {
     *               "data": {
     *                 "__typename": "TrackResponseWrapper" or "Track",
     *                 "uri": "spotify:track:xxx",
     *                 "name": "...",
     *                 "trackDuration": { "totalMilliseconds": 240000 },
     *                 "albumOfTrack": {
     *                   "uri": "spotify:album:xxx",
     *                   "name": "...",
     *                   "coverArt": { "sources": [{ "url": "..." }] }
     *                 },
     *                 "artists": {
     *                   "items": [{ "uri": "...", "profile": { "name": "..." } }]
     *                 }
     *               }
     *             }
     *           }
     *         ],
     *         "totalCount": 50
     *       }
     *     }
     *   }
     * }
     * ```
     */
    private fun parsePlaylistTracksResponse(responseJson: JsonObject): List<SpotifyTrackItem> {
        return try {
            val items = responseJson["data"]
                ?.jsonObject?.get("playlistV2")
                ?.jsonObject?.get("content")
                ?.jsonObject?.get("items")
                ?.jsonArray

            if (items == null) {
                Log.w(TAG, "parsePlaylistTracksResponse: could not find data.playlistV2.content.items")
                val dataKeys = responseJson["data"]?.jsonObject?.keys
                Log.d(TAG, "parsePlaylistTracksResponse: data keys: $dataKeys")
                return emptyList()
            }

            Log.d(TAG, "parsePlaylistTracksResponse: found ${items.size} items")

            items.mapNotNull { element ->
                try {
                    val wrapper = element.jsonObject
                    val itemV2 = wrapper["itemV2"]?.jsonObject
                    val data = itemV2?.get("data")?.jsonObject ?: return@mapNotNull null

                    val typeName = data["__typename"]?.jsonPrimitive?.contentOrNull
                    // Accept both "Track" and "TrackResponseWrapper" types
                    if (typeName != null && typeName != "Track" && typeName != "TrackResponseWrapper") {
                        Log.d(TAG, "parsePlaylistTracksResponse: skipping type: $typeName")
                        return@mapNotNull null
                    }

                    val uri = data["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    if (!uri.startsWith("spotify:track:")) return@mapNotNull null

                    val trackId = uri.removePrefix("spotify:track:")
                    val name = data["name"]?.jsonPrimitive?.contentOrNull ?: "Unknown"

                    // Parse duration
                    val durationMs = data["trackDuration"]
                        ?.jsonObject?.get("totalMilliseconds")
                        ?.jsonPrimitive?.longOrNull
                        ?: data["duration"]
                            ?.jsonObject?.get("totalMilliseconds")
                            ?.jsonPrimitive?.longOrNull
                        ?: 0L

                    // Parse artists
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

                    // Parse album
                    val albumData = data["albumOfTrack"]?.jsonObject
                    val album = if (albumData != null) {
                        val albumUri = albumData["uri"]?.jsonPrimitive?.contentOrNull ?: ""
                        val albumId = if (albumUri.startsWith("spotify:album:")) {
                            albumUri.removePrefix("spotify:album:")
                        } else {
                            ""
                        }
                        val albumName = albumData["name"]?.jsonPrimitive?.contentOrNull ?: ""

                        // Extract cover art URL
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
                    Log.w(TAG, "parsePlaylistTracksResponse: failed to parse track item", e)
                    null
                }
            }.also { tracks ->
                Log.d(TAG, "parsePlaylistTracksResponse: parsed ${tracks.size} tracks")
            }
        } catch (e: Exception) {
            Log.e(TAG, "parsePlaylistTracksResponse: failed to parse response", e)
            emptyList()
        }
    }
}
