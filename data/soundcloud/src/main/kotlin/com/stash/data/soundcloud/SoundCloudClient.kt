package com.stash.data.soundcloud

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** No tokens or signed URLs in exception messages. A failed page must never become an empty sync. */
class SoundCloudException(val status: Int? = null) : IOException("SoundCloud request failed" + (status?.let { " ($it)" } ?: ""))

data class SoundCloudUser(val id: String, val name: String, val avatarUrl: String?)
data class SoundCloudTrack(
    val id: String,
    val permalink: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val artworkUrl: String?,
    val isrc: String?,
    val preview: Boolean,
    val formats: List<SoundCloudFormat>,
    val authorization: String?,
)
data class SoundCloudStream(val url: String, val mimeType: String, val hls: Boolean, val preview: Boolean)

/**
 * The public web API. Callers own encrypted OAuth storage and pass a token per operation.
 * Authentication is sent only to exact API hosts; paging, assets and short links are untrusted.
 * No partial collections are returned: callers may only reconcile a fully fetched likes list.
 */
class SoundCloudClient(
    client: OkHttpClient,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val clientIdMutex = Mutex()
    @Volatile private var cachedClientId: String? = null

    suspend fun resolve(link: SoundCloudLink, token: String? = null): JsonObject {
        val canonical = if (link.kind == SoundCloudLink.Kind.SHORT) expand(link.url) else link
        // A profile's /likes URL isn't itself an API entity.
        val url = if (canonical.kind == SoundCloudLink.Kind.LIKES) canonical.url.removeSuffix("/").removeSuffix("/likes") else canonical.url
        return api("https://api-v2.soundcloud.com/resolve".toHttpUrl().newBuilder().addQueryParameter("url", url).build(), token)
    }

    suspend fun validateToken(token: String): SoundCloudUser {
        require(token.isNotBlank() && token.length <= 8192 && token.none { it.isISOControl() })
        val payload = buildJsonObject { putJsonObject("session") { put("access_token", token) } }.toString()
        api("https://api-auth.soundcloud.com/connect/session".toHttpUrl(), null, payload)
        return user(api("https://api-v2.soundcloud.com/me".toHttpUrl(), token))
    }

    suspend fun track(id: String, token: String? = null): SoundCloudTrack {
        require(id.matches(Regex("[0-9]+")))
        return parseTrack(api("https://api-v2.soundcloud.com/tracks/$id".toHttpUrl(), token))
    }

    suspend fun likes(userId: String, token: String? = null): List<SoundCloudTrack> {
        require(userId.matches(Regex("[0-9]+")))
        var next: HttpUrl? = "https://api-v2.soundcloud.com/users/$userId/likes?limit=200&linked_partitioning=1".toHttpUrl()
        val visited = mutableSetOf<String>()
        val tracks = linkedMapOf<String, SoundCloudTrack>()
        while (next != null) {
            val pageUrl = next
            if (visited.size >= 500 || !visited.add(pageUrl.toString())) throw SoundCloudException()
            val page = api(pageUrl, token)
            val collection = page["collection"] as? JsonArray ?: throw SoundCloudException()
            for (entry in collection) {
                val item = entry as? JsonObject ?: throw SoundCloudException()
                // Playlist likes and reposts aren't song likes.
                val value = item["track"] as? JsonObject ?: continue
                val parsed = parseTrack(value)
                tracks[parsed.id] = parsed
            }
            next = page.string("next_href")?.takeIf { it.isNotBlank() }?.let {
                it.toHttpUrlOrNull()?.also(::requireApiUrl) ?: throw SoundCloudException()
            }
            if (next != null) pause(250)
        }
        return tracks.values.toList()
    }

    /** Playlists may contain id-only stubs; hydrate them rather than silently dropping songs. */
    suspend fun playlistTracks(playlist: JsonObject, token: String? = null): List<SoundCloudTrack> {
        val tracks = playlist["tracks"] as? JsonArray ?: throw SoundCloudException()
        return tracks.map { value ->
            val item = value as? JsonObject ?: throw SoundCloudException()
            if (item.string("title") != null) parseTrack(item) else {
                pause(250)
                track(item.string("id") ?: throw SoundCloudException(), token)
            }
        }.distinctBy { it.id }
    }

    suspend fun stream(id: String, token: String? = null): SoundCloudStream? {
        val track = track(id, token)
        for (format in SoundCloudFormats.ordered(track.formats)) {
            val endpoint = format.endpoint.toHttpUrlOrNull() ?: throw SoundCloudException()
            requireApiUrl(endpoint)
            val url = endpoint.newBuilder().apply {
                track.authorization?.let { addQueryParameter("track_authorization", it) }
            }.build()
            val response = try {
                api(url, token)
            } catch (e: SoundCloudException) {
                if (e.status == 404) continue else throw e
            }
            val signed = response.string("url") ?: throw SoundCloudException()
            val parsed = signed.toHttpUrlOrNull() ?: throw SoundCloudException()
            if (!secure(parsed) || !mediaHost(parsed.host)) throw SoundCloudException()
            return SoundCloudStream(signed, format.mimeType, format.protocol == "hls", track.preview || format.preview || SoundCloudFormats.isPreviewUrl(signed))
        }
        return null
    }

    fun user(value: JsonObject): SoundCloudUser = SoundCloudUser(
        numericId(value), value.string("username") ?: throw SoundCloudException(), value.string("avatar_url"),
    )

    fun parseTrack(value: JsonObject): SoundCloudTrack {
        val formats = SoundCloudFormats.parse(value)
        val link = value.string("permalink_url")?.let(SoundCloudLinks::parse)
            ?.takeIf { it.kind == SoundCloudLink.Kind.TRACK } ?: throw SoundCloudException()
        return SoundCloudTrack(
            id = numericId(value), permalink = link.url,
            title = value.string("title") ?: throw SoundCloudException(),
            artist = (value["user"] as? JsonObject)?.string("username").orEmpty(),
            durationMs = ((value["full_duration"] as? JsonPrimitive)?.longOrNull
                ?: (value["duration"] as? JsonPrimitive)?.longOrNull)?.coerceAtLeast(0) ?: 0,
            artworkUrl = value.string("artwork_url") ?: (value["user"] as? JsonObject)?.string("avatar_url"),
            isrc = (value["publisher_metadata"] as? JsonObject)?.string("isrc"),
            preview = value.string("policy") == "SNIP" || (value["snipped"] as? JsonPrimitive)?.booleanOrNull == true ||
                (formats.isNotEmpty() && formats.all { it.preview }),
            formats = formats, authorization = value.string("track_authorization"),
        )
    }

    suspend fun expand(raw: String): SoundCloudLink {
        var link = SoundCloudLinks.parse(raw) ?: throw SoundCloudException()
        repeat(5) {
            if (link.kind != SoundCloudLink.Kind.SHORT) return link
            val response = request(Request.Builder().url(link.url).build())
            if (response.code !in setOf(301, 302, 303, 307, 308)) throw SoundCloudException(response.code)
            val location = response.location ?: throw SoundCloudException()
            val target = link.url.toHttpUrl().resolve(location)?.toString() ?: throw SoundCloudException()
            link = SoundCloudLinks.parse(target) ?: throw SoundCloudException()
        }
        throw SoundCloudException()
    }

    private suspend fun api(url: HttpUrl, token: String?, body: String? = null): JsonObject {
        requireApiUrl(url)
        var refreshed = false
        var retries = 0
        while (true) {
            val clientId = clientId()
            val builder = Request.Builder().url(url.newBuilder().setQueryParameter("client_id", clientId).build())
            token?.let { builder.header("Authorization", "OAuth $it") }
            body?.let { builder.post(it.toRequestBody("application/json".toMediaType())) }
            val response = request(builder.build())
            if (response.code in setOf(401, 403) && !refreshed) {
                clientIdMutex.withLock { if (cachedClientId == clientId) cachedClientId = null }
                refreshed = true
                continue
            }
            if (response.code == 429 && retries++ < 2) {
                val wait = retryDelay(response.retryAfter)
                // Do not shorten a server's long cooldown and hammer it again.
                if (wait > 60_000) throw SoundCloudException(429)
                pause(wait)
                continue
            }
            if (response.code !in 200..299) throw SoundCloudException(response.code)
            if (body != null && response.body.isBlank()) return JsonObject(emptyMap())
            return withContext(Dispatchers.Default) {
                (json.parseToJsonElement(response.body) as? JsonObject) ?: throw SoundCloudException()
            }
        }
    }

    private suspend fun clientId(): String = clientIdMutex.withLock {
        cachedClientId?.let { return@withLock it }
        val home = request(Request.Builder().url("https://soundcloud.com/").build())
        if (home.code != 200) throw SoundCloudException(home.code)
        val scripts = withContext(Dispatchers.Default) {
            Regex("<script[^>]+src=[\"']([^\"']+)[\"']").findAll(home.body)
                .mapNotNull { "https://soundcloud.com/".toHttpUrl().resolve(it.groupValues[1]) }
                .filter { secure(it) && (it.host == "soundcloud.com" || cdnHost(it.host)) }
                .toList().takeLast(20).asReversed()
        }
        for (script in scripts) {
            val response = request(Request.Builder().url(script).build())
            if (response.code != 200) continue
            val id = withContext(Dispatchers.Default) { extractClientId(response.body) } ?: continue
            cachedClientId = id
            return@withLock id
        }
        throw SoundCloudException()
    }

    private data class Reply(val code: Int, val body: String, val retryAfter: String?, val location: String?)

    private suspend fun request(request: Request): Reply = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(SoundCloudException()) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val reply = response.use {
                        val source = it.body?.source() ?: throw SoundCloudException(it.code)
                        // Read on OkHttp's thread, with cancellation active until the body is closed.
                        source.request(MAX_BODY + 1)
                        if (source.buffer.size > MAX_BODY) throw SoundCloudException()
                        Reply(it.code, source.readUtf8(), it.header("Retry-After"), it.header("Location"))
                    }
                    continuation.resume(reply)
                } catch (_: IOException) {
                    continuation.resumeWithException(SoundCloudException())
                }
            }
        })
    }

    companion object {
        private const val MAX_BODY = 8L * 1024 * 1024
        fun extractClientId(script: String): String? =
            Regex("client_id\\s*:\\s*\"([a-zA-Z0-9]{32})\"").find(script)?.groupValues?.get(1)

        fun retryDelay(value: String?, now: Long = System.currentTimeMillis()): Long {
            val seconds = value?.toLongOrNull()
            if (seconds != null) return seconds.coerceIn(0, Long.MAX_VALUE / 1000).times(1000).coerceAtLeast(1000)
            val deadline = runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            return deadline?.minus(now)?.coerceAtLeast(1000) ?: 2000
        }

        private fun secure(url: HttpUrl) = url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty()
        private fun mediaHost(host: String) = cdnHost(host) || host == "playback.media-streaming.soundcloud.cloud"
        private fun cdnHost(host: String) = host.endsWith(".sndcdn.com") || host == "sndcdn.com"
        private fun requireApiUrl(url: HttpUrl) {
            if (!secure(url) || url.host !in setOf("api-v2.soundcloud.com", "api-auth.soundcloud.com")) throw SoundCloudException()
        }
        private fun numericId(value: JsonObject): String = value.string("id")?.takeIf { it.matches(Regex("[0-9]+")) } ?: throw SoundCloudException()
        private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
    }
}
