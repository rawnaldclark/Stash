package com.stash.core.data.share

import com.stash.core.model.share.ShareConfig
import com.stash.core.model.share.ShareLinks
import com.stash.core.model.share.SharedTrack
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface ShareResult<out T> {
    data class Ok<T>(val value: T) : ShareResult<T>
    data object NotFound : ShareResult<Nothing>
    data object Gone : ShareResult<Nothing>
    data object Forbidden : ShareResult<Nothing>
    /** 400/413/other 4xx: the server will never accept this request as sent, so don't retry it. */
    data class Rejected(val code: Int) : ShareResult<Nothing>
    /** Network failure, 429 or 5xx: worth retrying later. */
    data class Failed(val message: String?) : ShareResult<Nothing> {
        companion object {
            /** The [message] for a 429, so a screen can say "wait a minute" rather than "couldn't reach". */
            const val RATE_LIMITED = "HTTP 429"
        }
    }
}

/** HTTP client for the stash-share Worker (spec §4): shared mixes and short song links. */
@Singleton
class ShareApiClient @Inject constructor(private val okHttpClient: OkHttpClient) {
    /** The hosts tried in turn. Test seam; off the constructor because Hilt rejects @Inject with default params. */
    internal var baseUrls: List<String> = ShareConfig.API_BASE_URLS
    private val http = ShareHttp(okHttpClient)

    data class Created(val id: String, val version: Int)
    @Serializable private data class CreatedBody(val id: String, val version: Int)
    @Serializable private data class VersionBody(val version: Int)

    private fun docJson(doc: SharedMixDocument) = ShareJson.encodeToJsonElement(SharedMixDocument.serializer(), doc)

    suspend fun create(doc: SharedMixDocument, editKey: String): ShareResult<Created> {
        val body = buildJsonObject { put("doc", docJson(doc)); put("editKey", editKey) }
        return call("/v1/mixes", { post(body.toBody()) }) {
            ShareJson.decodeFromString(CreatedBody.serializer(), it).let { c -> Created(c.id, c.version) }
        }
    }

    /** [baseVersion] = the version this phone last got back; the Worker never goes below it + 1 (stale-KV guard). */
    suspend fun update(id: String, doc: SharedMixDocument, editKey: String, baseVersion: Int): ShareResult<Int> {
        val body = buildJsonObject { put("doc", docJson(doc)); put("baseVersion", baseVersion) }
        return call("/v1/mixes/$id", { header(KEY_HEADER, editKey).put(body.toBody()) }) {
            ShareJson.decodeFromString(VersionBody.serializer(), it).version
        }
    }

    suspend fun delete(id: String, editKey: String): ShareResult<Unit> =
        call("/v1/mixes/$id", { header(KEY_HEADER, editKey).delete() }) { }

    suspend fun get(id: String): ShareResult<SharedMixDocument> =
        call("/v1/mixes/$id") { ShareJson.decodeFromString(SharedMixDocument.serializer(), it) }

    suspend fun version(id: String): ShareResult<Int> =
        call("/v1/mixes/$id/version") { ShareJson.decodeFromString(VersionBody.serializer(), it).version }

    /** Test seam for [createTrackLink]'s call timeout; set it before the first call. */
    internal var trackLinkTimeoutMs: Long = ShareLinks.SHORT_LINK_TIMEOUT_MS

    /**
     * Bounds a short-link request on OkHttp's side (every address, TLS, the answer), so its thread is freed once
     * the person sharing has stopped waiting. Built once, on first use.
     */
    private val trackLinkHttp by lazy { ShareHttp(okHttpClient) { callTimeout(trackLinkTimeoutMs, TimeUnit.MILLISECONDS) } }

    /** A short song link: [url] is `https://stashfm.app/t/{id}`. */
    data class TrackLink(val id: String, val url: String)
    @Serializable private data class TrackLinkBody(val id: String)
    @Serializable private data class TrackBody(val track: SharedTrack)

    /**
     * The short link for [track] (contract 2026-10-03 `POST /v1/tracks`). The Worker's id is deterministic, so
     * sharing the same song again returns the same link (200 instead of 201). The cover goes only when it's on
     * [ShareConfig.COVER_HOSTS] (a local art path never leaves the phone), and the room-only `by` never goes.
     */
    suspend fun createTrackLink(track: SharedTrack): ShareResult<TrackLink> {
        val body = track.copy(addedBy = null, artUrl = track.artUrl?.takeIf(ShareConfig::isAllowedCover))
        val json = ShareJson.encodeToString(SharedTrack.serializer(), body).toRequestBody(JSON)
        return trackLinkHttp.shareCall(baseUrls, "/v1/tracks", { post(json) }) {
            val id = ShareJson.decodeFromString(TrackLinkBody.serializer(), it).id
            // Built from the id, like mix links: always a stashfm.app link, whichever host answered.
            require(ShareLinks.isShareId(id)) { "bad track id" }
            TrackLink(id, ShareLinks.trackShortUrl(id))
        }
    }

    /** The song behind a short link; [ShareResult.NotFound] when no link has that id. */
    suspend fun getTrack(id: String): ShareResult<SharedTrack> =
        call("/v1/tracks/$id") { ShareJson.decodeFromString(TrackBody.serializer(), it).track }

    private suspend fun <T> call(path: String, request: Request.Builder.() -> Unit = {}, parse: (String) -> T): ShareResult<T> =
        http.shareCall(baseUrls, path, request, parse)

    private fun JsonObject.toBody() = toString().toRequestBody(JSON)

    private companion object {
        const val KEY_HEADER = "X-Stash-Edit-Key"
        val JSON = "application/json".toMediaType()
    }
}
