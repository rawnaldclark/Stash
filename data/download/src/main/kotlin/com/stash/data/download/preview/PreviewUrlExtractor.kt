package com.stash.data.download.preview

import android.content.Context
import android.util.Log
import com.stash.core.auth.TokenManager
import com.stash.data.download.CookieFileWriter
import com.stash.data.download.ytdlp.YtDlpManager
import com.stash.data.ytmusic.InnerTubeClient
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts a direct audio stream URL from YouTube for preview playback.
 *
 * ## Strategy: InnerTube fast path → yt-dlp fallback
 *
 * 1. **InnerTube player API (~1-2s)**: Calls the YouTube Music player endpoint
 *    with authenticated cookies. Parses `streamingData.adaptiveFormats` for
 *    audio-only URLs. These URLs may be n-parameter throttled (~50KB/s) but
 *    that's more than enough for audio preview (Opus is ~20KB/s).
 *
 * 2. **yt-dlp fallback (~15-35s)**: If InnerTube returns no usable URLs
 *    (ciphered, geo-blocked, etc.), falls back to yt-dlp with QuickJS for
 *    full signature solving. Slow but reliable.
 *
 * The InnerTube fast path succeeds for the majority of YouTube Music tracks,
 * making previews near-instant.
 */
@Singleton
class PreviewUrlExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ytDlpManager: YtDlpManager,
    private val tokenManager: TokenManager,
    private val innerTubeClient: InnerTubeClient,
) {
    companion object {
        private const val TAG = "PreviewUrlExtractor"
        private const val YTDLP_TIMEOUT_MS = 60_000L
        private const val INNERTUBE_TIMEOUT_MS = 10_000L
        private const val FORMAT_SELECTOR = "251/250/bestaudio"
    }

    /**
     * Extracts a direct audio stream URL for the given YouTube video ID.
     *
     * Tries InnerTube first (fast, ~1-2s), falls back to yt-dlp (slow, ~15-35s).
     */
    suspend fun extractStreamUrl(videoId: String): String {
        // Fast path: InnerTube player API
        try {
            val url = extractViaInnerTube(videoId)
            if (url != null) return url
        } catch (e: Exception) {
            Log.w(TAG, "InnerTube fast path failed for $videoId: ${e.message}")
        }

        // Slow fallback: yt-dlp with QuickJS cipher solving
        Log.d(TAG, "Falling back to yt-dlp for videoId=$videoId")
        return extractViaYtDlp(videoId)
    }

    /**
     * Fast path: extract stream URL via InnerTube player API.
     *
     * Calls `/youtubei/v1/player` with the user's YouTube session cookies.
     * Parses `streamingData.adaptiveFormats` for audio-only formats with
     * direct `url` fields (not `signatureCipher`).
     *
     * Returns null if no usable URL is found (all formats ciphered, video
     * unavailable, etc.).
     */
    private suspend fun extractViaInnerTube(videoId: String): String? {
        return withTimeout(INNERTUBE_TIMEOUT_MS) {
            Log.d(TAG, "Trying InnerTube fast path for videoId=$videoId")

            val response = innerTubeClient.player(videoId) ?: run {
                Log.w(TAG, "InnerTube player returned null for $videoId")
                return@withTimeout null
            }

            // Check playability
            val status = response["playabilityStatus"]
                ?.jsonObject?.get("status")
                ?.jsonPrimitive?.content
            if (status != "OK") {
                Log.w(TAG, "InnerTube: video $videoId status=$status")
                return@withTimeout null
            }

            // Extract streamingData.adaptiveFormats
            val streamingData = response["streamingData"]?.jsonObject ?: run {
                Log.w(TAG, "InnerTube: no streamingData for $videoId")
                return@withTimeout null
            }

            val adaptiveFormats = streamingData["adaptiveFormats"]?.jsonArray ?: run {
                Log.w(TAG, "InnerTube: no adaptiveFormats for $videoId")
                return@withTimeout null
            }

            // Find the best audio format with a direct URL (not signatureCipher)
            val audioFormats = adaptiveFormats
                .filterIsInstance<JsonObject>()
                .filter { format ->
                    val mimeType = format["mimeType"]?.jsonPrimitive?.content ?: ""
                    val hasDirectUrl = format["url"] != null
                    val isAudio = mimeType.startsWith("audio/")
                    isAudio && hasDirectUrl
                }
                .sortedByDescending { it["bitrate"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L }

            val bestAudio = audioFormats.firstOrNull() ?: run {
                Log.d(TAG, "InnerTube: no audio formats with direct URL for $videoId " +
                    "(${adaptiveFormats.size} total formats, all may be ciphered)")
                return@withTimeout null
            }

            val streamUrl = bestAudio["url"]!!.jsonPrimitive.content
            val mimeType = bestAudio["mimeType"]?.jsonPrimitive?.content ?: "unknown"
            val bitrate = bestAudio["bitrate"]?.jsonPrimitive?.content ?: "?"

            Log.d(TAG, "InnerTube: SUCCESS videoId=$videoId mime=$mimeType bitrate=$bitrate urlLen=${streamUrl.length}")
            streamUrl
        }
    }

    /**
     * Slow fallback: extract stream URL via yt-dlp with QuickJS cipher solving.
     */
    private suspend fun extractViaYtDlp(videoId: String): String {
        val cookieFile = File(context.noBackupFilesDir, "yt_preview_cookies_${System.nanoTime()}.txt")

        return withTimeout(YTDLP_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                ytDlpManager.initialize()

                try {
                    val url = "https://www.youtube.com/watch?v=$videoId"

                    val request = YoutubeDLRequest(url).apply {
                        addOption("-f", FORMAT_SELECTOR)
                        addOption("--print", "urls")
                        addOption("--no-download")

                        val qjsPath = ytDlpManager.quickJsPath
                        if (qjsPath != null) {
                            addOption("--js-runtimes", "quickjs:$qjsPath")
                            addOption("--remote-components", "ejs:github")
                        }
                    }

                    val cookie = tokenManager.getYouTubeCookie()
                    if (cookie != null) {
                        CookieFileWriter.write(cookie, cookieFile)
                        cookieFile.setReadable(false, false)
                        cookieFile.setReadable(true, true)
                        cookieFile.setWritable(false, false)
                        cookieFile.setWritable(true, true)
                        request.addOption("--cookies", cookieFile.absolutePath)
                    }

                    Log.d(TAG, "yt-dlp: invoking for videoId=$videoId")
                    val response = YoutubeDL.getInstance().execute(request, url, null)

                    val stdout = response.out.orEmpty()
                    val stderr = response.err.orEmpty()
                    Log.d(TAG, "yt-dlp: exit=${response.exitCode} stdoutLen=${stdout.length}")
                    if (stderr.isNotBlank()) {
                        Log.d(TAG, "yt-dlp stderr: ${stderr.take(500)}")
                    }

                    val streamUrl = stdout.trim().lines().firstOrNull { it.startsWith("http") }
                    check(!streamUrl.isNullOrBlank()) {
                        "yt-dlp returned no stream URL for videoId=$videoId. stderr: ${stderr.take(500)}"
                    }

                    Log.d(TAG, "yt-dlp: SUCCESS videoId=$videoId urlLen=${streamUrl.length}")
                    streamUrl
                } finally {
                    if (cookieFile.exists()) cookieFile.delete()
                }
            }
        }
    }
}
