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
import okhttp3.FormBody
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

data class SoundCloudCredentials(val accessToken: String, val refreshToken: String) {
    override fun toString() = "SoundCloudCredentials(redacted)"
}

data class SoundCloudPlaylist(val id: String, val title: String, val permalink: String,
    val artworkUrl: String?, val system: Boolean)

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

    fun tokenExpiresAt(token: String): Long? = tokenClaims(token)?.get("exp")?.let { (it as? JsonPrimitive)?.longOrNull }?.times(1000)

    private fun tokenClaims(token: String): JsonObject? = runCatching {
        val payload = token.split('.').takeIf { it.size == 3 }?.get(1) ?: return null
        json.parseToJsonElement(String(java.util.Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)) as? JsonObject
    }.getOrNull()

    suspend fun refreshCredentials(accessToken: String, refreshToken: String): SoundCloudCredentials {
        val id = tokenClaims(accessToken)?.string("client_id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
            ?: throw SoundCloudException()
        require(refreshToken.isNotBlank() && refreshToken.length <= 16384 && refreshToken.none { it.isISOControl() })
        val body = FormBody.Builder().add("client_id", id).add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken).build()
        val reply = request(Request.Builder().url("https://secure.soundcloud.com/oauth/token").post(body).build())
        if (reply.code !in 200..299) throw SoundCloudException(reply.code)
        val value = json.parseToJsonElement(reply.body) as? JsonObject ?: throw SoundCloudException()
        fun credential(name: String) = value.string(name)?.takeIf {
            it.isNotBlank() && it.length <= 16384 && it.none { c -> c.isISOControl() || c == ';' }
        } ?: throw SoundCloudException()
        return SoundCloudCredentials(credential("access_token"), credential("refresh_token"))
    }

    suspend fun authenticatedUser(token: String): SoundCloudUser {
        val reply = request(Request.Builder().url("https://api-v2.soundcloud.com/me")
            .header("Authorization", "OAuth $token").build())
        if (reply.code !in 200..299) throw SoundCloudException(reply.code)
        return user(json.parseToJsonElement(reply.body) as? JsonObject ?: throw SoundCloudException())
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

    suspend fun ownPlaylists(userId: String, token: String? = null): List<SoundCloudPlaylist> {
        require(userId.matches(Regex("[0-9]+")))
        return collection("https://api-v2.soundcloud.com/users/$userId/playlists?limit=50&linked_partitioning=1".toHttpUrl(), token)
            .filter { (it["user"] as? JsonObject)?.string("id") == userId || it.string("user_id") == userId }
            .map(::parsePlaylist).distinctBy { it.id }
    }

    /** Only the listener's Mixed for… shelf, not stations, charts or general recommendations. */
    suspend fun personalMixes(userId: String, token: String): List<SoundCloudPlaylist> {
        require(userId.matches(Regex("[0-9]+")))
        val sections = collection("https://api-v2.soundcloud.com/mixed-selections?limit=10&linked_partitioning=1".toHttpUrl(), token, maxPages = 5, discovery = true)
        val result = linkedMapOf<String, SoundCloudPlaylist>()
        for (section in sections.filter { it.string("urn") == "soundcloud:selections:your-moods" }) {
            val items = section["items"] as? JsonObject ?: throw SoundCloudException()
            val entries = collection(null, token, initial = items)
            for (entry in entries) {
                if (entry.string("kind") != "system-playlist" ||
                    (entry["made_for"] as? JsonObject)?.string("id") != userId) continue
                val playlist = parsePlaylist(entry)
                result[playlist.id] = playlist
            }
        }
        return result.values.toList()
    }

    fun parsePlaylist(value: JsonObject): SoundCloudPlaylist {
        val system = value.string("kind") == "system-playlist"
        if (!system && value.string("kind") != "playlist") throw SoundCloudException()
        val id = value.string("id") ?: value.string("urn") ?: throw SoundCloudException()
        requirePlaylistId(id, system)
        val link = value.string("permalink_url")?.let(SoundCloudLinks::parse)
            ?.takeIf { it.kind == if (system) SoundCloudLink.Kind.MIX else SoundCloudLink.Kind.SET }
            ?: throw SoundCloudException()
        return SoundCloudPlaylist(id, value.string("title") ?: throw SoundCloudException(), link.url,
            value.string("artwork_url") ?: value.string("calculated_artwork_url"), system)
    }

    suspend fun playlist(id: String, token: String? = null): JsonObject {
        val system = id.startsWith("soundcloud:system-playlists:")
        requirePlaylistId(id, system)
        val endpoint = if (system) "system-playlists" else "playlists"
        return api("https://api-v2.soundcloud.com/".toHttpUrl().newBuilder()
            .addPathSegment(endpoint).addPathSegment(id).build(), token).also {
                if (parsePlaylist(it).id != id) throw SoundCloudException()
            }
    }

    /** Hydrate stubs in batches, then restore service order. Never accept silently missing IDs. */
    suspend fun playlistTracks(playlist: JsonObject, token: String? = null): List<SoundCloudTrack> {
        val tracks = playlist["tracks"] as? JsonArray ?: throw SoundCloudException()
        val expected = (playlist["track_count"] as? JsonPrimitive)?.intOrNull
        if (expected != null && expected != tracks.size) throw SoundCloudException()
        val items = tracks.map { it as? JsonObject ?: throw SoundCloudException() }
        // A geo/subscription block is not a remote removal: never clear an existing downloaded member.
        if (items.any { it.string("policy") == "BLOCK" }) throw SoundCloudException()
        val full = mutableMapOf<String, SoundCloudTrack>()
        items.filter { it.string("title") != null }.forEach { full[numericId(it)] = parseTrack(it) }
        val missing = items.filter { it.string("title") == null }.map(::numericId).distinct()
        for (batch in missing.chunked(50)) {
            pause(250)
            val response = apiValue("https://api-v2.soundcloud.com/tracks".toHttpUrl().newBuilder()
                .addQueryParameter("ids", batch.joinToString(",")).build(), token) as? JsonArray ?: throw SoundCloudException()
            for (item in response) {
                val parsed = parseTrack(item as? JsonObject ?: throw SoundCloudException())
                if (parsed.id !in batch) throw SoundCloudException()
                full[parsed.id] = parsed
            }
            if (batch.any { it !in full }) throw SoundCloudException()
        }
        return items.map { full[numericId(it)] ?: throw SoundCloudException() }.distinctBy { it.id }
    }

    private fun requirePlaylistId(id: String, system: Boolean) {
        val valid = if (system) id.length <= 256 && id.matches(Regex("soundcloud:system-playlists:[A-Za-z0-9:_-]+"))
            else id.matches(Regex("[0-9]+"))
        if (!valid) throw SoundCloudException()
    }

    private suspend fun collection(start: HttpUrl?, token: String?, maxPages: Int = 500,
        discovery: Boolean = false, initial: JsonObject? = null): List<JsonObject> {
        var next = start
        var pending = initial
        val visited = mutableSetOf<String>()
        val result = mutableListOf<JsonObject>()
        var pages = 0
        do {
            if (pages++ >= maxPages) {
                if (discovery) break else throw SoundCloudException()
            }
            val page = pending ?: run {
                val url = next ?: throw SoundCloudException()
                if (!visited.add(url.toString())) throw SoundCloudException()
                api(url, token)
            }
            pending = null
            val values = page["collection"] as? JsonArray ?: throw SoundCloudException()
            result += values.map { it as? JsonObject ?: throw SoundCloudException() }
            next = page.string("next_href")?.takeIf { it.isNotBlank() }?.let {
                it.toHttpUrlOrNull()?.also(::requireApiUrl) ?: throw SoundCloudException()
            }
            if (next != null) pause(250)
        } while (next != null)
        return result
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

    private suspend fun api(url: HttpUrl, token: String?, body: String? = null): JsonObject =
        apiValue(url, token, body) as? JsonObject ?: throw SoundCloudException()

    private suspend fun apiValue(url: HttpUrl, token: String?, body: String? = null): JsonElement {
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
                json.parseToJsonElement(response.body)
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
