package com.stash.feature.record.imports

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.IntentCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A file intent's URIs for Your record's intake (RecordIntakeActivity, and an export in a mixed share that reached Stash's
 * window): what kind of file each is, and the intent that hands the others to Stash's window.
 */
object RecordIntake {
    /** On an intent the intake forwards: the files in it aren't an export's (Stash's window gives them to the library's import). */
    const val EXTRA_FORWARDED = "com.stash.feature.record.intake.FORWARDED"

    /** What a file's type says: an export's, something else's, or nothing (then its name decides). */
    enum class Kind { EXPORT, OTHER, BY_NAME }

    /** An export's own types: Spotify's zips (either name a phone gives them) and JSON. */
    private val EXPORT_TYPES = setOf("application/zip", "application/x-zip-compressed", "application/json")

    /** Types that say nothing about the file (a FLAC and a Spotify zip both come as `octet-stream` from some apps). */
    private val GENERIC_TYPES = setOf("application/octet-stream", "binary/octet-stream", "*/*", "application/*", "application/x-zip")

    /**
     * VIEW's data, SEND's stream, SEND_MULTIPLE's streams: content URIs of another app's only ([ImportIntake.takes]). All of them:
     * the export's files are capped by the import ([RecordImports.MAX_FILES]: more is refused whole), and the rest of a share
     * isn't the intake's to cut.
     */
    fun urisOf(intent: Intent?, ownPackage: String): List<Uri> = streamsOf(intent).filter { ImportIntake.takes(it, ownPackage) }

    /** Every file a VIEW or a share names, any scheme (what the library's import is handed). */
    fun streamsOf(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty().toList()
            else -> emptyList()
        }
    }

    /**
     * What [type] says (the file's own, or else the intent's): audio is never an export; an export's types are; a type that says
     * nothing leaves it to the file's name; anything else (an image, a PDF) isn't one.
     */
    fun kindOf(type: String?): Kind {
        val t = type?.substringBefore(';')?.trim()?.lowercase()
        return when {
            t == null || t.isEmpty() || t in GENERIC_TYPES -> Kind.BY_NAME
            t.startsWith("audio/") -> Kind.OTHER
            t in EXPORT_TYPES -> Kind.EXPORT
            else -> Kind.OTHER
        }
    }

    /** A name an export's file has: a zip or a JSON file (the web's own test of a picked file). */
    fun isExportName(name: String?): Boolean {
        val n = name?.trim()?.lowercase() ?: return false
        return n.endsWith(".zip") || n.endsWith(".json")
    }

    /** An intent of [type] can hold an export: one whose type doesn't rule it out (audio can't be; a wildcard or none can). */
    fun mayHoldExport(type: String?): Boolean = kindOf(type) != Kind.OTHER

    /**
     * What brings Stash's own window forward from the intake ([launch]: the app's launch intent), and hands it [rest], the files
     * of the share that aren't an export's (each one this app may read: forwarding a file it may not throws): as the share they
     * came in ([original]'s type), with the grant to read them (FLAG_GRANT_READ_URI_PERMISSION and a ClipData of them), so Stash's
     * window holds that grant once the intake has gone.
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

    /** What the intake starts: [intent], or, if the system refuses it (a file's grant it can't pass on), [fallback], with no files. */
    class Start(val intent: Intent, val fallback: Intent)

    /** Starts [start] with [launch] (the screen's startActivity); a refusal falls back to Stash's window alone, never a crash. */
    fun startSafely(start: Start, launch: (Intent) -> Unit) {
        try {
            launch(start.intent)
        } catch (e: SecurityException) {
            Log.w(TAG, "the files couldn't be handed to Stash's window: ${e.javaClass.simpleName}")
            try {
                launch(start.fallback)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Stash's window couldn't be brought forward: ${e.javaClass.simpleName}")
            }
        }
    }

    private const val TAG = "YourRecord"
}

/**
 * The intake screen's work, kept across a recreation of the screen (the model outlives it) and across the app's death (what was
 * done is in [SavedStateHandle]): it sorts the files ([ShareClassifier]: types and names asked off the main thread, briefly), hands
 * the export to the import (which copies it at once) and the others to Stash's window, and says when the copy is done. The screen
 * finishes only then: its grant to read the files ends with it.
 *
 * Restored after the app died, it never forwards again (the others were handed over, and Stash's window isn't pulled over the
 * sender a second time), and brings the export again only if its copy hadn't finished.
 */
@HiltViewModel
class RecordIntakeViewModel internal constructor(
    private val bringShared: (List<Uri>) -> Job?,
    private val classifier: ShareClassifier,
    private val ownPackage: String,
    private val launchIntent: () -> Intent?,
    private val saved: SavedStateHandle,
) : ViewModel() {
    @Inject constructor(imports: RecordImports, @ApplicationContext context: Context, saved: SavedStateHandle) : this(
        bringShared = imports::bringShared,
        classifier = ShareClassifier.of(context),
        ownPackage = context.packageName,
        launchIntent = { context.packageManager.getLaunchIntentForPackage(context.packageName) },
        saved = saved,
    )

    private val _starts = Channel<RecordIntake.Start>(Channel.BUFFERED)

    /** What to start (Stash's own window, with the files that aren't an export's), at most once per intent, ever. */
    val starts: Flow<RecordIntake.Start> = _starts.receiveAsFlow()

    private val _done = MutableStateFlow(false)

    /** The intake is done with the files (the export copied): the screen can go. */
    val done: StateFlow<Boolean> = _done.asStateFlow()

    private var taken = false

    /** Takes [intent] once: a recreated screen calls again, and nothing more happens. */
    fun take(intent: Intent) {
        if (taken) return
        taken = true
        viewModelScope.launch {
            val split = classifier.split(RecordIntake.urisOf(intent, ownPackage), intent.type)
            val copy = if (split.exports.isNotEmpty() && saved.get<Boolean>(COPIED) != true) bringShared(split.exports) else null
            if (saved.get<Boolean>(FORWARDED) != true && (split.exports.isNotEmpty() || split.others.isNotEmpty())) {
                launchIntent()?.let { launch ->
                    _starts.send(RecordIntake.Start(RecordIntake.forward(launch, intent, split.others), RecordIntake.forward(launch, intent, emptyList())))
                }
                saved[FORWARDED] = true
            }
            if (copy != null) {
                copy.join()
                saved[COPIED] = true
            }
            _done.value = true
        }
    }

    internal companion object {
        /** Stash's window was brought forward, with the others handed over. */
        const val FORWARDED = "forwarded"

        /** The export's copy finished. */
        const val COPIED = "copied"
    }
}
