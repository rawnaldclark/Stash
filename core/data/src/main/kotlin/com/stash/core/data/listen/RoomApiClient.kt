package com.stash.core.data.listen

import com.stash.core.data.community.CommunityHttp
import com.stash.core.data.community.CommunityResult
import com.stash.core.data.share.ShareHttp
import com.stash.core.data.share.ShareJson
import com.stash.core.data.share.ShareResult
import com.stash.core.data.share.cut
import com.stash.core.data.share.shareCall
import com.stash.core.model.share.ShareConfig
import com.stash.core.model.share.SharedTrack
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * REST half of Listen Together (spec 2026-09-24 §2): create a room, preview one for the Join screen. And a station's
 * session (Sessions protocol): a public room, opened with the station owner's Community key.
 */
@Singleton
class RoomApiClient @Inject constructor(okHttpClient: OkHttpClient) {
    /** The hosts tried in turn. Test seam; off the constructor because Hilt rejects @Inject with default params. */
    internal var baseUrls: List<String> = ShareConfig.API_BASE_URLS
    private val http = ShareHttp(okHttpClient)
    private val keyed = CommunityHttp(okHttpClient)

    @Serializable data class Created(val code: String, val hostKey: String, val url: String)

    /** [station] and [listeners]: a session's, which the Join screen tunes in to instead of joining. */
    @Serializable
    data class Preview(
        val hostName: String? = null,
        val memberCount: Int = 0,
        val full: Boolean = false,
        val track: SharedTrack? = null,
        val station: String? = null,
        val listeners: Int? = null,
    )

    suspend fun create(hostName: String?): ShareResult<Created> {
        val body = buildJsonObject { hostName?.trim()?.takeIf { it.isNotEmpty() }?.let { put("hostName", it.take(40)) } }
        return http.shareCall(baseUrls, "/v1/rooms", { post(body.toString().toRequestBody(JSON)) }) {
            ShareJson.decodeFromString(Created.serializer(), it)
        }
    }

    /**
     * Opens a session for station [stationId], under the station owner's Community [key]: `POST /v1/rooms {public:
     * true}`. [hostName] blank or null takes the station's own. Keeps the Worker's refusals: [CommunityResult.SESSIONS_OFF],
     * `gone` (missing, removed or hidden), `not_yours`, `blocked`, `bad_key`, `bad_request`, `rate_limited`.
     */
    suspend fun createSession(stationId: String, hostName: String?, key: String): CommunityResult<Created> {
        val body = buildJsonObject {
            put("public", true)
            put("stationId", stationId)
            hostName?.trim()?.takeIf { it.isNotEmpty() }?.let { put("hostName", it.cut(40)) }
        }
        return keyed.call(baseUrls, "/v1/rooms", key, { post(body.toString().toRequestBody(JSON)) }) {
            ShareJson.decodeFromString(Created.serializer(), it)
        }
    }

    /** [ShareResult.NotFound] = the room has ended or never existed. */
    suspend fun preview(code: String): ShareResult<Preview> =
        http.shareCall(baseUrls, "/v1/rooms/$code") {
            ShareJson.decodeFromString(Preview.serializer(), it)
        }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
