package com.stash.data.ytmusic

import com.stash.core.auth.TokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Low-level HTTP client for the YouTube Music InnerTube API.
 *
 * InnerTube is the internal API that powers youtube.com and music.youtube.com.
 * All requests are JSON POSTs to `https://music.youtube.com/youtubei/v1/{action}`
 * containing a `context` object with client metadata and action-specific parameters.
 *
 * When an access token is available (from the YouTube device-flow auth), it is
 * sent as a Bearer token so the API returns personalized results (liked songs,
 * playlists, mixes). When unauthenticated, only public data is accessible.
 *
 * This client handles the raw HTTP layer; higher-level parsing is done by
 * [YTMusicApiClient].
 */
@Singleton
class InnerTubeClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val tokenManager: TokenManager,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json".toMediaType()

    companion object {
        private const val BASE_URL = "https://music.youtube.com/youtubei/v1"

        /** InnerTube client name for YouTube Music web. */
        private const val CLIENT_NAME = "WEB_REMIX"

        /** InnerTube client version. Updated periodically; does not need to match exactly. */
        private const val CLIENT_VERSION = "1.20240101.01.00"

        /** Publicly-known API key used by the YouTube Music web app. */
        private const val API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    }

    /**
     * Calls the InnerTube `browse` action.
     *
     * Browse is the primary way to fetch pages in YouTube Music, including:
     * - `FEmusic_home` (home feed with mixes)
     * - `FEmusic_liked_videos` (liked songs)
     * - `VL{playlistId}` (playlist contents)
     *
     * @param browseId The InnerTube browse ID.
     * @return The parsed JSON response, or null on failure.
     */
    suspend fun browse(browseId: String): JsonObject? = withContext(Dispatchers.IO) {
        val token = tokenManager.getYouTubeAccessToken()
        val body = buildJsonObject {
            put("context", buildContext())
            put("browseId", browseId)
        }
        executeRequest("$BASE_URL/browse", body, token)
    }

    /**
     * Calls the InnerTube `search` action.
     *
     * @param query The search query string.
     * @return The parsed JSON response, or null on failure.
     */
    suspend fun search(query: String): JsonObject? = withContext(Dispatchers.IO) {
        val token = tokenManager.getYouTubeAccessToken()
        val body = buildJsonObject {
            put("context", buildContext())
            put("query", query)
        }
        executeRequest("$BASE_URL/search", body, token)
    }

    /**
     * Builds the InnerTube client context object required by every request.
     */
    private fun buildContext(): JsonObject = buildJsonObject {
        putJsonObject("client") {
            put("clientName", CLIENT_NAME)
            put("clientVersion", CLIENT_VERSION)
            put("hl", "en")
            put("gl", "US")
        }
    }

    /**
     * Executes a POST request against the InnerTube API.
     *
     * If an [accessToken] is provided, it is sent as a Bearer token and the
     * public API key is omitted (authenticated requests must not include both).
     * Otherwise, the public API key is appended as a query parameter.
     *
     * @param url         The full endpoint URL (without query parameters).
     * @param body        The JSON request body.
     * @param accessToken An optional OAuth access token for authenticated requests.
     * @return The parsed JSON response, or null on HTTP failure.
     */
    private fun executeRequest(
        url: String,
        body: JsonObject,
        accessToken: String?,
    ): JsonObject? {
        val fullUrl = if (accessToken != null) {
            "$url?prettyPrint=false"
        } else {
            "$url?key=$API_KEY&prettyPrint=false"
        }

        val requestBuilder = Request.Builder()
            .url(fullUrl)
            .post(body.toString().toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")

        accessToken?.let { requestBuilder.header("Authorization", "Bearer $it") }

        val response = okHttpClient.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful) return null

        val responseBody = response.body?.string() ?: return null
        return json.parseToJsonElement(responseBody).jsonObject
    }
}
