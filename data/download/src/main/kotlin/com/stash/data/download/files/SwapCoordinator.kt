package com.stash.data.download.files

import android.util.Log
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.audio.AudioMetadata
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.DownloadResult
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.prefs.toYtDlpArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
 *      it is still a temp file. The replacement usually lands on the old
 *      file's own path, so checking after the commit would delete the
 *      user's audio along with the junk.
 *   3. Claim the videoId for the track with one guarded UPDATE. The screen
 *      checked ownership when the user approved, but the download takes
 *      seconds; if another track took the video meanwhile, stop here,
 *      before any file is touched.
 *   4. Commit the file through [FileOrganizer] under the track's own
 *      artist/album (the same path a sync download uses), then mark it
 *      downloaded with the new file's format, quality and length.
 *   5. Delete the old file if it lived at a different path.
 *
 * Any failure re-flags the track so its row returns to Failed Matches and
 * leaves the old audio in place. A save failure after step 3 gives the claim
 * back, so the same replacement can be tried again.
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
     *
     * @param trackId      Primary key of the track to update.
     * @param oldFilePath  On-disk file to delete. Null = no existing file
     *                     (e.g. track was never downloaded).
     * @param artist       Artist name — used by [FileOrganizer] for path.
     * @param title        Track title — used by [FileOrganizer] for path.
     * @param album        The track's album — used by [FileOrganizer] for
     *                     path. Null/blank files it like any album-less
     *                     download.
     * @param newVideoId   YouTube video ID of the approved candidate.
     */
    fun swap(
        trackId: Long,
        oldFilePath: String?,
        artist: String,
        title: String,
        album: String?,
        newVideoId: String,
    ) {
        scope.launch {
            performSwap(trackId, oldFilePath, artist, title, album, newVideoId)
        }
    }

    /**
     * The actual swap body, exposed as a suspend function so it can be
     * unit-tested directly without racing the fire-and-forget [scope].
     * Emits exactly one [SwapOutcome].
     */
    internal suspend fun performSwap(
        trackId: Long,
        oldFilePath: String?,
        artist: String,
        title: String,
        album: String?,
        newVideoId: String,
    ) {
        val outcome = try {
            runSwap(trackId, oldFilePath, artist, title, album, newVideoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "swap: unexpected error for videoId=$newVideoId", e)
            reFlagAfterFailure(trackId)
            SwapOutcome.Failed(trackId, newVideoId, title)
        }
        _outcomes.emit(outcome)
    }

    private suspend fun runSwap(
        trackId: Long,
        oldFilePath: String?,
        artist: String,
        title: String,
        album: String?,
        newVideoId: String,
    ): SwapOutcome {
        // v0.9.15: Reject blocklisted identities. A swap on a blocked
        // track would re-mark it downloaded and resurrect the file.
        if (blocklistGuard.isBlocked(
                artist = artist, title = title,
                spotifyUri = null, youtubeId = newVideoId,
            )) {
            Log.d(TAG, "Refused swap of blocked: $artist - $title")
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

        // Same path derivation as a sync download, album included. album =
        // null filed every swap under <artist>/singles/<title>, so swapping
        // two same-titled tracks wrote both into one file (#531).
        val committed = try {
            fileOrganizer.commitDownload(
                tempFile = tempFile,
                artist = artist,
                album = album?.takeIf { it.isNotBlank() },
                title = title,
                format = tempFile.extension,
                trackId = trackId,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "swap: couldn't save the replacement for trackId=$trackId", e)
            deleteTempFile(tempFile)
            return giveBackClaim(trackId, newVideoId, before.youtubeId, title)
        }

        try {
            trackDao.markAsDownloaded(
                trackId = trackId,
                filePath = committed.filePath,
                fileSizeBytes = committed.sizeBytes,
                sampleRateHz = meta?.sampleRateHz,
                bitsPerSample = meta?.bitsPerSample,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "swap: couldn't record the replacement for trackId=$trackId", e)
            // The row still points at its old file; a new file elsewhere
            // would only be an orphan.
            if (committed.filePath != oldFilePath) localFileOps.delete(committed.filePath)
            return giveBackClaim(trackId, newVideoId, before.youtubeId, title)
        }
        persistFormatAndLength(trackId, meta, before.durationMs)

        // The cached StreamUrl was resolved against the OLD youtubeId —
        // without evicting it, buildMediaItemForTrack keeps serving the
        // stale (possibly-expired, definitely wrong-song) URL after a swap.
        trackIdentityEvents.emitIdentityChanged(trackId)

        // Only now is it safe to remove the old file — and only if it
        // isn't the very path we just wrote (same artist/album/title
        // resolves to the same canonical file). A stray leftover is fine;
        // the orphan cleanup pass eventually catches it.
        oldFilePath?.let { oldPath ->
            if (oldPath != committed.filePath) {
                try {
                    val deleted = File(oldPath).delete()
                    Log.d(TAG, "swap: old file delete path=$oldPath deleted=$deleted")
                } catch (e: Exception) {
                    Log.w(TAG, "swap: old file delete threw", e)
                }
            }
        }

        Log.i(
            TAG,
            "swap: completed trackId=$trackId → videoId=$newVideoId path=${committed.filePath}",
        )
        return SwapOutcome.Swapped(trackId, newVideoId, title)
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
