package com.stash.data.download.files

import android.util.Log
import com.stash.core.data.db.dao.TrackDao
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.DownloadResult
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.prefs.toYtDlpArgs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

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
 * Flow (all fire-and-forget from the caller's POV):
 *   1. Delete the old on-disk file. Failure logged and swallowed — a
 *      stray leftover file is strictly better than a lost swap, and the
 *      orphan cleanup pass eventually catches it.
 *   2. Download the new videoId via [DownloadExecutor] using the user's
 *      active quality tier.
 *   3. Commit the downloaded file through [FileOrganizer] so it lands
 *      in the canonical artist/album path (same path derivation as a
 *      normal sync download).
 *   4. Update `tracks.youtube_id` + mark downloaded, so the row
 *      reflects the new identity and disappears from Failed Matches.
 *
 * Errors at any step are logged at WARN and don't propagate — the user
 * already got optimistic feedback in the VM, and the track's flag has
 * already been cleared there. If the download truly fails, the user
 * can re-flag and try again.
 */
@Singleton
class SwapCoordinator @Inject constructor(
    private val downloadExecutor: DownloadExecutor,
    private val fileOrganizer: FileOrganizer,
    private val qualityPrefs: QualityPreferencesManager,
    private val trackDao: TrackDao,
) {
    companion object {
        private const val TAG = "SwapCoordinator"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fire-and-forget swap. Returns immediately; the download continues
     * on this coordinator's scope until completion regardless of what
     * happens to the caller's lifecycle.
     *
     * @param trackId      Primary key of the track to update.
     * @param oldFilePath  On-disk file to delete. Null = no existing file
     *                     (e.g. track was never downloaded).
     * @param artist       Artist name — used by [FileOrganizer] for path.
     * @param title        Track title — used by [FileOrganizer] for path.
     * @param newVideoId   YouTube video ID of the approved candidate.
     */
    fun swap(
        trackId: Long,
        oldFilePath: String?,
        artist: String,
        title: String,
        newVideoId: String,
    ) {
        scope.launch {
            oldFilePath?.let { oldPath ->
                try {
                    val deleted = File(oldPath).delete()
                    Log.d(TAG, "swap: old file delete path=$oldPath deleted=$deleted")
                } catch (e: Exception) {
                    Log.w(TAG, "swap: old file delete threw", e)
                }
            }

            try {
                val url = "https://www.youtube.com/watch?v=$newVideoId"
                val qualityArgs = qualityPrefs.qualityTier.first().toYtDlpArgs()
                val tempDir = fileOrganizer.getTempDir()
                val tempFilename = "swap_$newVideoId"

                val result = downloadExecutor.download(
                    url = url,
                    outputDir = tempDir,
                    filename = tempFilename,
                    qualityArgs = qualityArgs,
                )

                if (result is DownloadResult.Success) {
                    val committed = fileOrganizer.commitDownload(
                        tempFile = result.file,
                        artist = artist,
                        album = null,
                        title = title,
                        format = result.file.extension,
                    )
                    trackDao.updateYoutubeId(trackId, newVideoId)
                    trackDao.markAsDownloaded(trackId, committed.filePath, committed.sizeBytes)
                    Log.i(
                        TAG,
                        "swap: completed trackId=$trackId → videoId=$newVideoId path=${committed.filePath}",
                    )
                } else {
                    Log.w(TAG, "swap: download failed for videoId=$newVideoId: $result")
                }
            } catch (e: Exception) {
                Log.w(TAG, "swap: unexpected error for videoId=$newVideoId", e)
            }
        }
    }
}
