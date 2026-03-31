package com.stash.data.download

import android.content.Context
import android.util.Log
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

            // Point yt-dlp to the app's native lib dir where both ffmpeg executables
            // and libc++_shared.so are extracted together by useLegacyPackaging=true
            val nativeLibDir = context.applicationInfo.nativeLibraryDir

            val request = YoutubeDLRequest(url).apply {
                qualityArgs.forEach { addOption(it) }
                addOption("-o", outputTemplate)
                addOption("--no-playlist")
                addOption("--ffmpeg-location", nativeLibDir)
            }

            Log.d("StashDL", "download: starting url=$url, output=$outputTemplate, args=$qualityArgs")

            val response = YoutubeDL.getInstance().execute(
                request,
                url, // processId for cancellation
            ) { progress, _, _ ->
                onProgress((progress / 100f).coerceIn(0f, 1f))
            }

            Log.d("StashDL", "download: yt-dlp exit=${response.exitCode}, stdout=${response.out?.take(500)}, stderr=${response.err?.take(500)}")

            // Find the output file (extension may vary depending on format conversion)
            val result = outputDir.listFiles()?.firstOrNull { it.nameWithoutExtension == filename }
            Log.d("StashDL", "download: outputFile=${result?.absolutePath}, exists=${result?.exists()}")
            result
        } catch (e: Exception) {
            Log.e("StashDL", "download: FAILED url=$url", e)
            null
        }
    }
}
