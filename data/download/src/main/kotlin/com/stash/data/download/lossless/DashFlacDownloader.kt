package com.stash.data.download.lossless

import android.util.Log
import com.stash.core.data.audio.FFmpegBridge
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSink
import okio.buffer
import okio.sink

/**
 * Downloads a DASH-served FLAC (Tidal) to a real `.flac` file.
 *
 * Tidal serves lossless as fragmented MP4 over a DASH manifest with no DRM: the
 * init segment followed by every media segment, byte-concatenated, is a valid
 * fMP4 holding a FLAC stream. We fetch them all from the phone (Tidal's CDN
 * refuses Cloudflare, so the Worker can't do it) into a temp `.mp4`, then let
 * ffmpeg stream-copy the FLAC out into its native container — no re-encode, and
 * the result tags/plays like every other FLAC in the library.
 *
 * All-or-nothing: any segment that still fails after retries, or a remux
 * failure, fails the download and removes partials. An `.mp4` must never land
 * on disk named `.flac`.
 */
@Singleton
class DashFlacDownloader @Inject constructor(
    private val ffmpeg: FFmpegBridge,
) {
    /**
     * @param client      The caller's fetch client (LosslessUrlDownloader's, with its stall timeouts).
     *   For an add-on's answer it is the add-on media client: the manifest AND every segment URL it
     *   names are then held to the base rules by that client's guard (review-882 B1), so a segment
     *   on the user's own network is refused before any connection is opened.
     * @param maxBytes    The most the segments may add up to (`SourceResult.maxBytes`); null = no ceiling.
     * @param onProgress  (segmentsDone, totalSegments) — the init counts as one.
     */
    suspend fun download(
        client: OkHttpClient,
        mpdUrl: String,
        headers: Map<String, String>,
        destination: File,
        maxBytes: Long? = null,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        val temp = File(destination.parentFile, "${destination.nameWithoutExtension}.dash.mp4")
        try {
            // The MPD comes from our own Worker and may carry headers (token); the
            // segment URLs are Tidal-signed and get none — never leak them to the CDN.
            val mpd = fetchBytes(client, mpdUrl, headers).toString(Charsets.UTF_8)
            val plan = DashManifestParser.parse(mpd, mpdUrl)
            val urls = listOf(plan.initUrl) + plan.mediaUrls
            val total = urls.size.toLong()

            destination.parentFile?.mkdirs()
            var written = 0L
            temp.sink().buffer().use { sink ->
                urls.forEachIndexed { i, url ->
                    currentCoroutineContext().ensureActive()
                    written += fetchWithRetry(client, url, sink, label = if (i == 0) "init" else "segment $i/${total - 1}")
                    if (maxBytes != null && written > maxBytes) throw IOException("the segments add up to more than this song can be")
                    onProgress(i + 1L, total)
                }
                sink.flush()
            }
            if (temp.length() == 0L) throw IOException("dash download produced empty file")

            remux(temp, destination)
            Result.success(destination)
        } catch (e: CancellationException) {
            runCatching { destination.delete() }
            throw e
        } catch (e: Exception) {
            // Message only: an IOException's text can carry a signed URL.
            Log.w(TAG, "dash fetch failed: ${e.javaClass.simpleName}")
            runCatching { destination.delete() }
            Result.failure(e)
        } finally {
            runCatching { temp.delete() }
        }
    }

    private suspend fun remux(input: File, output: File) {
        runCatching { output.delete() }
        try {
            ffmpeg.runWithStderrCapture(buildRemuxArgs(input, output))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException("remux failed: ${e.message}", e)
        }
        if (!output.exists() || output.length() == 0L) throw IOException("remux produced no output")
    }

    /**
     * One URL into [sink], retried on I/O errors and 5xx. Each attempt buffers the
     * segment in memory first (Tidal's are ~0.5–2 MB) so a mid-body failure never
     * leaves half a segment appended to the file.
     */
    private suspend fun fetchWithRetry(client: OkHttpClient, url: String, sink: BufferedSink, label: String): Long {
        var attempt = 0
        while (true) {
            try {
                val bytes = fetchBytes(client, url, emptyMap())
                sink.write(bytes)
                return bytes.size.toLong()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // A URL the add-on guard refused stays refused: asking again only asks the add-on's host again.
                val refused = e is com.stash.core.addons.PrivateAddressException || e is com.stash.core.addons.RefusedAddonUrlException
                val retryable = !refused && (e !is HttpStatusException || e.code >= 500)
                if (!retryable || attempt >= MAX_RETRIES) throw IOException("$label failed: ${e.message}", e)
                attempt++
                Log.d(TAG, "$label attempt $attempt failed (${e.javaClass.simpleName}); retrying")
            }
        }
    }

    private suspend fun fetchBytes(client: OkHttpClient, url: String, headers: Map<String, String>): ByteArray {
        val req = Request.Builder().url(url).get().apply {
            for ((k, v) in headers) header(k, v)
        }.build()
        val call = client.newCall(req)
        return coroutineScope {
            // A blocking read ignores coroutine cancellation; cancel the call ourselves.
            val watcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.execute().use { r ->
                    if (!r.isSuccessful) throw HttpStatusException(r.code)
                    val body = r.body ?: throw IOException("empty body")
                    if (body.contentLength() > MAX_SEGMENT_BYTES) throw IOException("segment too large")
                    // No Content-Length is no promise: read no further than the ceiling.
                    val source = body.source()
                    if (source.request(MAX_SEGMENT_BYTES + 1)) throw IOException("segment too large")
                    val bytes = source.buffer.readByteArray()
                    // A body that ends cleanly short of its stated length is not the segment
                    // (`ResponseBody.bytes()` checked this; over HTTP/2 nothing else does). Appended
                    // as it was, it left a hole in the file (review-882 round 2, n1).
                    val stated = body.contentLength()
                    if (stated != -1L && stated != bytes.size.toLong()) throw IOException("Content-Length and stream length disagree")
                    bytes
                }
            } finally {
                watcher.cancel()
            }
        }
    }

    private class HttpStatusException(val code: Int) : IOException("HTTP $code")

    companion object {
        private const val TAG = "DashFlacDownloader"
        const val MAX_RETRIES = 2
        private const val MAX_SEGMENT_BYTES = 64L * 1024 * 1024

        /** Stream-copy the first audio stream (FLAC) out of the fMP4 into a native FLAC container. */
        fun buildRemuxArgs(input: File, output: File): List<String> = listOf(
            "-y",
            "-i", input.absolutePath,
            "-map", "0:a:0",
            "-c:a", "copy",
            "-vn",
            // Explicit muxer: the caller's temp name need not end in .flac.
            "-f", "flac",
            output.absolutePath,
        )
    }
}
