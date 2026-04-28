package com.stash.data.ytmusic

import android.util.Log
import com.stash.core.auth.TokenManager
import com.stash.core.auth.youtube.YouTubeCookieHelper
import com.stash.data.ytmusic.model.MusicVideoType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * InnerTube client variants that differ in the `context.client.*` fields
 * sent with each request. YouTube serves different response shapes per
 * client: the WEB family wraps audio URLs in `signatureCipher` (requiring
 * a JavaScript-solved transform before they can be played), while several
 * mobile/embedded clients often return direct, unciphered URLs in
 * `streamingData.adaptiveFormats[*].url`.
 *
 * We exploit that shape difference to skip the 14 s yt-dlp + QuickJS
 * cipher-solving fallback whenever possible. See [InnerTubeClient.playerForAudio]
 * for the ordered attempt logic.
 *
 * Variants and their ordering are best-effort: YouTube periodically tightens
 * extraction defenses on specific clients, so we try the most permissive
 * variants first and keep [WEB_REMIX] as a last resort.
 */
enum class InnerTubeVariant(
    val clientName: String,
    val clientVersion: String,
    val userAgent: String,
    val extraClientFields: Map<String, Any> = emptyMap(),
) {
    /** Oculus Quest 3 VR browser. Historically returns unciphered URLs. */
    ANDROID_VR(
        clientName = "ANDROID_VR",
        clientVersion = "1.60.19",
        userAgent =
            "com.google.android.apps.youtube.vr.oculus/1.60.19 " +
                "(Linux; U; Android 12L; eureka-user Build/SQ3A.220705.001.B1) gzip",
        extraClientFields = mapOf(
            "deviceMake" to "Oculus",
            "deviceModel" to "Quest 3",
            "osName" to "Android",
            "osVersion" to "12L",
            "androidSdkVersion" to 32,
        ),
    ),

    /** iOS YouTube app. Also frequently returns unciphered URLs. */
    IOS(
        clientName = "IOS",
        clientVersion = "19.45.4",
        userAgent =
            "com.google.ios.youtube/19.45.4 " +
                "(iPhone16,2; U; CPU iOS 17_7_1 like Mac OS X; en_US)",
        extraClientFields = mapOf(
            "deviceMake" to "Apple",
            "deviceModel" to "iPhone16,2",
            "osName" to "iOS",
            "osVersion" to "17.7.1.21H216",
        ),
    ),

    /** Standard web YouTube Music client. URLs are typically ciphered. */
    WEB_REMIX(
        clientName = "WEB_REMIX",
        // Version is "1.<today's date as YYYYMMDD>.01.00" to match the
        // cadence YouTube Music ships; a current date signals a current
        // client build. Computed lazily via [currentVersion] so a long-
        // lived process picks up date rollover without reinitialisation.
        clientVersion = "",
        userAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    );

    /** Resolves the reported client version, computing it fresh for [WEB_REMIX]. */
    fun currentVersion(): String = if (this == WEB_REMIX) {
        "1.${java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)}.01.00"
    } else {
        clientVersion
    }
}

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

        /** Publicly-known API key used by the YouTube Music web app. */
        private const val API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"

        /**
         * Ordered attempt list for audio URL extraction. Unciphered-friendly
         * variants first so [playerForAudio] exits on the earliest response
         * that carries a direct URL.
         */
        private val AUDIO_VARIANT_ORDER = listOf(
            InnerTubeVariant.ANDROID_VR,
            InnerTubeVariant.IOS,
            InnerTubeVariant.WEB_REMIX,
        )
    }

    /**
     * Internal representation of an HTTP outcome that carries both the parsed
     * body (if any) and the HTTP status code. Status code is needed by callers
     * that retry on 5xx but not on 4xx (e.g. [YTMusicApiClient.paginateBrowse]).
     *
     * @property body       Parsed JSON body on 2xx, null otherwise.
     * @property statusCode HTTP status code, or [STATUS_NETWORK_ERROR] if the
     *                      call threw before completing.
     */
    internal data class RequestOutcome(val body: JsonObject?, val statusCode: Int) {
        companion object {
            const val STATUS_NETWORK_ERROR = -1
        }
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
        val variant = InnerTubeVariant.WEB_REMIX
        val body = buildJsonObject {
            put("context", buildContext(variant))
            put("browseId", browseId)
        }
        executeRequest("$BASE_URL/browse", body, cookie, variant)
    }

    /**
     * Calls the InnerTube `browse` action with a continuation token.
     *
     * Continuation requests fetch the next page of a previously-browsed surface
     * (Liked Songs, a playlist, a long mix). The token comes from the previous
     * response's `continuations[0].nextContinuationData.continuation` field.
     * The body carries the same `context` object but no `browseId` — the URL
     * query string identifies the continuation chain.
     *
     * @param continuation The continuation token from the prior page.
     * @return The parsed JSON response, or null on failure.
     */
    suspend fun browseContinuation(continuation: String): JsonObject? = withContext(Dispatchers.IO) {
        val cookie = tokenManager.getYouTubeCookie()
        val variant = InnerTubeVariant.WEB_REMIX
        val body = buildJsonObject {
            put("context", buildContext(variant))
        }
        executeRequestWithStatus(
            url = "$BASE_URL/browse?ctoken=$continuation&continuation=$continuation&type=next",
            body = body,
            cookie = cookie,
            variant = variant,
        ).body
    }

    /**
     * Calls the InnerTube `search` action.
     *
     * @param query The search query string.
     * @return The parsed JSON response, or null on failure.
     */
    suspend fun search(query: String): JsonObject? = withContext(Dispatchers.IO) {
        val cookie = tokenManager.getYouTubeCookie()
        val variant = InnerTubeVariant.WEB_REMIX
        val body = buildJsonObject {
            put("context", buildContext(variant))
            put("query", query)
        }
        executeRequest("$BASE_URL/search", body, cookie, variant)
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
    suspend fun player(
        videoId: String,
        variant: InnerTubeVariant = InnerTubeVariant.WEB_REMIX,
    ): JsonObject? = withContext(Dispatchers.IO) {
        val cookie = tokenManager.getYouTubeCookie()
        val body = buildJsonObject {
            put("context", buildContext(variant))
            put("videoId", videoId)
        }
        executeRequest("$BASE_URL/player", body, cookie, variant)
    }

    /**
     * Audio-focused player lookup. Tries each variant in [AUDIO_VARIANT_ORDER]
     * until one returns `streamingData.adaptiveFormats` with at least one
     * entry carrying a direct `url` (i.e. unciphered). Returns the first
     * such response, or the last-tried response if none were unciphered
     * so downstream code still has *something* to parse.
     *
     * Rationale: YouTube serves different response shapes per client.
     * WEB_REMIX wraps URLs in `signatureCipher`, which forces our yt-dlp
     * fallback (~14 s with QuickJS). ANDROID_VR / IOS frequently return
     * direct URLs that play natively, cutting extraction to ~200 ms.
     */
    suspend fun playerForAudio(videoId: String): JsonObject? {
        var lastResponse: JsonObject? = null
        for (variant in AUDIO_VARIANT_ORDER) {
            val response = runCatching { player(videoId, variant) }
                .onFailure {
                    Log.w(TAG, "playerForAudio variant=$variant threw: ${it.message}")
                }
                .getOrNull()
                ?: continue
            lastResponse = response
            if (hasDirectAudioUrl(response)) {
                Log.d(TAG, "playerForAudio videoId=$videoId won with variant=$variant")
                return response
            }
            Log.d(TAG, "playerForAudio variant=$variant gave no direct URL for $videoId")
        }
        return lastResponse
    }

    /**
     * Returns true when [response] contains at least one
     * `streamingData.adaptiveFormats[*]` entry with a direct `url` field
     * (as opposed to a `signatureCipher`-wrapped entry that needs JS solving).
     */
    private fun hasDirectAudioUrl(response: JsonObject): Boolean {
        val formats: JsonArray = response["streamingData"]?.jsonObject
            ?.get("adaptiveFormats")?.jsonArray ?: return false
        return formats.any { format ->
            val obj = format as? JsonObject ?: return@any false
            val mime = obj["mimeType"]?.jsonPrimitive?.content ?: return@any false
            mime.startsWith("audio/") && obj["url"] != null
        }
    }

    /**
     * Fetches the `playbackTracking.videostatsPlaybackUrl.baseUrl` for a
     * video id by posting to `/youtubei/v1/player`.
     *
     * This is the URL that registers a view in the user's YouTube Watch
     * History. Hitting it on behalf of a known-played track is what
     * `YouTubeHistoryScrobbler` uses to retroactively write history entries.
     *
     * Mirrors the [player] POST exactly — same context / auth / headers —
     * and delegates field extraction to [PlaybackTrackingParser].
     *
     * @param videoId The YouTube video ID.
     * @return The playback-tracking base URL, or null on HTTP failure or if
     *   the `playbackTracking` block is absent from the response.
     */
    suspend fun getPlaybackTracking(videoId: String): String? =
        withContext(Dispatchers.IO) {
            val cookie = tokenManager.getYouTubeCookie()
            val variant = InnerTubeVariant.WEB_REMIX
            val body = buildJsonObject {
                put("context", buildContext(variant))
                put("videoId", videoId)
            }
            val response = executeRequest("$BASE_URL/player", body, cookie, variant)
                ?: return@withContext null
            PlaybackTrackingParser().extract(response)
                .also { url ->
                    if (url == null) {
                        Log.w(TAG, "getPlaybackTracking: no playbackTracking block for $videoId")
                    }
                }
        }

    /**
     * Finds the canonical YouTube Music video id for a track, preferring
     * ATV (Topic-channel audio) over OMV (official music video), and
     * skipping UGC and other non-music types.
     *
     * Uses the standard InnerTube `search` endpoint and walks the Songs
     * shelf results. For each candidate the `watchEndpointMusicConfig
     * .musicVideoType` field is read directly from the InnerTube JSON so
     * that the filter can run without a separate round-trip.
     *
     * Scoring is intentionally simple: the first ATV result wins, then the
     * first OMV result. The kill-switch in `YouTubeHistoryScrobbler` and the
     * strict ATV/OMV filter here are the real safeguards; replicating the
     * full [com.stash.data.download.matching.MatchScorer] heuristics would
     * be over-engineering for a caller that already has a confirmed
     * artist + title from the user's own watch history.
     *
     * @param artist The track artist.
     * @param title  The track title.
     * @return The video id of the best ATV or OMV match, or null if none found.
     */
    suspend fun searchCanonical(artist: String, title: String): String? =
        withContext(Dispatchers.IO) {
            val query = "$artist $title"
            val response = search(query) ?: return@withContext null

            // Walk the Songs shelf(ves) inside the search response. Each row is a
            // musicResponsiveListItemRenderer; we extract videoId and musicVideoType
            // inline so no separate parser dependency is needed.
            val shelves: JsonArray = response["contents"]
                ?.jsonObject?.get("tabbedSearchResultsRenderer")
                ?.jsonObject?.get("tabs")
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("tabRenderer")
                ?.jsonObject?.get("content")
                ?.jsonObject?.get("sectionListRenderer")
                ?.jsonObject?.get("contents")
                ?.jsonArray ?: return@withContext null

            // Collect (videoId, musicVideoType) pairs from Songs shelves only.
            data class Candidate(val videoId: String, val type: MusicVideoType?)
            val candidates = mutableListOf<Candidate>()

            for (shelf in shelves) {
                val shelfObj = shelf.jsonObject
                val renderer = shelfObj["musicShelfRenderer"]?.jsonObject ?: continue
                // Only process the Songs shelf (skip Artists, Albums, etc.)
                val shelfTitle = renderer["title"]?.jsonObject
                    ?.get("runs")?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("text")?.jsonPrimitive?.content
                if (shelfTitle != null && shelfTitle != "Songs") continue

                val items = renderer["contents"]?.jsonArray ?: continue
                for (item in items) {
                    val row = item.jsonObject["musicResponsiveListItemRenderer"]?.jsonObject
                        ?: continue

                    val videoId = row["playlistItemData"]?.jsonObject
                        ?.get("videoId")?.jsonPrimitive?.content
                        ?: row["overlay"]?.jsonObject
                            ?.get("musicItemThumbnailOverlayRenderer")?.jsonObject
                            ?.get("content")?.jsonObject
                            ?.get("musicPlayButtonRenderer")?.jsonObject
                            ?.get("playNavigationEndpoint")?.jsonObject
                            ?.get("watchEndpoint")?.jsonObject
                            ?.get("videoId")?.jsonPrimitive?.content
                        ?: continue

                    val rawType = row["overlay"]?.jsonObject
                        ?.get("musicItemThumbnailOverlayRenderer")?.jsonObject
                        ?.get("content")?.jsonObject
                        ?.get("musicPlayButtonRenderer")?.jsonObject
                        ?.get("playNavigationEndpoint")?.jsonObject
                        ?.get("watchEndpoint")?.jsonObject
                        ?.get("watchEndpointMusicSupportedConfigs")?.jsonObject
                        ?.get("watchEndpointMusicConfig")?.jsonObject
                        ?.get("musicVideoType")?.jsonPrimitive?.content

                    candidates.add(Candidate(videoId, MusicVideoType.fromInnerTube(rawType)))
                }
            }

            Log.d(TAG, "searchCanonical('$query'): ${candidates.size} candidates, " +
                "atv=${candidates.count { it.type == MusicVideoType.ATV }}, " +
                "omv=${candidates.count { it.type == MusicVideoType.OMV }}, " +
                "ugc=${candidates.count { it.type == MusicVideoType.UGC }}")

            // Prefer ATV first, then OMV; skip UGC and everything else.
            val best = candidates.firstOrNull { it.type == MusicVideoType.ATV }
                ?: candidates.firstOrNull { it.type == MusicVideoType.OMV }

            if (best == null) {
                Log.w(TAG, "searchCanonical('$query'): no ATV/OMV candidate found")
            } else {
                Log.d(TAG, "searchCanonical('$query'): resolved → ${best.videoId} (${best.type})")
            }
            best?.videoId
        }

    /**
     * Builds the InnerTube client context object required by every request.
     * The [variant]'s `client` fields are spread into the context so
     * YouTube recognises the impersonated client.
     */
    private fun buildContext(variant: InnerTubeVariant): JsonObject = buildJsonObject {
        putJsonObject("client") {
            put("clientName", variant.clientName)
            put("clientVersion", variant.currentVersion())
            put("hl", "en")
            put("gl", "US")
            variant.extraClientFields.forEach { (k, v) ->
                when (v) {
                    is String -> put(k, v)
                    is Int -> put(k, v)
                    is Long -> put(k, v)
                    is Boolean -> put(k, v)
                    else -> put(k, v.toString())
                }
            }
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
    /**
     * Executes a POST against the InnerTube API. Returns both the parsed body
     * (if 2xx) and the HTTP status code so callers can distinguish retryable
     * (5xx, network) from non-retryable (4xx) failures.
     */
    internal fun executeRequestWithStatus(
        url: String,
        body: JsonObject,
        cookie: String?,
        variant: InnerTubeVariant,
    ): RequestOutcome {
        val sapiSid = cookie?.let { cookieHelper.extractSapiSid(it) }

        val separator = if (url.contains('?')) '&' else '?'
        val fullUrl = if (sapiSid != null) {
            "${url}${separator}prettyPrint=false"
        } else {
            "${url}${separator}key=$API_KEY&prettyPrint=false"
        }

        Log.d(
            TAG,
            "executeRequest: POST $fullUrl (authenticated=${sapiSid != null}, variant=$variant)",
        )

        val requestBuilder = Request.Builder()
            .url(fullUrl)
            .post(body.toString().toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .header("User-Agent", variant.userAgent)
            .header("X-YouTube-Client-Name", variant.clientName)
            .header("X-YouTube-Client-Version", variant.currentVersion())

        // Cookies + SAPISIDHASH auth only make sense against the WEB family;
        // sending them to IOS / ANDROID_VR clients either no-ops or in some
        // cases earns the request a server-side reject. Skip for non-WEB.
        if (variant == InnerTubeVariant.WEB_REMIX &&
            sapiSid != null && cookie != null
        ) {
            requestBuilder
                .header("Cookie", cookie)
                .header("Authorization", cookieHelper.generateAuthHeader(sapiSid))
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .header("X-Goog-AuthUser", "0")
        }

        return try {
            okHttpClient.newCall(requestBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errorBodyLen = resp.body?.string()?.length ?: 0
                    Log.e(TAG, "executeRequest: HTTP ${resp.code}, errorBodyLen=$errorBodyLen")
                    return@use RequestOutcome(body = null, statusCode = resp.code)
                }
                val responseBody = resp.body?.string()
                    ?: return@use RequestOutcome(body = null, statusCode = resp.code)
                Log.d(TAG, "executeRequest: success, response length=${responseBody.length}")
                RequestOutcome(json.parseToJsonElement(responseBody).jsonObject, resp.code)
            }
        } catch (e: Exception) {
            Log.w(TAG, "executeRequest: threw ${e.javaClass.simpleName}: ${e.message}")
            RequestOutcome(body = null, statusCode = RequestOutcome.STATUS_NETWORK_ERROR)
        }
    }

    /** Backwards-compatible wrapper for callers that don't need the status code. */
    private fun executeRequest(
        url: String,
        body: JsonObject,
        cookie: String?,
        variant: InnerTubeVariant,
    ): JsonObject? = executeRequestWithStatus(url, body, cookie, variant).body

    /** Test-only convenience that builds a minimal request and returns the outcome. */
    internal fun executeRequestWithStatusForTest(url: String): RequestOutcome =
        executeRequestWithStatus(
            url = url,
            body = buildJsonObject { put("test", "true") },
            cookie = null,
            variant = InnerTubeVariant.WEB_REMIX,
        )

    /**
     * Test-only entry point that lets the test redirect [BASE_URL] to a
     * MockWebServer. Identical to [browseContinuation] except the host is
     * supplied externally.
     */
    internal suspend fun browseContinuationForTest(continuation: String, baseUrl: String): JsonObject? =
        withContext(Dispatchers.IO) {
            val cookie = tokenManager.getYouTubeCookie()
            val variant = InnerTubeVariant.WEB_REMIX
            val body = buildJsonObject { put("context", buildContext(variant)) }
            executeRequestWithStatus(
                url = "$baseUrl/youtubei/v1/browse?ctoken=$continuation&continuation=$continuation&type=next",
                body = body,
                cookie = cookie,
                variant = variant,
            ).body
        }
}
