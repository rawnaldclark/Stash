package com.stash.feature.record.imports

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

/**
 * A file intent's URIs for Your record's intake (RecordIntakeActivity, and an export in a mixed share that reached Stash's
 * window): which are a Spotify export's and which go elsewhere, and the intent that hands the rest to Stash's window.
 */
object RecordIntake {
    /** On an intent the intake forwards: the files in it aren't an export's (Stash's window gives them to the library's import). */
    const val EXTRA_FORWARDED = "com.stash.feature.record.intake.FORWARDED"

    /** The types the manifest offers Your record's import for: Spotify's zips (either name a phone gives them) and JSON. */
    private val EXPORT_TYPES = setOf("application/zip", "application/x-zip-compressed", "application/json")

    /**
     * VIEW's data, SEND's stream, SEND_MULTIPLE's streams: content URIs of another app's only ([ImportIntake.takes]). All of them:
     * the export's files are capped by the import ([RecordImports.MAX_FILES]: more is refused whole), and the rest of a share
     * isn't the intake's to cut.
     */
    fun urisOf(intent: Intent?, ownPackage: String): List<Uri> {
        if (intent == null) return emptyList()
        val all = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty().toList()
            else -> emptyList()
        }
        return all.filter { ImportIntake.takes(it, ownPackage) }
    }

    fun isExportType(type: String?): Boolean = type != null && type.substringBefore(';').trim().lowercase() in EXPORT_TYPES

    /** An intent of [type] can hold an export: an export's own type, or a wildcard a share of mixed files is typed with. */
    fun mayHoldExport(type: String?): Boolean {
        val t = type?.substringBefore(';')?.trim()?.lowercase() ?: return true
        return isExportType(t) || t == "*/*" || t == "application/*"
    }

    /**
     * [uris] as the export's files and the rest, by each file's own type ([typeOf]: a call into the app that has it, so made off
     * the main thread) or else the intent's.
     */
    fun split(uris: List<Uri>, intentType: String?, typeOf: (Uri) -> String?): Pair<List<Uri>, List<Uri>> {
        val exports = uris.filter { isExportType(typeOf(it) ?: intentType) }
        return exports to (uris - exports.toSet())
    }

    /**
     * What brings Stash's own window forward from the intake ([launch]: the app's launch intent), and hands it [rest], the files
     * of the share that aren't an export's: as the share they came in ([original]'s type), with the grant to read them
     * (FLAG_GRANT_READ_URI_PERMISSION and a ClipData of them), so Stash's window holds that grant once the intake has gone.
     */
    fun forward(launch: Intent, original: Intent, rest: List<Uri>): Intent = Intent(launch).apply {
        removeCategory(Intent.CATEGORY_LAUNCHER)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (rest.isEmpty()) return@apply
        action = if (rest.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
        type = original.type ?: "*/*"
        if (rest.size == 1) putExtra(Intent.EXTRA_STREAM, rest[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(rest))
        clipData = ClipData.newRawUri(null, rest[0]).apply { rest.drop(1).forEach { addItem(ClipData.Item(it)) } }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putExtra(EXTRA_FORWARDED, true)
    }
}

/**
 * The intake screen's work, kept across a recreation of the screen (a configuration change it didn't declare): it takes the
 * intent once, looks the files' types up off the main thread, hands the export to the import (which copies it at once) and the
 * rest to Stash's window, and says when the copy is done. The screen finishes only then: its grant to read the files ends with it.
 */
@HiltViewModel
class RecordIntakeViewModel internal constructor(
    private val bringShared: (List<Uri>) -> Job?,
    private val typeOf: (Uri) -> String?,
    private val ownPackage: String,
    private val launchIntent: () -> Intent?,
    private val io: CoroutineContext,
) : ViewModel() {
    @Inject constructor(imports: RecordImports, @ApplicationContext context: Context) : this(
        bringShared = imports::bringShared,
        typeOf = { uri -> kotlin.runCatching { context.contentResolver.getType(uri) }.getOrNull() },
        ownPackage = context.packageName,
        launchIntent = { context.packageManager.getLaunchIntentForPackage(context.packageName) },
        io = Dispatchers.IO,
    )

    private val _starts = Channel<Intent>(Channel.BUFFERED)

    /** The intent to start (Stash's own window, with the files that aren't an export's), once. */
    val starts: Flow<Intent> = _starts.receiveAsFlow()

    private val _done = MutableStateFlow(false)

    /** The intake is done with the files (the export copied): the screen can go. */
    val done: StateFlow<Boolean> = _done.asStateFlow()

    private var taken = false

    /** Takes [intent] once: a recreated screen calls again, and nothing more happens. */
    fun take(intent: Intent) {
        if (taken) return
        taken = true
        viewModelScope.launch {
            val uris = RecordIntake.urisOf(intent, ownPackage)
            val (exports, rest) = withContext(io) { RecordIntake.split(uris, intent.type, typeOf) }
            val copy = if (exports.isNotEmpty()) bringShared(exports) else null
            if (exports.isNotEmpty() || rest.isNotEmpty()) launchIntent()?.let { _starts.send(RecordIntake.forward(it, intent, rest)) }
            copy?.join()
            _done.value = true
        }
    }
}
