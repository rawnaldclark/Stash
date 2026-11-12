package com.stash.data.download.lossless

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
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
import com.stash.core.addons.HttpAddonClient
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink

/**
 * Downloads a [SourceResult] to a local temp file. Used by the
 * lossless-source path to fetch the signed CDN URL produced by
 * `LosslessSourceRegistry.resolve(query)` — the resulting file is
 * then handed off to the existing `MetadataEmbedder` and `FileOrganizer`
 * pipeline (same as a yt-dlp output) and ends up in the same on-disk
 * location format as a yt-dlp-sourced track would.
 *
 * Failure modes (signed URL expired, network blip, partial body) all
 * return [Result.failure] rather than throwing, so the caller can
 * fall through to the next download strategy without try/catch noise.
 *
 * Streams via OkHttp+Okio rather than buffering in memory — FLAC files
 * are typically 25-50 MB and would put unnecessary pressure on the
 * heap if read whole.
 */
@Singleton
class LosslessUrlDownloader @Inject constructor(
    httpClient: OkHttpClient,
    private val dashDownloader: DashFlacDownloader,
    private val addons: HttpAddonClient,
) {
    // Whole-file lossless fetches are large (a Hi-Res FLAC runs 70-140 MB). The
    // shared client's 30 s read timeout is an inter-byte stall limit — fine for
    // small JSON, too tight when a congested/shared network pauses mid-stream on
    // a big FLAC. Derive a client with a roomier read/write stall window (no
    // callTimeout, so total duration stays uncapped for big files). Shares the
    // pool/dispatcher/TLS.
    private val fetchClient: OkHttpClient = httpClient.newBuilder()
        .readTimeout(FETCH_STALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(FETCH_STALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    // JioSaavn URLs are accepted only on the exact aac.saavncdn.com host.
    // Disable redirects for the actual whole-file fetch so a URL that was
    // safe during the range probe cannot later pivot to an arbitrary host.
    private val noRedirectFetchClient: OkHttpClient = fetchClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * What a file from an add-on is fetched with (review-882 B1): the add-on media client
     * ([HttpAddonClient.mediaClient]), with this class's stall window. An add-on chose the URL, so
     * the base rules hold for it here as they do in the player: the URL rule on the URL and on
     * every redirect hop, DNS answers on the user's own network refused, `User-Agent:
     * Stash/<version>`, and none of the app's interceptors (their debug logging prints the URL).
     * A DASH answer's manifest and every segment it names go through the same client.
     */
    private val addonFetchClient: OkHttpClient by lazy {
        addons.mediaClient.newBuilder()
            .readTimeout(FETCH_STALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(FETCH_STALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** The client [source]'s URL may be fetched with: an add-on's on the guarded one, always. */
    private fun clientFor(source: SourceResult): OkHttpClient = when {
        isAddonSource(source.sourceId) -> addonFetchClient
        source.sourceId == NO_REDIRECT_SOURCE_ID -> noRedirectFetchClient
        else -> fetchClient
    }

    /**
     * Fetch [source] to [destination]. Returns the file on success, or
     * a failure with the reason to log at the call site.
     *
     * @param source       The resolved match from a [LosslessSource].
     * @param destination  Pre-allocated temp file path; will be
     *   truncated and overwritten. Caller chooses the extension based
     *   on [SourceResult.format].
     * @param onProgress   Bytes-downloaded callback. Total size is the
     *   `Content-Length` header when provided, else 0 — so callers
     *   should treat 0/total as "indeterminate" rather than "complete".
     */
    suspend fun download(
        source: SourceResult,
        destination: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        // An add-on's fetch has a time limit as well as a size ceiling (review-882 round 2, F2).
        // The stall window alone let a server that sends a byte every 89 s hold a download slot
        // for as long as it liked, and with a few songs the whole sync. The limit covers the whole
        // fetch, a DASH answer's manifest and every segment included. Stash's own sources have none.
        val limitMs = source.maxBytes?.takeIf { isAddonSource(source.sourceId) }?.let(timeLimitMs)
            ?: return@withContext fetch(source, destination, onProgress)
        withTimeoutOrNull(limitMs) {
            // A blocking read that the limit cut short comes back as an ordinary failure without
            // the coroutine ever having suspended, and withTimeoutOrNull then hands that failure
            // on as the block's own answer: ask whether the limit has passed.
            fetch(source, destination, onProgress).also { ensureActive() }
        } ?: run {
            Log.w(TAG, "fetch ${source.sourceId}: took longer than a file of its size may (${limitMs / 1000} s); not kept")
            runCatching { if (destination.exists()) destination.delete() }
            Result.failure(TookTooLongException())
        }
    }

    /** Tests: the time an add-on's fetch may take for a ceiling of so many bytes ([timeLimitFor]). */
    internal var timeLimitMs: (Long) -> Long = { timeLimitFor(it) }

    private suspend fun fetch(
        source: SourceResult,
        destination: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ): Result<File> {
        // A DASH manifest (Tidal) is not the file: fetch its segments and remux.
        if (source.isDash) {
            return dashDownloader.download(
                client = clientFor(source),
                mpdUrl = source.downloadUrl,
                headers = source.downloadHeaders,
                destination = destination,
                onProgress = onProgress,
                maxBytes = source.maxBytes,
            ).asStated(source)
        }
        return fetchFile(source, destination, onProgress).asStated(source)
    }

    /**
     * A file an add-on answered is kept only when it starts as the format the add-on said it is
     * (review-882 M9). "Lossless" is the add-on's own word: one that serves an MP3 or AAC file
     * under it had that file saved as `.flac` in the library. Stash's own sources are not judged
     * here: their files are what their catalogs say, and the checks after this one (length,
     * codec probe) are theirs.
     */
    private fun Result<File>.asStated(source: SourceResult): Result<File> {
        val file = getOrNull() ?: return this
        if (!isAddonSource(source.sourceId)) return this
        val extension = source.format.fileExtension.ifBlank { source.format.codec }
        if (startsAs(file, extension)) return this
        Log.w(TAG, "fetch ${source.sourceId}: the file is not the ${extension.lowercase()} it was said to be; not kept")
        runCatching { file.delete() }
        return Result.failure(NotTheStatedFormatException(extension.lowercase()))
    }

    private suspend fun fetchFile(
        source: SourceResult,
        destination: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ): Result<File> {
        val maxBytes = source.maxBytes
        val requestBuilder = Request.Builder().url(source.downloadUrl).get()
        for ((name, value) in source.downloadHeaders) {
            requestBuilder.header(name, value)
        }
        val request = requestBuilder.build()

        return try {
            val call = clientFor(source).newCall(request)
            coroutineScope {
                // A blocking response-body read does not observe coroutine
                // cancellation itself. This child is started immediately and
                // cancels the OkHttp call as soon as the parent is cancelled.
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
                    call.execute().use { response ->
                if (!response.isSuccessful) {
                    return@use Result.failure(
                        IllegalStateException(
                            // The reason phrase is the media host's own text, which an add-on can
                            // now choose: short, and without control characters (review-882 N5).
                            "fetch ${source.sourceId} failed: HTTP ${response.code} ${loggable(response.message)}",
                        ),
                    )
                }
                val body = response.body ?: return@use Result.failure(
                    IllegalStateException("fetch ${source.sourceId} failed: empty body"),
                )
                val totalBytes = body.contentLength().coerceAtLeast(0L)
                // A file larger than the song can be (review-882 M11): refused before a byte is kept.
                if (maxBytes != null && totalBytes > maxBytes) {
                    return@use Result.failure(
                        IllegalStateException("fetch ${source.sourceId} failed: $totalBytes bytes is more than this song can be ($maxBytes)"),
                    )
                }

                // Stream body → file in 64 KB chunks. Okio's BufferedSink
                // gives us flush guarantees without us managing a manual
                // buffer; the per-chunk callback drives the progress UI.
                destination.parentFile?.mkdirs()
                destination.sink().buffer().use { sink ->
                    val bodySource = body.source()
                    var bytesRead = 0L
                    val buf = okio.Buffer()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = bodySource.read(buf, 64 * 1024)
                        if (read == -1L) break
                        sink.write(buf, read)
                        bytesRead += read
                        // No Content-Length, or one that lied: stop at the ceiling all the same.
                        if (maxBytes != null && bytesRead > maxBytes) {
                            throw java.io.IOException("the file is larger than this song can be")
                        }
                        onProgress(bytesRead, totalBytes)
                    }
                    sink.flush()
                }

                if (destination.length() == 0L) {
                    runCatching { if (destination.exists()) destination.delete() }
                    return@use Result.failure(
                        IllegalStateException("fetch ${source.sourceId} produced empty file"),
                    )
                }

                Result.success(destination)
                    }
                } finally {
                    cancellationWatcher.cancel()
                }
            }
        } catch (e: CancellationException) {
            runCatching { if (destination.exists()) destination.delete() }
            throw e
        } catch (e: Exception) {
            // An add-on's failure by class only: its message can name the URL, which can carry a token.
            Log.w(TAG, "fetch ${source.sourceId} threw: ${e.javaClass.simpleName}" + if (isAddonSource(source.sourceId)) "" else ": ${e.message}")
            // Best-effort cleanup of any partial file so the caller's
            // fallback path doesn't accidentally treat a 0-byte temp
            // file as a successful download.
            runCatching { if (destination.exists()) destination.delete() }
            Result.failure(e)
        }
    }

    /** A fetched file is not the format its source said it is ([asStated]). Names the format, never the URL. */
    class NotTheStatedFormatException(val stated: String) : java.io.IOException("the file is not $stated")

    /** An add-on's fetch ran past its time limit ([timeLimitFor]). */
    class TookTooLongException : java.io.IOException("the fetch took longer than a file of its size may")

    companion object {
        private const val TAG = "LosslessUrlDownloader"

        /**
         * Whether [file] begins as a file of [extension] does. Judged: `flac` (the `fLaC` marker,
         * after an ID3v2 tag when the file carries one in front, as some do) and `wav` (`RIFF` …
         * `WAVE`). Any other extension is not judged here: an `.m4a` starts the same whether it
         * holds ALAC or AAC. A file that cannot be read is not what it was said to be.
         */
        internal fun startsAs(file: File, extension: String): Boolean {
            val ext = extension.lowercase()
            if (ext != "flac" && ext != "wav") return true
            return try {
                java.io.RandomAccessFile(file, "r").use { f ->
                    val head = ByteArray(12)
                    if (f.read(head) < head.size) {
                        false
                    } else if (ext == "wav") {
                        head.hasAt(0, "RIFF") && head.hasAt(8, "WAVE")
                    } else if (head.hasAt(0, "ID3")) {
                        // "ID3", two version bytes, flags, then the tag's length in four 7-bit bytes.
                        val length = ((head[6].toInt() and 0x7f) shl 21) or ((head[7].toInt() and 0x7f) shl 14) or
                            ((head[8].toInt() and 0x7f) shl 7) or (head[9].toInt() and 0x7f)
                        val footer = if ((head[5].toInt() and 0x10) != 0) 10 else 0
                        f.seek(10L + length + footer)
                        val marker = ByteArray(4)
                        f.read(marker) == marker.size && marker.hasAt(0, "fLaC")
                    } else {
                        head.hasAt(0, "fLaC")
                    }
                }
            } catch (e: java.io.IOException) {
                false
            }
        }

        private fun ByteArray.hasAt(offset: Int, text: String): Boolean =
            size >= offset + text.length && text.indices.all { this[offset + it] == text[it].code.toByte() }

        /** A source whose URL an add-on chose (`AddonLosslessSource.sourceId`): fetched on the guarded client only. */
        fun isAddonSource(sourceId: String): Boolean = sourceId.startsWith("addon:")

        /**
         * How long an add-on's fetch may take for a ceiling of [maxBytes]: the time that many
         * bytes need at [FLOOR_BYTES_PER_SECOND], and a minute to get going. A 4-minute CD FLAC
         * (ceiling 61 MB) gets about 17 minutes; the slowest connection it allows still plays the
         * same file in real time.
         */
        fun timeLimitFor(maxBytes: Long): Long = maxBytes.coerceAtLeast(0L) / FLOOR_BYTES_PER_SECOND * 1_000L + 60_000L

        /** The slowest an add-on's fetch may run, on average, before it is given up. */
        const val FLOOR_BYTES_PER_SECOND = 64L * 1024

        /**
         * True when the add-on media client's guard refused the fetch [e] came from: it pointed at
         * the user's own network (a private address, a name that resolves there) or broke the URL
         * rule. Looked for down the causes: a DASH segment's refusal arrives wrapped.
         */
        fun isGuardRefusal(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(8).any {
            it is com.stash.core.addons.PrivateAddressException ||
                it is com.stash.core.addons.RefusedAddonUrlException ||
                it is com.stash.core.addons.NoPublicAddressException
        }

        /** Text a server chose, as a log line can carry it: short, no control characters. */
        fun loggable(s: String): String = s.take(60).map { if (it < ' ' || it in '\u007f'..'\u009f') ' ' else it }.joinToString("")

        // Inter-byte stall timeout for the big-file fetch (was 30 s on the shared
        // client). A Hi-Res FLAC pull can stall past 30 s on a congested hotspot;
        // 90 s tolerates the pause without masking a truly dead socket.
        private const val FETCH_STALL_TIMEOUT_SECONDS = 90L
        private const val NO_REDIRECT_SOURCE_ID = "jiosaavn"
    }
}
