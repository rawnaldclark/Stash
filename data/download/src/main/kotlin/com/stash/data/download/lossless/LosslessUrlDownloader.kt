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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
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
    private val decryptor: com.stash.data.download.lossless.amz.AmzDecryptor,
) {
    // Whole-file lossless fetches are large (amz ultrahd CMAF runs 100-150 MB and
    // must be downloaded in full before decrypt/play). The shared client's 30 s
    // read timeout is an inter-byte stall limit — fine for small JSON, too tight
    // when a congested/shared network pauses mid-stream on a big FLAC. Derive a
    // client with a roomier read/write stall window (no callTimeout, so total
    // duration stays uncapped for big files). Shares the pool/dispatcher/TLS.
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

    // Caps concurrent ENCRYPTED (amz) whole-file fetches. The DownloadManager
    // ceiling is 8, but 8 parallel 20-150 MB amz pulls starve each other's
    // bandwidth (~0.86 MB/s measured vs ~2.3 MB/s at low concurrency) and widen
    // the token-expiry 403 herd. Gating amz fetches to a few in-flight gives each
    // real bandwidth (so tracks land sooner) and keeps the herd narrow. @Singleton,
    // so this is a process-wide amz-fetch limit. Non-amz (Qobuz) fetches: ungated.
    private val amzFetchGate = Semaphore(AMZ_MAX_CONCURRENT_FETCHES)
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
        val requestBuilder = Request.Builder().url(source.downloadUrl).get()
        for ((name, value) in source.downloadHeaders) {
            requestBuilder.header(name, value)
        }
        val request = requestBuilder.build()

        // Encrypted sources (amz CMAF): fetch the encrypted bytes into a
        // sibling `.enc` temp, then ffmpeg-decrypt into [destination]. ffmpeg
        // can't read and write the same path, hence the separate fetch target.
        val key = source.decryptionKey?.takeIf { it.isNotBlank() }
        val fetchTarget = if (key != null) File("${destination.absolutePath}.enc") else destination

        // Gate amz (encrypted) fetches to bound concurrency; non-amz fetches run
        // ungated as before. Released in the finally so a throw/return can't leak
        // a permit and wedge the source.
        if (key != null) amzFetchGate.acquire()
        try {
            val client = if (source.sourceId == NO_REDIRECT_SOURCE_ID) {
                noRedirectFetchClient
            } else {
                fetchClient
            }
            val call = client.newCall(request)
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
                            "fetch ${source.sourceId} failed: HTTP ${response.code} ${response.message}",
                        ),
                    )
                }
                val body = response.body ?: return@use Result.failure(
                    IllegalStateException("fetch ${source.sourceId} failed: empty body"),
                )
                val totalBytes = body.contentLength().coerceAtLeast(0L)

                // Stream body → file in 64 KB chunks. Okio's BufferedSink
                // gives us flush guarantees without us managing a manual
                // buffer; the per-chunk callback drives the progress UI.
                fetchTarget.parentFile?.mkdirs()
                fetchTarget.sink().buffer().use { sink ->
                    val bodySource = body.source()
                    var bytesRead = 0L
                    val buf = okio.Buffer()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = bodySource.read(buf, 64 * 1024)
                        if (read == -1L) break
                        sink.write(buf, read)
                        bytesRead += read
                        onProgress(bytesRead, totalBytes)
                    }
                    sink.flush()
                }

                if (fetchTarget.length() == 0L) {
                    runCatching { if (fetchTarget.exists()) fetchTarget.delete() }
                    return@use Result.failure(
                        IllegalStateException("fetch ${source.sourceId} produced empty file"),
                    )
                }

                if (key == null) {
                    return@use Result.success(fetchTarget)
                }

                // amz: decrypt the encrypted CMAF temp → clear FLAC at
                // [destination], then drop the encrypted temp. A decrypt
                // failure is a source failure (fall through to the next).
                val decrypted = decryptor.decryptToFlac(fetchTarget, key, destination)
                runCatching { if (fetchTarget.exists()) fetchTarget.delete() }
                if (decrypted && destination.length() > 0) {
                    Result.success(destination)
                } else {
                    runCatching { if (destination.exists()) destination.delete() }
                    Result.failure(
                        IllegalStateException("decrypt ${source.sourceId} failed for ${destination.name}"),
                    )
                }
                    }
                } finally {
                    cancellationWatcher.cancel()
                }
            }
        } catch (e: CancellationException) {
            runCatching { if (fetchTarget.exists()) fetchTarget.delete() }
            runCatching { if (destination.exists()) destination.delete() }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "fetch ${source.sourceId} threw: ${e.javaClass.simpleName}: ${e.message}")
            // Best-effort cleanup of any partial files so the caller's
            // fallback path doesn't accidentally treat a 0-byte temp
            // file as a successful download.
            runCatching { if (fetchTarget.exists()) fetchTarget.delete() }
            runCatching { if (destination.exists()) destination.delete() }
            Result.failure(e)
        } finally {
            if (key != null) amzFetchGate.release()
        }
    }

    private companion object {
        const val TAG = "LosslessUrlDownloader"

        // Max concurrent amz whole-file fetches (see [amzFetchGate]). 3 balances
        // per-file bandwidth against aggregate throughput; also caps the 403 herd.
        const val AMZ_MAX_CONCURRENT_FETCHES = 3

        // Inter-byte stall timeout for the big-file fetch (was 30 s on the shared
        // client). amz ultrahd whole-file pulls stalled past 30 s on a congested
        // hotspot; 90 s tolerates the pause without masking a truly dead socket.
        const val FETCH_STALL_TIMEOUT_SECONDS = 90L
        const val NO_REDIRECT_SOURCE_ID = "jiosaavn"
    }
}
