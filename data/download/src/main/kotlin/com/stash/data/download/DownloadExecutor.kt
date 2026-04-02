package com.stash.data.download

import android.content.Context
import android.util.Log
import com.stash.core.auth.TokenManager
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
        ytDlpManager.initialize()

        // SECURITY: Cookie file contains sensitive YouTube session cookies (SAPISID,
        // LOGIN_INFO, etc.). It is written only for the duration of the yt-dlp call and
        // deleted in the finally block. File permissions are restricted to owner-only
        // read/write to prevent other apps on rooted devices from reading it.
        val cookieFile = File(context.noBackupFilesDir, "yt_cookies_${System.nanoTime()}.txt")

        try {
            val outputTemplate = File(outputDir, "$filename.%(ext)s").absolutePath

            val request = YoutubeDLRequest(url).apply {
                qualityArgs.forEach { addOption(it) }
                addOption("-o", outputTemplate)
                addOption("--no-playlist")

                // Tell yt-dlp to use QuickJS for YouTube's JS signature challenges.
                // Without a JS runtime, yt-dlp can't decrypt stream URLs and
                // downloads fail with "Signature solving failed".
                val qjsPath = ytDlpManager.quickJsPath
                if (qjsPath != null) {
                    addOption("--js-runtimes", "quickjs:$qjsPath")
                    // Download EJS challenge solver scripts from GitHub on first use.
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

            Log.d(TAG, "download: starting url=$url, output=$outputTemplate, args=$qualityArgs")

            val response = YoutubeDL.getInstance().execute(
                request,
                url,
            ) { progress, _, _ ->
                onProgress((progress / 100f).coerceIn(0f, 1f))
            }

            val stdout = response.out.orEmpty()
            val stderr = response.err.orEmpty()
            Log.d(TAG, "download: yt-dlp exit=${response.exitCode}, " +
                "stdoutLen=${stdout.length}, stderrLen=${stderr.length}")

            // Find the output file — yt-dlp determines the extension based on format
            val result = outputDir.listFiles()?.firstOrNull { it.nameWithoutExtension == filename }

            if (result != null && result.exists() && result.length() > 0) {
                Log.d(TAG, "download: SUCCESS file=${result.absolutePath} size=${result.length()}")
                DownloadResult.Success(result)
            } else {
                // yt-dlp exited 0 but no file found — list what IS in the dir for debugging
                val dirContents = outputDir.listFiles()?.map { "${it.name} (${it.length()}b)" }
                Log.w(TAG, "download: no output file found. Dir contents: $dirContents")
                Log.w(TAG, "download: expected filename prefix: $filename")
                DownloadResult.NoOutput(stdout.take(2000), stderr.take(2000))
            }
        } catch (e: YoutubeDLException) {
            // yt-dlp exited with non-zero code. The exception message IS the stderr.
            val errMsg = e.message ?: "Unknown yt-dlp error"
            Log.e(TAG, "download: YT-DLP ERROR url=$url\n$errMsg")
            DownloadResult.YtDlpError(errMsg)
        } catch (e: Exception) {
            Log.e(TAG, "download: UNEXPECTED ERROR url=$url", e)
            DownloadResult.Error(e.message ?: "Unknown error", e)
        } finally {
            if (cookieFile.exists()) cookieFile.delete()
        }
    }
}
