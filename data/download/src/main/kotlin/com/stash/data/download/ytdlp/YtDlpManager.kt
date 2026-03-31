package com.stash.data.download.ytdlp

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the yt-dlp binary lifecycle: initialization, version queries, and
 * self-updates via the JunkFood02 youtubedl-android library.
 *
 * Thread-safe: concurrent callers of [initialize] will coalesce behind a mutex.
 */
@Singleton
class YtDlpManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val initMutex = Mutex()
    private var initialized = false

    /**
     * Initializes the yt-dlp native binary. Safe to call multiple times;
     * only the first invocation performs real work.
     */
    suspend fun initialize() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context)
            }
            initialized = true
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
