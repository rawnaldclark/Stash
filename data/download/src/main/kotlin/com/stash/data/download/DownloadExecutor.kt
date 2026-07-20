package com.stash.data.download

import android.content.Context
import android.util.Log
import com.stash.core.auth.TokenManager
import com.stash.data.download.files.WebmAudioRemuxer
import com.stash.data.download.ytdlp.YtDlpManager
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a yt-dlp download attempt.
 */
sealed class DownloadResult {
    /** Download succeeded; [file] is the audio on disk. */
    data class Success(val file: File) : DownloadResult()

    /** yt-dlp exited with an error. [message] contains the stderr output. */
    data class YtDlpError(val message: String) : DownloadResult()

    /** An unexpected exception occurred. */
    data class Error(val message: String, val cause: Throwable? = null) : DownloadResult()

    /** yt-dlp ran without error but no output file was found. */
    data class NoOutput(val stdout: String?, val stderr: String?) : DownloadResult()
}

/**
 * Executes a single yt-dlp download, converting the result to audio.
 *
 * Initializes yt-dlp on first use via [YtDlpManager] and delegates the
 * actual download to the youtubedl-android library. Progress is reported
 * via the [onProgress] callback as a fraction in [0.0, 1.0].
 *
 * Note: The youtubedl-android library automatically adds --ffmpeg-location
 * during execute(), pointing to the FFmpeg binary it extracted during init().
 * We do NOT add our own --ffmpeg-location to avoid conflicts.
 */
@Singleton
class DownloadExecutor @Inject constructor(
    private val ytDlpManager: YtDlpManager,
    @ApplicationContext private val context: Context,
    private val tokenManager: TokenManager,
    private val webmRemuxer: WebmAudioRemuxer,
) {
    companion object {
        private const val TAG = "StashDL"
    }

    /**
     * Downloads audio from a YouTube URL using yt-dlp.
     *
     * @param url         YouTube video URL to download.
     * @param outputDir   Directory to write the downloaded file into.
     * @param filename    Base filename (without extension) for the output.
     * @param qualityArgs yt-dlp CLI arguments controlling audio quality and format.
     * @param onProgress  Callback receiving download progress as a fraction in [0.0, 1.0].
     * @return A [DownloadResult] describing the outcome.
     */
    suspend fun download(
        url: String,
        outputDir: File,
        filename: String,
        qualityArgs: List<String>,
        onProgress: (Float) -> Unit = {},
    ): DownloadResult = withContext(Dispatchers.IO) {
        // Gate on a freshened (latest-nightly) yt-dlp + warmed EJS solver
        // before the first download in a session. YouTube's signature /
        // n-challenge changes land in nightly well before stable, so running
        // the lib-bundled snapshot is what makes downloads fail en masse.
        ytDlpManager.ensureFreshened()

        // SECURITY: Cookie file contains sensitive YouTube session cookies (SAPISID,
        // LOGIN_INFO, etc.). It is written only for the duration of the yt-dlp call and
        // deleted in the finally block. File permissions are restricted to owner-only
        // read/write to prevent other apps on rooted devices from reading it.
        val cookieFile = File(context.noBackupFilesDir, "yt_cookies_${System.nanoTime()}.txt")
        val cookie = tokenManager.getYouTubeCookie()
        if (cookie != null) {
            CookieFileWriter.write(cookie, cookieFile)
            cookieFile.setReadable(false, false)
            cookieFile.setReadable(true, true)
            cookieFile.setWritable(false, false)
            cookieFile.setWritable(true, true)
            Log.i(TAG, "download: cookies attached (len=${cookie.length})")
        } else {
            Log.w(TAG, "download: NO COOKIE attached — yt-dlp will run anonymously")
        }
        val cookiePath = if (cookie != null) cookieFile.absolutePath else null

        try {
            // Fast path: pin to the android_vr client. It returns a direct
            // download format with no player-JS fetch, no QuickJS n-sig/JS
            // challenge solve, and no m3u8 probe — ~8s vs ~30s for the default
            // multi-client path under 8-way parallel sync load (measured
            // on-device 2026-06-26; the concurrent challenge solves were also
            // thrashing the CPU). Fall back to the default client set when
            // android_vr can't serve the track, so broad-catalog coverage —
            // the whole reason the YouTube download path exists — never regresses.
            val fast = runYtDlp(url, outputDir, filename, qualityArgs, cookiePath, "android_vr", onProgress)
            if (fast is DownloadResult.Success) fast
            else runYtDlp(url, outputDir, filename, qualityArgs, cookiePath, null, onProgress)
        } finally {
            if (cookieFile.exists()) cookieFile.delete()
        }
    }

    /**
     * One yt-dlp download invocation. [playerClient] pins the YouTube extractor
     * to a single client (e.g. `android_vr`); null leaves the default client
     * set. Returns the result for THIS attempt; the caller decides whether a
     * non-[DownloadResult.Success] should fall back to another client.
     */
    private suspend fun runYtDlp(
        url: String,
        outputDir: File,
        filename: String,
        qualityArgs: List<String>,
        cookiePath: String?,
        playerClient: String?,
        onProgress: (Float) -> Unit,
    ): DownloadResult {
        return try {
            val outputTemplate = File(outputDir, "$filename.%(ext)s").absolutePath

            val request = YoutubeDLRequest(url).apply {
                qualityArgs.forEach { addOption(it) }
                addOption("-o", outputTemplate)
                addOption("--no-playlist")
                playerClient?.let { addOption("--extractor-args", "youtube:player_client=$it") }

                // Tell yt-dlp to use QuickJS for YouTube's JS signature challenges.
                // Without a JS runtime, yt-dlp can't decrypt stream URLs and
                // downloads fail with "Signature solving failed". (android_vr
                // usually needs none, but the default-client fallback does.)
                val qjsPath = ytDlpManager.quickJsPath
                if (qjsPath != null) {
                    addOption("--js-runtimes", "quickjs:$qjsPath")
                    addOption("--remote-components", "ejs:github")
                }

                // Log the actually-selected format (140 AAC-128 vs 141 AAC-256
                // vs 251 Opus — all land as their own ext). after_video: fires
                // AFTER the download; bare --print implies --simulate and breaks it.
                addOption(
                    "--print",
                    "after_video:STASHDL_FMT|id=%(format_id)s|abr=%(abr)s|" +
                        "acodec=%(acodec)s|ext=%(ext)s|height=%(height)s",
                )
                cookiePath?.let { addOption("--cookies", it) }
            }

            Log.d(TAG, "download: starting url=$url client=${playerClient ?: "default"} args=$qualityArgs")

            // processId must be null, not `url`: youtubedl-android throws
            // "Process ID already exists" if two execute() calls share a
            // non-null id, and a preview prefetch + this download routinely
            // hit the same video at once. Nothing cancels by id (no
            // destroyProcessById caller), so the handle was unused anyway. (#210)
            val response = YoutubeDL.getInstance().execute(request, null) { progress, _, _ ->
                onProgress((progress / 100f).coerceIn(0f, 1f))
            }

            val stdout = response.out.orEmpty()
            val stderr = response.err.orEmpty()
            Log.d(TAG, "download: yt-dlp exit=${response.exitCode} client=${playerClient ?: "default"} " +
                "stdoutLen=${stdout.length}, stderrLen=${stderr.length}")
            stdout.lineSequence()
                .firstOrNull { it.startsWith("STASHDL_FMT|") }
                ?.let { Log.i(TAG, "download: picked $it") }

            // Find the output file — yt-dlp determines the extension based on format
            val result = outputDir.listFiles()?.firstOrNull { it.nameWithoutExtension == filename }

            if (result != null && result.exists() && result.length() > 0) {
                Log.d(TAG, "download: SUCCESS file=${result.absolutePath} size=${result.length()}")
                // YouTube's Opus itags land in a `.webm` container, which
                // MediaStore tags as video/webm (→ black-screen "videos" in
                // Google Photos once a library is moved to shared storage).
                // Losslessly remux webm→opus so it indexes as audio. No-op for
                // `.m4a`/`.flac`/etc.
                DownloadResult.Success(webmRemuxer.toOpusIfWebm(result))
            } else {
                val dirContents = outputDir.listFiles()?.map { "${it.name} (${it.length()}b)" }
                Log.w(TAG, "download: no output file (client=${playerClient ?: "default"}). Dir: $dirContents")
                DownloadResult.NoOutput(stdout.take(2000), stderr.take(2000))
            }
        } catch (e: YoutubeDLException) {
            // yt-dlp exited with non-zero code. The exception message IS the stderr.
            val errMsg = e.message ?: "Unknown yt-dlp error"
            Log.e(TAG, "download: YT-DLP ERROR client=${playerClient ?: "default"} url=$url\n$errMsg")
            DownloadResult.YtDlpError(errMsg)
        } catch (e: Exception) {
            Log.e(TAG, "download: UNEXPECTED ERROR url=$url", e)
            DownloadResult.Error(e.message ?: "Unknown error", e)
        }
    }
}
