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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import com.stash.core.model.SyncResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    private fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000

    companion object {
        private const val TAG = "StashSync"
        private const val DEFAULT_LIMIT = 50

        /** Base URL for the Spotify Web API v1 (used with client_credentials tokens). */
        private const val WEB_API_BASE = "https://api.spotify.com/v1"

        /**
         * How long the Web API playlist-tracks breaker stays open after a 429.
         * Matches Spotify's observed `Retry-After: 86400` (24h) for
         * client_credentials tokens on these endpoints.
         */
        private const val WEB_API_RATE_LIMIT_COOLDOWN_SEC = 24L * 60 * 60

        /** Regex pattern for identifying Spotify-generated Daily Mix playlists. */
        private val DAILY_MIX_REGEX = Regex("""Daily Mix \d+""")

        /**
         * Names of Spotify-generated personalized playlists that appear in the
         * home feed. These are all owned by "spotify" and rotate on various
         * schedules. See [isSpotifyMix] for the full keep-decision (this set is
         * one branch of it).
         */
        private val SPOTIFY_MIX_NAMES = setOf(
            "discover weekly",
            "release radar",
            "on repeat",
            "repeat rewind",
            "time capsule",
            "daylist",
        )

        /**
         * Whether a home-feed playlist is a Spotify-personalized mix worth
         * syncing. Keeps "Daily Mix N", the known named mixes, and — the
         * locale-proof catch-all — anything owned by "spotify" (Your Top Songs,
         * Blend, Made-For-You mood mixes, "This Is <artist>": names vary by
         * locale but all are spotify-owned).
         *
         * Note the caller defaults [ownerId] to "spotify" when the home item
         * carries no owner data, so owner-less items also take the catch-all.
         *
         * ponytail: owner rule first; add a deny-set only if device shows
         * editorial leakage (e.g. "Today's Top Hits" surfacing in the home feed).
         */
        internal fun isSpotifyMix(name: String, ownerId: String): Boolean {
            if (DAILY_MIX_REGEX.matches(name)) return true
            if (name.lowercase(java.util.Locale.ROOT) in SPOTIFY_MIX_NAMES) return true
            return ownerId == "spotify"
        }
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
     * Circuit-breaker for the public Web API playlist-tracks endpoint.
     *
     * `getPlaylistTracks` tries client_credentials + Web API first, then falls
     * back to sp_dc GraphQL. For editorial (`37i9…`) playlists the Web API
     * hard-429s with `Retry-After: 86400` (24h). Without this breaker every
     * playlist page in a sync pays a guaranteed-to-429 round-trip before the
     * GraphQL fallback — one affected sync logged 64 such wasted calls, a
     * meaningful slice of the >10-min runtime WorkManager then kills.
     *
     * Once a 429 is seen we skip the Web API prong until this epoch-seconds
     * deadline, going straight to GraphQL. Auto-recovers after the window so
     * a genuine transient 429 doesn't disable the prong forever.
     */
    @Volatile
    private var webApiRateLimitedUntil: Long = 0

    /**
     * Serializes refreshes of [clientCredentialsToken]. Concurrent callers
     * (e.g. parallel [searchTracks] calls) would otherwise each fire a token
     * request when the cache expires; the mutex + in-lock double-check makes
     * exactly one refresh win and everyone else reuse its result.
     */
    private val tokenMutex = Mutex()

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

        // Serialize the refresh so concurrent callers don't each acquire a
        // fresh token. Double-check inside the lock: a caller that blocked
        // here while another refreshed reuses the now-valid cached token.
        return tokenMutex.withLock {
            val recheckNow = System.currentTimeMillis() / 1000
            val recached = clientCredentialsToken
            if (recached != null && recheckNow < clientCredentialsExpiry - 60) {
                return@withLock recached
            }

            Log.d(TAG, "getClientCredentialsToken: cache expired or empty, acquiring new token")
            val token = spotifyAuthManager.getClientCredentialsToken()
            if (token != null) {
                clientCredentialsToken = token
                clientCredentialsExpiry = recheckNow + 3600 // 1 hour
                Log.d(TAG, "getClientCredentialsToken: cached new token, expires at ${clientCredentialsExpiry}")
            } else {
                Log.e(TAG, "getClientCredentialsToken: failed to acquire token")
            }
            token
        }
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
            Log.d(TAG, "getCurrentUserProfile: userId resolved (${username.length} chars)")
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
     * Fetches one page of the user's library via the GraphQL `libraryV3`
     * operation — playlists AND folder URIs (see [SpotifyLibraryPage]).
     *
     * Uses sp_dc-derived access token + client token (Prong 2).
     * The previous Web API endpoint (/v1/users/{id}/playlists) was removed
     * by Spotify in February 2026.
     *
     * @param folderUri When non-null, lists the CONTENTS of that folder
     *   instead of the library root — the same persisted query the web
     *   client uses when a sidebar folder is opened. Required because
     *   libraryV3 is hierarchical: folder-filed playlists never appear at
     *   the root (issues #48/#26/#80/#136).
     */
    suspend fun getUserPlaylists(
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
        folderUri: String? = null,
    ): SpotifyLibraryPage = withContext(Dispatchers.IO) {
        Log.d(
            TAG,
            "getUserPlaylists: limit=$limit, offset=$offset, " +
                "folderUri=${folderUri ?: "<root>"} (via GraphQL libraryV3)",
        )

        try {
            val variables = buildJsonObject {
                putJsonArray("filters") { add("Playlists") }
                put("order", JsonNull)
                put("textFilter", "")
                putJsonArray("features") { add("LIKED_SONGS"); add("YOUR_EPISODES") }
                put("limit", limit)
                put("offset", offset)
                if (folderUri != null) put("folderUri", folderUri)
            }.toString()

            val responseJson = executeGraphQL(
                operationName = "libraryV3",
                variables = variables,
                hash = SpotifyAuthConfig.HASH_LIBRARY_V3,
            )

            if (responseJson != null) {
                val page = parseLibraryPage(responseJson)
                Log.d(
                    TAG,
                    "getUserPlaylists: parsed ${page.playlists.size} playlists, " +
                        "${page.folderUris.size} folders from libraryV3",
                )
                page
            } else {
                Log.w(TAG, "getUserPlaylists: GraphQL returned null")
                SpotifyLibraryPage.EMPTY
            }
        } catch (e: Exception) {
            Log.e(TAG, "getUserPlaylists: GraphQL libraryV3 failed", e)
            SpotifyLibraryPage.EMPTY
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
    suspend fun getPlaylistTracks(playlistId: String): SyncResult<List<SpotifyTrackItem>> {
        Log.d(TAG, "getPlaylistTracks: playlistId=$playlistId")

        try {
            // Prong 1: Try client credentials + Web API first — UNLESS the
            // breaker is open (a recent 429 with a 24h Retry-After). Skipping
            // the guaranteed-429 round-trip is the bulk of the sync speedup.
            if (nowEpochSeconds() >= webApiRateLimitedUntil) {
                val webApiTracks = tryGetPlaylistTracksViaWebApi(playlistId)
                if (webApiTracks != null) {
                    Log.d(TAG, "getPlaylistTracks: got ${webApiTracks.size} tracks via Web API")
                    return SyncResult.Success(webApiTracks)
                }
            } else {
                Log.d(TAG, "getPlaylistTracks: Web API breaker open, straight to GraphQL")
            }

            // Prong 2: Fall back to sp_dc GraphQL
            Log.d(TAG, "getPlaylistTracks: trying GraphQL")
            val graphqlTracks = tryGetPlaylistTracksViaGraphQL(playlistId)
            if (graphqlTracks != null) {
                Log.d(TAG, "getPlaylistTracks: got ${graphqlTracks.size} tracks via GraphQL")
                return SyncResult.Success(graphqlTracks)
            }

            return SyncResult.Error("getPlaylistTracks: both Web API and GraphQL failed for $playlistId")
        } catch (e: SpotifyApiException) {
            throw e  // Preserve for doWork() 429 retry handling
        } catch (e: Exception) {
            return SyncResult.Error("getPlaylistTracks failed: ${e.message}", cause = e)
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
    ): SyncResult<List<SpotifyTrackItem>> = withContext(Dispatchers.IO) {
        Log.d(TAG, "getLikedSongs: limit=$limit, offset=$offset (via sp_dc GraphQL)")

        try {
            val variables = buildJsonObject {
                put("offset", offset)
                put("limit", limit)
            }.toString()

            val responseJson = executeGraphQL(
                operationName = "fetchLibraryTracks",
                variables = variables,
                hash = SpotifyAuthConfig.HASH_FETCH_LIBRARY_TRACKS,
            )

            if (responseJson != null) {
                val tracks = parseLibraryTracksResponse(responseJson)
                Log.d(TAG, "getLikedSongs: got ${tracks.size} liked songs")
                if (tracks.isEmpty()) {
                    SyncResult.Empty("GraphQL returned empty liked songs")
                } else {
                    SyncResult.Success(tracks)
                }
            } else {
                SyncResult.Error("getLikedSongs: GraphQL returned null (sp_dc may be expired)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "getLikedSongs: failed", e)
            SyncResult.Error("getLikedSongs failed: ${e.message}", e)
        }
    }

    /**
     * Finds the user's Spotify-generated personalized playlists from the home feed.
     *
     * This includes Daily Mixes, Discover Weekly, Release Radar, On Repeat,
     * Repeat Rewind, Time Capsule, Daylist, and any other algorithmic mixes
     * matching [DAILY_MIX_REGEX] or listed in [SPOTIFY_MIX_NAMES].
     *
     * These playlists don't appear in the user's library unless pinned. They are
     * personalized playlists generated by Spotify and served via the `home`
     * GraphQL query (the same endpoint that powers the Spotify home page).
     *
     * @return List of [SpotifyPlaylistItem] representing all Spotify-generated mixes.
     */
    suspend fun getDailyMixes(): SyncResult<List<SpotifyPlaylistItem>> = withContext(Dispatchers.IO) {
        Log.d(TAG, "getDailyMixes: fetching home feed for Spotify mixes")

        try {
            val spT = spotifyAuthManager.getSpT() ?: ""
            val variables = buildJsonObject {
                put("homeEndUserIntegration", "INTEGRATION_WEB_PLAYER")
                put("timeZone", java.util.TimeZone.getDefault().id)
                put("sp_t", spT)
                put("facet", JsonNull)
                put("sectionItemsLimit", 40)
            }.toString()

            val responseJson = executeGraphQL(
                operationName = "home",
                variables = variables,
                hash = SpotifyAuthConfig.HASH_HOME,
            )

            if (responseJson == null) {
                return@withContext SyncResult.Error("Home feed GraphQL returned null")
            }

            Log.d(TAG, "getDailyMixes: home feed response keys: ${responseJson["data"]?.jsonObject?.keys}")

            val mixes = parseHomeFeedForSpotifyMixes(responseJson)
            Log.d(TAG, "getDailyMixes: found ${mixes.size} Spotify mixes: ${mixes.map { it.name }}")

            if (mixes.isEmpty()) {
                SyncResult.Empty("No Spotify mix playlists found in home feed")
            } else {
                SyncResult.Success(mixes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getDailyMixes: failed", e)
            SyncResult.Error("getDailyMixes failed: ${e.message}", e)
        }
    }

    /**
     * Parses the home feed response to extract all Spotify-generated mix playlists.
     *
     * The home feed has sections, each with items. We scan all sections for
     * playlist items matching the "Daily Mix N" pattern or any name in
     * [SPOTIFY_MIX_NAMES] (Discover Weekly, Release Radar, etc.).
     */
    private fun parseHomeFeedForSpotifyMixes(responseJson: JsonObject): List<SpotifyPlaylistItem> {
        return try {
            val home = responseJson["data"]?.jsonObject?.get("home") ?: run {
                // Try alternative path
                val dataKeys = responseJson["data"]?.jsonObject?.keys
                Log.d(TAG, "parseHomeFeedForSpotifyMixes: data keys: $dataKeys")
                responseJson["data"]?.jsonObject?.get("home")
            }

            val sections = home?.jsonObject?.get("sectionContainer")
                ?.jsonObject?.get("sections")
                ?.jsonObject?.get("items")
                ?.jsonArray
                ?: run {
                    // Log what we found for debugging
                    val homeKeys = home?.jsonObject?.keys
                    Log.d(TAG, "parseHomeFeedForSpotifyMixes: home keys: $homeKeys")
                    return emptyList()
                }

            Log.d(TAG, "parseHomeFeedForSpotifyMixes: found ${sections.size} sections")

            val allPlaylists = mutableListOf<SpotifyPlaylistItem>()

            for (section in sections) {
                val sectionObj = section.jsonObject
                val sectionItems = sectionObj["sectionItems"]
                    ?.jsonObject?.get("items")
                    ?.jsonArray ?: continue

                for (item in sectionItems) {
                    try {
                        val content = item.jsonObject["content"]?.jsonObject ?: continue
                        val data = content["data"]?.jsonObject ?: continue
                        val typeName = data["__typename"]?.jsonPrimitive?.contentOrNull

                        if (typeName != "Playlist") continue

                        val name = data["name"]?.jsonPrimitive?.contentOrNull ?: continue
                        val uri = data["uri"]?.jsonPrimitive?.contentOrNull ?: continue

                        val ownerData = data["ownerV2"]?.jsonObject?.get("data")?.jsonObject
                        val ownerId = ownerData?.get("username")?.jsonPrimitive?.contentOrNull
                            ?: ownerData?.get("id")?.jsonPrimitive?.contentOrNull
                            ?: "spotify"
                        val ownerName = ownerData?.get("name")?.jsonPrimitive?.contentOrNull ?: "Spotify"

                        if (!isSpotifyMix(name, ownerId)) continue

                        val playlistId = uri.removePrefix("spotify:playlist:")

                        val images = data["images"]?.jsonObject?.get("items")?.jsonArray
                        val artUrl = images?.firstOrNull()
                            ?.jsonObject?.get("sources")?.jsonArray?.firstOrNull()
                            ?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull

                        val trackCount = data["content"]
                            ?.jsonObject?.get("totalCount")
                            ?.jsonPrimitive?.intOrNull ?: 0

                        allPlaylists.add(SpotifyPlaylistItem(
                            id = playlistId,
                            name = name,
                            owner = SpotifyOwner(id = ownerId, display_name = ownerName),
                            images = if (artUrl != null) listOf(SpotifyImage(url = artUrl)) else emptyList(),
                            tracks = SpotifyTracksRef(total = trackCount),
                        ))

                        Log.d(TAG, "parseHomeFeedForSpotifyMixes: found '$name' ($playlistId)")
                    } catch (e: Exception) {
                        Log.w(TAG, "parseHomeFeedForSpotifyMixes: failed to parse item", e)
                    }
                }
            }

            allPlaylists
        } catch (e: Exception) {
            Log.e(TAG, "parseHomeFeedForSpotifyMixes: failed", e)
            emptyList()
        }
    }

    /**
     * Searches the public Spotify catalog for tracks via the Web API
     * `/v1/search?type=track` endpoint, using a client_credentials token.
     *
     * Returns up to [limit] candidate tracks that the caller's scorer judges.
     * Parseable-but-empty responses yield an empty list.
     *
     * @param query  The free-text search query (URL-encoded internally).
     * @param limit  Max candidates to return (Spotify caps at 50).
     * @param market ISO 3166-1 alpha-2 market for relinking/availability.
     * @return List of [SpotifyTrackCandidate], possibly empty.
     * @throws SpotifyRateLimitException on HTTP 429 (so callers can back off).
     * @throws SpotifyApiException on other non-2xx responses or missing token.
     */
    suspend fun searchTracks(
        query: String,
        limit: Int = 8,
        market: String = "US",
    ): List<SpotifyTrackCandidate> = withContext(Dispatchers.IO) {
        // Use the user's sp_dc-derived WEB access token, NOT the app-level
        // client_credentials token. Spotify hard-blocks /v1/search for
        // client_credentials (observed on-device: 429 Retry-After=86400 on a
        // fresh token) — search requires a user-authorized token. This web
        // token is the same one used for liked-songs / private playlists
        // (executeGraphQL) and carries the user's search quota.
        val token = tokenManager.getSpotifyAccessToken()
            ?: throw SpotifyApiException(
                httpCode = 0,
                url = "$WEB_API_BASE/search",
                message = "searchTracks: no Spotify web access token (user not connected)",
            )

        val url = "$WEB_API_BASE/search?type=track&limit=$limit&market=$market" +
            "&q=${URLEncoder.encode(query, "UTF-8")}"

        Log.d(TAG, "searchTracks: GET $url")

        var response = executeSearchRequest(url, token)
        var responseBody = response.body?.string()

        // 401: web token expired — force-refresh once and retry.
        if (response.code == 401) {
            Log.w(TAG, "searchTracks: 401, refreshing web access token and retrying once")
            val refreshed = tokenManager.forceRefreshSpotifyAccessToken()
                ?: throw SpotifyApiException(
                    httpCode = 401,
                    url = url,
                    message = "searchTracks: web token refresh failed after 401",
                )
            response = executeSearchRequest(url, refreshed)
            responseBody = response.body?.string()
        }

        if (response.code == 429) {
            val retryAfter = response.header("Retry-After")?.toLongOrNull()
            Log.w(TAG, "searchTracks: 429 rate limited (Retry-After=$retryAfter)")
            throw SpotifyRateLimitException(retryAfterSeconds = retryAfter)
        }

        if (!response.isSuccessful) {
            throw SpotifyApiException(
                httpCode = response.code,
                url = url,
                message = "searchTracks: HTTP ${response.code}, " +
                    "bodyLen=${responseBody?.length ?: 0}",
            )
        }

        val body = responseBody ?: return@withContext emptyList()
        val candidates = parseSearchTracks(body)
        Log.d(TAG, "searchTracks: parsed ${candidates.size} candidates for query '$query'")
        candidates
    }

    /** Builds and executes a single authenticated GET against the search endpoint. */
    private fun executeSearchRequest(url: String, token: String): okhttp3.Response {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        return okHttpClient.newCall(request).execute()
    }

    /**
     * Search tracks via the web player's `searchDesktop` GraphQL operation
     * (Partner API), NOT the rate-limited public /v1/search REST endpoint.
     * Same auth/plumbing as the other GraphQL operations (sp_dc access token +
     * client token). Returns full track metadata (name/artists/duration) so the
     * bulletproof matcher still runs.
     *
     * Returns [] on any failure (no token, HTTP error, parse failure, or the
     * persisted-query hash going stale). Unlike [searchTracks] this does NOT
     * throw on rate limits — the Partner API isn't quota-throttled like the
     * public API — so the caller treats an empty list as "no candidates".
     */
    suspend fun searchTracksGraphQL(query: String, limit: Int = 10): List<SpotifyTrackCandidate> =
        withContext(Dispatchers.IO) {
            val variables = buildJsonObject {
                put("searchTerm", query)
                put("offset", 0)
                put("limit", limit)
                put("numberOfTopResults", 5)
                put("includeAudiobooks", false)
                put("includePreReleases", false)
            }.toString()

            val responseJson = executeGraphQL(
                operationName = "searchDesktop",
                variables = variables,
                hash = SpotifyAuthConfig.HASH_SEARCH_DESKTOP,
            ) ?: return@withContext emptyList<SpotifyTrackCandidate>()

            parseSearchDesktop(responseJson).also {
                Log.d(TAG, "searchTracksGraphQL: parsed ${it.size} candidates for '$query'")
            }
        }

    /**
     * Parse a `searchDesktop` GraphQL response into [SpotifyTrackCandidate]s.
     *
     * The response shape depends on which persisted-query hash is live
     * (device-confirmed 2026-06-10), so both variants are handled:
     *  - `data.search.tracks.items[].track`           (current hash)
     *  - `data.searchV2.tracksV2.items[].item.data`   (newer web-player hash)
     * Track fields: { uri, name, artists.items[].profile.name,
     * duration.totalMilliseconds, album|albumOfTrack.name, contentRating.label }.
     * Null-safe at every hop so a schema tweak yields an empty list, never a crash.
     */
    private fun parseSearchDesktop(root: JsonObject): List<SpotifyTrackCandidate> {
        return try {
            val data = root["data"]?.jsonObject
            val items = data?.get("search")?.jsonObject
                ?.get("tracks")?.jsonObject
                ?.get("items")?.jsonArray
                ?: data?.get("searchV2")?.jsonObject
                    ?.get("tracksV2")?.jsonObject
                    ?.get("items")?.jsonArray
                ?: run {
                    Log.w(TAG, "parseSearchDesktop: no tracks container, " +
                        "data keys=${data?.keys}")
                    return emptyList()
                }

            items.mapNotNull { itemEl ->
                val itemObj = itemEl as? JsonObject ?: return@mapNotNull null
                // Track payload: item.track (search), item.item.data (searchV2),
                // or a flatter item.data.
                val track = itemObj["track"]?.jsonObject
                    ?: itemObj["item"]?.jsonObject?.get("data")?.jsonObject
                    ?: itemObj["data"]?.jsonObject
                    ?: return@mapNotNull null

                val typename = track["__typename"]?.jsonPrimitive?.contentOrNull
                if (typename != null && typename != "Track") return@mapNotNull null

                val uri = track["uri"]?.jsonPrimitive?.contentOrNull
                val id = uri?.removePrefix("spotify:track:")?.takeIf { it != uri && it.isNotBlank() }
                    ?: return@mapNotNull null
                val name = track["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

                val artists = track["artists"]?.jsonObject
                    ?.get("items")?.jsonArray
                    ?.mapNotNull {
                        it.jsonObject["profile"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                    }
                    ?: emptyList()

                val durationMs = track["duration"]?.jsonObject
                    ?.get("totalMilliseconds")?.jsonPrimitive?.longOrNull
                    ?: 0L

                val album = (track["album"] ?: track["albumOfTrack"])?.jsonObject
                    ?.get("name")?.jsonPrimitive?.contentOrNull
                    ?: ""

                SpotifyTrackCandidate(
                    id = id,
                    name = name,
                    artists = artists,
                    albumName = album,
                    durationMs = durationMs,
                    isrc = null, // searchDesktop doesn't surface ISRC
                    explicit = track["contentRating"]?.jsonObject
                        ?.get("label")?.jsonPrimitive?.contentOrNull == "EXPLICIT",
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseSearchDesktop: parse failed: ${e.message}")
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
        // `external_ids` is requested as a whole object, not `external_ids(isrc)`
        // or `external_ids.isrc`. Empirically (verified against live Spotify
        // responses on device, 2026-04-19), both nested forms are silently
        // dropped by Spotify's `fields=` parser while the plain object name
        // returns the full `{"isrc": "..."}`. Costs maybe 30 bytes per track,
        // buys us a working ISRC — fair trade.
        var url: String? = "$WEB_API_BASE/playlists/$playlistId/tracks?limit=50&offset=0" +
            "&fields=items(track(id,name,uri,duration_ms,explicit,external_ids," +
            "artists(id,name),album(id,name,images))),next"

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
                Log.e(TAG, "tryGetPlaylistTracksViaWebApi: HTTP $responseCode for $playlistId, " +
                    "bodyLen=${responseBody?.length ?: 0}")

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

                // 429: open the breaker so the rest of this sync skips the
                // Web API prong entirely (Retry-After is 24h for these tokens).
                if (responseCode == 429) {
                    webApiRateLimitedUntil = nowEpochSeconds() + WEB_API_RATE_LIMIT_COOLDOWN_SEC
                    Log.w(TAG, "tryGetPlaylistTracksViaWebApi: 429 — Web API breaker open for 24h")
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

    // `parseWebApiPlaylistPage` lives as a top-level `internal fun` in
    // [SpotifyTrackParser.kt] so unit tests can exercise it directly.

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
            val variables = buildJsonObject {
                put("uri", uri)
                put("offset", currentOffset)
                put("limit", pageSize)
                put("enableWatchFeedEntrypoint", false)
            }.toString()

            val responseJson = executeGraphQL(
                operationName = "fetchPlaylist",
                variables = variables,
                hash = SpotifyAuthConfig.HASH_FETCH_PLAYLIST,
            ) ?: break

            val page = parsePlaylistTracksGraphQLResponse(responseJson)
            Log.d(
                TAG,
                "tryGetPlaylistTracksViaGraphQL: offset=$currentOffset, " +
                    "parsed=${page.tracks.size}/raw=${page.rawItemCount} tracks",
            )

            allTracks.addAll(page.tracks)

            // Terminate on raw item count, NOT parsed count. A page with
            // any filtered item (local tracks, podcast episodes, region-
            // blocked URIs) has parsed.size < pageSize even when Spotify
            // has more pages to give us. Using rawItemCount catches the
            // true "last page" condition. 2026-05-13: this is the fix
            // for users whose private playlists capped at ~99 tracks.
            if (page.rawItemCount < pageSize) break
            currentOffset += pageSize
        }

        return if (allTracks.isNotEmpty()) allTracks else null
    }

    // ── Library writes (heart button) — GraphQL mutations ───────────────

    /**
     * Saves [uris] to the user's library (the web player's `addToLibrary`
     * mutation — the heart button). [uris] are full `spotify:track:…` URIs.
     * Idempotent: re-saving an already-saved track succeeds.
     *
     * This replaced the deprecated + throttled `PUT /v1/me/tracks` REST call:
     * Spotify removed that endpoint in Feb 2026 and hard-429s it for sp_dc
     * tokens. This mutation is the real web player's path, on the same token
     * the library reads already use.
     */
    suspend fun addToLibrary(uris: List<String>): SpotifyLibraryWriteResult =
        mutateLibrary("addToLibrary", uris)

    /** Removes [uris] from the user's library (`removeFromLibrary`, symmetric un-like). */
    suspend fun removeFromLibrary(uris: List<String>): SpotifyLibraryWriteResult =
        mutateLibrary("removeFromLibrary", uris)

    /** Seeded hash, swapped for a re-scraped one after a PersistedQueryNotFound. */
    @Volatile
    private var libraryMutationHash: String = SpotifyAuthConfig.HASH_LIBRARY_MUTATION

    private suspend fun mutateLibrary(
        operationName: String,
        uris: List<String>,
    ): SpotifyLibraryWriteResult = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext SpotifyLibraryWriteResult.Success

        val accessToken = tokenManager.getSpotifyAccessToken()
            ?: return@withContext SpotifyLibraryWriteResult.AuthFailed
        val clientToken = ensureClientToken()
            ?: return@withContext SpotifyLibraryWriteResult.Failed("no client token")

        // First attempt with the current hash; on PersistedQueryNotFound
        // (the hash rotated with a new web-player build) re-scrape and retry once.
        val first = postLibraryMutation(operationName, uris, accessToken, clientToken, libraryMutationHash)
        if (first !is PersistedQueryMissing) return@withContext first.result

        Log.w(TAG, "mutateLibrary: PersistedQueryNotFound — re-scraping hash")
        val fresh = spotifyAuthManager.scrapeLibraryMutationHash()
            ?: return@withContext SpotifyLibraryWriteResult.Failed("persisted-query hash rotated; re-scrape failed")
        libraryMutationHash = fresh
        postLibraryMutation(operationName, uris, accessToken, clientToken, fresh).result
    }

    /** Wraps a mutation outcome so the caller can distinguish the retry-worthy
     * PersistedQueryNotFound case from a terminal result. */
    private sealed interface MutationOutcome {
        val result: SpotifyLibraryWriteResult
    }
    private data class Terminal(override val result: SpotifyLibraryWriteResult) : MutationOutcome
    private data object PersistedQueryMissing : MutationOutcome {
        override val result = SpotifyLibraryWriteResult.Failed("persisted query not found")
    }

    private fun postLibraryMutation(
        operationName: String,
        uris: List<String>,
        accessToken: String,
        clientToken: String,
        hash: String,
    ): MutationOutcome {
        val body = buildJsonObject {
            put("variables", buildJsonObject {
                putJsonArray(SpotifyAuthConfig.LIBRARY_MUTATION_URIS_VAR) {
                    uris.forEach { add(it) }
                }
            })
            put("operationName", operationName)
            put("extensions", buildJsonObject {
                put("persistedQuery", buildJsonObject {
                    put("version", 1)
                    put("sha256Hash", hash)
                })
            })
        }.toString()

        val request = Request.Builder()
            .url(SpotifyAuthConfig.GRAPHQL_ENDPOINT)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $accessToken")
            .header("Client-Token", clientToken)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("App-Platform", "WebPlayer")
            .header("Spotify-App-Version", spotifyAuthManager.getClientVersion())
            .header("Origin", "https://open.spotify.com")
            .header("Referer", "https://open.spotify.com/")
            .header("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36")
            .build()

        return okHttpClient.newCall(request).execute().use { response ->
            val respBody = response.body?.string().orEmpty()
            when (response.code) {
                429 -> Terminal(
                    SpotifyLibraryWriteResult.RateLimited(
                        response.header("Retry-After")?.toIntOrNull(),
                    ),
                )
                401 -> {
                    // Invalidate so the next op refreshes; caller retries organically.
                    cachedClientToken = null
                    Terminal(SpotifyLibraryWriteResult.AuthFailed)
                }
                in 200..299 -> {
                    // Pathfinder returns 200 with a top-level `errors` array on
                    // GraphQL failure — including PersistedQueryNotFound when the
                    // hash has rotated.
                    if (respBody.contains("PersistedQueryNotFound", ignoreCase = true)) {
                        PersistedQueryMissing
                    } else if (respBody.contains("\"errors\"")) {
                        Terminal(SpotifyLibraryWriteResult.Failed("graphql errors: ${respBody.take(200)}"))
                    } else {
                        Terminal(SpotifyLibraryWriteResult.Success)
                    }
                }
                400 -> {
                    // 400 can also carry PersistedQueryNotFound.
                    if (respBody.contains("PersistedQueryNotFound", ignoreCase = true)) {
                        PersistedQueryMissing
                    } else {
                        Terminal(SpotifyLibraryWriteResult.Failed("http 400: ${respBody.take(200)}"))
                    }
                }
                else -> Terminal(
                    SpotifyLibraryWriteResult.Failed("http ${response.code}: ${respBody.take(200)}"),
                )
            }
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
        val extensions = buildJsonObject {
            put("persistedQuery", buildJsonObject {
                put("version", 1)
                put("sha256Hash", hash)
            })
        }.toString()
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
            .header("Spotify-App-Version", spotifyAuthManager.getClientVersion())
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
            Log.e(TAG, "executeGraphQL: HTTP $responseCode for $operationName, " +
                "bodyLen=${responseBody?.length ?: 0}")

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

        Log.d(TAG, "executeGraphQL: $operationName response: ${responseBody.length} chars")

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
            .header("Spotify-App-Version", spotifyAuthManager.getClientVersion())
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
                "HTTP ${retryResponse.code}, bodyLen=${retryBody?.length ?: 0}")
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

        Log.d(TAG, "ensureClientToken: acquiring client token (clientId ${clientId.length} chars)")
        val token = spotifyAuthManager.getClientToken(clientId)
        if (token != null) {
            Log.d(TAG, "ensureClientToken: acquired client token (${token.length} chars)")
            cachedClientToken = token
        } else {
            Log.e(TAG, "ensureClientToken: failed to acquire client token")
        }
        return token
    }

    // ── Response parsing (GraphQL) ──────────────────────────────────────

    /**
     * Parses the `fetchPlaylist` GraphQL response into [SpotifyTrackItem]s.
     *
     * Returns a [PlaylistTracksPage]: the parsed tracks (post-filter) AND
     * the raw item count from Spotify (pre-filter). Callers MUST use
     * `rawItemCount` — not `tracks.size` — as the page-end signal, otherwise
     * any per-page filtered item (local tracks, podcast episodes, region-
     * blocked URIs) prematurely terminates pagination. Issue: a user with
     * 850 tracks saw 99 because one item on page 1 got filtered and
     * `tracks.size (99) < pageSize (100)` broke the loop early.
     */
    private fun parsePlaylistTracksGraphQLResponse(responseJson: JsonObject): PlaylistTracksPage {
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
                return PlaylistTracksPage(emptyList(), 0)
            }

            val rawItemCount = items.size
            Log.d(TAG, "parsePlaylistTracksGraphQLResponse: found $rawItemCount items")

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
            }.let { parsed ->
                Log.d(
                    TAG,
                    "parsePlaylistTracksGraphQLResponse: parsed ${parsed.size}/$rawItemCount tracks",
                )
                PlaylistTracksPage(parsed, rawItemCount)
            }
        } catch (e: Exception) {
            Log.e(TAG, "parsePlaylistTracksGraphQLResponse: failed to parse response", e)
            PlaylistTracksPage(emptyList(), 0)
        }
    }

    /**
     * Single GraphQL pagination page: the tracks that parsed plus the
     * raw item count returned by Spotify. The raw count is the only
     * reliable signal for "is this the last page?" — using
     * `tracks.size < pageSize` mis-fires whenever an item gets filtered
     * by the parser (local tracks, podcast episodes, non-Track URIs).
     */
    private data class PlaylistTracksPage(
        val tracks: List<SpotifyTrackItem>,
        val rawItemCount: Int,
    )

    /**
     * Parses the `fetchLibraryTracks` GraphQL response into [SpotifyTrackItem] objects.
     *
     * Response shape: `data.me.library.tracks.items[].item.data` where each item
     * contains track metadata in the same format as fetchPlaylist.
     */
    private fun parseLibraryTracksResponse(responseJson: JsonObject): List<SpotifyTrackItem> {
        return try {
            // Log top-level keys for debugging
            val dataObj = responseJson["data"]?.jsonObject
            if (dataObj == null) {
                Log.w(TAG, "parseLibraryTracksResponse: no 'data' key, responseKeys=${responseJson.keys}")
                return emptyList()
            }
            Log.d(TAG, "parseLibraryTracksResponse: data keys: ${dataObj.keys}")

            // Try multiple possible response paths
            val items = dataObj["me"]
                ?.jsonObject?.get("library")
                ?.jsonObject?.get("tracks")
                ?.jsonObject?.get("items")
                ?.jsonArray
                ?: dataObj["me"]
                    ?.jsonObject?.get("libraryTracks")
                    ?.jsonObject?.get("items")
                    ?.jsonArray

            if (items == null) {
                // Log what we DID find so we can fix the path
                val meKeys = dataObj["me"]?.jsonObject?.keys
                Log.w(TAG, "parseLibraryTracksResponse: items not found. me keys: $meKeys")
                val meObj = dataObj["me"]?.jsonObject
                meObj?.keys?.forEach { key ->
                    val subKeys = meObj[key]?.jsonObject?.keys
                    Log.d(TAG, "parseLibraryTracksResponse: me.$key keys: $subKeys")
                }
                return emptyList()
            }

            Log.d(TAG, "parseLibraryTracksResponse: found ${items.size} items")

            items.mapNotNull { element ->
                try {
                    val wrapper = element.jsonObject
                    // Response shape: items[].track.data (with _uri on the track wrapper)
                    val trackData = wrapper["track"]?.jsonObject?.get("data")?.jsonObject
                        ?: wrapper["item"]?.jsonObject?.get("data")?.jsonObject
                        ?: wrapper["itemV2"]?.jsonObject?.get("data")?.jsonObject
                        ?: return@mapNotNull null

                    val uri = trackData["uri"]?.jsonPrimitive?.contentOrNull
                        ?: wrapper["track"]?.jsonObject?.get("_uri")?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                    if (!uri.startsWith("spotify:track:")) return@mapNotNull null

                    val trackId = uri.removePrefix("spotify:track:")
                    val name = trackData["name"]?.jsonPrimitive?.contentOrNull ?: "Unknown"

                    val durationMs = trackData["trackDuration"]
                        ?.jsonObject?.get("totalMilliseconds")
                        ?.jsonPrimitive?.longOrNull
                        ?: trackData["duration"]
                            ?.jsonObject?.get("totalMilliseconds")
                            ?.jsonPrimitive?.longOrNull
                        ?: 0L

                    val artistItems = trackData["artists"]
                        ?.jsonObject?.get("items")
                        ?.jsonArray

                    val artists = artistItems?.mapNotNull { artistElement ->
                        val artistObj = artistElement.jsonObject
                        val artistName = artistObj["profile"]
                            ?.jsonObject?.get("name")
                            ?.jsonPrimitive?.contentOrNull
                            ?: return@mapNotNull null
                        val artistUri = artistObj["uri"]?.jsonPrimitive?.contentOrNull ?: ""
                        val artistId = artistUri.removePrefix("spotify:artist:")
                        SpotifyArtist(id = artistId, name = artistName)
                    } ?: emptyList()

                    val albumData = trackData["albumOfTrack"]?.jsonObject
                    val album = if (albumData != null) {
                        val albumUri = albumData["uri"]?.jsonPrimitive?.contentOrNull ?: ""
                        val albumId = albumUri.removePrefix("spotify:album:")
                        val albumName = albumData["name"]?.jsonPrimitive?.contentOrNull ?: ""
                        val coverArtUrl = albumData["coverArt"]
                            ?.jsonObject?.get("sources")
                            ?.jsonArray?.firstOrNull()
                            ?.jsonObject?.get("url")
                            ?.jsonPrimitive?.contentOrNull
                        SpotifyAlbum(
                            id = albumId,
                            name = albumName,
                            images = if (coverArtUrl != null) listOf(SpotifyImage(url = coverArtUrl)) else null,
                        )
                    } else null

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
                    Log.w(TAG, "parseLibraryTracksResponse: failed to parse item", e)
                    null
                }
            }.also { tracks ->
                Log.d(TAG, "parseLibraryTracksResponse: parsed ${tracks.size} tracks")
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseLibraryTracksResponse: failed", e)
            emptyList()
        }
    }
}
