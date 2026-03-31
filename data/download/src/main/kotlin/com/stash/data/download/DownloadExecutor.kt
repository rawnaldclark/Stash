package com.stash.data.download

import android.content.Context
import android.util.Log
import com.stash.core.auth.TokenManager
import com.stash.data.download.ytdlp.YtDlpManager
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes a single yt-dlp download, converting the result to audio.
 *
 * Initializes yt-dlp on first use via [YtDlpManager] and delegates the
 * actual download to the youtubedl-android library. Progress is reported
 * via the [onProgress] callback as a fraction in [0.0, 1.0].
 */
@Singleton
class DownloadExecutor @Inject constructor(
    private val ytDlpManager: YtDlpManager,
    @ApplicationContext private val context: Context,
    private val tokenManager: TokenManager,
) {
    /**
     * Downloads audio from a YouTube URL using yt-dlp.
     *
     * @param url         YouTube video URL to download.
     * @param outputDir   Directory to write the downloaded file into.
     * @param filename    Base filename (without extension) for the output.
     * @param qualityArgs yt-dlp CLI arguments controlling audio quality and format.
     * @param onProgress  Callback receiving download progress as a fraction in [0.0, 1.0].
     * @return The downloaded file, or null on failure.
     */
    suspend fun download(
        url: String,
        outputDir: File,
        filename: String,
        qualityArgs: List<String>,
        onProgress: (Float) -> Unit = {},
    ): File? = withContext(Dispatchers.IO) {
        ytDlpManager.initialize()

        // Write YouTube cookies to a temp file for yt-dlp authentication.
        // Unique filename prevents collisions with concurrent downloads (Semaphore allows 3).
        val cookieFile = File(context.noBackupFilesDir, "yt_cookies_${System.nanoTime()}.txt")

        try {
            val outputTemplate = File(outputDir, "$filename.%(ext)s").absolutePath
            val nativeLibDir = context.applicationInfo.nativeLibraryDir

            val request = YoutubeDLRequest(url).apply {
                qualityArgs.forEach { addOption(it) }
                addOption("-o", outputTemplate)
                addOption("--no-playlist")
                addOption("--ffmpeg-location", nativeLibDir)
            }

            // Add cookies if YouTube is authenticated
            val cookie = tokenManager.getYouTubeCookie()
            if (cookie != null) {
                CookieFileWriter.write(cookie, cookieFile)
                request.addOption("--cookies", cookieFile.absolutePath)
                Log.d("StashDL", "download: using YouTube cookies")
            }

            Log.d("StashDL", "download: starting url=$url, output=$outputTemplate, args=$qualityArgs")

            val response = YoutubeDL.getInstance().execute(
                request,
                url,
            ) { progress, _, _ ->
                onProgress((progress / 100f).coerceIn(0f, 1f))
            }

            Log.d("StashDL", "download: yt-dlp exit=${response.exitCode}, stdout=${response.out?.take(500)}, stderr=${response.err?.take(500)}")

            val result = outputDir.listFiles()?.firstOrNull { it.nameWithoutExtension == filename }
            Log.d("StashDL", "download: outputFile=${result?.absolutePath}, exists=${result?.exists()}")
            result
        } catch (e: Exception) {
            Log.e("StashDL", "download: FAILED url=$url", e)
            null
        } finally {
            // Always delete the cookie file — cookies should not persist on disk
            if (cookieFile.exists()) {
                cookieFile.delete()
            }
        }
    }
}
