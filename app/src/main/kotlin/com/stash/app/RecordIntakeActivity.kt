package com.stash.app

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.stash.data.download.files.LocalImportCoordinator
import com.stash.feature.record.imports.RecordImports
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Where a Spotify export opened with Stash (Files' "Open with", an email's attachment) or shared to it lands: an invisible screen
 * in the sending app's task. It hands the files to Your record's import, which copies them in at once, while this screen's grant
 * to read them holds; brings up Stash's own window, where the import sheet is showing; and goes once the copy is done (its grant
 * goes with it). Without it Stash opened inside Files' task: a second copy of the app on top of Files, whose grant ended when
 * that copy was closed.
 *
 * Its own task affinity (none), so it never sits in Stash's task, where bringing Stash's window forward would clear it, and its
 * grant with it, mid-copy. Only content URIs are taken. Audio in the same share goes to the library's import, as a share to
 * Stash's window does.
 */
@AndroidEntryPoint
class RecordIntakeActivity : ComponentActivity() {
    @Inject lateinit var recordImports: RecordImports

    @Inject lateinit var localImportCoordinator: LocalImportCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Touches go to the app underneath while the copy finishes (this window is see-through and lasts a moment)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        if (savedInstanceState != null) {
            // Recreated (the files were handed over the first time): nothing more to do
            finish()
            return
        }
        val (exports, rest) = RecordIntake.split(intent, contentResolver)
        if (rest.isNotEmpty()) localImportCoordinator.start(rest)
        val copy = if (exports.isNotEmpty()) recordImports.bringShared(exports) else null
        if (exports.isNotEmpty() || rest.isNotEmpty()) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
        if (copy == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            copy.join()
            finish()
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

/** A file intent's URIs, for Your record's intake: VIEW's data, SEND's stream, SEND_MULTIPLE's streams; content URIs only. */
internal object RecordIntake {
    /** The types the manifest offers Your record's import for: Spotify's zips (either name a phone gives them) and JSON. */
    private val EXPORT_TYPES = setOf("application/zip", "application/x-zip-compressed", "application/json")

    fun urisOf(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val all = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty().toList()
            else -> emptyList()
        }
        // A file: URI could name anything on the phone (/dev/zero, another app's files through a path trick): never read
        return all.filter { it.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true) }
    }

    /** The export files of a file intent, and the rest (audio among a share), by each file's own type or the intent's. */
    fun split(intent: Intent?, resolver: ContentResolver): Pair<List<Uri>, List<Uri>> {
        val all = urisOf(intent)
        val exports = all.filter { isExportType(runCatching { resolver.getType(it) }.getOrNull() ?: intent?.type) }
        return exports to (all - exports.toSet())
    }

    fun isExportType(type: String?): Boolean = type != null && type.substringBefore(';').trim().lowercase() in EXPORT_TYPES
}
