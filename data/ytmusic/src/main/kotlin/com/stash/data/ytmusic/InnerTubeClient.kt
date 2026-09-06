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
    /** Host this client must POST against. Defaults to the YT-Music host. */
    val apiBase: String = "https://music.youtube.com/youtubei/v1",
    /** Numeric InnerTube client id for the `X-YouTube-Client-Name` header. */
    val clientNameId: String = "",
    /** Whether unauthenticated requests should append the YT-Music API key. */
    val sendsApiKey: Boolean = false,
) {
    /**
     * Oculus Quest 3 VR browser. Returns direct, unciphered audio URLs with no PO
     * token — this is the client yt-dlp pins as `player_client=android_vr`, and
     * every URL Stash currently streams through the YouTube fallback comes from
     * it (via a ~3.6 s Python process instead of this ~200 ms request).
     *
     * Must be queried on the `www.youtube.com` host, keyless, with the numeric
     * client-name header (`28`). It was dropped from [AUDIO_VARIANT_ORDER] once
     * as "no longer unciphered" while still pointing at the YT-Music host with a
     * blank client-name id — the identical misconfiguration that made IOS look
     * broken until it was moved to www/keyless/numeric.
     */
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
        apiBase = "https://www.youtube.com/youtubei/v1",
        clientNameId = "28",
        sendsApiKey = false,
    ),

    /**
     * iOS YouTube app. Returns direct, unciphered audio URLs in <1 s when hit
     * on the `www.youtube.com` host, keyless, with the numeric client-name
     * header (`5`) and a current app version. This is the fast lane; the
     * config below was proven on-device against a no-rebuild spike.
     */
    IOS(
        clientName = "IOS",
        clientVersion = "21.02.3",
        userAgent =
            "com.google.ios.youtube/21.02.3 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
        extraClientFields = mapOf(
            "deviceMake" to "Apple",
            "deviceModel" to "iPhone16,2",
            "osName" to "iPhone",
            "osVersion" to "18.3.2.22D82",
        ),
        apiBase = "https://www.youtube.com/youtubei/v1",
        clientNameId = "5",
        sendsApiKey = false,
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
        clientNameId = "67",
        sendsApiKey = true,
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
/**
 * A canonical song match: the resolved [videoId] plus, when present, the
 * song's square YT Music album-art thumbnail (high-res lh3).
 */
data class CanonicalMatch(val videoId: String, val thumbnailUrl: String?)

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
         * Ordered attempt list for audio URL extraction.
         *
         * ANDROID_VR first: it is the client yt-dlp pins to get a direct itag-251
         * URL with no PO token, no m3u8 manifest and no QuickJS signature solve.
         * It was removed from this list once as "no longer unciphered", but it had
         * never been given the www-host / numeric-client-id / keyless transport
         * that the same change applied to IOS — so it was judged on a
         * misconfiguration rather than on merit.
         *
         * IOS second: the proven fast lane, kept as the backstop.
         *
         * WEB_REMIX is deliberately absent: it wraps URLs in `signatureCipher`,
         * which forces the yt-dlp path anyway, so trying it only adds latency.
         *
         * `internal` so variant tests can assert the order without widening to
         * the public API.
         */
        internal val AUDIO_VARIANT_ORDER = listOf(
            InnerTubeVariant.ANDROID_VR,
            InnerTubeVariant.IOS,
        )
    }

    @Volatile private var sessionActive: Boolean = false
    @Volatile private var cachedCookie: String? = null
    @Volatile private var cachedSapiSid: String? = null
    @Volatile private var cachedAuthHeader: String? = null

    /**
     * Marks the start of a sync run. The first authenticated request inside the
     * session populates the cookie / SAPISID / auth-header cache; subsequent
     * requests reuse it. A 401 response anywhere in the session clears the
     * cache so the next call re-resolves (typically forcing re-auth).
     *
     * Safe to call from any thread. Non-sync callers (search, player, etc.)
     * are unaffected — they continue to resolve auth per call.
     */
    suspend fun beginSyncSession() {
        sessionActive = true
        cachedCookie = null
        cachedSapiSid = null
        cachedAuthHeader = null
    }

    /** Ends the sync session and clears the auth cache. Idempotent. */
    fun endSyncSession() {
        sessionActive = false
        cachedCookie = null
        cachedSapiSid = null
        cachedAuthHeader = null
    }

    /** Internal: invalidate cached auth on receipt of a 401. */
    private fun invalidateAuthCache() {
        cachedCookie = null
        cachedSapiSid = null
        cachedAuthHeader = null
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
    suspend fun browse(browseId: String, params: String? = null): JsonObject? = withContext(Dispatchers.IO) {
        val variant = InnerTubeVariant.WEB_REMIX
        val body = buildJsonObject {
            put("context", buildContext(variant))
            put("browseId", browseId)
            // params scopes a browse (e.g. an artist-discography "View all" grid
            // to albums-only vs singles-only). Omitted → unfiltered/default view.
            if (!params.isNullOrBlank()) put("params", params)
        }
        executeRequest("$BASE_URL/browse", body, null, variant)
    }

    /**
     * Calls the InnerTube `next` action.
     *
     * `next` is the "watch next" endpoint — given a videoId it returns the
     * surfaces that flank the current playback (up-next queue, related,
     * lyrics tab, etc.). For lyrics discovery, the response carries a
     * `singleColumnMusicWatchNextResultsRenderer.tabbedRenderer.tabs[]`
     * array; the tab whose endpoint points at `MPLY...` is the lyrics page,
     * which can then be fetched via [browse].
     *
     * @param videoId The YouTube video ID.
     * @return The parsed JSON response, or null on failure.
     */
    suspend fun next(videoId: String): JsonObject? = withContext(Dispatchers.IO) {
        val variant = InnerTubeVariant.WEB_REMIX
        val body = buildJsonObject {
            put("context", buildContext(variant))
            put("videoId", videoId)
        }
        executeRequest("$BASE_URL/next", body, null, variant)
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
        val variant = InnerTubeVariant.WEB_REMIX
        val body = buildJsonObject {
            put("context", buildContext(variant))
        }
        executeRequestWithStatus(
            url = "$BASE_URL/browse?ctoken=$continuation&continuation=$continuation&type=next",
            body = body,
            cookie = null,
            variant = variant,
        ).body
    }

    /**
     * Like [browseContinuation] but returns the full HTTP outcome so callers
     * (specifically [YTMusicApiClient.paginateBrowse]) can distinguish 4xx
     * (no retry, likely auth) from 5xx/network (retry).
     */
    internal suspend fun browseWithStatus(continuation: String): RequestOutcome =
        withContext(Dispatchers.IO) {
            val cookie = tokenManager.getYouTubeCookie()
            val variant = InnerTubeVariant.WEB_REMIX
            val body = buildJsonObject { put("context", buildContext(variant)) }
            executeRequestWithStatus(
                url = "$BASE_URL/browse?ctoken=$continuation&continuation=$continuation&type=next",
                body = body,
                cookie = cookie,
                variant = variant,
            )
        }

    /**
     * Calls the InnerTube `search` action.
     *
     * @param query The search query string.
     * @return The parsed JSON response, or null on failure.
     */
    suspend fun search(query: String, params: String? = null): JsonObject? = withContext(Dispatchers.IO) {
        val cookie = tokenManager.getYouTubeCookie()
        val variant = InnerTubeVariant.WEB_REMIX
        val body = buildJsonObject {
            put("context", buildContext(variant))
            put("query", query)
            if (params != null) put("params", params)
        }
        executeRequest("$BASE_URL/search", body, cookie, variant)
    }

    /**
     * Calls the InnerTube `search` action, preserving the HTTP outcome.
     *
     * Unlike [search], which discards the status code and collapses every
     * failure into `null`, this returns the [RequestOutcome] so a caller can
     * tell "the API answered but found nothing" apart from "the API did not
     * answer" (network failure, 4xx/5xx, rate limit) — the distinction the
     * artist-photo backfill needs to decide between a permanent "no photo"
     * sentinel and a transient retry.
     */
    internal suspend fun searchWithStatus(query: String, params: String? = null): RequestOutcome =
        withContext(Dispatchers.IO) {
            val cookie = tokenManager.getYouTubeCookie()
            val variant = InnerTubeVariant.WEB_REMIX
            val body = buildJsonObject {
                put("context", buildContext(variant))
                put("query", query)
                if (params != null) put("params", params)
            }
            executeRequestWithStatus("$BASE_URL/search", body, cookie, variant)
        }

    /** ytmusicapi-derived filter selector that constrains search results to the
     *  "Songs" shelf only. With this set, the response reverts to the legacy
     *  `musicShelfRenderer` shape that downstream parsers expect. */
    private val songsFilterParams = "EgWKAQIIAWoKEAoQAxAJEAQQBQ%3D%3D"

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
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        executeRequest("${variant.apiBase}/player", body, cookie, variant)
    }

    /**
     * v0.9.13: Like a track in YouTube Music. Sets `likeStatus = LIKE`
     * on the videoId, which adds it to the user's Liked Music library
     * AND surfaces it as recommendation input. Auth via existing
     * SAPISID-hash flow (per-request header).
     *
     * Returns true on success. Logs and returns false on failure
     * (signed-out, network down, endpoint blocked) — caller treats
     * non-success as "skip this destination, surface snackbar."
     */
    suspend fun likeVideo(videoId: String): Boolean =
        sendLikeAction("$BASE_URL/like/like", videoId)

    /**
     * v0.9.52: symmetric un-like — sets likeStatus back to INDIFFERENT
     * and removes the track from Liked Music. Same payload/auth shape
     * as [likeVideo]; same boolean soft-failure contract.
     */
    suspend fun removeLike(videoId: String): Boolean =
        sendLikeAction("$BASE_URL/like/removelike", videoId)

    private suspend fun sendLikeAction(url: String, videoId: String): Boolean = runCatching {
        val variant = InnerTubeVariant.WEB_REMIX
        val payload = buildJsonObject {
            put("context", buildContext(variant))
            put("target", buildJsonObject { put("videoId", videoId) })
        }
        val outcome = executeRequestWithStatus(
            url = url,
            body = payload,
            cookie = null,
            variant = variant,
        )
        outcome.body != null && outcome.statusCode in 200..299
    }.getOrElse { e ->
        Log.w(TAG, "like action failed for $videoId at $url: ${e.message}")
        false
    }

    /** Test-only seam mirroring [executeRequestWithStatusForTest] — lets a test
     * point the like action at a MockWebServer URL. */
    internal suspend fun sendLikeActionForTest(url: String, videoId: String): Boolean =
        sendLikeAction(url, videoId)

    /**
     * Audio-focused player lookup. Tries each variant in [AUDIO_VARIANT_ORDER]
     * — currently IOS only — until one returns `streamingData.adaptiveFormats`
     * with at least one entry carrying a direct `url` (i.e. unciphered).
     * Returns that response, or the last-tried response if none were
     * unciphered so downstream code still has *something* to parse.
     *
     * Rationale: YouTube serves different response shapes per client. The IOS
     * client (queried against www.youtube.com) frequently returns direct,
     * unciphered URLs that play natively, cutting extraction to ~200 ms. On a
     * miss the last response is returned, which downstream maps to the yt-dlp
     * fallback (~14 s with QuickJS) for the `signatureCipher` case.
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
            // YT's anti-bot challenge for /player rejects requests missing
            // playbackContext.contentPlaybackContext.signatureTimestamp with
            // a RELOAD_PAGE error. signatureTimestamp is the day number
            // (days since epoch) and tells YouTube which JS player build the
            // client is on; sending today's value passes the freshness check.
            val signatureTimestamp = (System.currentTimeMillis() / 86_400_000L).toInt() - 1
            val body = buildJsonObject {
                put("context", buildContext(variant))
                put("videoId", videoId)
                putJsonObject("playbackContext") {
                    putJsonObject("contentPlaybackContext") {
                        put("signatureTimestamp", signatureTimestamp)
                    }
                }
            }
            val response = executeRequest("$BASE_URL/player", body, cookie, variant)
                ?: return@withContext null
            PlaybackTrackingParser().extract(response)
                .also { url ->
                    if (url == null) {
                        val playStatus = response["playabilityStatus"]?.jsonObject
                            ?.get("status")?.jsonPrimitive?.content
                        Log.w(TAG, "getPlaybackTracking: no playbackTracking block for " +
                            "$videoId (playabilityStatus=$playStatus)")
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
        searchCanonicalMatch(artist, title)?.videoId

    /**
     * Like [searchCanonical] but also returns the matched song's square YT
     * Music album-art thumbnail (lh3, upgraded to high-res) from the same
     * search response — no extra request. Radio tracks resolved this way
     * (song radio, Last.fm neighbours) otherwise fall back to the low-res
     * `mqdefault` video frame; this gives them a crisp, no-bars cover.
     */
    suspend fun searchCanonicalMatch(artist: String, title: String): CanonicalMatch? =
        withContext(Dispatchers.IO) {
            val query = "$artist $title"
            val response = search(query, params = songsFilterParams) ?: return@withContext null

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

            // Collect (videoId, musicVideoType, thumbnail) from Songs shelves only.
            data class Candidate(val videoId: String, val type: MusicVideoType?, val thumbnailUrl: String?)
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

                    // Square album-art thumbnail (lh3) — largest of the set,
                    // upgraded to high-res. Absent for some rows; that's fine.
                    val thumbnailUrl = row["thumbnail"]?.jsonObject
                        ?.get("musicThumbnailRenderer")?.jsonObject
                        ?.get("thumbnail")?.jsonObject
                        ?.get("thumbnails")?.jsonArray
                        ?.lastOrNull()?.jsonObject
                        ?.get("url")?.jsonPrimitive?.content
                        ?.let { com.stash.core.common.ArtUrlUpgrader.upgrade(it) }

                    candidates.add(Candidate(videoId, MusicVideoType.fromInnerTube(rawType), thumbnailUrl))
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
            best?.let { CanonicalMatch(it.videoId, it.thumbnailUrl) }
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
     * Executes a POST against the InnerTube API. Returns both the parsed body
     * (if 2xx) and the HTTP status code so callers can distinguish retryable
     * (5xx, network) from non-retryable (4xx) failures.
     *
     * When a sync session is active ([beginSyncSession] was called), auth
     * resolution is cached across calls: cookie + SAPISID + Authorization
     * header are fetched once and reused. A 401 response clears the cache so
     * the next call re-resolves. Non-session callers resolve auth per request.
     */
    internal suspend fun executeRequestWithStatus(
        url: String,
        body: JsonObject,
        cookie: String?,
        variant: InnerTubeVariant,
    ): RequestOutcome = withContext(Dispatchers.IO) {
        val (effectiveCookie, sapiSid, authHeader) = resolveAuth(cookie, variant)

        val separator = if (url.contains('?')) '&' else '?'
        val fullUrl = when {
            sapiSid != null -> "${url}${separator}prettyPrint=false"
            variant.sendsApiKey -> "${url}${separator}key=$API_KEY&prettyPrint=false"
            else -> "${url}${separator}prettyPrint=false" // keyless (IOS etc.)
        }

        Log.d(TAG, "executeRequest: POST $fullUrl (authenticated=${sapiSid != null}, variant=$variant)")

        val requestBuilder = Request.Builder()
            .url(fullUrl)
            .post(body.toString().toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .header("User-Agent", variant.userAgent)
            .header("X-YouTube-Client-Name", variant.clientNameId.ifBlank { variant.clientName })
            .header("X-YouTube-Client-Version", variant.currentVersion())

        // Cookies + SAPISIDHASH auth only make sense against the WEB family;
        // sending them to IOS / ANDROID_VR clients either no-ops or in some
        // cases earns the request a server-side reject. Skip for non-WEB.
        if (variant == InnerTubeVariant.WEB_REMIX && sapiSid != null && effectiveCookie != null && authHeader != null) {
            requestBuilder
                .header("Cookie", effectiveCookie)
                .header("Authorization", authHeader)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .header("X-Goog-AuthUser", "0")
        }

        try {
            okHttpClient.newCall(requestBuilder.build()).execute().use { resp ->
                if (resp.code == 401) invalidateAuthCache()
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

    /**
     * Resolves auth credentials for a request, using the session cache when a
     * sync session is active and falling back to per-call resolution otherwise.
     *
     * @param explicitCookie A cookie provided directly by the caller (rare); if
     *   null the token manager is queried.
     * @param variant        The InnerTube client variant (auth is only used for
     *   [InnerTubeVariant.WEB_REMIX]).
     * @return Triple of (cookie, sapiSid, authHeader); any element may be null
     *   if auth is unavailable or not needed for the given variant.
     */
    private suspend fun resolveAuth(
        explicitCookie: String?,
        variant: InnerTubeVariant,
    ): Triple<String?, String?, String?> {
        if (!sessionActive) {
            val c = explicitCookie ?: tokenManager.getYouTubeCookie()
            val s = c?.let { cookieHelper.extractSapiSid(it) }
            val a = s?.let { cookieHelper.generateAuthHeader(it) }
            return Triple(c, s, a)
        }
        // Session active: serve from cache, populate on first miss.
        val cachedC = cachedCookie
        val cachedS = cachedSapiSid
        val cachedA = cachedAuthHeader
        if (cachedC != null && cachedS != null && cachedA != null) {
            return Triple(cachedC, cachedS, cachedA)
        }
        val c = explicitCookie ?: tokenManager.getYouTubeCookie()
        val s = c?.let { cookieHelper.extractSapiSid(it) }
        val a = s?.let { cookieHelper.generateAuthHeader(it) }
        cachedCookie = c
        cachedSapiSid = s
        cachedAuthHeader = a
        return Triple(c, s, a)
    }

    /** Backwards-compatible wrapper for callers that don't need the status code. */
    private suspend fun executeRequest(
        url: String,
        body: JsonObject,
        cookie: String?,
        variant: InnerTubeVariant,
    ): JsonObject? = executeRequestWithStatus(url, body, cookie, variant).body

    /** Test-only convenience that builds a minimal request and returns the outcome. */
    internal suspend fun executeRequestWithStatusForTest(url: String): RequestOutcome =
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
            val variant = InnerTubeVariant.WEB_REMIX
            val body = buildJsonObject { put("context", buildContext(variant)) }
            executeRequestWithStatus(
                url = "$baseUrl/youtubei/v1/browse?ctoken=$continuation&continuation=$continuation&type=next",
                body = body,
                cookie = null,
                variant = variant,
            ).body
        }
}
