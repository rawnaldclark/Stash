package com.stash.data.spotify

import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.UserInfo
import com.stash.data.spotify.model.SpotifyPlaylistItem
import com.stash.data.spotify.model.SpotifyPlaylistsResponse
import com.stash.data.spotify.model.SpotifyTrackItem
import com.stash.data.spotify.model.SpotifyTracksResponse
import com.stash.data.spotify.model.SpotifyUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level client for the Spotify Web API.
 *
 * All methods automatically retrieve a valid access token from [TokenManager]
 * and attach it as a Bearer token in the Authorization header. If the token
 * is unavailable or the API returns 401, the method returns null (or an empty
 * list) so that callers can handle the auth-failure gracefully.
 */
@Singleton
class SpotifyApiClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val tokenManager: TokenManager,
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val BASE_URL = "https://api.spotify.com/v1"
        private const val DEFAULT_LIMIT = 50
        private val DAILY_MIX_REGEX = Regex("""Daily Mix \d+""")
    }

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
     * @return List of [SpotifyPlaylistItem], or empty if the request fails.
     */
    suspend fun getUserPlaylists(
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
    ): List<SpotifyPlaylistItem> = withContext(Dispatchers.IO) {
        val body = executeGet("$BASE_URL/me/playlists?limit=$limit&offset=$offset")
            ?: return@withContext emptyList()
        json.decodeFromString<SpotifyPlaylistsResponse>(body).items
    }

    /**
     * Fetches all tracks in a specific playlist.
     *
     * @param playlistId The Spotify playlist ID.
     * @return List of [SpotifyTrackItem], or empty if the request fails.
     */
    suspend fun getPlaylistTracks(playlistId: String): List<SpotifyTrackItem> {
        return withContext(Dispatchers.IO) {
            val allTracks = mutableListOf<SpotifyTrackItem>()
            var offset = 0

            while (true) {
                val body = executeGet(
                    "$BASE_URL/playlists/$playlistId/tracks?limit=$DEFAULT_LIMIT&offset=$offset"
                ) ?: break

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
     * @return List of [SpotifyTrackItem], or empty if the request fails.
     */
    suspend fun getLikedSongs(
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
    ): List<SpotifyTrackItem> = withContext(Dispatchers.IO) {
        val body = executeGet("$BASE_URL/me/tracks?limit=$limit&offset=$offset")
            ?: return@withContext emptyList()
        json.decodeFromString<SpotifyTracksResponse>(body).items
    }

    // ── Internal ─────────────────────────────────────────────────────────

    /**
     * Executes an authenticated GET request against the Spotify API.
     *
     * @param url The full request URL including query parameters.
     * @return The response body as a String, or null if the token is unavailable
     *         or the response is not successful (e.g. 401 Unauthorized).
     */
    private suspend fun executeGet(url: String): String? {
        val accessToken = tokenManager.getSpotifyAccessToken() ?: return null

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $accessToken")
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) return null
        return response.body?.string()
    }
}
