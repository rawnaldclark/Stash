package com.stash.core.data.audio

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Discards everything written to it. A desugaring-safe stand-in for
 * [OutputStream.nullOutputStream] (added in Java 11 / Android API 33), which
 * throws [NoSuchMethodError] on this app's minSdk-26 range — every device
 * below Android 13 crashed the ffmpeg/loudness path with it. Overriding the
 * bulk `write` keeps `InputStream.copyTo` (which writes whole buffers) cheap.
 */
internal object NullOutputStream : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}

/**
 * Thin adapter around the ffmpeg binary shipped by
 * `io.github.junkfood02.youtubedl-android:ffmpeg`. Sole purpose: testability.
 *
 * The library only exposes `FFmpeg.init(context)`, which extracts the ffmpeg
 * native binary to `applicationInfo.nativeLibraryDir/libffmpeg.so` (the
 * "fake .so" trick that lets Android's loader unpack an executable as if it
 * were a shared library — same approach used by `libqjs.so` in this project).
 * There is no in-process `execute(args)` API, so we shell out via
 * `ProcessBuilder` ourselves. The youtubedl-android library does the same
 * thing internally when running yt-dlp.
 *
 * The ebur128 filter writes its Summary block to **stderr**, so the contract
 * captures stderr specifically. stdout (typically empty for `-f null -`) is
 * discarded.
 *
 * Callers MUST ensure `FFmpeg.getInstance().init(context)` has been called
 * before invoking [runWithStderrCapture] (the binary won't exist on disk
 * otherwise). In this project that happens at app start inside
 * [com.stash.data.download.ytdlp.YtDlpManager.initialize].
 */
interface FFmpegBridge {
    /**
     * Runs ffmpeg with [args] and returns its captured **stderr** as a single
     * String. stdout is consumed (to keep the process pipe drained) but
     * discarded.
     *
     * Suspends on `Dispatchers.IO` — safe to call from any coroutine.
     *
     * Throws [IOException] if the binary cannot be located, the process cannot
     * be started, or ffmpeg exits non-zero. Non-zero failures include the
     * captured stderr in the exception message so callers retain ffmpeg's
     * diagnostic output while rejecting any partial output file.
     */
    suspend fun runWithStderrCapture(args: List<String>): String

    /**
     * Runs ffmpeg with [args] and hands its **stdout** to [onBytes] block by block as it arrives (the array is
     * reused: read `length` bytes of it before returning). For decoding to raw audio on `-`, where the output is
     * far too large to hold.
     *
     * Throws [IOException] as [runWithStderrCapture] does, with the end of stderr in the message. Cancelling the
     * caller kills ffmpeg.
     */
    suspend fun streamStdout(args: List<String>, onBytes: (bytes: ByteArray, length: Int) -> Unit) {
        throw UnsupportedOperationException("streamStdout")
    }
}

/**
 * Production [FFmpegBridge] backed by the youtubedl-android ffmpeg native
 * binary at `nativeLibraryDir/libffmpeg.so`.
 *
 * Required `LD_LIBRARY_PATH` paths are reconstructed from `Context` using the
 * same layout that `YoutubeDL.init` sets up: ffmpeg's shared libraries (and
 * Python's, which ffmpeg can transitively need for some codecs) live under
 * `noBackupFilesDir/youtubedl-android/packages/{python,ffmpeg,aria2c}/usr/lib`.
 * Mirroring the library's env-var convention guarantees ffmpeg resolves its
 * sibling .so files at runtime; without it `dlopen` of libavcodec etc. fails
 * with "library not found."
 */
@Singleton
class FFmpegBridgeImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : FFmpegBridge {

    private fun start(args: List<String>): Process {
        val binary = ffmpegBinary()
        if (!binary.exists() || !binary.canExecute()) {
            throw IOException(
                "ffmpeg binary missing or not executable at ${binary.absolutePath}; " +
                    "ensure FFmpeg.getInstance().init(context) has been called",
            )
        }

        val cmd = ArrayList<String>(args.size + 1).apply {
            add(binary.absolutePath)
            addAll(args)
        }

        return ProcessBuilder(cmd)
            .apply {
                val env = environment()
                env["LD_LIBRARY_PATH"] = ldLibraryPath()
                env["TMPDIR"] = context.cacheDir.absolutePath
            }
            .redirectErrorStream(false)
            .start()
    }

    override suspend fun streamStdout(args: List<String>, onBytes: (bytes: ByteArray, length: Int) -> Unit) =
        withContext(Dispatchers.IO) {
            val (exit, stderr) = awaitStreamingStdout(start(args), onBytes)
            if (exit != 0) {
                Log.w(TAG, "ffmpeg exited with code $exit; stderr length=${stderr.length}")
                throw IOException("ffmpeg exited with code $exit" + if (stderr.isBlank()) "" else ":\n$stderr")
            }
        }

    override suspend fun runWithStderrCapture(args: List<String>): String =
        withContext(Dispatchers.IO) {
            val process = start(args)

            val (exit, stderr) = awaitCapturingStderr(process)

            if (exit != 0) {
                Log.w(TAG, "ffmpeg exited with code $exit; stderr length=${stderr.length}")
                throw IOException(
                    buildString {
                        append("ffmpeg exited with code "); append(exit)
                        if (stderr.isNotBlank()) {
                            append(":\n"); append(stderr)
                        }
                    },
                )
            }
            stderr
        }

    private fun ffmpegBinary(): File =
        File(context.applicationInfo.nativeLibraryDir, FFMPEG_LIB_NAME)

    /**
     * Reconstructs the `LD_LIBRARY_PATH` that the youtubedl-android library
     * sets internally. Order matches the library so behavior is identical:
     * python's lib dir first, then ffmpeg's, then aria2c's.
     *
     * If a given package dir doesn't exist (e.g. user only ever ran ffmpeg
     * direct), that entry still gets added — `dlopen` ignores non-existent
     * directories, and emitting the same string the library does avoids
     * subtle drift if the youtubedl-android internals later check it.
     */
    private fun ldLibraryPath(): String {
        val base = File(context.noBackupFilesDir, "youtubedl-android/packages")
        return buildString {
            append(File(base, "python/usr/lib").absolutePath); append(':')
            append(File(base, "ffmpeg/usr/lib").absolutePath); append(':')
            append(File(base, "aria2c/usr/lib").absolutePath)
        }
    }

    companion object {
        private const val TAG = "FFmpegBridge"

        /**
         * Reads [process]'s stderr to the end, waits for it to exit and returns
         * (exit code, stderr). stdout is drained and discarded.
         *
         * Kills the process if the calling coroutine is cancelled first. The
         * reads block a thread, and cancellation cannot interrupt a blocked
         * read: a cancelled LoudnessBackfillWorker (the charger unplugged) left
         * ffmpeg decoding the whole file, 25-50 s for a FLAC, on battery, which
         * is what deferring to charging exists to avoid. A watcher waiting on
         * another thread destroys the process on cancellation, which ends the
         * reads, and the cancellation is then rethrown rather than reported
         * as an ffmpeg failure.
         */
        internal suspend fun awaitCapturingStderr(process: Process): Pair<Int, String> = coroutineScope {
            // UNDISPATCHED so the watcher is armed before the first blocking
            // read, even if this coroutine is already cancelled.
            val killer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    process.destroy()
                }
            }
            try {
                // Drain stdout on a side thread so the pipe buffer can't fill up
                // and block ffmpeg's writes. Content is discarded — ebur128's
                // Summary lives on stderr.
                val stdoutDrainer = Thread {
                    try {
                        process.inputStream.use { it.copyTo(NullOutputStream) }
                    } catch (_: Exception) {
                        // Stream closed while draining — fine.
                    }
                }.also { it.isDaemon = true; it.start() }

                val stderr = try {
                    process.errorStream.bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    Log.w(TAG, "stderr read failed: ${e.message}")
                    ""
                }

                val exit = try {
                    process.waitFor()
                } catch (ie: InterruptedException) {
                    process.destroy()
                    Thread.currentThread().interrupt()
                    throw ie
                }
                stdoutDrainer.join(500)
                ensureActive()
                exit to stderr
            } finally {
                // Normal exit: the process has already ended, so the watcher's
                // destroy() is a no-op.
                killer.cancel()
            }
        }

        /**
         * Hands [process]'s stdout to [onBytes] as it arrives, waits for it to exit and returns (exit code, the
         * end of its stderr). stderr is read on a side thread so it can't fill and block the process. As in
         * [awaitCapturingStderr], cancelling the caller destroys the process, which ends the blocked read.
         */
        internal suspend fun awaitStreamingStdout(
            process: Process,
            onBytes: (bytes: ByteArray, length: Int) -> Unit,
        ): Pair<Int, String> = coroutineScope {
            val killer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    process.destroy()
                }
            }
            try {
                val stderr = StringBuffer()
                val stderrReader = Thread {
                    try {
                        process.errorStream.bufferedReader().use { reader ->
                            val chunk = CharArray(4096)
                            while (true) {
                                val got = reader.read(chunk)
                                if (got < 0) break
                                stderr.append(chunk, 0, got)
                                // The end is what says why it failed.
                                if (stderr.length > STDERR_KEPT * 2) stderr.delete(0, stderr.length - STDERR_KEPT)
                            }
                        }
                    } catch (_: Exception) {
                        // Stream closed while reading: fine.
                    }
                }.also { it.isDaemon = true; it.start() }

                try {
                    process.inputStream.use { input ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            val got = input.read(buffer)
                            if (got < 0) break
                            if (got > 0) onBytes(buffer, got)
                        }
                    }
                } catch (e: IOException) {
                    // The process was destroyed under the read (cancellation), or died: the exit code says which.
                    ensureActive()
                }
                val exit = try {
                    process.waitFor()
                } catch (ie: InterruptedException) {
                    process.destroy()
                    Thread.currentThread().interrupt()
                    throw ie
                }
                stderrReader.join(500)
                ensureActive()
                exit to stderr.toString()
            } finally {
                killer.cancel()
            }
        }

        private const val STDERR_KEPT = 4_000

        // youtubedl-android extracts the ffmpeg binary into nativeLibraryDir
        // under this name. Confirmed by disassembling the library AAR:
        // `YoutubeDL.init` sets `ffmpegPath = File(binDir, "libffmpeg.so")`.
        // The .so suffix is cosmetic — Android's PackageManager unpacks
        // anything matching `lib*.so` from the APK and grants it the
        // apk_data_file SELinux context that permits execution.
        private const val FFMPEG_LIB_NAME = "libffmpeg.so"
    }
}
