package com.stash.data.download.files

import android.util.Log
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.audio.AudioMetadata
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.DownloadResult
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.prefs.toYtDlpArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * How one wrong-match swap ended, so the screen that asked for it can say so.
 * A failed swap used to put the row back with no word at all (#531). Every
 * outcome except [Swapped] leaves the track's audio and identity as they were
 * and puts its row back in Failed Matches.
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
     * Downloading or saving the replacement failed. Nothing changed, so the
     * same replacement can be tried again.
     */
    data class Failed(
        override val trackId: Long,
        override val newVideoId: String,
        override val title: String,
    ) : SwapOutcome

    /**
     * Another track took this video while it downloaded (`tracks.youtube_id`
     * is UNIQUE). Nothing was written, and this video can't be swapped in.
     */
    data class AlreadyLinked(
        override val trackId: Long,
        override val newVideoId: String,
        override val title: String,
        val ownerArtist: String,
        val ownerTitle: String,
        val ownerAlbum: String,
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
 *      claim the videoId for it with one guarded UPDATE. The screen checked
 *      ownership when the user approved, but the download takes seconds; if
 *      another track took the video meanwhile, stop here, before any file is
 *      touched.
 *   4. Name the file: the canonical name under the track's artist/album,
 *      unless another track already uses the file there (see
 *      [pickNameSuffix]).
 *   5. If the save would land on the old file's own path, move the old file
 *      aside first: the save deletes what is there before writing.
 *   6. Save through [FileOrganizer] and record it ([TrackDao.completeSwap]).
 *   7. Delete the old file, or its set-aside copy, unless another track
 *      still uses it or it is the file just written.
 *
 * Any failure re-flags the track and leaves the old audio as it was: a
 * set-aside file is put back, a half-written one removed, and the claim given
 * back so the same replacement can be tried again.
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
     * swap failed. With no screen collecting, nothing is buffered; the
     * re-flagged row already shows the swap didn't happen.
     */
    private val _outcomes = MutableSharedFlow<SwapOutcome>(extraBufferCapacity = 16)

    /** How each swap ended. Collected by the Failed Matches screen. */
    val outcomes: SharedFlow<SwapOutcome> = _outcomes.asSharedFlow()

    /**
     * Fire-and-forget swap. Returns immediately; the download continues
     * on this coordinator's scope until completion regardless of what
     * happens to the caller's lifecycle. The result arrives on [outcomes].
     * The track's artist, title, album and current file are read from the
     * database, not passed in, so a stale screen can't steer where the file
     * goes or which file is replaced.
     *
     * @param trackId    Primary key of the track to update.
     * @param newVideoId YouTube video ID of the approved candidate.
     */
    fun swap(trackId: Long, newVideoId: String) {
        scope.launch {
            performSwap(trackId, newVideoId)
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "swap: unexpected error for videoId=$newVideoId", e)
            reFlagAfterFailure(trackId)
            SwapOutcome.Failed(trackId, newVideoId, title = "")
        }
        _outcomes.emit(outcome)
    }

    private suspend fun runSwap(trackId: Long, newVideoId: String): SwapOutcome {
        val track = trackDao.getById(trackId)
            ?: return SwapOutcome.Failed(trackId, newVideoId, title = "")
        val title = track.title

        // v0.9.15: Reject blocklisted identities. A swap on a blocked
        // track would re-mark it downloaded and resurrect the file.
        if (blocklistGuard.isBlocked(
                artist = track.artist, title = title,
                spotifyUri = null, youtubeId = newVideoId,
            )) {
            Log.d(TAG, "Refused swap of blocked: ${track.artist} - $title")
            reFlagAfterFailure(trackId)
            return SwapOutcome.Blocked(trackId, newVideoId, title)
        }

        val url = "https://www.youtube.com/watch?v=$newVideoId"
        val qualityArgs = qualityPrefs.qualityTier.first().toYtDlpArgs()

        // #36: download + commit the replacement BEFORE touching the old
        // file. The previous order deleted the user's existing audio up
        // front, so a failed download left them with nothing AND a row
        // silently gone (the flag was already cleared in the VM).
        val result = downloadExecutor.download(
            url = url,
            outputDir = fileOrganizer.getTempDir(),
            // Per track: two tracks swapping to the same video at once
            // must not share (and delete) one temp file.
            filename = "swap_${trackId}_$newVideoId",
            qualityArgs = qualityArgs,
        )
        if (result !is DownloadResult.Success) {
            // The optimistic flag-clear in the VM made the row disappear.
            // Re-flag so it reappears in Failed Matches (#36); the old file
            // is untouched.
            Log.w(TAG, "swap: download failed for videoId=$newVideoId: $result")
            reFlagAfterFailure(trackId)
            return SwapOutcome.Failed(trackId, newVideoId, title)
        }
        val tempFile = result.file

        // A "successful" yt-dlp run can still produce a tiny error body.
        // Check it while it's a temp file: committed under the track's album
        // it usually replaces the old file in place, and rejecting it then
        // would delete the user's audio. acceptDownloadOrDelete deletes it.
        if (!localFileOps.acceptDownloadOrDelete(tempFile.absolutePath)) {
            Log.w(TAG, "swap: discarded too-small download for trackId=$trackId: ${tempFile.name}")
            reFlagAfterFailure(trackId)
            return SwapOutcome.Failed(trackId, newVideoId, title)
        }

        // Re-read: a reorganize or reconciliation can move the file during
        // the download, and the file to replace is the one the row points at
        // now, not when the user approved.
        val before = trackDao.getById(trackId)
        if (before == null) {
            Log.w(TAG, "swap: trackId=$trackId was deleted during the download; dropping it")
            deleteTempFile(tempFile)
            return SwapOutcome.Failed(trackId, newVideoId, title)
        }

        // Claim the video right before the first write. The approve-time
        // check ran before a download that takes seconds; a guarded UPDATE
        // can't lose to a track that took the video meanwhile.
        if (trackDao.updateYoutubeIdIfUnclaimed(trackId, newVideoId) == 0) {
            deleteTempFile(tempFile)
            reFlagAfterFailure(trackId)
            val owner = trackDao.findByYoutubeId(newVideoId)?.takeIf { it.id != trackId }
            Log.w(
                TAG,
                "swap: videoId=$newVideoId is linked to trackId=${owner?.id}; " +
                    "nothing written for trackId=$trackId",
            )
            return if (owner != null) {
                SwapOutcome.AlreadyLinked(
                    trackId = trackId,
                    newVideoId = newVideoId,
                    title = title,
                    ownerArtist = owner.artist,
                    ownerTitle = owner.title,
                    ownerAlbum = owner.album,
                )
            } else {
                SwapOutcome.Failed(trackId, newVideoId, title)
            }
        }

        // Read off the temp copy: a plain path even when the library is a
        // SAF tree, and the same bytes the commit writes.
        val meta = readAudioMetadata(tempFile)
        val savedPath = saveReplacement(before, tempFile, meta)
            ?: return giveBackClaim(trackId, newVideoId, before.youtubeId, title)
        persistFormatAndLength(trackId, meta, before.durationMs)

        // The cached StreamUrl was resolved against the OLD youtubeId —
        // without evicting it, buildMediaItemForTrack keeps serving the
        // stale (possibly-expired, definitely wrong-song) URL after a swap.
        trackIdentityEvents.emitIdentityChanged(trackId)

        Log.i(TAG, "swap: completed trackId=$trackId → videoId=$newVideoId path=$savedPath")
        return SwapOutcome.Swapped(trackId, newVideoId, title)
    }

    /**
     * Saves [tempFile] as [track]'s file and records it. Returns the saved
     * path, or null when that failed, with the user's old file where it was.
     */
    private suspend fun saveReplacement(track: TrackEntity, tempFile: File, meta: AudioMetadata?): String? {
        val trackId = track.id
        // Same path derivation as a sync download, album included. album =
        // null filed every swap under <artist>/singles/<title>, so swapping
        // two same-titled tracks wrote both into one file (#531).
        val album = track.album.takeIf { it.isNotBlank() }
        val format = tempFile.extension
        val oldPath = track.filePath
        val oldShared = oldPath != null && trackDao.countOtherTracksWithFilePath(oldPath, trackId) > 0
        val nameSuffix = pickNameSuffix(track, album, format)
        val destination = fileOrganizer.plannedPath(track.artist, album, track.title, format, trackId, nameSuffix)
        val destinationExisted = destination != null && localFileOps.exists(destination)

        // Saving onto the old file's own path deletes it before writing (File.copyTo
        // overwrite, and the SAF write deletes the document first), so a failed save
        // would leave the row on a missing or half-written file. Move it aside first.
        val backup = if (oldPath != null && destinationExisted && localFileOps.isSameFile(destination, oldPath)) {
            localFileOps.setAside(oldPath) ?: run {
                Log.w(TAG, "swap: couldn't move $oldPath aside; not saving over it")
                deleteTempFile(tempFile)
                return null
            }
        } else {
            null
        }

        var committedPath: String? = null
        var recorded = false
        try {
            val committed = fileOrganizer.commitDownload(
                tempFile = tempFile,
                artist = track.artist,
                album = album,
                title = track.title,
                format = format,
                trackId = trackId,
                nameSuffix = nameSuffix,
            )
            committedPath = committed.filePath
            // One write: the new file, its quality (bit depth included, null
            // for lossy), loudness cleared, the flag cleared, and the user's
            // pick recorded so the automatic FLAC upgrade leaves it alone.
            val now = System.currentTimeMillis()
            recorded = trackDao.completeSwap(
                trackId = trackId,
                filePath = committed.filePath,
                fileSizeBytes = committed.sizeBytes,
                sampleRateHz = meta?.sampleRateHz,
                bitsPerSample = meta?.bitsPerSample,
                pickedAt = now,
                downloadedAt = now,
            ) == 1
            if (!recorded) Log.w(TAG, "swap: trackId=$trackId is gone; not recording the replacement")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "swap: couldn't save the replacement for trackId=$trackId", e)
        } finally {
            if (!recorded) {
                withContext(NonCancellable) {
                    undoSave(tempFile, committedPath, destination, destinationExisted, backup, oldPath)
                }
            }
        }
        val savedPath = committedPath?.takeIf { recorded } ?: return null

        // The old file goes only now: its set-aside copy when the new file
        // took its place; otherwise the old path itself, unless another track
        // still plays from it or it is the very file just written (SAF paths
        // compare by document id). localFileOps.delete handles content://
        // paths, which File.delete never did.
        when {
            backup != null -> localFileOps.delete(backup)
            oldPath != null && !oldShared && !localFileOps.isSameFile(oldPath, savedPath) -> {
                localFileOps.delete(oldPath)
                Log.d(TAG, "swap: deleted the old file $oldPath")
            }
        }
        return savedPath
    }

    /**
     * The file name for the replacement: null for the canonical name, unless
     * another track already uses the file there. Single-folder and
     * Per-playlist layouts name files `<artist>-<title>`, and same or blank
     * albums collide under Artist/Album, so two recordings of one song (#531)
     * would share a file: saving would write over the other track's audio.
     * Then the album name is added, then the track id.
     */
    private suspend fun pickNameSuffix(track: TrackEntity, album: String?, format: String): String? {
        val withId = listOfNotNull(album, track.id.toString()).joinToString("-")
        val choices = (listOf<String?>(null) + listOfNotNull(album) + withId).distinct()
        for (suffix in choices) {
            val planned = fileOrganizer.plannedPath(track.artist, album, track.title, format, track.id, suffix)
            if (planned == null || trackDao.countOtherTracksWithFilePath(planned, track.id) == 0) return suffix
        }
        return choices.last()
    }

    /**
     * Undoes a failed save: the temp file goes, whatever the save left at the
     * destination goes, and a set-aside old file comes back over it. A file
     * that was at the destination before the save is never deleted here.
     */
    private fun undoSave(
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
            if (!localFileOps.restoreSetAside(backup, oldPath)) {
                Log.e(TAG, "swap: couldn't put $oldPath back; the old audio is at $backup")
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
     * Mirrors `LosslessUpgraderImpl.persistUpgrade`: the file is the truth
     * for format and quality — a swap over a FLAC kept saying 'flac' on the
     * lossy replacement — and for length when the row's is missing or more
     * than 10% off. Best-effort: the swap itself already succeeded.
     */
    private suspend fun persistFormatAndLength(trackId: Long, meta: AudioMetadata?, rowDurationMs: Long) {
        if (meta == null) return
        if (meta.format != "unknown") {
            try {
                trackDao.setFormatAndQuality(trackId, meta.format, meta.bitrateKbps)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "swap: setFormatAndQuality failed for trackId=$trackId", e)
            }
        }
        if (meta.durationMs > 0) {
            val drift = if (rowDurationMs > 0) {
                abs(meta.durationMs - rowDurationMs).toDouble() / meta.durationMs.toDouble()
            } else {
                1.0
            }
            if (drift > DURATION_DRIFT_TOLERANCE) {
                try {
                    trackDao.setDuration(trackId, meta.durationMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "swap: setDuration failed for trackId=$trackId", e)
                }
            }
        }
    }

    /**
     * Undoes the claim after a failed save and puts the row back. Restoring
     * the previous youtube_id matters for the retry: left on the new video,
     * the row would treat the replacement as its own "current" video and
     * refuse it.
     */
    private suspend fun giveBackClaim(
        trackId: Long,
        claimed: String,
        previous: String?,
        title: String,
    ): SwapOutcome {
        try {
            trackDao.restoreYoutubeIdIfClaimed(trackId, claimed, previous)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Another track took the previous id meanwhile (UNIQUE): clear it.
            Log.w(TAG, "swap: couldn't restore youtube_id=$previous on trackId=$trackId; clearing it", e)
            try {
                trackDao.restoreYoutubeIdIfClaimed(trackId, claimed, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "swap: couldn't release videoId=$claimed from trackId=$trackId", e)
            }
        }
        reFlagAfterFailure(trackId)
        return SwapOutcome.Failed(trackId, claimed, title)
    }

    private fun deleteTempFile(file: File) {
        runCatching { file.delete() }
            .onFailure { e -> Log.w(TAG, "swap: couldn't delete temp file ${file.name}", e) }
    }

    /**
     * Restores the wrong-match flag after a failed swap so the row returns to
     * the Failed Matches screen. Best-effort: a failure to re-flag is logged
     * but not propagated (the swap already failed; nothing more to do here).
     */
    private suspend fun reFlagAfterFailure(trackId: Long) {
        try {
            trackDao.updateMatchFlagged(trackId, true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "swap: failed to re-flag trackId=$trackId after failure", e)
        }
    }
}
