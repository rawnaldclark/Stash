package com.stash.data.download.preview

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proves an audio URL can serve its WHOLE body, not just its opening megabyte.
 *
 * InnerTube can hand back PO-token-gated URLs that stream ~1 MB and then 403.
 * Those look perfect in a 30-second search preview and die mid-track in playback
 * — the failure that got the InnerTube fast lane removed from the playback path
 * on 2026-06-08, leaving every YouTube-fallback play to pay for a yt-dlp process
 * spawn. A HEAD or head-range request cannot tell a gated URL from a good one.
 * The last byte can.
 *
 * **Fails OPEN.** No `contentLength`, a timeout, or a transport error all return
 * true. A false rejection costs a needless ~3.6 s yt-dlp fallback, while a false
 * acceptance is still caught downstream by RefreshingDataSourceFactory's 403
 * re-resolve — so absence of evidence must never veto a URL. Only an explicit
 * refusal from the CDN rejects.
 */
@Singleton
class AudioUrlTailProbe @Inject constructor(private val client: OkHttpClient) {

    /**
     * @param contentLength the chosen `adaptiveFormats` entry's `contentLength`.
     *   Null (or non-positive) means we have nothing to probe against and the URL
     *   is accepted.
     */
    suspend fun servesFullFile(url: String, contentLength: Long?): Boolean {
        if (contentLength == null || contentLength <= 0L) return true

        val code = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder()
                        .url(url)
                        // The final byte: a gated URL refuses here while serving
                        // the head of the file happily.
                        .header("Range", "bytes=${contentLength - 1}-")
                        .build()
                    client.newBuilder()
                        .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .build()
                        .newCall(request)
                        .execute()
                        .use { it.code }
                }.getOrElse { t ->
                    if (t is CancellationException) throw t
                    Log.d(TAG, "tail probe transport failure for ${url.take(60)}: ${t.message}")
                    null
                }
            }
        }

        if (code == null) return true
        val ok = code == 206 || code == 200
        if (!ok) Log.i(TAG, "tail probe rejected a gated URL: code=$code")
        return ok
    }

        /**
     * Cheap existence check: does [url] open at all? Unlike
     * [servesFullFile], this needs no `contentLength` — it catches a URL
     * that's blocked from byte 0 (e.g. PO-token gating that has widened to
     * cover a format yt-dlp didn't warn about), not just one that streams a
     * preview-sized chunk before cutting off. Same fail-open contract:
     * timeout/transport error accepts, only an explicit non-2xx rejects.
     */
    suspend fun opens(url: String): Boolean {
        val code = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder()
                        .url(url)
                        .header("Range", "bytes=0-0")
                        .build()
                    client.newBuilder()
                        .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .build()
                        .newCall(request)
                        .execute()
                        .use { it.code }
                }.getOrElse { t ->
                    if (t is CancellationException) throw t
                    Log.d(TAG, "open probe transport failure for ${url.take(60)}: ${t.message}")
                    null
                }
            }
        }

        if (code == null) return true
        val ok = code == 206 || code == 200
        if (!ok) Log.i(TAG, "open probe rejected a blocked URL: code=$code")
        return ok
    }

    private companion object {
        private const val TAG = "AudioUrlTailProbe"
        private const val PROBE_TIMEOUT_MS = 2_000L
    }
}
