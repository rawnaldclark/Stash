package com.stash.data.download.ytdlp

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
    private val warmMutex = Mutex()
    private var warmed = false
    private val freshenMutex = Mutex()
    @Volatile private var freshened = false

    /**
     * The "freshen" operation: pull the latest yt-dlp from the configured
     * update channel. Overridable so the [runFreshenOnce] coalescing logic
     * can be unit-tested without touching the youtubedl-android singleton.
     */
    internal var freshenOp: suspend () -> Unit = { doNightlyUpdate() }

    /**
     * Runs [freshenOp] at most once for the lifetime of this process,
     * coalescing concurrent callers behind [freshenMutex]. Subsequent
     * callers (and the first download after the op has run) return
     * immediately.
     */
    internal suspend fun runFreshenOnce() {
        if (freshened) return
        freshenMutex.withLock {
            if (freshened) return
            freshenOp()
            freshened = true
        }
    }

    /** Path to the extracted QuickJS binary, set during [initialize]. */
    var quickJsPath: String? = null
        private set

    companion object {
        private const val TAG = "YtDlpManager"
        private const val QJS_LIB_NAME = "libqjs.so"

        /**
         * Evergreen clip for the throwaway warmup extraction at app start, so
         * the first real user preview doesn't pay the cold-start cost
         * (player-JS fetch + parse + QuickJS bootstrap ~14 s on-device) and so
         * a failed warmup is a genuine signal that challenge-solving is broken.
         *
         * "Me at the zoo" — the first-ever YouTube upload (id `jNQXAC9IVRw`).
         * Public, never age/geo-gated, and effectively guaranteed to outlive
         * the app. The previous canary (`BaW_jenozKc`, youtube-dl's old test
         * clip) went "Video unavailable" in 2026, which silently masked the
         * warmup's ability to detect signature/n-challenge regressions.
         */
        private const val WARMUP_VIDEO_URL =
            "https://www.youtube.com/watch?v=jNQXAC9IVRw"
    }

    /**
     * Initializes the yt-dlp native binary and locates QuickJS. Safe to call
     * multiple times; only the first invocation performs real work.
     *
     * Does NOT update yt-dlp — freshening is gated through [ensureFreshened]
     * so the first download in a session is guaranteed to run the latest
     * nightly extractor + EJS solver rather than the lib-bundled snapshot.
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
            }
            initialized = true
        }
    }

    /**
     * Idempotent session gate for the download path: ensures yt-dlp is
     * initialized, freshened to the latest nightly ([runFreshenOnce], at most
     * once per process), and the runtime/EJS solver are warmed. The update is
     * AWAITED so the first download doesn't run on the stale lib-bundled
     * binary — that race is what made "sync finds tracks, then every download
     * fails" survive even with a self-update in place.
     *
     * Best-effort: a failed update/warmup leaves the existing binary in place
     * and the gate still opens (we can't do better than the bundled binary,
     * and refusing to download would be strictly worse).
     */
    suspend fun ensureFreshened() {
        initialize()
        runFreshenOnce()
        warmUp()
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
     * One-shot background warmup. Kicks a throwaway URL extraction against
     * a known-stable short clip so yt-dlp's in-process player-JS cache
     * and QuickJS runtime are primed before the user ever taps Preview.
     *
     * Idempotent (coalesces behind [warmMutex]); safe to call any number
     * of times. Failures are swallowed — warmup is a pure optimization;
     * if it fails, the normal extraction path still works fine.
     *
     * Call AFTER [initialize]; if not initialized yet, this no-ops.
     */
    suspend fun warmUp() {
        if (warmed) return
        if (!initialized) return
        warmMutex.withLock {
            if (warmed) return
            withContext(Dispatchers.IO) {
                val t0 = System.currentTimeMillis()
                try {
                    val request = YoutubeDLRequest(WARMUP_VIDEO_URL).apply {
                        addOption("-f", "bestaudio")
                        addOption("--print", "urls")
                        addOption("--no-download")
                        quickJsPath?.let { qjs ->
                            addOption("--js-runtimes", "quickjs:$qjs")
                            addOption("--remote-components", "ejs:github")
                        }
                    }
                    // null processId for the same reason as the download/preview
                    // paths: a non-null id can collide with a concurrent
                    // execute() of the same video. (#210)
                    val response = YoutubeDL.getInstance()
                        .execute(request, null, null)
                    val dt = System.currentTimeMillis() - t0
                    Log.i(
                        TAG,
                        "warmup: exit=${response.exitCode} dt=${dt}ms " +
                            "stdoutLen=${response.out.orEmpty().length}",
                    )
                } catch (t: Throwable) {
                    val dt = System.currentTimeMillis() - t0
                    Log.w(TAG, "warmup failed after ${dt}ms: ${t.message}")
                }
            }
            warmed = true
        }
    }

    /**
     * Pulls the latest yt-dlp from the NIGHTLY channel. YouTube's player /
     * n-challenge changes land in yt-dlp nightly (and its bundled EJS solver)
     * well before they reach the ~monthly stable release, so following
     * nightly is what keeps downloads working as YouTube tightens signing.
     * Best-effort: a failed update leaves the previously-installed binary in
     * place. Re-throws cancellation so WorkManager teardown isn't masked.
     */
    private suspend fun doNightlyUpdate() {
        withContext(Dispatchers.IO) {
            try {
                val status = YoutubeDL.getInstance().updateYoutubeDL(
                    context,
                    YoutubeDL.UpdateChannel._NIGHTLY,
                )
                Log.i(TAG, "yt-dlp freshen (nightly): $status, version now ${getVersion()}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "yt-dlp freshen (nightly) failed: ${e.message}")
            }
        }
    }

    /**
     * Attempts to update the yt-dlp binary to the latest nightly release.
     *
     * @return an [UpdateResult] describing the outcome.
     */
    suspend fun updateYtDlp(): UpdateResult {
        return withContext(Dispatchers.IO) {
            try {
                val status = YoutubeDL.getInstance().updateYoutubeDL(
                    context,
                    YoutubeDL.UpdateChannel._NIGHTLY,
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
