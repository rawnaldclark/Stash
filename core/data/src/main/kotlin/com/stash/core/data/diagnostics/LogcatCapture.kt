package com.stash.core.data.diagnostics

import android.content.Context
import android.os.Process
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Continuously tails THIS app's own logcat (no READ_LOGS needed — the log daemon
 * returns the caller's own UID lines) into a rotating file under
 * cacheDir/diagnostics, so a diagnostics bundle can include the lead-up to a
 * failure even after a crash/restart. Best-effort: if the OEM blocks the spawn,
 * it logs a warning and the bundle simply omits logs.
 */
@Singleton
open class LogcatCapture @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // Overridable in tests for a small rotation cap.
    internal open val maxBytes: Long = 512L * 1024

    private val dir: File get() = File(context.cacheDir, "diagnostics")
    private val active: File get() = File(dir, ACTIVE)
    private val rotated: File get() = File(dir, ROTATED)

    @Volatile private var started = false

    /** Open handle + its running size, both guarded by the [append] lock. */
    private var writer: BufferedWriter? = null
    private var bytesWritten = 0L

    /** Start the background tail. Idempotent. Call once at app init. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        Thread {
            runCatching {
                dir.mkdirs()
                val process = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-v", "threadtime", "--pid", Process.myPid().toString()),
                )
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        append(line)
                        line = reader.readLine()
                    }
                }
            }.onFailure { Log.w(TAG, "logcat capture unavailable; diagnostics will omit logs", it) }
        }.apply { isDaemon = true; name = "stash-logcat-capture"; start() }
    }

    /**
     * Append one line; rotate active->rotated when the active file passes
     * [maxBytes].
     *
     * Holds ONE writer open across lines. This used to be an `appendText`
     * per line, which is an open + write + close every time, on top of an
     * `mkdirs` and two `stat`s to check the rotation cap — around six
     * syscalls and a fresh file handle per log line, under the same lock the
     * reader thread needs. That is cheap when the app is quiet and awful
     * exactly when it is not: an error loop or GC storm is when the app logs
     * hardest, and it is the run this capture exists to record.
     *
     * Still flushed every line — dropping the tail of the log before a crash
     * would defeat the point — but a flush is one write, not a reopen.
     */
    @Synchronized
    internal fun append(line: String) {
        if (FRAME_SPAM.containsMatchIn(line)) return
        runCatching {
            if (bytesWritten >= maxBytes) rotate()
            val out = writer ?: openWriter().also { writer = it }
            out.write(line)
            out.write("\n")
            out.flush()
            // Counted rather than stat'ed. UTF-8 multi-byte lines make this
            // an under-estimate, so the cap is approximate by design — it is
            // a diagnostics ceiling, not a quota.
            bytesWritten += line.length + 1
        }
    }

    /** Close, roll active->rotated, and reopen empty. */
    private fun rotate() {
        runCatching { writer?.close() }
        writer = null
        rotated.delete()
        active.renameTo(rotated)
        bytesWritten = 0
    }

    private fun openWriter(): BufferedWriter {
        dir.mkdirs()
        bytesWritten = if (active.exists()) active.length() else 0L
        return BufferedWriter(FileWriter(active, /* append = */ true))
    }

    /** The capture files that exist right now, oldest first — the bundle zips them whole. */
    fun logFiles(): List<File> = listOf(rotated, active).filter { it.exists() }

    /** Return the last [maxLines] lines across the rotated + active files. */
    fun recentLogs(maxLines: Int = 1500): String = runCatching {
        val all = buildList {
            if (rotated.exists()) addAll(rotated.readLines())
            if (active.exists()) addAll(active.readLines())
        }
        all.takeLast(maxLines).joinToString("\n")
    }.getOrDefault("")

    companion object {
        private const val TAG = "LogcatCapture"

        /**
         * Samsung One UI logs `View: setRequestedFrameRate…` and `VRI[<Activity>]@<id>: Requested
         * frameRateCategory…` on every frame. In a Galaxy S26 Ultra bundle (2026-09-27) they were 96%
         * of the bytes, so the rotating 1 MB window held seconds of real logs. Neither tag is ours.
         */
        private val FRAME_SPAM = Regex("""^\S+ \S+\s+\d+\s+\d+ [VDIWEF] (View|VRI\[[^\]]*\]@\w+)\s*: """)
        private const val ACTIVE = "applog.txt"
        private const val ROTATED = "applog.1.txt"
    }
}
