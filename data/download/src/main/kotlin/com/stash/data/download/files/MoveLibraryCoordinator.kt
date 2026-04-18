package com.stash.data.download.files

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State for the one-shot "Move library to external storage" operation.
 *
 * Observable via [MoveLibraryCoordinator.state]. Stays in [Done] or [Error]
 * until the Settings screen explicitly [MoveLibraryCoordinator.dismiss]es it,
 * which flips back to [Idle].
 */
sealed interface MoveLibraryState {
    data object Idle : MoveLibraryState
    data class Running(val current: Int, val total: Int) : MoveLibraryState
    data class Done(val moved: Int, val failed: Int) : MoveLibraryState
    data class Error(val message: String) : MoveLibraryState
}

/**
 * Moves every downloaded track currently stored under the app's internal
 * music directory into a user-picked SAF folder, preserving
 * `<artist>/<album>/<title>.ext` layout and updating `Track.filePath` in the
 * DB to the resulting content URI. Deletes the internal copy after each
 * successful SAF write.
 *
 * Runs on a `@Singleton`-scoped [scope] so the move survives VM death (the
 * user can leave the Settings screen and come back to see progress). Work
 * does NOT survive process death — this is a user-triggered manual action,
 * and restarting mid-library-move is acceptable.
 *
 * Safety invariants:
 *  - Internal file is deleted ONLY after the SAF copy + DB update succeed.
 *    If any step throws, the track stays internal — next invocation will
 *    retry it.
 *  - The DB write happens BEFORE the internal delete, so a crash between
 *    the two at worst leaves a duplicate file (harmless — DB points to SAF).
 *  - Concurrent invocations are collapsed: a second [start] while [Running]
 *    is a no-op.
 *  - Cancellation mid-move leaves the DB consistent — each track is its own
 *    atomic unit, and a cancelled coroutine stops at track boundaries.
 */
@Singleton
class MoveLibraryCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackDao: TrackDao,
) {
    private val _state = MutableStateFlow<MoveLibraryState>(MoveLibraryState.Idle)
    val state: StateFlow<MoveLibraryState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    /**
     * Absolute path prefix shared by every internally-stored track. Matches
     * what [FileOrganizer.getTrackDir] produces so the DB `LIKE` filter in
     * [TrackDao.getDownloadedTracksWithPathPrefix] catches them all.
     */
    private val internalPrefix: String
        get() = File(context.filesDir, "music").absolutePath

    /**
     * Count of internal-path tracks eligible for migration. The Settings
     * UI calls this to gate the "Move library" button so it's hidden when
     * there's nothing to move.
     */
    suspend fun countMovableTracks(): Int =
        trackDao.getDownloadedTracksWithPathPrefix(internalPrefix).size

    /**
     * Kick off the move. No-op if already [Running]. Returns immediately;
     * observe [state] for progress.
     */
    fun start(targetTreeUri: Uri) {
        if (_state.value is MoveLibraryState.Running) return
        activeJob = scope.launch { runMove(targetTreeUri) }
    }

    /** Cancel an in-progress move. Flips state back to [Idle]. */
    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        _state.value = MoveLibraryState.Idle
    }

    /** Dismiss a terminal state ([Done] / [Error]), returning to [Idle]. */
    fun dismiss() {
        if (_state.value is MoveLibraryState.Done || _state.value is MoveLibraryState.Error) {
            _state.value = MoveLibraryState.Idle
        }
    }

    private suspend fun runMove(targetTreeUri: Uri) {
        try {
            val tracks = trackDao.getDownloadedTracksWithPathPrefix(internalPrefix)
            val root = DocumentFile.fromTreeUri(context, targetTreeUri) ?: run {
                _state.value = MoveLibraryState.Error(
                    "Couldn't open the destination folder. Permission may have been revoked.",
                )
                return
            }

            val total = tracks.size
            if (total == 0) {
                _state.value = MoveLibraryState.Done(moved = 0, failed = 0)
                return
            }

            var moved = 0
            var failed = 0
            _state.value = MoveLibraryState.Running(current = 0, total = total)

            for ((index, track) in tracks.withIndex()) {
                _state.value = MoveLibraryState.Running(current = index, total = total)
                val ok = runCatching { moveOne(track, root) }
                if (ok.isSuccess) {
                    moved++
                } else {
                    failed++
                    Log.w(TAG, "Move failed for '${track.title}'", ok.exceptionOrNull())
                }
            }

            _state.value = MoveLibraryState.Done(moved = moved, failed = failed)
        } catch (t: CancellationException) {
            _state.value = MoveLibraryState.Idle
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "Library move failed", t)
            _state.value = MoveLibraryState.Error(t.message ?: "Unknown error")
        }
    }

    private suspend fun moveOne(track: TrackEntity, root: DocumentFile) {
        val path = track.filePath ?: return
        val localFile = File(path)
        if (!localFile.exists()) {
            error("Internal file missing: $path")
        }

        val artistSlug = slugify(track.artist)
        val albumSlug = if (track.album.isNotBlank()) slugify(track.album) else "singles"
        val titleSlug = slugify(track.title)
        val format = localFile.extension.ifBlank { "m4a" }
        val filename = "$titleSlug.$format"

        val artistDir = root.findOrCreateDir(artistSlug)
        val albumDir = artistDir.findOrCreateDir(albumSlug)
        albumDir.findFile(filename)?.delete()
        val target = albumDir.createFile(mimeTypeFor(format), filename)
            ?: error("Could not create SAF file for '${track.title}'")

        localFile.inputStream().use { input ->
            context.contentResolver.openOutputStream(target.uri)?.use { output ->
                input.copyTo(output)
            } ?: error("Could not open SAF output stream for '${track.title}'")
        }

        // DB write first so a crash before delete leaves a duplicate (harmless)
        // rather than a dangling DB pointer.
        trackDao.markAsDownloaded(track.id, target.uri.toString(), localFile.length())
        localFile.delete()
    }

    private fun DocumentFile.findOrCreateDir(name: String): DocumentFile =
        findFile(name)?.takeIf { it.isDirectory }
            ?: createDirectory(name)
            ?: error("Could not create SAF directory '$name'")

    private fun mimeTypeFor(format: String): String = when (format.lowercase()) {
        "m4a", "mp4", "aac" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        else -> "audio/*"
    }

    private fun slugify(input: String): String =
        input.lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .trim('-')
            .take(60)

    companion object {
        private const val TAG = "MoveLibraryCoord"
    }
}
