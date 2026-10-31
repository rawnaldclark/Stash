package com.stash.feature.record.imports

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.OpenableColumns
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext

/** A share's files sorted: the Spotify export's, and the others; both only files this app can read. */
data class ShareSplit(val exports: List<Uri>, val others: List<Uri>)

/**
 * Which files of a share are a Spotify export's, one rule for the intake screen and for Stash's window: a file's own type, and
 * for a type that says nothing (none, `application/octet-stream`, a wildcard, `application/x-zip`) its name's extension (a zip
 * from Gmail or a cloud drive often comes as `octet-stream`; so does a FLAC, which must keep going to the library's import).
 *
 * Every question goes to the app that has the file (its type, its name) or to the system (may this app read it), so each is
 * asked off the main thread and waited for at most [timeoutMs]: a provider that never answers costs that long, and its file is
 * taken as not an export (it goes where a share always went). A file this app can't read is in neither list.
 */
class ShareClassifier internal constructor(
    private val typeOf: (Uri) -> String?,
    private val nameOf: (Uri) -> String?,
    private val canRead: (Uri) -> Boolean,
    io: CoroutineContext,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    /** Where the questions are asked: one that never returns is left there (a supervisor: it fails nobody else). */
    private val calls = CoroutineScope(SupervisorJob() + io)

    suspend fun split(uris: List<Uri>, intentType: String?): ShareSplit = coroutineScope {
        val sorted = uris.map { uri -> async { uri to sort(uri, intentType) } }.awaitAll()
        ShareSplit(
            exports = sorted.filter { it.second == Sorted.EXPORT }.map { it.first },
            others = sorted.filter { it.second == Sorted.OTHER }.map { it.first },
        )
    }

    private enum class Sorted { EXPORT, OTHER, UNREADABLE }

    private suspend fun sort(uri: Uri, intentType: String?): Sorted {
        if (ask { canRead(uri) }?.getOrNull() != true) return Sorted.UNREADABLE
        val type = ask { typeOf(uri) } ?: return Sorted.OTHER
        return when (RecordIntake.kindOf(type.getOrNull() ?: intentType)) {
            RecordIntake.Kind.EXPORT -> Sorted.EXPORT
            RecordIntake.Kind.OTHER -> Sorted.OTHER
            RecordIntake.Kind.BY_NAME -> {
                val name = ask { nameOf(uri) } ?: return Sorted.OTHER
                if (RecordIntake.isExportName(name.getOrNull() ?: uri.lastPathSegment)) Sorted.EXPORT else Sorted.OTHER
            }
        }
    }

    /** [block] off the main thread, its answer (or its failure); null when it didn't answer within [timeoutMs]. */
    private suspend fun <T> ask(block: () -> T): Result<T>? {
        val call = calls.async { runCatching(block) }
        return withTimeoutOrNull(timeoutMs) { call.await() } ?: run {
            call.cancel()
            null
        }
    }

    companion object {
        /** How long a provider's answer is waited for. */
        const val TIMEOUT_MS = 2_000L

        /** The classifier for this app: types and names from the files' apps, reading rights from the system. */
        fun of(context: Context): ShareClassifier {
            val resolver = context.contentResolver
            return ShareClassifier(
                typeOf = { resolver.getType(it) },
                nameOf = { uri ->
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
                    }
                },
                canRead = { canRead(context, it) },
                io = Dispatchers.IO,
            )
        }

        /** This app holds the right to read [uri] (a grant, or the provider's own permission): what forwarding it needs. */
        fun canRead(context: Context, uri: Uri): Boolean =
            context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
    }
}

/**
 * A share that reached Stash's window and may hold a Spotify export (a share of mixed types matches the audio filters too),
 * routed in the app's own scope rather than the window's: a recreation of the window halfway (a rotation, dark mode, the UI
 * scale) can't drop it, and the window's grant to the files outlives a recreation. The export goes to Your record's import; the
 * rest, or the whole share when nothing in it is an export, to the library's import ([library], called on the main thread, as
 * the window always called it), every file of it as before.
 */
@Singleton
class RecordShareRouter internal constructor(
    private val classifier: ShareClassifier,
    private val bringShared: (List<Uri>) -> Job?,
    private val scope: CoroutineScope,
    private val main: CoroutineContext,
) {
    @Inject constructor(imports: RecordImports, @ApplicationContext context: Context) : this(
        classifier = ShareClassifier.of(context),
        bringShared = imports::bringShared,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        main = Dispatchers.Main,
    )

    /**
     * Routes a share typed [intentType]: [streams], every file of it as the library's import takes them; [candidates], those Your
     * record's intake may take (content URIs of other apps). [library] must hold no screen: it runs after the screen may be gone.
     */
    fun route(intentType: String?, streams: List<Uri>, candidates: List<Uri>, library: (List<Uri>) -> Unit): Job = scope.launch {
        val exports = classifier.split(candidates, intentType).exports
        withContext(main) {
            if (exports.isEmpty()) {
                library(streams)
                return@withContext
            }
            bringShared(exports)
            val rest = streams - exports.toSet()
            if (rest.isNotEmpty()) library(rest)
        }
        Log.i(TAG, "a share routed: ${exports.size} to the import, ${streams.size - exports.size} to the library")
    }

    private companion object {
        const val TAG = "YourRecord"
    }
}
