package com.stash.data.ytmusic

import com.stash.core.auth.TokenManager
import com.stash.core.auth.youtube.YouTubeCookieHelper
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
import android.util.Log
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
 * Authentication uses browser cookies with SAPISIDHASH authorization (the same
 * approach used by ytmusicapi and similar projects). When cookies are available,
 * the API returns personalized results (liked songs, playlists, mixes). When
 * unauthenticated, only public data is accessible.
 *
 * This client handles the raw HTTP layer; higher-level parsing is done by
 * [YTMusicApiClient].
 */
@Singleton
class InnerTubeClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val tokenManager: TokenManager,
    private val cookieHelper: YouTubeCookieHelper,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json".toMediaType()

    companion object {
        private const val TAG = "StashYT"
        private const val BASE_URL = "https://music.youtube.com/youtubei/v1"

        /** InnerTube client name for YouTube Music web. */
        private const val CLIENT_NAME = "WEB_REMIX"

        /**
         * InnerTube client version — generated from today's date.
         * This matches the approach used by ytmusicapi (Python reference implementation).
         * InnerTube uses this for client identification; a current date signals a current web client.
         */
        private val CLIENT_VERSION: String
            get() = "1.${java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)}.01.00"

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
        val cookie = tokenManager.getYouTubeCookie()
        val body = buildJsonObject {
            put("context", buildContext())
            put("browseId", browseId)
        }
        executeRequest("$BASE_URL/browse", body, cookie)
    }

    /**
     * Calls the InnerTube `search` action.
     *
     * @param query The search query string.
     * @return The parsed JSON response, or null on failure.
     */
    suspend fun search(query: String): JsonObject? = withContext(Dispatchers.IO) {
        val cookie = tokenManager.getYouTubeCookie()
        val body = buildJsonObject {
            put("context", buildContext())
            put("query", query)
        }
        executeRequest("$BASE_URL/search", body, cookie)
    }

    /**
     * Calls the InnerTube `player` action to get actual video metadata.
     *
     * Used to verify that a video ID returned by search actually corresponds
     * to the expected song. InnerTube search metadata and actual video content
     * can diverge — this endpoint returns the ground-truth title/author.
     *
     * @param videoId The YouTube video ID to look up.
     * @return The parsed JSON response containing `videoDetails`, or null on failure.
     */
    suspend fun player(videoId: String): JsonObject? = withContext(Dispatchers.IO) {
        val cookie = tokenManager.getYouTubeCookie()
        val body = buildJsonObject {
            put("context", buildContext())
            put("videoId", videoId)
        }
        executeRequest("$BASE_URL/player", body, cookie)
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
        putJsonObject("user") {}
    }

    /**
     * Executes a POST request against the InnerTube API.
     *
     * If a [cookie] string is provided, it is sent along with a SAPISIDHASH
     * authorization header derived from the SAPISID cookie value. This mimics
     * an authenticated browser session and returns personalized results.
     * Otherwise, the public API key is appended as a query parameter for
     * unauthenticated access.
     *
     * @param url    The full endpoint URL (without query parameters).
     * @param body   The JSON request body.
     * @param cookie An optional cookie string from the user's browser session.
     * @return The parsed JSON response, or null on HTTP failure.
     */
    private fun executeRequest(
        url: String,
        body: JsonObject,
        cookie: String?,
    ): JsonObject? {
        val sapiSid = cookie?.let { cookieHelper.extractSapiSid(it) }

        val fullUrl = if (sapiSid != null) {
            "$url?prettyPrint=false"
        } else {
            "$url?key=$API_KEY&prettyPrint=false"
        }

        Log.d(TAG, "executeRequest: POST $fullUrl (authenticated=${sapiSid != null})")

        val requestBuilder = Request.Builder()
            .url(fullUrl)
            .post(body.toString().toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

        if (sapiSid != null && cookie != null) {
            requestBuilder
                .header("Cookie", cookie)
                .header("Authorization", cookieHelper.generateAuthHeader(sapiSid))
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .header("X-Goog-AuthUser", "0")
        }

        val response = okHttpClient.newCall(requestBuilder.build()).execute()
        return response.use { resp ->
            if (!resp.isSuccessful) {
                val errorBodyLen = resp.body?.string()?.length ?: 0
                Log.e(TAG, "executeRequest: HTTP ${resp.code}, errorBodyLen=$errorBodyLen")
                return@use null
            }

            val responseBody = resp.body?.string() ?: return@use null
            Log.d(TAG, "executeRequest: success, response length=${responseBody.length}")
            json.parseToJsonElement(responseBody).jsonObject
        }
    }
}
