package com.stash.core.ui.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Shares an image Stash drew (a Recap card, later others) through the system share sheet.
 *
 * The PNG goes to `cacheDir/share/` (the only folder the app's FileProvider exposes for it:
 * `<cache-path name="share" path="share/"/>`) under a name with the time in it, so each share is
 * its own file: a share sheet or the app it handed the file to may still be reading an earlier
 * one. Files older than [MAX_AGE_MS] are deleted on the next share, so the folder stays small.
 * The recipient gets a content:// URI with a one-off read grant (on the intent and its ClipData,
 * which is what Android 10+ share sheets honour for previews).
 */
object ShareImage {

    /** The cacheDir subfolder, as declared in app/src/main/res/xml/file_paths.xml. */
    const val DIR = "share"

    /** Share images older than this are deleted on the next share (any recipient is done by then). */
    const val MAX_AGE_MS = 10 * 60_000L

    /** Longest file-name stem kept (the time and extension come on top). */
    private const val MAX_STEM = 48

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

    /**
     * A safe, readable file name with the time it was made: "stash-your-october-2026-top-songs-
     * 20261019-001205.png". Lower case, letters and digits joined by single hyphens; never empty.
     */
    fun fileName(stem: String, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val cleaned = stem.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(MAX_STEM)
            .trimEnd('-')
        val stamp = Instant.ofEpochMilli(nowMs).atZone(zone).format(STAMP)
        return (if (cleaned.isEmpty()) "stash" else cleaned) + "-" + stamp + ".png"
    }

    /** Deletes the files in [dir] last written more than [maxAgeMs] before [nowMs]. Returns how many. */
    fun clearOld(dir: File, nowMs: Long, maxAgeMs: Long = MAX_AGE_MS): Int {
        var removed = 0
        dir.listFiles()?.forEach { f ->
            if (f.isFile && nowMs - f.lastModified() > maxAgeMs && f.delete()) removed++
        }
        return removed
    }

    /** Writes [bitmap] as a PNG named from [stem] into [dir] (old images cleared first). */
    fun writePng(dir: File, bitmap: Bitmap, stem: String, nowMs: Long = System.currentTimeMillis()): File {
        if (!dir.exists()) dir.mkdirs()
        clearOld(dir, nowMs)
        val file = File(dir, fileName(stem, nowMs))
        file.outputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG encode failed" }
        }
        return file
    }

    /**
     * Writes [bitmap] and opens the share sheet for it. [stem] names the file; [title] is the
     * sheet's title. Runs the encoding off the main thread. The caller still owns [bitmap].
     */
    suspend fun share(context: Context, bitmap: Bitmap, stem: String, title: String = "Share") {
        val file = withContext(Dispatchers.IO) { writePng(File(context.cacheDir, DIR), bitmap, stem) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, title).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
