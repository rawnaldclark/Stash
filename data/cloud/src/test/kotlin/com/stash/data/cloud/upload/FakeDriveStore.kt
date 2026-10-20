package com.stash.data.cloud.upload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Collections

/**
 * A small Google Drive v3 with uploads behind an OkHttp interceptor: `about`, `files.list` (the two
 * query shapes Stash sends), `files.get` (metadata and `alt=media` with Range), folder creation,
 * delete, and resumable uploads (`uploadType=resumable` sessions taking `PUT` pieces with
 * Content-Range, answering 308 + Range until done). Faults can be injected per piece.
 */
class FakeDriveStore : Interceptor {
    class Entry(
        val id: String,
        var name: String,
        var parents: List<String>,
        val mimeType: String,
        val bytes: ByteArray? = null,
        val appProperties: Map<String, String> = emptyMap(),
        var trashed: Boolean = false,
    ) {
        val md5: String? get() = bytes?.let { md5(it) }
        val isFolder get() = mimeType == DriveUploadApi.FOLDER_MIME
    }

    class Session(
        val name: String,
        val parents: List<String>,
        val mimeType: String,
        val appProperties: Map<String, String>,
        val total: Long,
    ) {
        val data = ByteArray(total.toInt())
        var committed = 0L
        var fileId: String? = null
        var expired = false
    }

    val entries = LinkedHashMap<String, Entry>()
    val sessions = HashMap<String, Session>()
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
    val validTokens = mutableSetOf("tok1")
    var permissionId = "perm-123"

    var storageFull = false

    /** Codes the next piece PUTs answer instead of taking their bytes. */
    val putFaults = ArrayDeque<Int>()

    /** Drive keeps at most this many bytes of each piece (it may keep less than was sent). */
    var keepPerPiece = Long.MAX_VALUE

    /** The next finished upload stores different bytes than were sent (an md5 mismatch). */
    var corruptNext = false

    private var nextId = 1
    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    fun newId(prefix: String = "1F"): String = prefix + "%09d".format(nextId++)

    fun addFile(name: String, parent: String, bytes: ByteArray, appProperties: Map<String, String> = emptyMap()): Entry =
        Entry(newId(), name, listOf(parent), "audio/flac", bytes, appProperties).also { entries[it.id] = it }

    fun folders(name: String) = entries.values.filter { it.isFolder && it.name == name }
    fun files() = entries.values.filter { !it.isFolder }

    /** Trashes [id] and everything below it, as Drive does. */
    fun trash(id: String) {
        entries[id]?.trashed = true
        entries.values.filter { id in it.parents }.forEach { trash(it.id) }
    }

    fun uploadStarts() = requests.filter { it.method == "POST" && it.url.encodedPath == "/upload/drive/v3/files" }
    fun pieces() = requests.filter { it.method == "PUT" && it.header("Content-Range")?.startsWith("bytes */") == false }
    fun statusQueries() = requests.filter { it.method == "PUT" && it.header("Content-Range")?.startsWith("bytes */") == true }

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        requests += req
        val path = req.url.encodedPath
        if (req.method == "PUT" && path == "/upload/drive/v3/files") return piece(req)
        val token = req.header("Authorization")?.removePrefix("Bearer ")
        if (token !in validTokens) return respond(req, 401, error(401, "authError"))
        return when {
            path.endsWith("/about") -> respond(req, 200, """{"user":{"displayName":"Ron","emailAddress":"ron@example.com","permissionId":"$permissionId"}}""")
            req.method == "POST" && path == "/upload/drive/v3/files" -> startSession(req)
            req.method == "POST" && path == "/drive/v3/files" -> createFolder(req)
            req.method == "GET" && path == "/drive/v3/files" -> list(req)
            req.method == "DELETE" -> {
                entries.remove(req.url.pathSegments.last())
                respond(req, 204, "")
            }
            req.method == "GET" -> get(req)
            else -> respond(req, 400, error(400, "badRequest"))
        }
    }

    private fun get(req: Request): Response {
        val e = entries[req.url.pathSegments.last()] ?: return respond(req, 404, error(404, "notFound"))
        if (req.url.queryParameter("alt") == "media") {
            val bytes = e.bytes ?: return respond(req, 403, error(403, "fileNotDownloadable"))
            val range = req.header("Range")?.removePrefix("bytes=")?.split('-')
            if (range != null) {
                val start = range[0].toInt()
                if (start >= bytes.size) return respond(req, 416, "")
                val end = minOf(range[1].toInt(), bytes.size - 1)
                return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(206).message("Partial")
                    .body(bytes.copyOfRange(start, end + 1).toResponseBody("audio/flac".toMediaType())).build()
            }
            return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(bytes.toResponseBody("audio/flac".toMediaType())).build()
        }
        return respond(req, 200, json(e).toString())
    }

    private fun list(req: Request): Response {
        val q = req.url.queryParameter("q").orEmpty()
        val all = entries.values.filter { matches(it, q) }
        val size = req.url.queryParameter("pageSize")?.toInt() ?: 100
        val from = req.url.queryParameter("pageToken")?.toInt() ?: 0
        val page = all.drop(from).take(size)
        val body = buildJsonObject {
            put("files", buildJsonArray { page.forEach { add(json(it)) } })
            if (from + size < all.size) put("nextPageToken", (from + size).toString())
        }
        return respond(req, 200, body.toString())
    }

    private fun matches(e: Entry, q: String): Boolean {
        if ("trashed = false" in q && e.trashed) return false
        PROPERTY.find(q)?.let { m -> return e.appProperties[m.groupValues[1]] == unescape(m.groupValues[2]) }
        FOLDER.find(q)?.let { m ->
            return e.isFolder && e.name == unescape(m.groupValues[1]) && m.groupValues[2] in e.parents
        }
        return false
    }

    private fun createFolder(req: Request): Response {
        val body = Json.parseToJsonElement(bodyOf(req).decodeToString()).jsonObject
        val e = Entry(
            newId("0D"), body.str("name"), body["parents"]!!.jsonArray.map { it.jsonPrimitive.content },
            body.str("mimeType"), null, props(body),
        )
        entries[e.id] = e
        return respond(req, 200, """{"id":"${e.id}"}""")
    }

    private fun startSession(req: Request): Response {
        if (storageFull) return respond(req, 403, error(403, "storageQuotaExceeded"))
        val body = Json.parseToJsonElement(bodyOf(req).decodeToString()).jsonObject
        val parents = body["parents"]!!.jsonArray.map { it.jsonPrimitive.content }
        if (parents.any { p -> p != "root" && entries[p]?.isFolder != true }) return respond(req, 404, error(404, "notFound"))
        val id = "S" + newId()
        sessions[id] = Session(body.str("name"), parents, body.str("mimeType"), props(body), req.header("X-Upload-Content-Length")!!.toLong())
        return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("Location", "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=$id")
            .body("".toResponseBody(null)).build()
    }

    private fun piece(req: Request): Response {
        val s = sessions[req.url.queryParameter("upload_id")] ?: return respond(req, 404, error(404, "notFound"))
        if (s.expired) return respond(req, 404, error(404, "notFound"))
        val range = req.header("Content-Range")!!.removePrefix("bytes ")
        if (range.startsWith("*/")) return progress(req, s)
        putFaults.removeFirstOrNull()?.let { code ->
            return respond(req, code, if (code == 403) error(403, "userRateLimitExceeded") else error(code, "backendError"))
        }
        if (storageFull) return respond(req, 403, error(403, "storageQuotaExceeded"))
        val start = range.substringBefore('-').toLong()
        val end = range.substringAfter('-').substringBefore('/').toLong()
        if (start > s.committed) return respond(req, 400, error(400, "badContentRange"))
        val bytes = bodyOf(req)
        check(bytes.size.toLong() == end - start + 1) { "piece length doesn't match its Content-Range" }
        val keep = minOf(bytes.size.toLong(), keepPerPiece).toInt()
        System.arraycopy(bytes, 0, s.data, start.toInt(), keep)
        s.committed = maxOf(s.committed, start + keep)
        return progress(req, s)
    }

    private fun progress(req: Request, s: Session): Response {
        if (s.committed >= s.total && s.fileId == null) {
            val stored = if (corruptNext) s.data.copyOf().also { it[0] = (it[0] + 1).toByte(); corruptNext = false } else s.data
            val e = Entry(newId(), s.name, s.parents, s.mimeType, stored, s.appProperties)
            entries[e.id] = e
            s.fileId = e.id
        }
        s.fileId?.let { return respond(req, 200, json(entries[it]!!).toString()) }
        val b = Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(308).message("Resume Incomplete")
        if (s.committed > 0) b.header("Range", "bytes=0-${s.committed - 1}")
        return b.body("".toResponseBody(null)).build()
    }

    private fun json(e: Entry): JsonObject = buildJsonObject {
        put("id", e.id)
        put("name", e.name)
        put("mimeType", e.mimeType)
        e.bytes?.let { put("size", it.size.toString()); put("md5Checksum", md5(it)); put("headRevisionId", "h-" + md5(it)) }
        put("parents", buildJsonArray { e.parents.forEach { add(it) } })
        put("trashed", e.trashed)
        putJsonObject("appProperties") { e.appProperties.forEach { (k, v) -> put(k, v) } }
    }

    private fun bodyOf(req: Request): ByteArray = Buffer().also { req.body?.writeTo(it) }.readByteArray()

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private fun props(body: JsonObject) = body["appProperties"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()

    private fun respond(req: Request, code: Int, body: String) =
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body(body.toResponseBody("application/json".toMediaType())).build()

    private fun error(code: Int, reason: String) = """{"error":{"code":$code,"errors":[{"reason":"$reason"}]}}"""

    companion object {
        private val PROPERTY = Regex("""appProperties has \{ key='((?:[^'\\]|\\.)*)' and value='((?:[^'\\]|\\.)*)' \}""")
        private val FOLDER = Regex("""name = '((?:[^'\\]|\\.)*)' and '((?:[^'\\]|\\.)*)' in parents""")
        private fun unescape(s: String) = s.replace("\\'", "'").replace("\\\\", "\\")
        fun md5(b: ByteArray): String = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
    }
}

/** Song files in memory, by path. */
class MemoryAudioFiles : LocalAudioFiles {
    val files = HashMap<String, ByteArray>()
    override suspend fun size(path: String): Long? = files[path]?.size?.toLong()
    override fun open(path: String, offset: Long): InputStream =
        ByteArrayInputStream(files[path] ?: throw java.io.FileNotFoundException(path)).apply { skip(offset) }
}

/** [DriveFolderCache] in memory. */
class MemoryFolderCache : DriveFolderCache {
    val map = HashMap<String, String>()
    override suspend fun get(accountId: Long, path: String) = map["$accountId:$path"]
    override suspend fun put(accountId: Long, path: String, id: String) { map["$accountId:$path"] = id }
    override suspend fun remove(accountId: Long, path: String) { map.keys.removeAll { it == "$accountId:$path" || it.startsWith("$accountId:$path/") } }
    override suspend fun clear(accountId: Long) { map.keys.removeAll { it.startsWith("$accountId:") } }
}
