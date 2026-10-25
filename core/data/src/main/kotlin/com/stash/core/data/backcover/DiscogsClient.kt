package com.stash.core.data.backcover

import android.graphics.BitmapFactory
import com.stash.core.data.share.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One image of a Discogs release, in the order its editors gave them: [url] is the full image
 * (600px at most), [thumb] its 150px thumbnail, [width] by [height] the full image's size. Discogs
 * types an image only "primary" or "secondary", and the front is often listed as secondary (6 of
 * 30 masters, 2026-10-24), so the type isn't kept: the first image is the front.
 */
data class DiscogsImage(val url: String, val thumb: String?, val width: Int, val height: Int)

/**
 * A Discogs release: its images, its formats ([formats] the names, "Vinyl", "CD", "File"; [formatText]
 * the free text editors add, where "Gatefold" is noted, when it is), and its page on discogs.com,
 * which the API terms want linked beside its images.
 */
data class DiscogsRelease(
    val id: Long,
    val images: List<DiscogsImage>,
    val formats: List<String>,
    val formatText: String,
    val page: String?,
    /** The master it is a version of; null for a release on its own. */
    val masterId: Long? = null,
)

/** One version (release) of a Discogs master, from its versions list. */
data class DiscogsVersion(val id: Long, val formats: List<String>, val accepted: Boolean, val inCollection: Int)

/** A decoded image as ARGB pixels, row by row. */
class Pixels(val width: Int, val height: Int, val argb: IntArray)

/**
 * Discogs' database (api.discogs.com). A master's versions and a release's images need no key, nor
 * do the image URLs on i.discogs.com (measured 2026-10-24). Search does, so the lookup reaches
 * Discogs only through MusicBrainz's links: a release group's to its Discogs master, else a
 * release's to its Discogs release.
 */
interface DiscogsClient {
    /** The first 100 versions of a master in [format] ("Vinyl", "CD"); empty when it has none, null on failure. */
    suspend fun versions(masterId: Long, format: String): List<DiscogsVersion>?

    /** A release; null when Discogs has none, or on failure. */
    suspend fun release(id: Long): DiscogsRelease?

    /** An image (a 150px thumbnail) decoded; null on failure. */
    suspend fun pixels(url: String): Pixels?
}

@Singleton
class DiscogsClientImpl @Inject constructor(
    okHttpClient: OkHttpClient,
) : DiscogsClient {
    private val http: OkHttpClient = okHttpClient.newBuilder()
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val rateGate = Mutex()
    @Volatile private var lastCallMs = 0L

    /** Test seams: the server and the gap between calls. */
    internal var base: String = BASE
    internal var minIntervalMs: Long = MIN_INTERVAL_MS

    override suspend fun versions(masterId: Long, format: String): List<DiscogsVersion>? =
        api("masters/$masterId/versions?format=$format&per_page=100") { body ->
            if (body == null) emptyList() else parseDiscogsVersions(body)
        }

    override suspend fun release(id: Long): DiscogsRelease? =
        api("releases/$id") { body -> body?.let(::parseDiscogsRelease) }

    /**
     * GET [path] on the API and [read] its body (null on a 404). Null on any other failure: a 429
     * (over the per-IP limit) or a 503 fails this lookup softly, to be tried again after the
     * repository's backoff, rather than waiting out Discogs' minute here. The call cancels with its
     * caller ([await]); a cancellation is rethrown.
     */
    private suspend fun <T> api(path: String, read: (JsonObject?) -> T?): T? = try {
        throttle()
        val req = Request.Builder().url("$base$path").header("User-Agent", USER_AGENT).build()
        http.newCall(req).await { code, body ->
            when {
                code == 404 -> read(null)
                code !in 200..299 -> null
                else -> read(json.parseToJsonElement(body).jsonObject)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** The image CDN isn't the API: its calls aren't counted against the rate limit, nor gated. */
    override suspend fun pixels(url: String): Pixels? = try {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val bytes = http.newCall(req).awaitBytes()
        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.let { bitmap ->
            val argb = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            Pixels(bitmap.width, bitmap.height, argb).also { bitmap.recycle() }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun Call.awaitBytes(): ByteArray? =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) =
                    cont.resumeWith(Result.failure(e))

                override fun onResponse(call: Call, response: Response) =
                    cont.resumeWith(runCatching { response.use { if (it.isSuccessful) it.body?.bytes() else null } })
            })
        }

    /** Serializes API calls and spaces them [minIntervalMs] apart. */
    private suspend fun throttle() = rateGate.withLock {
        val wait = minIntervalMs - (System.currentTimeMillis() - lastCallMs)
        if (wait > 0) delay(wait)
        lastCallMs = System.currentTimeMillis()
    }

    private companion object {
        const val BASE = "https://api.discogs.com/"
        const val CALL_TIMEOUT_SECONDS = 10L
        const val USER_AGENT = "Stash/1.0 ( https://github.com/rawnaldclark/Stash )"

        /** Unauthenticated, Discogs allows 25 calls a minute per IP (x-discogs-ratelimit, 2026-10-24). */
        const val MIN_INTERVAL_MS = 2_500L
    }
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.content

/** A release's images, formats and page; an image without a URL is skipped. */
internal fun parseDiscogsRelease(body: JsonObject): DiscogsRelease? {
    val id = (body["id"] as? JsonPrimitive)?.longOrNull ?: return null
    val images = (body["images"] as? JsonArray).orEmpty().mapNotNull { el ->
        val img = el as? JsonObject ?: return@mapNotNull null
        val url = img.string("uri")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        DiscogsImage(
            url = url.replaceFirst("http://", "https://"),
            thumb = img.string("uri150")?.takeIf { it.isNotBlank() }?.replaceFirst("http://", "https://"),
            width = img["width"]?.jsonPrimitive?.intOrNull ?: 0,
            height = img["height"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }
    val formats = (body["formats"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    return DiscogsRelease(
        id = id,
        images = images,
        formats = formats.mapNotNull { it.string("name") },
        formatText = formats.mapNotNull { it.string("text") }.joinToString(", "),
        page = body.string("uri")?.takeIf { it.startsWith("https://www.discogs.com/") },
        masterId = (body["master_id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 },
    )
}

/** A master's versions list. */
internal fun parseDiscogsVersions(body: JsonObject): List<DiscogsVersion> =
    (body["versions"] as? JsonArray).orEmpty().mapNotNull { el ->
        val v = el as? JsonObject ?: return@mapNotNull null
        val id = (v["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
        val community = (v["stats"] as? JsonObject)?.get("community") as? JsonObject
        DiscogsVersion(
            id = id,
            formats = (v["major_formats"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
            accepted = v.string("status") == "Accepted",
            inCollection = community?.get("in_collection")?.jsonPrimitive?.intOrNull ?: 0,
        )
    }
