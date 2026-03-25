package com.stash.data.spotify

import android.util.Log
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.UserInfo
import com.stash.data.spotify.model.SpotifyPlaylistItem
import com.stash.data.spotify.model.SpotifyPlaylistsResponse
import com.stash.data.spotify.model.SpotifyTrackItem
import com.stash.data.spotify.model.SpotifyTracksResponse
import com.stash.data.spotify.model.SpotifyUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
 * All methods automatically retrieve a valid access token from [TokenManager]
 * and attach it as a Bearer token in the Authorization header. Requests that
 * receive HTTP 429 (rate limited) are automatically retried up to [MAX_RETRIES]
 * times, respecting the server's `Retry-After` header. A small delay is inserted
 * between consecutive requests to avoid triggering rate limits in the first place.
 *
 * If the token is unavailable the method returns null (or throws). If the API
 * returns a non-recoverable error after retries, a [SpotifyApiException] is thrown
 * so callers can distinguish "no data" from "API failure."
 */
@Singleton
class SpotifyApiClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val tokenManager: TokenManager,
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "SpotifyApiClient"
        private const val BASE_URL = "https://api.spotify.com/v1"
        private const val DEFAULT_LIMIT = 50

        /** Maximum number of retry attempts for 429 responses. */
        private const val MAX_RETRIES = 3

        /** Default wait time (ms) when 429 has no Retry-After header. */
        private const val DEFAULT_RETRY_AFTER_MS = 1000L

        /** Minimum delay (ms) between consecutive API requests to avoid bursts. */
        private const val INTER_REQUEST_DELAY_MS = 100L

        private val DAILY_MIX_REGEX = Regex("""Daily Mix \d+""")
    }

    /**
     * Timestamp of the last completed API request. Used to enforce
     * [INTER_REQUEST_DELAY_MS] spacing between calls.
     */
    private var lastRequestTimeMs = 0L

    // ── Profile ──────────────────────────────────────────────────────────

    /**
     * Fetches the current user's Spotify profile.
     *
     * @return A [UserInfo] with the user's ID, display name, and optional profile image,
     *         or null if the request fails (e.g. auth expired).
     */
    suspend fun getCurrentUserProfile(): UserInfo? = withContext(Dispatchers.IO) {
        val body = executeGet("$BASE_URL/me") ?: return@withContext null
        val user = json.decodeFromString<SpotifyUser>(body)
        UserInfo(
            id = user.id,
            displayName = user.display_name ?: user.id,
            imageUrl = user.images?.firstOrNull()?.url,
        )
    }

    // ── Playlists ────────────────────────────────────────────────────────

    /**
     * Fetches a page of the current user's playlists.
     *
     * @param limit  Maximum number of playlists to return (1..50, default 50).
     * @param offset Zero-based index of the first playlist to return.
     * @return List of [SpotifyPlaylistItem].
     * @throws SpotifyApiException if the API request fails after retries.
     */
    suspend fun getUserPlaylists(
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
    ): List<SpotifyPlaylistItem> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/me/playlists?limit=$limit&offset=$offset"
        val body = executeGet(url)
            ?: throw SpotifyApiException(0, url, "No access token available for Spotify")
        json.decodeFromString<SpotifyPlaylistsResponse>(body).items
    }

    /**
     * Fetches all tracks in a specific playlist, paginating automatically.
     *
     * @param playlistId The Spotify playlist ID.
     * @return List of [SpotifyTrackItem].
     * @throws SpotifyApiException if any page request fails after retries.
     */
    suspend fun getPlaylistTracks(playlistId: String): List<SpotifyTrackItem> {
        return withContext(Dispatchers.IO) {
            val allTracks = mutableListOf<SpotifyTrackItem>()
            var offset = 0

            while (true) {
                val url = "$BASE_URL/playlists/$playlistId/tracks?limit=$DEFAULT_LIMIT&offset=$offset"
                val body = executeGet(url)
                    ?: throw SpotifyApiException(0, url, "No access token available for Spotify")

                val page = json.decodeFromString<SpotifyTracksResponse>(body)
                allTracks.addAll(page.items)

                if (allTracks.size >= page.total || page.items.isEmpty()) break
                offset += page.items.size
            }

            allTracks
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
     * @param limit  Maximum number of tracks to return (1..50, default 50).
     * @param offset Zero-based index of the first track to return.
     * @return List of [SpotifyTrackItem].
     * @throws SpotifyApiException if the API request fails after retries.
     */
    suspend fun getLikedSongs(
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
    ): List<SpotifyTrackItem> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/me/tracks?limit=$limit&offset=$offset"
        val body = executeGet(url)
            ?: throw SpotifyApiException(0, url, "No access token available for Spotify")
        json.decodeFromString<SpotifyTracksResponse>(body).items
    }

    // ── Internal ─────────────────────────────────────────────────────────

    /**
     * Executes an authenticated GET request against the Spotify API with
     * automatic retry handling for HTTP 429 (Too Many Requests).
     *
     * On a 429 response the method reads the `Retry-After` header (seconds),
     * waits that duration, and retries up to [MAX_RETRIES] times. A small
     * inter-request delay is also enforced to prevent burst-triggering rate limits.
     *
     * @param url The full request URL including query parameters.
     * @return The response body as a String, or null if the access token is unavailable.
     * @throws SpotifyApiException if the request fails with a non-recoverable status
     *         code or if all retry attempts for a 429 are exhausted.
     */
    private suspend fun executeGet(url: String): String? {
        val accessToken = tokenManager.getSpotifyAccessToken() ?: return null

        // Enforce minimum spacing between consecutive requests.
        val elapsed = System.currentTimeMillis() - lastRequestTimeMs
        if (elapsed < INTER_REQUEST_DELAY_MS) {
            delay(INTER_REQUEST_DELAY_MS - elapsed)
        }

        var lastResponseCode = 0
        for (attempt in 0..MAX_RETRIES) {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Authorization", "Bearer $accessToken")
                .build()

            val response = okHttpClient.newCall(request).execute()
            lastRequestTimeMs = System.currentTimeMillis()
            lastResponseCode = response.code

            if (response.isSuccessful) {
                return response.body?.string()
            }

            // Handle 429 rate limiting with Retry-After header.
            if (response.code == 429 && attempt < MAX_RETRIES) {
                val retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()
                val waitMs = if (retryAfterSeconds != null && retryAfterSeconds > 0) {
                    retryAfterSeconds * 1000
                } else {
                    DEFAULT_RETRY_AFTER_MS * (attempt + 1) // linear backoff fallback
                }
                Log.w(
                    TAG,
                    "429 rate limited on $url (attempt ${attempt + 1}/$MAX_RETRIES), " +
                        "retrying after ${waitMs}ms",
                )
                response.body?.close()
                delay(waitMs)
                continue
            }

            // Non-429 error or final 429 attempt -- close and throw.
            response.body?.close()
            throw SpotifyApiException(
                httpCode = response.code,
                url = url,
                message = "Spotify API request failed: HTTP ${response.code} on $url" +
                    if (attempt > 0) " (after ${attempt + 1} attempts)" else "",
            )
        }

        // Should not reach here, but satisfy the compiler.
        throw SpotifyApiException(
            httpCode = lastResponseCode,
            url = url,
            message = "Spotify API request failed after ${MAX_RETRIES + 1} attempts: HTTP $lastResponseCode on $url",
        )
    }
}
