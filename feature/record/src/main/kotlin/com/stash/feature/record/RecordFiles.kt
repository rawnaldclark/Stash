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
import com.stash.feature.record.bridge.SaveOutcome
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** A file ready for the share sheet: the chooser to start, and the file behind it (cleared if it never opens). */
class ShareReady(val chooser: Intent, val file: File)

/**
 * Where the record's posters and race videos go on the phone.
 *
 * - Save: Downloads, through MediaStore (Android 10 and up: no permission needed). Android 9 and older would need storage
 *   permission for Downloads, which Stash doesn't ask for, so there the share sheet opens instead ("Save to device" and every
 *   app are in it), and nothing claims the file was saved ([SaveOutcome.SHARED]).
 * - Share: the system share sheet, the file handed over from `cacheDir/share/` through the app's FileProvider (the only
 *   folder it exposes for this), under its own name; files there older than [MAX_AGE_MS] are cleared on the next share.
 *
 * The share sheet itself isn't opened here: Android doesn't let an app in the background start it, and a race video can
 * finish while the listener is elsewhere. The file waits in [ready] for the record's screen, which opens it while it is
 * started (at once, or when Stash comes back) and then calls [shown].
 */
class RecordFiles @Inject constructor(@ApplicationContext private val context: Context) : RecordSaver {

    private val _ready = MutableStateFlow<ShareReady?>(null)

    /** A share waiting for the screen to open its sheet. */
    val ready: StateFlow<ShareReady?> = _ready.asStateFlow()

    /** The screen opened [share]'s sheet. */
    fun shown(share: ShareReady) {
        _ready.compareAndSet(share, null)
    }

    /** The screen is going for good: a share still waiting goes too. True when one was waiting. */
    fun dropWaiting(): Boolean {
        val waiting = _ready.getAndUpdate { null } ?: return false
        waiting.file.delete()
        return true
    }

    override suspend fun save(file: File, name: String, mime: String): SaveOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return if (share(file, name, mime)) SaveOutcome.SHARED else SaveOutcome.FAILED
        return withContext(Dispatchers.IO) { if (toDownloads(file, name, mime)) SaveOutcome.SAVED else SaveOutcome.FAILED }
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
        var done = false
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw IOException("no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            done = true
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        } finally {
            // Any failure (and a cancellation) leaves no half-written, pending row behind in Downloads
            if (!done) resolver.delete(uri, null, null)
        }
    }

    override suspend fun share(file: File, name: String, mime: String): Boolean {
        val shared = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach { f -> if (f.isFile && f.name.startsWith(PREFIX) && now - f.lastModified() > MAX_AGE_MS) f.delete() }
            // Each share its own file (the time in its name): a share sheet may still be reading an earlier one
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
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // A newer share replaces one still waiting (its file goes)
        _ready.value?.file?.takeIf { it != shared }?.delete()
        _ready.value = ShareReady(chooser, shared)
        return true
    }

    private companion object {
        /** The cacheDir folder the app's FileProvider shares from (app/src/main/res/xml/file_paths.xml). */
        const val SHARE_DIR = "share"
        const val PREFIX = "record-"
        const val MAX_AGE_MS = 10 * 60_000L
    }
}
