package com.stash.core.data.share

import com.stash.core.model.share.ShareConfig
import com.stash.core.model.share.ShareLinks
import com.stash.core.model.share.SharedTrack
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /** Test seam; off the constructor because Hilt rejects @Inject with default params. */
    internal var baseUrl: String = ShareConfig.BASE_URL

    data class Created(val id: String, val version: Int)
    @Serializable private data class CreatedBody(val id: String, val version: Int)
    @Serializable private data class VersionBody(val version: Int)

    private fun docJson(doc: SharedMixDocument) = ShareJson.encodeToJsonElement(SharedMixDocument.serializer(), doc)

    suspend fun create(doc: SharedMixDocument, editKey: String): ShareResult<Created> {
        val body = buildJsonObject { put("doc", docJson(doc)); put("editKey", editKey) }
        return call(Request.Builder().url("$baseUrl/v1/mixes").post(body.toBody())) {
            ShareJson.decodeFromString(CreatedBody.serializer(), it).let { c -> Created(c.id, c.version) }
        }
    }

    /** [baseVersion] = the version this phone last got back; the Worker never goes below it + 1 (stale-KV guard). */
    suspend fun update(id: String, doc: SharedMixDocument, editKey: String, baseVersion: Int): ShareResult<Int> {
        val body = buildJsonObject { put("doc", docJson(doc)); put("baseVersion", baseVersion) }
        return call(Request.Builder().url("$baseUrl/v1/mixes/$id").header(KEY_HEADER, editKey).put(body.toBody())) {
            ShareJson.decodeFromString(VersionBody.serializer(), it).version
        }
    }

    suspend fun delete(id: String, editKey: String): ShareResult<Unit> =
        call(Request.Builder().url("$baseUrl/v1/mixes/$id").header(KEY_HEADER, editKey).delete()) { }

    suspend fun get(id: String): ShareResult<SharedMixDocument> =
        call(Request.Builder().url("$baseUrl/v1/mixes/$id").get()) { ShareJson.decodeFromString(SharedMixDocument.serializer(), it) }

    suspend fun version(id: String): ShareResult<Int> =
        call(Request.Builder().url("$baseUrl/v1/mixes/$id/version").get()) { ShareJson.decodeFromString(VersionBody.serializer(), it).version }

    /** Test seam for [createTrackLink]'s call timeout. */
    internal var trackLinkTimeoutMs: Long = ShareLinks.SHORT_LINK_TIMEOUT_MS

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
        // Bounds the whole call (every address, TLS, the answer): the share sheet's withTimeout can't cut a
        // blocking execute() short, and the person sharing is waiting on it.
        val http = okHttpClient.newBuilder().callTimeout(trackLinkTimeoutMs, TimeUnit.MILLISECONDS).build()
        return http.shareCall(Request.Builder().url("$baseUrl/v1/tracks").post(json)) {
            val id = ShareJson.decodeFromString(TrackLinkBody.serializer(), it).id
            // Built from the id, like mix links, so it's always a link this app's App Links open.
            require(ShareLinks.isShareId(id)) { "bad track id" }
            TrackLink(id, ShareLinks.trackShortUrl(id))
        }
    }

    /** The song behind a short link; [ShareResult.NotFound] when no link has that id. */
    suspend fun getTrack(id: String): ShareResult<SharedTrack> =
        call(Request.Builder().url("$baseUrl/v1/tracks/$id").get()) { ShareJson.decodeFromString(TrackBody.serializer(), it).track }

    private suspend fun <T> call(builder: Request.Builder, parse: (String) -> T): ShareResult<T> =
        okHttpClient.shareCall(builder, parse)

    private fun JsonObject.toBody() = toString().toRequestBody(JSON)

    private companion object {
        const val KEY_HEADER = "X-Stash-Edit-Key"
        val JSON = "application/json".toMediaType()
    }
}

/** One request to the stash-share Worker, mapped to a [ShareResult]. Shared by the mix and room clients. */
internal suspend fun <T> OkHttpClient.shareCall(builder: Request.Builder, parse: (String) -> T): ShareResult<T> =
    withContext(Dispatchers.IO) {
        runCatching {
            newCall(builder.build()).execute().use { r ->
                when (r.code) {
                    in 200..299 -> ShareResult.Ok(parse(r.body?.string().orEmpty()))
                    403 -> ShareResult.Forbidden
                    404 -> ShareResult.NotFound
                    410 -> ShareResult.Gone
                    429 -> ShareResult.Failed(ShareResult.Failed.RATE_LIMITED)
                    in 400..499 -> ShareResult.Rejected(r.code)
                    else -> ShareResult.Failed("HTTP ${r.code}")
                }
            }
        }.getOrElse { t ->
            if (t is kotlinx.coroutines.CancellationException) throw t
            ShareResult.Failed(t.message)
        }
    }
