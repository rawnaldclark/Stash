package com.stash.data.download.files

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.fileUsedByAnotherTrack
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.LibraryLayout
import com.stash.core.data.prefs.StoragePreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
 * music directory into a user-picked SAF folder, preserving the folder
 * structure selected by the user's [LibraryLayout] preference (#198/#104)
 * and updating `Track.filePath` in the DB to the resulting content URI.
 * Deletes the internal copy after each successful SAF write.
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
 *  - Only one whole-library `file_path` rewrite runs at a time: the move
 *    and the reorganize exclude each other through [LibraryRewriteGate],
 *    in either start order.
 *  - Cancellation mid-move leaves the DB consistent — each track is its own
 *    atomic unit, and a cancelled coroutine stops at track boundaries.
 *  - A document another track records is never replaced: a name clash
 *    gives the moved file a distinct name instead (see [createTarget]).
 *  - An internal file another track still records is never deleted (see
 *    [moveOne]).
 */
@Singleton
class MoveLibraryCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackDao: TrackDao,
    private val storagePreference: StoragePreference,
    private val rewriteGate: LibraryRewriteGate,
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
        trackDao.countDownloadedTracksWithPathPrefix(internalPrefix)

    /**
     * Kick off the move. No-op if already [Running]. Returns immediately;
     * observe [state] for progress.
     */
    fun start(targetTreeUri: Uri) {
        if (_state.value is MoveLibraryState.Running) return
        // Only one whole-library file_path rewrite at a time — see
        // LibraryRewriteGate. Released in the launch's finally, so a
        // cancelled or failed move hands it straight back.
        if (!rewriteGate.tryAcquire(GATE_OWNER)) {
            _state.value = MoveLibraryState.Error(
                "${rewriteGate.heldBy} is still running. Try again once it finishes.",
            )
            return
        }
        activeJob = scope.launch {
            try {
                runMove(targetTreeUri)
            } finally {
                rewriteGate.release(GATE_OWNER)
            }
        }
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

            // Freeze the layout for the whole move so a mid-flight pref
            // change can't produce a half-artist/album, half-playlist tree.
            val layout = runCatching { storagePreference.libraryLayout.first() }
                .getOrDefault(LibraryLayout.DEFAULT)

            // PLAYLIST-layout moves need each track's owning playlist name
            // for its destination directory. Resolve them all upfront — one
            // batched query instead of one per track, which was one Room
            // round-trip per moved file.
            // NOT runCatching-wrapped: on failure this pass must stop BEFORE
            // the first file moves. A swallowed error here would silently
            // file every track under its ARTIST_ALBUM fallback location —
            // misplacing the whole library in one pass (and each later pass
            // would see them misplaced again). It propagates to the catch
            // below as an Error state the user can retry. A track ABSENT
            // from the map is the normal no-playlist case, not an error.
            val playlistNamesByTrackId: Map<Long, String> =
                if (layout == LibraryLayout.PLAYLIST) {
                    trackDao.getFirstPlaylistNamesForTracks(tracks.map { it.id })
                } else {
                    emptyMap()
                }

            var moved = 0
            var failed = 0
            _state.value = MoveLibraryState.Running(current = 0, total = total)

            // Directory-lookup cache. DocumentFile.findFile() is O(children)
            // per SAF provider — on a moving target that fills up as we go,
            // the library-move becomes O(n²). Caching by cumulative relative
            // dir key ("<artistSlug>" / "<playlistSlug>" /
            // "<artistSlug>/<albumSlug>") collapses the N² to O(n+unique-dirs).
            val dirCache = HashMap<String, DocumentFile>(256)
            // One listing per directory for the whole move — see
            // [SafListingSession]; createTarget()'s findFile was a full
            // folder re-list per candidate name per track.
            val listing = SafListingSession()

            for ((index, track) in tracks.withIndex()) {
                _state.value = MoveLibraryState.Running(current = index, total = total)
                val ok = runCatching { moveOne(track, root, layout, playlistNamesByTrackId[track.id], dirCache, listing) }
                // runCatching swallows CancellationException too. Cancel works
                // by cancelling activeJob, which surfaced here as one failed
                // track: the loop copied the REST of the library (each copy
                // then orphaned when the cancelled DB write threw) and ended
                // by overwriting Idle with Done. Re-throw so the pass stops,
                // as ReorganizeLibraryCoordinator does.
                ok.exceptionOrNull()?.let { if (it is CancellationException) throw it }
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

    private suspend fun moveOne(
        track: TrackEntity,
        root: DocumentFile,
        layout: LibraryLayout,
        playlistName: String?,
        dirCache: MutableMap<String, DocumentFile>,
        listing: SafListingSession,
    ) {
        val path = track.filePath ?: return
        val localFile = File(path)
        if (!localFile.exists()) {
            error("Internal file missing: $path")
        }

        // Same resolver the download path uses, so the moved library has
        // exactly the structure new downloads will keep producing (#198/#104).
        // The playlist name is resolved upfront for the whole pass in
        // [runMove]; null here is the normal "no playlist owns this track"
        // case, which the resolver handles with the ARTIST_ALBUM fallback.
        val location = LibraryLayoutResolver.resolve(
            layout,
            artist = track.artist,
            album = track.album.takeIf { it.isNotBlank() },
            title = track.title,
            playlistName = playlistName,
        )

        var cursor = root
        var cumulativeKey = ""
        for (segment in location.segments) {
            cumulativeKey = if (cumulativeKey.isEmpty()) segment else "$cumulativeKey/$segment"
            cursor = dirCache.getOrPut(cumulativeKey) { cursor.findOrCreateDir(segment) }
        }

        val format = localFile.extension.ifBlank { "m4a" }
        val target = createTarget(track, cursor, location, format, listing, cumulativeKey)

        try {
            localFile.inputStream().use { input ->
                context.contentResolver.openOutputStream(target.uri)?.use { output ->
                    input.copyTo(output)
                } ?: error("Could not open SAF output stream for '${track.title}'")
            }
            // A cancel during the (blocking) copy: stop before the DB write,
            // which would throw anyway and leave the copy behind.
            currentCoroutineContext().ensureActive()
        } catch (e: Throwable) {
            // A half-written or abandoned document at the track's name would
            // be adopted as the download by the next reconciliation; the
            // internal file is still the real one.
            target.delete()
            // The pass's file-name cache still thinks the name is taken by
            // [target] — keep it true or the next track with the same base
            // name would skip the suffix and hit the deleted document.
            target.name?.let { listing.noteDeleted(cumulativeKey, it) }
            throw e
        }

        // DB write first so a crash before delete leaves a duplicate (harmless)
        // rather than a dangling DB pointer.
        trackDao.markAsDownloaded(track.id, target.uri.toString(), localFile.length())
        // A legacy library can have two rows on one internal file (an import
        // that took a downloaded song's name). The file stays while another row
        // records it: that row moves later in this pass to its own copy, and
        // the last row to leave deletes it. Repointing those rows at this copy
        // instead would keep them sharing one file, and a row whose move then
        // ran with its stale path would fail as "missing".
        if (!trackDao.fileUsedByAnotherTrack(path, track.id)) localFile.delete()
    }

    /**
     * Creates the document [track] is copied into, at the first free name:
     * `<base>.<ext>`, then with the album, then with the album and track id
     * added, as a download names a clash (see [FileOrganizer.commitDownload]).
     *
     * A document already at a name is replaced only when no other track
     * records it (a leftover of a move cut short). Two internal files can
     * resolve to ONE name (Single folder drops the album, so a studio and a
     * live "Intro" collide), and the second track's move used to delete the
     * first one's document, whose internal copy was already gone: the first
     * track lost its only file, or on path-based providers silently played
     * the second's audio from the reused URI.
     */
    private suspend fun createTarget(
        track: TrackEntity,
        cursor: DocumentFile,
        location: LibraryLayoutResolver.ResolvedLocation,
        format: String,
        listing: SafListingSession,
        dirKey: String,
    ): DocumentFile {
        val album = FileOrganizerSlugs.slugify(track.album).ifEmpty { null }
        val suffixes = listOf(null, album, listOfNotNull(album, track.id.toString()).joinToString("-")).distinct()
        for (suffix in suffixes) {
            val filename = if (suffix == null) "${location.baseName}.$format" else "${location.baseName}-$suffix.$format"
            // Listing cache: the old findFile re-listed this folder once per
            // candidate name, per track — the O(n²) binder scans the move
            // shares with the reorganize (see SafListingSession).
            val existing = listing.find(dirKey, cursor, filename)
            if (existing != null) {
                if (trackDao.countOtherTracksWithFilePath(existing.uri.toString(), track.id) > 0) continue
                existing.delete()
                listing.noteDeleted(dirKey, filename)
            }
            val target = cursor.createFile(mimeTypeFor(format), filename)
                ?: error("Could not create SAF file for '${track.title}'")
            // SAFENAME GUARD: the move trusts its listing cache, but
            // downloads write to the same tree while it runs (the gate
            // excludes only the other bulk pass), so a name that was free
            // when the folder was listed can be taken by now; providers
            // silently auto-rename ("song (1).opus" in the AOSP provider).
            // A misfiled name breaks the on-disk layout contract, so the
            // document is dropped and this suffix is given up — the source
            // file is untouched and the next suffix / next run gets an
            // honest chance at a free name.
            if (target.name != null && target.name != filename) {
                target.delete()
                continue
            }
            listing.noteCreated(dirKey, filename, target)
            // Path-based providers hand a new document a deleted one's URI,
            // which a track whose file went missing may still record. Only
            // probed for a freshly created name — after a delete the reused
            // URI was already vetted by the check above.
            if (existing == null && trackDao.countOtherTracksWithFilePath(target.uri.toString(), track.id) > 0) {
                target.delete()
                listing.noteDeleted(dirKey, filename)
                continue
            }
            return target
        }
        error("No free name for '${track.title}': other tracks use every candidate")
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

    companion object {
        /** Name this pass claims [LibraryRewriteGate] under. */
        const val GATE_OWNER = "A library move"
        private const val TAG = "MoveLibraryCoord"
    }
}
