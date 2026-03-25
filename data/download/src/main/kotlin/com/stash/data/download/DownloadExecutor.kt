package com.stash.data.download

import com.stash.data.download.ytdlp.YtDlpManager
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
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

        try {
            val outputTemplate = File(outputDir, "$filename.%(ext)s").absolutePath

            val request = YoutubeDLRequest(url).apply {
                qualityArgs.forEach { addOption(it) }
                addOption("-o", outputTemplate)
                addOption("--no-playlist")
            }

            YoutubeDL.getInstance().execute(
                request,
                url, // processId for cancellation
            ) { progress, _, _ ->
                onProgress((progress / 100f).coerceIn(0f, 1f))
            }

            // Find the output file (extension may vary depending on format conversion)
            outputDir.listFiles()?.firstOrNull { it.nameWithoutExtension == filename }
        } catch (_: Exception) {
            null
        }
    }
}
