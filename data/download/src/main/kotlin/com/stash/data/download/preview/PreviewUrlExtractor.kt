package com.stash.data.download.preview

import android.content.Context
import android.util.Log
import com.stash.core.auth.TokenManager
import com.stash.data.download.CookieFileWriter
import com.stash.data.download.ytdlp.YtDlpManager
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts a direct CDN audio stream URL from YouTube using yt-dlp, without
 * downloading the audio file.
 *
 * yt-dlp's `--dump-json` flag causes it to emit a JSON metadata blob on stdout
 * instead of downloading content. The `url` field in that blob is the signed CDN
 * URL that points directly to the audio bitstream (typically a Google Video CDN
 * address). This URL is valid for roughly 6 hours and can be fed to ExoPlayer
 * for gapless preview playback without any disk I/O.
 *
 * ## QuickJS
 * YouTube's n-parameter challenge and signature cipher require a JS runtime to
 * solve. Without it yt-dlp fails with "Signature solving failed". We pass the
 * bundled QuickJS binary (libqjs.so) via `--js-runtimes quickjs:<path>` using
 * the same pattern as [com.stash.data.download.DownloadExecutor].
 *
 * ## Cookies
 * Passing YouTube session cookies suppresses bot-detection throttling. The cookie
 * file is written to [Context.noBackupFilesDir] (excluded from Android backups),
 * restricted to owner-only permissions, and deleted in the `finally` block.
 *
 * ## Timeout
 * The entire operation is bounded to [TIMEOUT_MS] milliseconds. yt-dlp must
 * contact YouTube's API, solve JavaScript signature challenges, and resolve
 * the stream URL. This typically takes 5-35 seconds depending on network
 * conditions and whether QuickJS caches are warm.
 */
@Singleton
class PreviewUrlExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ytDlpManager: YtDlpManager,
    private val tokenManager: TokenManager,
) {
    companion object {
        private const val TAG = "PreviewUrlExtractor"

        /**
         * Wall-clock budget for the entire extraction, including yt-dlp startup.
         * Full metadata extraction (with signature solving) typically takes 5-35s
         * depending on network conditions and YouTube's response time.
         */
        private const val TIMEOUT_MS = 60_000L

        /** yt-dlp format selector: prefer Opus/WebM Opus (251/250), fall back to best audio. */
        private const val FORMAT_SELECTOR = "251/250/bestaudio"
    }

    /**
     * Extracts a direct CDN audio stream URL for the given YouTube video ID.
     *
     * Calls yt-dlp with `--dump-json --no-download` so no audio bytes are written
     * to disk. The `url` field of the resulting JSON is the signed stream URL.
     *
     * @param videoId YouTube video ID (the `v=` query parameter, e.g. "dQw4w9WgXcQ").
     * @return The direct CDN stream URL string, ready for use in a media player.
     * @throws YoutubeDLException  if yt-dlp exits with a non-zero status code.
     * @throws IllegalStateException if yt-dlp succeeds but the JSON contains no `url` field,
     *                               which can happen for geo-blocked or age-restricted videos.
     * @throws kotlinx.coroutines.TimeoutCancellationException if the operation exceeds [TIMEOUT_MS].
     */
    suspend fun extractStreamUrl(videoId: String): String {
        // SECURITY: Cookie file contains sensitive YouTube session cookies (SAPISID,
        // LOGIN_INFO, etc.). Written only for the duration of the yt-dlp call and
        // deleted in the finally block. Permissions are restricted to owner-only
        // read/write to prevent other apps on rooted devices from reading it.
        val cookieFile = File(context.noBackupFilesDir, "yt_preview_cookies_${System.nanoTime()}.txt")

        return withTimeout(TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                // Ensure the yt-dlp native binary and QuickJS are ready. Safe to call
                // multiple times — YtDlpManager coalesces concurrent callers behind a mutex.
                ytDlpManager.initialize()

                try {
                    val url = "https://www.youtube.com/watch?v=$videoId"

                    val request = YoutubeDLRequest(url).apply {
                        // Select the best audio-only format. 251 = Opus/WebM at ~160 kbps,
                        // 250 = Opus/WebM at ~70 kbps, bestaudio = widest compatibility fallback.
                        addOption("-f", FORMAT_SELECTOR)

                        // Print ONLY the direct stream URL — much faster than --dump-json
                        // which serializes 600KB+ of metadata we don't need.
                        addOption("--print", "urls")
                        addOption("--no-download")

                        // NOTE: --flat-playlist is intentionally omitted. That flag causes
                        // yt-dlp to skip format resolution and emit only the video ID/title,
                        // which means the `url` field (the signed CDN stream URL) is absent.

                        // QuickJS runtime for YouTube's JS signature challenges.
                        // QuickJS-NG is slow (~20-35s) but required — without it yt-dlp
                        // cannot solve signatures at all. The latency is hidden by
                        // pre-extracting URLs in the background during search.
                        val qjsPath = ytDlpManager.quickJsPath
                        if (qjsPath != null) {
                            addOption("--js-runtimes", "quickjs:$qjsPath")
                            addOption("--remote-components", "ejs:github")
                        }
                    }

                    // Pass cookies so YouTube doesn't bot-detect us.
                    val cookie = tokenManager.getYouTubeCookie()
                    if (cookie != null) {
                        CookieFileWriter.write(cookie, cookieFile)
                        // Restrict file permissions to owner-only (prevents access by other apps)
                        cookieFile.setReadable(false, false)
                        cookieFile.setReadable(true, true)
                        cookieFile.setWritable(false, false)
                        cookieFile.setWritable(true, true)
                        request.addOption("--cookies", cookieFile.absolutePath)
                    }

                    Log.d(TAG, "extractStreamUrl: invoking yt-dlp for videoId=$videoId")

                    val response = YoutubeDL.getInstance().execute(request, url, null)

                    val stdout = response.out.orEmpty()
                    val stderr = response.err.orEmpty()
                    Log.d(
                        TAG,
                        "extractStreamUrl: exit=${response.exitCode} stdoutLen=${stdout.length} " +
                            "stderrLen=${stderr.length}",
                    )
                    if (stderr.isNotBlank()) {
                        Log.d(TAG, "extractStreamUrl stderr: ${stderr.take(1000)}")
                    }

                    // With --print urls, stdout contains just the direct CDN URL (one per line).
                    val streamUrl = stdout.trim().lines().firstOrNull { it.startsWith("http") }
                    check(!streamUrl.isNullOrBlank()) {
                        "yt-dlp returned no stream URL for videoId=$videoId — " +
                            "video may be geo-blocked, age-restricted, or require sign-in. " +
                            "stderr: ${stderr.take(500)}"
                    }

                    Log.d(TAG, "extractStreamUrl: SUCCESS videoId=$videoId urlLen=${streamUrl.length}")
                    streamUrl
                } finally {
                    // Always delete the cookie file, even on exception or timeout cancellation.
                    if (cookieFile.exists()) cookieFile.delete()
                }
            }
        }
    }
}
