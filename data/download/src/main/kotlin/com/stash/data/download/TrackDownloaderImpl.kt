package com.stash.data.download

import android.util.Log
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.files.LocalFileOps
import com.stash.core.data.files.TrackFileReplacement
import com.stash.core.data.sync.TrackDownloadOutcome
import com.stash.core.data.sync.TrackDownloader
import com.stash.core.model.Track
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete implementation of [TrackDownloader] that delegates to [DownloadManager].
 *
 * Bound into the Hilt graph via [di.DownloadModule] so that any component
 * depending on the [TrackDownloader] interface (e.g. TrackDownloadWorker in
 * `:core:data`) receives this implementation without a circular module dependency.
 */
@Singleton
class TrackDownloaderImpl @Inject constructor(
    private val downloadManager: DownloadManager,
    private val downloadQueueDao: DownloadQueueDao,
    private val localFileOps: LocalFileOps,
    private val fileReplacement: TrackFileReplacement,
    private val cloudDownloader: com.stash.data.download.cloud.CloudTrackDownloader,
) : TrackDownloader {

    override suspend fun downloadTrack(track: Track, preResolvedUrl: String?): TrackDownloadOutcome {
        // A cloud song (the user's own Dropbox / OneDrive file) is copied from their cloud as is;
        // it never enters DownloadManager's search / lossless / YouTube pipeline.
        val result = if (track.source == com.stash.core.model.MusicSource.CLOUD) {
            cloudDownloader.download(track)
        } else {
            downloadManager.downloadTrack(track, preResolvedUrl)
        }
        return when (result) {
            is TrackDownloadResult.Success -> {
                // Validate the committed file before it can be marked downloaded.
                // A "successful" download can still be junk — e.g. yt-dlp writing
                // a ~274-byte error body into a .webm. If that were marked
                // isDownloaded it would later fail playback with
                // ERROR_CODE_PARSING_CONTAINER_MALFORMED and skip-storm the queue.
                // acceptDownloadOrDelete deletes the junk + returns false, so the
                // track stays not-downloaded (and therefore streamable); the
                // worker routes Failed through its normal bounded-retry handling.
                // DownloadManager already refuses a junk temp file before the
                // commit, where it could still replace the track's own file in
                // place; this is the backstop for the committed one.
                // The track's previous file is NOT deleted here: the worker
                // deletes it once the row records the new one (see
                // TrackFileReplacement.deleteReplacedFile).
                if (localFileOps.acceptDownloadOrDelete(result.filePath)) {
                    TrackDownloadOutcome.Success(result.filePath)
                } else {
                    Log.w(TAG, "discarded too-small download for '${track.artist} - ${track.title}': ${result.filePath}")
                    TrackDownloadOutcome.Failed("Downloaded file too small — discarded as a failed download")
                }
            }
            is TrackDownloadResult.Unmatched -> TrackDownloadOutcome.Unmatched(result.rejectedVideoId)
            is TrackDownloadResult.Failed -> TrackDownloadOutcome.Failed(result.error)
            // v0.9.17: Deferred is the strict-FLAC stay-in-queue signal.
            // Translate to a DownloadStatus.WAITING_FOR_LOSSLESS DAO write so
            // the row persists in the queue (out of the retryable-FAILED set)
            // until the LosslessRetryScheduler re-resolves it. The benign
            // [TrackDownloadOutcome.Deferred] outcome tells [TrackDownloadWorker]
            // not to increment retries, not to mark FAILED, and not to fail
            // the worker chain.
            //
            // A track handed back for a better copy with its file kept
            // (TrackDao.markForReplacement) gets that file back as its download
            // meanwhile, or it would vanish from the Library while it waits.
            is TrackDownloadResult.Deferred -> {
                // The row keeps why it waits, for the Downloads screen, Now Playing and the sweep.
                val queueEntry = downloadQueueDao.getByTrackId(track.id)
                queueEntry?.let {
                    downloadQueueDao.deferIfInProgress(it.id, result.reason.code)
                }
                fileReplacement.restoreKeptFile(track.id, track.filePath)
                TrackDownloadOutcome.Deferred
            }
        }
    }

    private companion object {
        const val TAG = "TrackDownloader"
    }
}
