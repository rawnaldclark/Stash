package com.stash.data.download.files

import android.util.Log
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.audio.AudioMetadata
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.model.YouTubeVideoId
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.DownloadResult
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.prefs.toYtDlpArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * How one wrong-match swap ended, so the screen that asked for it can say so.
 * A failed swap used to put the row back with no word at all (#531). Every
 * outcome except [Swapped] leaves the track's audio, identity and flag as
 * they were, so its row stays in Failed Matches.
 */
sealed interface SwapOutcome {
    val trackId: Long
    val newVideoId: String

    /** The track's title, for the message. */
    val title: String

    /** The replacement is on disk and the track points at it. */
    data class Swapped(
        override val trackId: Long,
        override val newVideoId: String,
        override val title: String,
    ) : SwapOutcome

    /**
     * Another track has this video (`tracks.youtube_id` is UNIQUE). Nothing
     * was written, and this video can't be swapped in.
     */
    data class AlreadyLinked(
        override val trackId: Long,
        override val newVideoId: String,
        override val title: String,
        val ownerArtist: String,
        val ownerTitle: String,
        val ownerAlbum: String,
    ) : SwapOutcome

    /**
     * The replacement didn't download (or downloaded as junk). Nothing
     * changed; trying again may well work.
     */
    data class DownloadFailed(
        override val trackId: Long,
        override val newVideoId: String,
        override val title: String,
    ) : SwapOutcome

    /**
     * The replacement downloaded but couldn't be saved or recorded: storage
     * full, the music folder gone, a database error. Nothing changed, and
     * retrying only helps once that is sorted out.
     */
    data class SaveFailed(
        override val trackId: Long,
        override val newVideoId: String,
        override val title: String,
    ) : SwapOutcome

    /** The track or the replacement video is on the blocklist. Nothing was written. */
    data class Blocked(
        override val trackId: Long,
        override val newVideoId: String,
        override val title: String,
    ) : SwapOutcome
}

/**
 * Runs wrong-match swap downloads on an application-lifetime scope,
 * independent of any ViewModel.
 *
 * The Failed Matches UI lets users pick a replacement YouTube video for
 * a track they've flagged as wrong. Until Phase 5, that download lived
 * on `FailedMatchesViewModel`'s `viewModelScope` — which gets cancelled
 * as soon as the user navigates away. In practice that meant a user
 * could approve a swap, hit back before the ~10-second download
 * finished, and end up with the DB pointing at a deleted file while
 * the new audio was never persisted.
 *
 * This coordinator owns its own `CoroutineScope(SupervisorJob() +
 * Dispatchers.IO)` — same pattern [LocalImportCoordinator] already uses
 * for long-running imports. The swap survives screen navigation; the
 * only thing that can abort it mid-flight is process death, which is
 * rare during the few seconds between "user taps approve" and "download
 * completes."
 *
 * Flow (fire-and-forget from the caller's POV; how it ended arrives on
 * [outcomes]):
 *   1. Download the new videoId via [DownloadExecutor] using the user's
 *      active quality tier.
 *   2. Reject a junk download (a failed yt-dlp run's tiny error body) while
 *      it is still a temp file.
 *   3. Re-read the track, whose file may have moved during the download, and
 *      stop if another track has taken the video meanwhile, before any file
 *      is touched.
 *   4. Tag the file with Stash's own tags and cover, as a sync download is.
 *   5. Name the file: the canonical name under the track's artist/album,
 *      unless another track already uses the file there (see [pickName]).
 *   6. If the save would land on the old file's own path, move the old file
 *      aside first (the save deletes what is there before writing), and write
 *      that down in [SwapJournal].
 *   7. Save through [FileOrganizer] and record everything in one guarded
 *      write ([TrackDao.completeSwap]): the video id, the file, its format,
 *      quality and length, the cleared flag and the user's pick. Nothing about
 *      the track changes before that write.
 *   8. Delete the old file, or its set-aside copy, unless another track
 *      still uses it or it is the file just written.
 *
 * A failure, or the app dying mid-swap, leaves the track as it was: still
 * flagged, on its old video, with its old audio. A set-aside file is put back
 * right away, or at the next start by [recoverInterruptedSwaps].
 */
@Singleton
class SwapCoordinator @Inject constructor(
    private val downloadExecutor: DownloadExecutor,
    private val fileOrganizer: FileOrganizer,
    private val qualityPrefs: QualityPreferencesManager,
    private val trackDao: TrackDao,
    private val blocklistGuard: com.stash.core.data.blocklist.BlocklistGuard,
    private val localFileOps: com.stash.core.data.files.LocalFileOps,
    private val trackIdentityEvents: TrackIdentityEvents,
    private val audioExtractor: AudioDurationExtractor,
    private val metadataEmbedder: MetadataEmbedder,
    private val albumArtCache: AlbumArtCache,
    private val journal: SwapJournal,
) {
    companion object {
        private const val TAG = "SwapCoordinator"

        /**
         * A file length this far from the row's means a different cut; the
         * file wins, as in [com.stash.core.data.sync.workers.TrackDownloadWorker].
         */
        private const val DURATION_DRIFT_TOLERANCE = 0.10
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Suspending emit, never dropping (same reasoning as
     * [TrackIdentityEvents]): an outcome is the only way the user learns a
     * swap failed. With no screen collecting, nothing is buffered; the row,
     * still flagged, already shows the swap didn't happen.
     */
    private val _outcomes = MutableSharedFlow<SwapOutcome>(extraBufferCapacity = 16)

    /** How each swap ended. Collected by the Failed Matches screen. */
    val outcomes: SharedFlow<SwapOutcome> = _outcomes.asSharedFlow()

    private val _running = MutableStateFlow<Set<Long>>(emptySet())

    /**
     * Tracks whose swap is running now: the screen shows them as "Swapping…",
     * a re-created screen included. One swap per track (see [swap]).
     */
    val running: StateFlow<Set<Long>> = _running.asStateFlow()

    /** How [saveReplacement] ended. */
    private sealed interface Save {
        data class Done(val path: String) : Save

        /** The final write found the video taken by another track (or the track gone). */
        data object VideoTaken : Save

        data object Failed : Save
    }

    /**
     * Fire-and-forget swap. Returns immediately; the download continues
     * on this coordinator's scope until completion regardless of what
     * happens to the caller's lifecycle. The result arrives on [outcomes].
     * The track's artist, title, album and current file are read from the
     * database, not passed in, so a stale screen can't steer where the file
     * goes or which file is replaced.
     *
     * Returns false, starting nothing, when a swap of [trackId] is already
     * running. The screen's own guard goes with the screen (approve, leave,
     * come back, approve again), and two swaps at once would interleave the
     * set-aside, save and undo, and could leave the track on a missing file.
     *
     * @param trackId    Primary key of the track to update.
     * @param newVideoId YouTube video ID of the approved candidate.
     */
    fun swap(trackId: Long, newVideoId: String): Boolean {
        if (!tryStart(trackId)) return false
        scope.launch {
            try {
                performSwap(trackId, newVideoId)
            } finally {
                _running.update { it - trackId }
            }
        }
        return true
    }

    /** Adds [trackId] to [running] unless it is there already; true when added. */
    private fun tryStart(trackId: Long): Boolean {
        while (true) {
            val current = _running.value
            if (trackId in current) return false
            if (_running.compareAndSet(current, current + trackId)) return true
        }
    }

    /**
     * The actual swap body, exposed as a suspend function so it can be
     * unit-tested directly without racing the fire-and-forget [scope].
     * Emits exactly one [SwapOutcome].
     */
    internal suspend fun performSwap(trackId: Long, newVideoId: String) {
        val outcome = try {
            runSwap(trackId, newVideoId)
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            Log.w(TAG, "swap: unexpected error for videoId=$newVideoId", e)
            SwapOutcome.DownloadFailed(trackId, newVideoId, title = "")
        }
        // Done before the report, so the screen no longer shows "Swapping…"
        // when the message arrives.
        _running.update { it - trackId }
        _outcomes.emit(outcome)
    }

    /**
     * Repairs swaps cut short by the app being killed between moving the old
     * file aside and finishing (#531 review). Runs once at app start.
     *
     * A swap finished when the track has its video: the final write sets it
     * with the new file. (The flag can't tell: the user may unflag a row mid-
     * swap. Entries from before the video was written down fall back to it.)
     * A finished swap's set-aside copy is deleted, never when it is the very
     * file the track points at (a SAF provider with stable document ids keeps
     * the URI through the rename). An unfinished swap's old file goes back,
     * but only while the track still expects it there and no other track uses
     * that path; otherwise the copy stays on disk and is logged.
     */
    suspend fun recoverInterruptedSwaps() {
        for (entry in journal.pending()) {
            if (entry.trackId in _running.value) continue // a live swap owns its entry
            try {
                recover(entry)
            } catch (e: Exception) {
                rethrowIfCancelled(e)
                Log.w(TAG, "couldn't recover the interrupted swap of trackId=${entry.trackId}", e)
            }
        }
    }

    private suspend fun recover(entry: SwapJournal.Entry) {
        val trackId = entry.trackId
        val backup = entry.backupPath
        if (!localFileOps.exists(backup)) {
            // Cut short before the move, or already handled.
            journal.clear(trackId)
            return
        }
        val track = trackDao.getById(trackId)
        val finished = when {
            track == null -> true // the track is gone; its old file is of no use
            entry.newVideoId != null -> track.youtubeId == entry.newVideoId
            else -> !track.matchFlagged
        }
        if (finished) {
            if (localFileOps.isSameFile(backup, track?.filePath)) {
                Log.w(TAG, "recovery: $backup is trackId=$trackId's current file; keeping it")
                journal.clear(trackId)
            } else if (localFileOps.delete(backup)) {
                journal.clear(trackId)
                Log.i(TAG, "recovery: removed the set-aside copy of trackId=$trackId")
            } else {
                Log.w(TAG, "recovery: couldn't delete $backup; trying again next start")
            }
            return
        }
        val original = entry.originalPath
        val stillExpected = localFileOps.isSameFile(track?.filePath, original) &&
            trackDao.countOtherTracksWithFilePath(original, trackId) == 0
        if (!stillExpected) {
            Log.w(TAG, "recovery: trackId=$trackId no longer expects $original; keeping its old audio at $backup")
            journal.clear(trackId)
            return
        }
        if (localFileOps.restoreSetAside(backup, original)) {
            journal.clear(trackId)
            Log.i(TAG, "recovery: put trackId=$trackId's old file back")
        } else {
            Log.w(TAG, "recovery: couldn't put $original back from $backup; trying again next start")
        }
    }

    /**
     * Rethrows [e] only when this coroutine really is cancelled. A
     * CancellationException can also come out of a callee (a timeout); then
     * the swap must still be undone and reported, not silently dropped.
     */
    private suspend fun rethrowIfCancelled(e: Exception) {
        if (e is CancellationException && !currentCoroutineContext().isActive) throw e
    }

    private suspend fun runSwap(trackId: Long, newVideoId: String): SwapOutcome {
        val track = trackDao.getById(trackId)
            ?: return SwapOutcome.DownloadFailed(trackId, newVideoId, title = "")
        val title = track.title

        // The replacement id names the temp file and the yt-dlp URL below and is
        // written to the track. It came from a search answer, so one without the
        // YouTube video-id shape goes no further.
        val url = YouTubeVideoId.watchUrl(newVideoId) ?: run {
            Log.w(TAG, "swap: trackId=$trackId was offered an id that isn't a YouTube video id; not swapping")
            return SwapOutcome.DownloadFailed(trackId, newVideoId, title)
        }

        // An earlier swap of this track left its old file set aside (a
        // restore failed, or the app died). The next start's recovery deals
        // with it; a second set-aside now could bury the user's original audio.
        if (journal.has(trackId)) {
            Log.w(TAG, "swap: trackId=$trackId still has a set-aside file; not swapping until it is handled")
            return SwapOutcome.SaveFailed(trackId, newVideoId, title)
        }

        // v0.9.15: Reject blocklisted identities. A swap on a blocked
        // track would re-mark it downloaded and resurrect the file.
        if (blocklistGuard.isBlocked(
                artist = track.artist, title = title,
                spotifyUri = null, youtubeId = newVideoId,
            )) {
            Log.d(TAG, "Refused swap of blocked: ${track.artist} - $title")
            return SwapOutcome.Blocked(trackId, newVideoId, title)
        }

        val qualityArgs = qualityPrefs.qualityTier.first().toYtDlpArgs()

        // #36: download + commit the replacement BEFORE touching the old
        // file. The previous order deleted the user's existing audio up
        // front, so a failed download left them with nothing.
        val result = downloadExecutor.download(
            url = url,
            outputDir = fileOrganizer.getTempDir(),
            // Per track: two tracks swapping to the same video at once
            // must not share (and delete) one temp file.
            filename = "swap_${trackId}_$newVideoId",
            qualityArgs = qualityArgs,
        )
        if (result !is DownloadResult.Success) {
            Log.w(TAG, "swap: download failed for videoId=$newVideoId: $result")
            return SwapOutcome.DownloadFailed(trackId, newVideoId, title)
        }
        val tempFile = result.file

        // A "successful" yt-dlp run can still produce a tiny error body.
        // Check it while it's a temp file: committed under the track's album
        // it usually replaces the old file in place, and rejecting it then
        // would delete the user's audio. acceptDownloadOrDelete deletes it.
        if (!localFileOps.acceptDownloadOrDelete(tempFile.absolutePath)) {
            Log.w(TAG, "swap: discarded too-small download for trackId=$trackId: ${tempFile.name}")
            return SwapOutcome.DownloadFailed(trackId, newVideoId, title)
        }

        // Re-read: a reorganize or reconciliation can move the file during
        // the download, and the file to replace is the one the row points at
        // now, not when the user approved.
        val before = trackDao.getById(trackId)
        if (before == null) {
            Log.w(TAG, "swap: trackId=$trackId was deleted during the download; dropping it")
            deleteTempFile(tempFile)
            return SwapOutcome.DownloadFailed(trackId, newVideoId, title)
        }

        // The approve-time ownership check ran before a download that takes
        // seconds. Check again before touching any file; the final write
        // checks once more, atomically.
        alreadyLinked(trackId, newVideoId, title)?.let { taken ->
            deleteTempFile(tempFile)
            return taken
        }

        val tagged = embedTags(tempFile, before)
        // Read off the temp copy: a plain path even when the library is a
        // SAF tree, and the same bytes the commit writes.
        val meta = readAudioMetadata(tempFile)
        return when (val saved = saveReplacement(before, newVideoId, tempFile, meta, tagged)) {
            is Save.Done -> {
                // The cached StreamUrl was resolved against the OLD youtubeId —
                // without evicting it, buildMediaItemForTrack keeps serving the
                // stale (possibly-expired, definitely wrong-song) URL after a swap.
                trackIdentityEvents.emitIdentityChanged(trackId)
                Log.i(TAG, "swap: completed trackId=$trackId → videoId=$newVideoId path=${saved.path}")
                SwapOutcome.Swapped(trackId, newVideoId, title)
            }
            Save.VideoTaken ->
                alreadyLinked(trackId, newVideoId, title) ?: SwapOutcome.SaveFailed(trackId, newVideoId, title)
            Save.Failed -> SwapOutcome.SaveFailed(trackId, newVideoId, title)
        }
    }

    /** [SwapOutcome.AlreadyLinked] when another track has [videoId]; null when it is free. */
    private suspend fun alreadyLinked(trackId: Long, videoId: String, title: String): SwapOutcome.AlreadyLinked? {
        val owner = trackDao.findByYoutubeId(videoId)?.takeIf { it.id != trackId } ?: return null
        Log.w(TAG, "swap: videoId=$videoId is linked to trackId=${owner.id}; nothing written for trackId=$trackId")
        return SwapOutcome.AlreadyLinked(
            trackId = trackId,
            newVideoId = videoId,
            title = title,
            ownerArtist = owner.artist,
            ownerTitle = owner.title,
            ownerAlbum = owner.album,
        )
    }

    /**
     * Saves [tempFile] as [track]'s file and records the swap in one guarded
     * write. On any failure the track and its old file are left as they were.
     */
    private suspend fun saveReplacement(
        track: TrackEntity,
        newVideoId: String,
        tempFile: File,
        meta: AudioMetadata?,
        tagged: Boolean,
    ): Save {
        val trackId = track.id
        // Same path derivation as a sync download, album included. album =
        // null filed every swap under <artist>/singles/<title>, so swapping
        // two same-titled tracks wrote both into one file (#531).
        val album = track.album.takeIf { it.isNotBlank() }
        val extension = tempFile.extension
        val oldPath = track.filePath
        var oldShared = false
        var destination: String? = null
        var destinationExisted = false
        var backup: String? = null
        var committedPath: String? = null
        var result: Save = Save.Failed
        try {
            oldShared = oldPath != null && trackDao.countOtherTracksWithFilePath(oldPath, trackId) > 0
            val (nameSuffix, planned) = pickName(track, album, extension)
            destination = planned
            // A SAF destination is only planned when the folder listing found
            // it; asking SAF exists() again can wrongly say no.
            destinationExisted = planned != null &&
                (planned.startsWith("content://") || localFileOps.exists(planned))

            // Saving onto the old file's own path deletes it before writing
            // (File.copyTo overwrite; the SAF write deletes the document
            // first), so a failed save would leave the row on a missing or
            // half-written file. Move it aside first, written down so a swap
            // cut short by the app dying can be repaired at the next start:
            // before the move for a plain file, whose backup name is known
            // ahead; right after it on SAF, where the rename picks the name.
            if (oldPath != null && destinationExisted && localFileOps.isSameFile(planned, oldPath)) {
                val plannedBackup = localFileOps.backupPathFor(oldPath)
                if (plannedBackup != null) {
                    journal.record(SwapJournal.Entry(trackId, oldPath, plannedBackup, newVideoId))
                }
                val movedTo = localFileOps.setAside(oldPath, plannedBackup)
                if (movedTo == null) {
                    if (plannedBackup != null) journal.clear(trackId)
                    Log.w(TAG, "swap: couldn't move $oldPath aside; not saving over it")
                    return Save.Failed
                }
                // Known to the undo before the SAF record, which can throw.
                backup = movedTo
                if (plannedBackup == null) {
                    journal.record(SwapJournal.Entry(trackId, oldPath, movedTo, newVideoId))
                }
            }

            val committed = fileOrganizer.commitDownload(
                tempFile = tempFile,
                artist = track.artist,
                album = album,
                title = track.title,
                format = extension,
                trackId = trackId,
                nameSuffix = nameSuffix,
            )
            committedPath = committed.filePath
            val now = System.currentTimeMillis()
            val recorded = trackDao.completeSwap(
                trackId = trackId,
                youtubeId = newVideoId,
                filePath = committed.filePath,
                fileSizeBytes = committed.sizeBytes,
                fileFormat = meta?.format?.takeIf { it != "unknown" } ?: formatOf(extension),
                qualityKbps = meta?.bitrateKbps ?: 0,
                sampleRateHz = meta?.sampleRateHz,
                bitsPerSample = meta?.bitsPerSample,
                durationMs = newLength(meta, track.durationMs),
                metadataEmbeddedAt = if (tagged) now else null,
                pickedAt = now,
                downloadedAt = now,
            ) == 1
            result = if (recorded) {
                Save.Done(committed.filePath)
            } else {
                Log.w(TAG, "swap: the final write for trackId=$trackId found the video taken or the track gone")
                Save.VideoTaken
            }
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            Log.w(TAG, "swap: couldn't save the replacement for trackId=$trackId", e)
        } finally {
            if (result !is Save.Done) {
                withContext(NonCancellable) {
                    undoSave(trackId, tempFile, committedPath, destination, destinationExisted, backup, oldPath)
                }
            }
        }

        // The old file goes only now: its set-aside copy when the new file
        // took its place; otherwise the old path itself, unless another track
        // still plays from it or it is the very file just written (SAF paths
        // compare by document id). localFileOps.delete handles content://
        // paths, which File.delete never did.
        val done = result as? Save.Done ?: return result
        when {
            backup != null -> {
                // Cleared only once the copy is gone: the next start retries.
                if (localFileOps.delete(backup)) {
                    journal.clear(trackId)
                } else {
                    Log.w(TAG, "swap: couldn't delete the set-aside copy $backup; the next start tries again")
                }
            }
            oldPath != null && !oldShared && !localFileOps.isSameFile(oldPath, done.path) -> {
                localFileOps.delete(oldPath)
                Log.d(TAG, "swap: deleted the old file $oldPath")
            }
        }
        return done
    }

    /**
     * The name suffix and planned path for the replacement: no suffix (the
     * canonical name), unless another track already uses the file there.
     * Single-folder and Per-playlist layouts name files `<artist>-<title>`,
     * and same or blank albums collide under Artist/Album, so two recordings
     * of one song (#531) would share a file: saving would write over the
     * other track's audio. Then the album name is added, then the track id.
     * One [FileOrganizer.plannedPaths] call answers every candidate (on SAF,
     * one listing of the folder).
     */
    private suspend fun pickName(track: TrackEntity, album: String?, extension: String): Pair<String?, String?> {
        val withId = listOfNotNull(album, track.id.toString()).joinToString("-")
        val choices = (listOf<String?>(null) + listOfNotNull(album) + withId).distinct()
        val planned = fileOrganizer.plannedPaths(track.artist, album, track.title, extension, track.id, choices)
        for ((suffix, path) in choices.zip(planned)) {
            if (path == null || trackDao.countOtherTracksWithFilePath(path, track.id) == 0) return suffix to path
        }
        return choices.last() to planned.last()
    }

    /**
     * Undoes a failed save: the temp file goes, whatever the save left at the
     * destination goes, and a set-aside old file comes back over it. A file
     * that was at the destination before the save is never deleted here.
     */
    private fun undoSave(
        trackId: Long,
        tempFile: File,
        committedPath: String?,
        destination: String?,
        destinationExisted: Boolean,
        backup: String?,
        oldPath: String?,
    ) {
        deleteTempFile(tempFile) // commitDownload deletes it only on success
        if (backup != null && oldPath != null) {
            if (committedPath != null && !localFileOps.isSameFile(committedPath, oldPath)) {
                localFileOps.delete(committedPath)
            }
            if (localFileOps.restoreSetAside(backup, oldPath)) {
                journal.clear(trackId)
            } else {
                Log.e(TAG, "swap: couldn't put $oldPath back; it is at $backup, and the next start tries again")
            }
            return
        }
        when {
            committedPath != null -> localFileOps.delete(committedPath)
            destination != null && !destinationExisted && localFileOps.exists(destination) ->
                localFileOps.delete(destination)
        }
    }

    /**
     * Stash's own tags and cover, as a sync download gets them (yt-dlp's tags
     * are YouTube's). True when written. Best-effort: an untagged file still
     * plays, and its stamp stays empty for a later tag pass.
     */
    private suspend fun embedTags(file: File, track: TrackEntity): Boolean = try {
        val domain = track.toDomain()
        val art = try {
            albumArtCache.resolveArt(domain)
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            null
        }
        metadataEmbedder.embedMetadata(file, domain, art)
        true
    } catch (e: Exception) {
        rethrowIfCancelled(e)
        Log.w(TAG, "swap: couldn't tag the replacement for trackId=${track.id}", e)
        false
    }

    /**
     * Codec, bitrate, bit depth and length of the downloaded file, or null
     * when it can't be read (the swap still goes ahead, as in sync).
     */
    private fun readAudioMetadata(file: File): AudioMetadata? = try {
        audioExtractor.extract(file.absolutePath)
    } catch (e: Exception) {
        Log.w(TAG, "swap: couldn't read audio metadata from ${file.name}", e)
        null
    }

    /**
     * The file's length when the row's is missing or more than 10% off: the
     * file is the truth, as in sync. Null keeps the row's length.
     */
    private fun newLength(meta: AudioMetadata?, rowDurationMs: Long): Long? {
        val fileMs = meta?.durationMs?.takeIf { it > 0 } ?: return null
        if (rowDurationMs <= 0) return fileMs
        val drift = abs(fileMs - rowDurationMs).toDouble() / fileMs.toDouble()
        return fileMs.takeIf { drift > DURATION_DRIFT_TOLERANCE }
    }

    /** The stored format of a file whose codec couldn't be read, from its extension. */
    private fun formatOf(extension: String): String = when (val ext = extension.lowercase()) {
        "m4a", "mp4", "aac" -> "aac"
        "opus", "webm" -> "opus"
        "ogg" -> "vorbis"
        else -> ext
    }

    private fun deleteTempFile(file: File) {
        runCatching { file.delete() }
            .onFailure { e -> Log.w(TAG, "swap: couldn't delete temp file ${file.name}", e) }
    }
}
