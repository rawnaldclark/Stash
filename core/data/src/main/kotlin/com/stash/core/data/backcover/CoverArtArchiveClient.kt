package com.stash.core.data.backcover

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    override suspend fun release(mbid: String): CaaListing? = listing("release/$mbid")

    override suspend fun releaseGroup(mbid: String): CaaListing? = listing("release-group/$mbid")

    /** A listing, [CaaListing.NONE] on a 404, null on any other failure. A cancellation is rethrown. */
    private suspend fun listing(path: String): CaaListing? = try {
        val req = Request.Builder().url("$BASE$path").header("User-Agent", USER_AGENT).build()
        withContext(Dispatchers.IO) {
            http.newCall(req).execute().use { resp ->
                when {
                    resp.code == 404 -> CaaListing.NONE
                    !resp.isSuccessful -> null
                    else -> resp.body?.string()?.let { parseCaaListing(json.parseToJsonElement(it).jsonObject) }
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val BASE = "https://coverartarchive.org/"
        const val CALL_TIMEOUT_SECONDS = 10L
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
