package com.stash.data.cloud.provider.gdrive

import android.content.Intent
import android.content.IntentSender
import com.stash.core.auth.model.ServiceToken
import com.stash.data.cloud.auth.CloudTokenStore
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Collections

/**
 * A tiny Google Drive v3 behind an OkHttp interceptor: `about`, `files.get` (metadata) and
 * `files.get?alt=media` with Range. Every request is recorded so tests can check where the token
 * went (the Authorization header, never a URL).
 */
class FakeDrive : Interceptor {
    data class File(
        val id: String,
        var name: String,
        var bytes: ByteArray,
        var md5: String,
        var trashed: Boolean = false,
        /** Stash lost access (or the file was deleted for good): 404. */
        var gone: Boolean = false,
        /** Drive rate-limits requests for this file (403 userRateLimitExceeded). */
        var throttled: Boolean = false,
        var mimeType: String = "audio/flac",
    )

    val files = linkedMapOf<String, File>()
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    /** Tokens Drive accepts; anything else answers 401. */
    val validTokens = mutableSetOf("tok1")
    var abusive = mutableSetOf<String>()

    /** The Google account the tokens belong to, as `about` reports it. */
    var permissionId = "perm-123"

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    fun add(f: File) {
        files[f.id] = f
    }

    /** Metadata requests (not media) for [id]. */
    fun metadataCalls(id: String? = null) = requests.filter {
        it.url.queryParameter("alt") == null && it.url.encodedPath.startsWith("/drive/v3/files/") &&
            (id == null || it.url.pathSegments.last() == id)
    }

    fun mediaCalls() = requests.filter { it.url.queryParameter("alt") == "media" }

    fun aboutCalls() = requests.filter { it.url.pathSegments.lastOrNull() == "about" }

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        requests += req
        val token = req.header("Authorization")?.removePrefix("Bearer ")
        if (token !in validTokens) return respond(req, 401, """{"error":{"code":401,"errors":[{"reason":"authError"}]}}""")
        val path = req.url.pathSegments
        if (path.lastOrNull() == "about") {
            return respond(req, 200, """{"user":{"displayName":"Ron","emailAddress":"ron@example.com","permissionId":"$permissionId"}}""")
        }
        val id = path.last()
        val f = files[id] ?: return respond(req, 404, """{"error":{"code":404,"errors":[{"reason":"notFound"}]}}""")
        if (f.gone) return respond(req, 404, """{"error":{"code":404,"errors":[{"reason":"notFound"}]}}""")
        if (f.throttled) return respond(req, 403, """{"error":{"code":403,"errors":[{"reason":"userRateLimitExceeded"}]}}""")
        if (req.url.queryParameter("alt") == "media") {
            if (id in abusive) return respond(req, 403, """{"error":{"code":403,"errors":[{"reason":"cannotDownloadAbusiveFile"}]}}""")
            val range = req.header("Range")?.removePrefix("bytes=")?.split('-')
            if (range != null) {
                val start = range[0].toInt()
                val end = minOf(range[1].toInt(), f.bytes.size - 1)
                if (start >= f.bytes.size) return respond(req, 416, "")
                return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(206).message("Partial")
                    .body(f.bytes.copyOfRange(start, end + 1).toResponseBody("audio/flac".toMediaType())).build()
            }
            return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(f.bytes.toResponseBody("audio/flac".toMediaType())).build()
        }
        return respond(
            req, 200,
            """{"id":"${f.id}","name":"${f.name}","mimeType":"${f.mimeType}","size":"${f.bytes.size}","md5Checksum":"${f.md5}",
               "headRevisionId":"h-${f.md5}","parents":["0BParent"],"trashed":${f.trashed}}""",
        )
    }

    private fun respond(req: Request, code: Int, body: String) =
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body(body.toResponseBody("application/json".toMediaType())).build()
}

/**
 * Silent re-authorization on demand: hands out [next] (null = Google wants the user), or throws
 * [failure] (Play services busy, no network) when set.
 */
class FakeAuthorizer(var next: String? = "tok2") : GoogleAuthorizer {
    val silentCalls = mutableListOf<Pair<String?, String?>>()
    var failure: Exception? = null
    override suspend fun silentToken(accountName: String?, stale: String?): String? {
        silentCalls += accountName to stale
        failure?.let { throw it }
        return next
    }
    val cleared = mutableListOf<String>()
    override suspend fun clearToken(token: String) { cleared += token }
    override suspend fun pickerIntent(accountName: String?): IntentSender = error("not in unit tests")
    override fun pickerResult(data: Intent?): GooglePickerResult = error("not in unit tests")
}

class MemoryTokenStore : CloudTokenStore {
    val map = mutableMapOf<String, ServiceToken>()
    override suspend fun get(key: String) = map[key]
    override suspend fun save(key: String, token: ServiceToken) { map[key] = token }
    override suspend fun clear(key: String) { map.remove(key) }
}

class InMemoryGooglePickStore : GooglePickStore {
    private val map = HashMap<Long, MutableSet<String>>()
    override suspend fun pending(folderId: Long): Set<String> = synchronized(this) { map[folderId]?.toSet().orEmpty() }
    override suspend fun add(folderId: Long, ids: Collection<String>) = synchronized(this) { map.getOrPut(folderId) { LinkedHashSet() } += ids }
    override suspend fun remove(folderId: Long, ids: Collection<String>) = synchronized(this) { map[folderId]?.removeAll(ids.toSet()); Unit }
    override suspend fun clear(folderId: Long) = synchronized(this) { map.remove(folderId); Unit }
}
