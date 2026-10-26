package com.stash.feature.record

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.stash.feature.record.bridge.RecordSaver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Where the record's posters and race videos go on the phone.
 *
 * - Save: Downloads, through MediaStore (Android 10 and up: no permission needed). Older Android would need storage permission
 *   for Downloads, so there the share sheet opens instead, where "Save to device" and every app are.
 * - Share: the system share sheet, the file handed over from `cacheDir/share/` through the app's FileProvider (the only
 *   folder it exposes for this), under its own name; files there older than [MAX_AGE_MS] are cleared on the next share.
 */
class RecordFiles @Inject constructor(@ApplicationContext private val context: Context) : RecordSaver {

    override suspend fun save(file: File, name: String, mime: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return share(file, name, mime)
        return withContext(Dispatchers.IO) { toDownloads(file, name, mime) }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun toDownloads(file: File, name: String, mime: String): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw IOException("no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            true
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            false
        } catch (e: SecurityException) {
            resolver.delete(uri, null, null)
            false
        }
    }

    override suspend fun share(file: File, name: String, mime: String): Boolean {
        val shared = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach { f -> if (f.isFile && f.name.startsWith(PREFIX) && now - f.lastModified() > MAX_AGE_MS) f.delete() }
            // Each share its own folder name-wise: a share sheet may still be reading an earlier file
            val target = File(dir, "$PREFIX$now-$name")
            if (!file.renameTo(target)) file.copyTo(target, overwrite = true)
            target
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shared)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, context.getString(R.string.record_share)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        withContext(Dispatchers.Main) { context.startActivity(chooser) }
        return true
    }

    private companion object {
        /** The cacheDir folder the app's FileProvider shares from (app/src/main/res/xml/file_paths.xml). */
        const val SHARE_DIR = "share"
        const val PREFIX = "record-"
        const val MAX_AGE_MS = 10 * 60_000L
    }
}
