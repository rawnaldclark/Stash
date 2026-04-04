package com.stash.data.download.ytdlp

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the yt-dlp binary lifecycle: initialization, version queries, and
 * self-updates via the JunkFood02 youtubedl-android library.
 *
 * Also extracts and manages the QuickJS runtime binary, which yt-dlp needs
 * to solve YouTube's JavaScript signature challenges.
 *
 * Thread-safe: concurrent callers of [initialize] will coalesce behind a mutex.
 */
@Singleton
class YtDlpManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val initMutex = Mutex()
    private var initialized = false
    private val backgroundScope = CoroutineScope(Dispatchers.IO)

    /** Path to the extracted QuickJS binary, set during [initialize]. */
    var quickJsPath: String? = null
        private set

    companion object {
        private const val TAG = "YtDlpManager"
        private const val QJS_LIB_NAME = "libqjs.so"
    }

    /**
     * Initializes the yt-dlp native binary, locates QuickJS, and attempts
     * a self-update. Safe to call multiple times; only the first invocation
     * performs real work.
     */
    suspend fun initialize() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context)
                Log.i(TAG, "yt-dlp initialized, version: ${getVersion()}")

                // Find QuickJS in nativeLibraryDir where Android extracts .so files.
                // Must be here (not filesDir) because SELinux blocks execute_no_trans
                // on app_data_file — only native libs have the apk_data_file context
                // that allows process execution.
                locateQuickJs()

                // Schedule non-blocking background update check so initialization
                // is not held up by network latency.
                backgroundScope.launch {
                    try {
                        val status = YoutubeDL.getInstance().updateYoutubeDL(
                            context,
                            YoutubeDL.UpdateChannel._STABLE,
                        )
                        Log.i(TAG, "yt-dlp background update: $status")
                    } catch (e: Exception) {
                        Log.w(TAG, "yt-dlp background update failed: ${e.message}")
                    }
                }
            }
            initialized = true
        }
    }

    /**
     * Locates the QuickJS binary in the native library directory.
     * The binary is bundled as libqjs.so in jniLibs/ and extracted by Android
     * to nativeLibraryDir, where it has execute permission (apk_data_file SELinux context).
     */
    private fun locateQuickJs() {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val qjsFile = File(nativeDir, QJS_LIB_NAME)
        if (qjsFile.exists() && qjsFile.canExecute()) {
            quickJsPath = qjsFile.absolutePath
            Log.i(TAG, "QuickJS found at $quickJsPath (${qjsFile.length()} bytes)")
        } else {
            Log.e(TAG, "QuickJS not found at ${qjsFile.absolutePath} exists=${qjsFile.exists()} exec=${qjsFile.canExecute()}")
        }
    }

    /**
     * Attempts to update the yt-dlp binary to the latest stable release.
     *
     * @return an [UpdateResult] describing the outcome.
     */
    suspend fun updateYtDlp(): UpdateResult {
        return withContext(Dispatchers.IO) {
            try {
                val status = YoutubeDL.getInstance().updateYoutubeDL(
                    context,
                    YoutubeDL.UpdateChannel._STABLE,
                )
                when (status) {
                    YoutubeDL.UpdateStatus.DONE -> UpdateResult.Updated
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> UpdateResult.AlreadyUpToDate
                    else -> UpdateResult.AlreadyUpToDate
                }
            } catch (e: Exception) {
                UpdateResult.Failed(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Returns the current yt-dlp version string, or "unknown" if the binary
     * has not been initialized or the version cannot be determined.
     */
    fun getVersion(): String {
        return try {
            YoutubeDL.getInstance().version(context) ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    /** Outcome of a yt-dlp self-update attempt. */
    sealed class UpdateResult {
        /** Binary was updated to a newer version. */
        data object Updated : UpdateResult()

        /** Binary was already at the latest version. */
        data object AlreadyUpToDate : UpdateResult()

        /** Update failed; [reason] contains the error message. */
        data class Failed(val reason: String) : UpdateResult()
    }
}
