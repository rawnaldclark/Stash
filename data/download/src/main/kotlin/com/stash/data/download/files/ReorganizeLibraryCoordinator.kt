package com.stash.data.download.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.LibraryLayout
import com.stash.core.data.prefs.StoragePreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State for the one-shot "Reorganize library" operation (#198/#104).
 *
 * Observable via [ReorganizeLibraryCoordinator.state]. Stays in [Done] or
 * [Error] until the Settings screen explicitly [ReorganizeLibraryCoordinator.dismiss]es
 * it, which flips back to [Idle].
 */
sealed interface ReorganizeLibraryState {
    data object Idle : ReorganizeLibraryState
    data class Running(val current: Int, val total: Int) : ReorganizeLibraryState
    data class Done(val moved: Int, val skipped: Int, val failed: Int) : ReorganizeLibraryState
    data class Error(val message: String) : ReorganizeLibraryState
}

/**
 * Relocates every downloaded track INTO the user's chosen folder structure
 * ([LibraryLayout]) without changing its storage destination — internal
 * tracks stay internal, SAF-tree tracks stay inside the granted tree. The
 * destination switch itself remains the separate "Move library to SD" flow;
 * this pass is purely about the on-disk SHAPE (#198 per-playlist, #104 flat).
 *
 * Runs on a `@Singleton`-scoped [scope] so the reorganize survives ViewModel
 * death (the user can leave Settings and come back to progress), mirroring
 * [MoveLibraryCoordinator]. Work does NOT survive process death — it's a
 * manual, restartable action.
 *
 * Safety invariants (mirroring MoveLibraryCoordinator):
 *  - A track is skipped, never double-copied, when its stored location is
 *    already exactly what the current layout prescribes.
 *  - The DB `file_path` is healed only AFTER the new copy exists; the old
 *    file is deleted only AFTER the DB write succeeded. A crash between
 *    steps at worst leaves a duplicate file, never a dangling pointer.
 *  - Each track is an atomic unit; cancellation stops at track boundaries.
 *  - Concurrent invocations are collapsed (a second [start] while running
 *    is a no-op).
 */
@Singleton
class ReorganizeLibraryCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackDao: TrackDao,
    private val storagePreference: StoragePreference,
    private val fileOrganizer: FileOrganizer,
) {
    private val _state = MutableStateFlow<ReorganizeLibraryState>(ReorganizeLibraryState.Idle)
    val state: StateFlow<ReorganizeLibraryState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    /** Kick off the reorganize. No-op if already running. */
    fun start() {
        if (_state.value is ReorganizeLibraryState.Running) return
        activeJob = scope.launch { runPass() }
    }

    /** Cancel an in-progress pass. State reverts to Idle. */
    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        _state.value = ReorganizeLibraryState.Idle
    }

    /** Dismiss a terminal state, returning to Idle. */
    fun dismiss() {
        if (_state.value is ReorganizeLibraryState.Done || _state.value is ReorganizeLibraryState.Error) {
            _state.value = ReorganizeLibraryState.Idle
        }
    }

    /**
     * Cheap count of downloaded tracks whose stored location doesn't already
     * match the current layout — used by Settings to gate/label the button.
     * Internal paths are compared exactly; SAF URIs are decoded from their
     * document ids and compared structurally (unparseable ⇒ counted as
     * misplaced so the pass gets a chance to verify properly on disk).
     */
    suspend fun countMisplacedTracks(): Int {
        val plan = buildPlan() ?: return 0
        return plan.count { !it.alreadyInPlace }
    }

    private suspend fun buildPlan(): List<PlanEntry>? {
        val refs = try {
            trackDao.getDownloadedTrackRefs()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not load downloaded tracks", t)
            return null
        }
        if (refs.isEmpty()) return emptyList()
        val layout = fileOrganizer.currentLayout()
        val treeUri = if (refs.any { it.filePath.startsWith("content://") }) {
            runCatching { storagePreference.externalTreeUri.first() }.getOrNull()
        } else {
            null
        }
        val musicRoot = fileOrganizer.internalMusicRoot().absolutePath.trimEnd('/')

        return refs.mapNotNull { ref ->
            val ext = ref.filePath.substringAfterLast('.', "").ifBlank { "m4a" }
            val playlistName =
                if (layout == LibraryLayout.PLAYLIST) {
                    runCatching { trackDao.getFirstPlaylistNameForTrack(ref.id) }.getOrNull()
                } else {
                    null
                }
            val location = LibraryLayoutResolver.resolve(
                layout,
                artist = ref.artist,
                album = ref.album.takeIf { it.isNotBlank() },
                title = ref.title,
                playlistName = playlistName,
            )
            val targetName = "${location.baseName}.$ext"
            val isSaf = ref.filePath.startsWith("content://")

            val alreadyInPlace = if (isSaf) {
                treeUri?.let { safAlreadyInPlace(it, ref.filePath, location.dirKey, targetName) } ?: false
            } else {
                val expected = if (location.segments.isEmpty()) {
                    File(musicRoot, targetName)
                } else {
                    File(File(musicRoot, location.dirKey), targetName)
                }
                File(ref.filePath).absoluteFile == expected.absoluteFile
            }
            PlanEntry(ref.id, ref.filePath, isSaf, location, targetName, alreadyInPlace)
        }
    }

    private data class PlanEntry(
        val trackId: Long,
        val sourcePath: String,
        val sourceIsSaf: Boolean,
        val location: LibraryLayoutResolver.ResolvedLocation,
        val targetName: String,
        val alreadyInPlace: Boolean,
    )

    private suspend fun runPass() {
        try {
            val plan = buildPlan()
            if (plan == null) {
                _state.value = ReorganizeLibraryState.Error("Couldn't read the library database.")
                return
            }
            val work = plan.filterNot { it.alreadyInPlace }
            if (work.isEmpty()) {
                _state.value = ReorganizeLibraryState.Done(0, plan.size, 0)
                return
            }

            // Resolve the SAF root once when any SAF track needs work.
            val safRoot = if (work.any { it.sourceIsSaf }) {
                val uri = runCatching { storagePreference.externalTreeUri.first() }.getOrNull()
                if (uri == null) null else DocumentFile.fromTreeUri(context, uri)
            } else {
                null
            }
            if (work.any { it.sourceIsSaf } && safRoot == null) {
                _state.value = ReorganizeLibraryState.Error(
                    "Couldn't open the external folder. Permission may have been revoked.",
                )
                return
            }

            val dirCache = HashMap<String, DocumentFile>(256)
            var moved = 0
            var failed = 0
            _state.value = ReorganizeLibraryState.Running(current = 0, total = work.size)

            for ((index, entry) in work.withIndex()) {
                _state.value = ReorganizeLibraryState.Running(current = index, total = work.size)
                val ok = runCatching {
                    if (entry.sourceIsSaf) {
                        moveWithinSaf(entry, safRoot!!, dirCache)
                    } else {
                        moveWithinInternal(entry)
                    }
                }
                if (ok.getOrDefault(false)) moved++ else {
                    failed++
                    Log.w(TAG, "Reorganize failed for track ${entry.trackId}", ok.exceptionOrNull())
                }
            }

            _state.value = ReorganizeLibraryState.Done(moved, plan.size - work.size, failed)
        } catch (t: CancellationException) {
            _state.value = ReorganizeLibraryState.Idle
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "Reorganize failed", t)
            _state.value = ReorganizeLibraryState.Error(t.message ?: "Unknown error")
        }
    }

    /** Internal → internal relocation via rename (copy+delete fallback). */
    private suspend fun moveWithinInternal(entry: PlanEntry): Boolean {
        val musicRoot = fileOrganizer.internalMusicRoot()
        val source = File(entry.sourcePath)
        if (!source.exists()) error("Internal file missing: ${entry.sourcePath}")

        val targetDir = if (entry.location.segments.isEmpty()) {
            musicRoot
        } else {
            File(musicRoot, entry.location.dirKey).also { it.mkdirs() }
        }
        val target = File(targetDir, entry.targetName)
        // Late re-check (races between plan and execution): nothing to do is
        // a successful no-op, not a failure.
        if (target.absoluteFile == source.absoluteFile) return true
        if (target.exists()) target.delete()

        val renamed = source.renameTo(target)
        if (!renamed) {
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        // DB first, then remove the leftover copy — same ordering guarantee as
        // the library move (crash ⇒ duplicate file, never dangling pointer).
        trackDao.healFilePath(entry.trackId, target.absolutePath)
        if (!renamed) source.delete()
        moveInternalSidecar(source, target)
        return true
    }

    /**
     * Carry the `.lrc` lyrics sidecar along with its audio (#198/#104).
     *
     * [com.stash.data.lyrics.sidecar.LyricsSidecarWriter] writes
     * `<basename>.lrc` NEXT TO the audio file, which is the contract external
     * players rely on. Moving the audio without the sidecar silently breaks
     * that pairing and strands the old file as litter, so the relocation is
     * part of moving a track, not a separate concern.
     *
     * Best-effort by design: a missing sidecar is the normal case (most
     * tracks have no lyrics) and a failed sidecar move must never fail the
     * track's move — the audio and its DB row are already consistent by the
     * time this runs.
     */
    private fun moveInternalSidecar(source: File, target: File) {
        val from = File(source.parentFile, source.nameWithoutExtension + LRC_EXTENSION)
        if (!from.exists()) return
        val to = File(target.parentFile, target.nameWithoutExtension + LRC_EXTENSION)
        if (from.absoluteFile == to.absoluteFile) return
        try {
            if (to.exists()) to.delete()
            if (!from.renameTo(to)) {
                from.inputStream().use { input ->
                    to.outputStream().use { output -> input.copyTo(output) }
                }
                from.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not move lyrics sidecar for ${target.name}", t)
        }
    }


    /** SAF → SAF relocation within the granted tree via copy + delete. */
    private suspend fun moveWithinSaf(
        entry: PlanEntry,
        root: DocumentFile,
        dirCache: MutableMap<String, DocumentFile>,
    ): Boolean {
        // sourcePath is the audio DOCUMENT uri healed in by a previous write
        // (createFile().uri), not the picked TREE uri. fromTreeUri would
        // rebuild the tree ROOT from it, so the copy+delete below would run
        // against the wrong document and every SAF track would fail to
        // reorganize. A document uri needs fromSingleUri.
        val source = DocumentFile.fromSingleUri(context, Uri.parse(entry.sourcePath))
            ?: error("Source document unreadable: ${entry.sourcePath}")

        var cursor = root
        var cumulativeKey = ""
        for (segment in entry.location.segments) {
            cumulativeKey = if (cumulativeKey.isEmpty()) segment else "$cumulativeKey/$segment"
            cursor = dirCache.getOrPut(cumulativeKey) {
                cursor.findFile(segment)?.takeIf { it.isDirectory }
                    ?: cursor.createDirectory(segment)
                    ?: error("Could not create SAF directory '$segment'")
            }
        }

        // Already exactly where the layout wants it → skip without touching
        // (protects against delete-then-recreate of the only copy). Counted
        // as a successful no-op.
        val existing = cursor.findFile(entry.targetName)
        if (existing != null && existing.uri == source.uri) return true

        existing?.delete()
        val format = entry.targetName.substringAfterLast('.', "m4a")
        val target = cursor.createFile(mimeTypeFor(format), entry.targetName)
            ?: error("Could not create SAF file '${entry.targetName}'")

        context.contentResolver.openInputStream(source.uri)?.use { input ->
            context.contentResolver.openOutputStream(target.uri)?.use { output ->
                input.copyTo(output)
            }
        } ?: error("Could not copy '${entry.targetName}' within the SAF tree")

        // DB write before deleting the source — a crash leaves a duplicate
        // document (harmless orphan), never a dead pointer.
        trackDao.healFilePath(entry.trackId, target.uri.toString())
        if (!source.delete()) {
            Log.w(TAG, "Old SAF document could not be deleted: ${entry.sourcePath}")
        }
        return true
    }

    /**
     * Structural in-place check for a SAF-stored file: decode the document id
     * (`<volume>:<path>`) and compare the volume-relative path against the
     * picked tree's base plus the layout-derived relative path. Returns null
     * when the provider's ids don't follow that convention (caller then
     * treats the track as needing verification during the actual pass).
     */
    private fun safAlreadyInPlace(
        treeUri: Uri,
        docUriString: String,
        dirKey: String,
        fileName: String,
    ): Boolean {
        return try {
            val docUri = Uri.parse(docUriString)
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val docDocId = DocumentsContract.getDocumentId(docUri)
            val baseRel = treeDocId.substringAfter(':', "")
            val docRel = docDocId.substringAfter(':', "")
            val expectedRel = if (dirKey.isEmpty()) fileName else "$dirKey/$fileName"
            val fullExpected = if (baseRel.isBlank()) expectedRel else "$baseRel/$expectedRel"
            docRel.equals(fullExpected, ignoreCase = true)
        } catch (t: Throwable) {
            Log.d(TAG, "SAF in-place check unparseable ($docUriString): ${t.message}")
            false
        }
    }

    private fun mimeTypeFor(format: String): String = when (format.lowercase()) {
        "m4a", "mp4", "aac" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        else -> "audio/*"
    }

    companion object {
        /** Sidecar extension written by the lyrics module, moved alongside audio. */
        private const val LRC_EXTENSION = ".lrc"
        private const val TAG = "ReorganizeCoord"
    }
}
