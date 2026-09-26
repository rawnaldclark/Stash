package com.stash.core.data.listen

import com.stash.core.data.share.ShareJson
import com.stash.core.data.share.ShareResult
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
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** REST half of Listen Together (spec 2026-09-24 §2): create a room, preview one for the Join screen. */
@Singleton
class RoomApiClient @Inject constructor(private val okHttpClient: OkHttpClient) {
    /** Test seam; off the constructor because Hilt rejects @Inject with default params. */
    internal var baseUrl: String = ShareConfig.BASE_URL

    @Serializable data class Created(val code: String, val hostKey: String, val url: String)

    @Serializable
    data class Preview(val hostName: String? = null, val memberCount: Int = 0, val full: Boolean = false, val track: SharedTrack? = null)

    suspend fun create(hostName: String?): ShareResult<Created> {
        val body = buildJsonObject { hostName?.trim()?.takeIf { it.isNotEmpty() }?.let { put("hostName", it.take(40)) } }
        return okHttpClient.shareCall(Request.Builder().url("$baseUrl/v1/rooms").post(body.toString().toRequestBody(JSON))) {
            ShareJson.decodeFromString(Created.serializer(), it)
        }
    }

    /** [ShareResult.NotFound] = the room has ended or never existed. */
    suspend fun preview(code: String): ShareResult<Preview> =
        okHttpClient.shareCall(Request.Builder().url("$baseUrl/v1/rooms/$code").get()) {
            ShareJson.decodeFromString(Preview.serializer(), it)
        }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
