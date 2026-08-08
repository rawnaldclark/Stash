package com.stash.data.download.jiosaavn

import android.util.Log
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Narrow on-device adapter for JioSaavn search and its encrypted media template. */
@Singleton
class JioSaavnClient @Inject constructor(sharedClient: OkHttpClient) {
    private val httpClient = sharedClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    /** Test seam for MockWebServer. */
    internal var baseUrl = "https://www.jiosaavn.com"

    suspend fun search(query: String, limit: Int = 10): JioSaavnSearchOutcome {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("api.php")
            .addQueryParameter("__call", "search.getResults")
            .addQueryParameter("_format", "json")
            .addQueryParameter("_marker", "0")
            .addQueryParameter("ctx", "web6dot0")
            .addQueryParameter("n", limit.coerceIn(1, 20).toString())
            .addQueryParameter("p", "1")
            .addQueryParameter("q", query)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.jiosaavn.com/")
            .get()
            .build()
        return try {
            executeCancellable(request) { response ->
                when {
                    response.code == 429 -> JioSaavnSearchOutcome.RateLimited
                    !response.isSuccessful -> JioSaavnSearchOutcome.Failure("HTTP ${response.code}")
                    else -> {
                        val parsed = JSON.decodeFromString<NativeSearchResponse>(
                            response.body?.string().orEmpty(),
                        )
                        JioSaavnSearchOutcome.Success(parsed.results.mapNotNull(::normalize))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Log.d(TAG, "search failed: ${e.javaClass.simpleName}: ${e.message}")
            JioSaavnSearchOutcome.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Validate the synthesized 320 URL before it enters playback. */
    suspend fun isPlayable320(url: String): JioSaavnProbeOutcome {
        if (!isTrustedMediaUrl(url)) return JioSaavnProbeOutcome.Unavailable
        val request = Request.Builder().url(url).header("Range", "bytes=0-4095").get().build()
        return try {
            executeCancellable(request) { response ->
                if (response.code == 429) {
                    return@executeCancellable JioSaavnProbeOutcome.RateLimited
                }
                if (response.code >= 500) {
                    return@executeCancellable JioSaavnProbeOutcome.Failure("HTTP ${response.code}")
                }
                if (response.code != 206) return@executeCancellable JioSaavnProbeOutcome.Unavailable
                val range = response.header("Content-Range")
                    ?: return@executeCancellable JioSaavnProbeOutcome.Unavailable
                if (!range.startsWith("bytes 0-", ignoreCase = true)) {
                    return@executeCancellable JioSaavnProbeOutcome.Unavailable
                }
                val type = response.body?.contentType()?.toString().orEmpty().lowercase()
                if (!type.startsWith("audio/mp4") && !type.startsWith("application/octet-stream")) {
                    return@executeCancellable JioSaavnProbeOutcome.Unavailable
                }
                val prefix = ByteArray(12)
                val stream = response.body?.byteStream()
                    ?: return@executeCancellable JioSaavnProbeOutcome.Unavailable
                var offset = 0
                while (offset < prefix.size) {
                    val read = stream.read(prefix, offset, prefix.size - offset)
                    if (read < 0) break
                    offset += read
                }
                if (offset >= 8 && prefix.copyOfRange(4, 8).contentEquals("ftyp".toByteArray())) {
                    JioSaavnProbeOutcome.Playable
                } else {
                    JioSaavnProbeOutcome.Unavailable
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            JioSaavnProbeOutcome.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * OkHttp's synchronous execute ignores coroutine cancellation. The async
     * bridge binds cancellation to Call.cancel(), making the resolver's outer
     * seven-second deadline a real end-to-end deadline.
     */
    private suspend fun <T> executeCancellable(
        request: Request,
        block: (Response) -> T,
    ): T = coroutineScope {
        val call = httpClient.newCall(request)
        val cancellationWatcher = launch(
            context = Dispatchers.IO,
            start = CoroutineStart.UNDISPATCHED,
        ) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) {
                awaitResponse(call).use(block)
            }
        } finally {
            cancellationWatcher.cancel()
        }
    }

    private suspend fun awaitResponse(call: Call): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, unclaimedResponse, _ ->
                            unclaimedResponse.close()
                        }
                    }
                },
            )
        }

    internal fun isTrustedMediaUrl(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.isHttps && url.host == AAC_CDN_HOST && url.encodedPath.contains("_320")
    }

    internal fun decrypt320Url(encryptedMediaUrl: String): String? = runCatching {
        if (encryptedMediaUrl.isBlank()) return null
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(DES_KEY.toByteArray(), "DES"))
        val template = String(
            cipher.doFinal(Base64.getDecoder().decode(encryptedMediaUrl)),
            Charsets.UTF_8,
        )
        if (!template.contains("_96")) return null
        template.replace("_96", "_320")
    }.getOrNull()

    private fun normalize(raw: NativeSong): JioSaavnSong? {
        val id = raw.id?.takeIf { it.isNotBlank() } ?: return null
        val name = decodeEntities(raw.song.orEmpty()).takeIf { it.isNotBlank() } ?: return null
        val primary = raw.primaryArtists.orEmpty().split(',')
            .map { decodeEntities(it).trim() }
            .filter { it.isNotBlank() }
            .map(::JioSaavnArtist)
        if (primary.isEmpty()) return null
        val media = if (raw.has320.equals("true", ignoreCase = true)) {
            decrypt320Url(raw.encryptedMediaUrl.orEmpty())
                ?.let { listOf(JioSaavnMediaLink("320kbps", it)) }
                .orEmpty()
        } else emptyList()
        val images = raw.image?.takeIf { it.isNotBlank() }?.let { value ->
            val high = value.replace(Regex("(?:50|150)x(?:50|150)"), "500x500")
                .replace(Regex("^http://"), "https://")
            listOf(JioSaavnImage("500x500", high))
        }.orEmpty()
        return JioSaavnSong(
            id = id,
            name = name,
            duration = raw.duration?.toIntOrNull(),
            explicitContent = raw.explicitContent == 1,
            album = raw.album?.let { JioSaavnAlbum(decodeEntities(it)) },
            artists = JioSaavnArtists(primary),
            image = images,
            downloadUrl = media,
        )
    }

    private fun decodeEntities(value: String): String = value
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#039;", "'", ignoreCase = true)
        .replace("&apos;", "'", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)

    @Serializable
    private data class NativeSearchResponse(val results: List<NativeSong> = emptyList())

    @Serializable
    private data class NativeSong(
        val id: String? = null,
        val song: String? = null,
        val album: String? = null,
        val duration: String? = null,
        val image: String? = null,
        @SerialName("primary_artists") val primaryArtists: String? = null,
        @SerialName("explicit_content") val explicitContent: Int? = null,
        @SerialName("320kbps") val has320: String? = null,
        @SerialName("encrypted_media_url") val encryptedMediaUrl: String? = null,
    )

    private companion object {
        const val TAG = "JioSaavnClient"
        const val DES_KEY = "38346591"
        const val AAC_CDN_HOST = "aac.saavncdn.com"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
