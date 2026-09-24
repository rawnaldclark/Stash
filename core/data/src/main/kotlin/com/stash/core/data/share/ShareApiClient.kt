package com.stash.core.data.share

import com.stash.core.model.share.ShareConfig
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
    data class Failed(val message: String?) : ShareResult<Nothing>
}

/** HTTP client for the stash-share Worker (spec §4). */
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
                    429 -> ShareResult.Failed("HTTP 429")
                    in 400..499 -> ShareResult.Rejected(r.code)
                    else -> ShareResult.Failed("HTTP ${r.code}")
                }
            }
        }.getOrElse { t ->
            if (t is kotlinx.coroutines.CancellationException) throw t
            ShareResult.Failed(t.message)
        }
    }
