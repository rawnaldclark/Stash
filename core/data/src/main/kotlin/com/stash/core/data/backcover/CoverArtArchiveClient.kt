package com.stash.core.data.backcover

import android.util.Log
import com.stash.core.data.share.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** One image of a Cover Art Archive listing: its types ("Front", "Back", "Booklet"...) and 1200px URL. */
data class CaaImage(
    val types: List<String>,
    val front: Boolean,
    val back: Boolean,
    val url: String,
)

/**
 * A Cover Art Archive listing. For a release group, [releaseMbid] is the release its cover comes
 * from (the album's main edition) and [images] holds only that cover.
 */
data class CaaListing(val images: List<CaaImage>, val releaseMbid: String?) {
    companion object {
        /** The archive has no art for it (404). */
        val NONE = CaaListing(emptyList(), null)
    }
}

/** The Cover Art Archive (coverartarchive.org): MusicBrainz's scans, no key, no published rate limit. */
interface CoverArtArchiveClient {
    /** A release's images; [CaaListing.NONE] when it has none, null on failure. */
    suspend fun release(mbid: String): CaaListing?

    /** A release group's cover and the release it comes from; [CaaListing.NONE] when it has none, null on failure. */
    suspend fun releaseGroup(mbid: String): CaaListing?
}

@Singleton
class CoverArtArchiveClientImpl @Inject constructor(
    okHttpClient: OkHttpClient,
) : CoverArtArchiveClient {
    // The archive redirects every listing to archive.org: 1-3 s measured (2026-10-24).
    private val http: OkHttpClient = okHttpClient.newBuilder()
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** Test seam: the server. */
    internal var base: String = BASE

    /** Test seam: the wait before another try. */
    internal var retryMs: Long = RETRY_MS

    override suspend fun release(mbid: String): CaaListing? = listing("release/$mbid")

    override suspend fun releaseGroup(mbid: String): CaaListing? = listing("release-group/$mbid")

    /**
     * A listing, [CaaListing.NONE] on a 404, null on any other failure. A 5xx or a network failure
     * (archive.org, where every listing is redirected, answers a 500 now and then and the same
     * call a second later: measured 2026-10-25) is tried again after [retryMs], up to [RETRIES]
     * times. The call cancels with its caller ([await]), so a lookup that has its answer stops
     * waiting on slower listings; a cancellation is rethrown.
     */
    private suspend fun listing(path: String): CaaListing? {
        repeat(RETRIES + 1) { attempt ->
            val last = attempt == RETRIES
            try {
                val req = Request.Builder().url("$base$path").header("User-Agent", USER_AGENT).build()
                val (code, body) = http.newCall(req).await { code, body -> code to body }
                when {
                    code == 404 -> return CaaListing.NONE
                    // A body that can't be read is no better a second time.
                    code in 200..299 -> return runCatching { parseCaaListing(json.parseToJsonElement(body).jsonObject) }.getOrNull()
                    code < 500 || last -> {
                        Log.w(TAG, "$path: HTTP $code")
                        return null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (last) {
                    Log.w(TAG, "$path: ${e.javaClass.simpleName}")
                    return null
                }
            }
            delay(retryMs)
        }
        return null
    }

    private companion object {
        const val BASE = "https://coverartarchive.org/"
        const val CALL_TIMEOUT_SECONDS = 10L
        const val RETRIES = 2
        const val RETRY_MS = 1_000L
        const val TAG = "BackCover"
        const val USER_AGENT = "Stash/1.0 ( https://github.com/rawnaldclark/Stash )"
    }
}

/**
 * A listing's images, each at its 1200px thumbnail (the original when it has none: an old upload),
 * on https: the archive hands out http URLs, which Android refuses.
 */
internal fun parseCaaListing(body: JsonObject): CaaListing {
    val images = body["images"]?.jsonArray.orEmpty().mapNotNull { el ->
        val img = el.jsonObject
        val thumbs = img["thumbnails"] as? JsonObject
        val url = (thumbs?.get("1200") ?: thumbs?.get("large") ?: img["image"])
            ?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        CaaImage(
            types = img["types"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
            front = img["front"]?.jsonPrimitive?.booleanOrNull == true,
            back = img["back"]?.jsonPrimitive?.booleanOrNull == true,
            url = url.replaceFirst("http://", "https://"),
        )
    }
    val release = body["release"]?.jsonPrimitive?.content?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    return CaaListing(images, release)
}
