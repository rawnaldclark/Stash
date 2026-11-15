package com.stash.core.data.files

import android.util.Log
import androidx.room.withTransaction
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.sync.DownloadJobRegistry
import com.stash.core.data.sync.SingleTrackDownloadEnqueuer
import com.stash.core.model.DownloadStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads a song that is already downloaded again, keeping its file until the new one lands.
 *
 * The song is handed back as the YouTube-library backfill hands one back for a better copy
 * ([TrackDao.markForReplacement]): not downloaded, its file kept. The download workers then treat
 * it as [TrackFileReplacement] describes: the old file goes only once the row records the new one
 * (a same-name download replaces it in place, at commit), and when no new file comes (no source
 * has the song, the download waits or fails for good) the kept file is the song's download again.
 *
 * The queue row is the person's own request (`user_requested`), as a Download tap files it
 * ([DownloadQueueDao.requeueForTap]): no cleanup of a playlist's downloads drops it, and a wait for
 * a lossless copy is retried in either download mode. It downloads through
 * [com.stash.core.data.sync.workers.TrackDownloadWorker]'s single-song mode, as a tap does, but a pause the person set stays: the download waits for Resume.
 *
 * Used by the one-time check for clips saved as songs (0.9.125, `ExistingClipCheck`).
 */
@Singleton
class RedownloadKeepingFile @Inject constructor(
    private val database: StashDatabase,
    private val trackDao: TrackDao,
    private val downloadQueueDao: DownloadQueueDao,
    private val downloadJobs: DownloadJobRegistry,
    private val enqueuer: SingleTrackDownloadEnqueuer,
) {

    /** What [queue] did. */
    enum class Outcome {
        /** Handed back and queued: it downloads again. */
        QUEUED,

        /** A download of it was already running here; it carries on, now the person's own request. */
        ALREADY_DOWNLOADING,

        /** Not downloaded (any more): the person removed it, or it is gone. Nothing changed. */
        NOT_DOWNLOADED,
    }

    /**
     * Hands track [trackId] back for a fresh download, keeping its file, and queues it.
     *
     * Under the job registry's claim lock, as a tap's reset is: no worker can claim the song's row
     * between the read of its state and the writes. The two writes (the row PENDING, the track not
     * downloaded) are one transaction, so no runner sees a downloaded song with a waiting row (the
     * queue drains complete those without downloading) or a handed-back song with no row.
     */
    suspend fun queue(trackId: Long): Outcome {
        var queueId = 0L
        val outcome = downloadJobs.withClaimsHeld {
            val track = trackDao.getById(trackId)
            if (track == null || !track.isDownloaded || track.filePath.isNullOrBlank()) return@withClaimsHeld Outcome.NOT_DOWNLOADED
            val row = downloadQueueDao.getByTrackId(trackId)
            if (row != null && downloadJobs.isLive(row.id) && downloadQueueDao.getStatusById(row.id) == DownloadStatus.IN_PROGRESS) {
                // A download of this downloaded song (a Retry, an upgrade) replaces its file already.
                downloadQueueDao.markUserRequested(row.id)
                return@withClaimsHeld Outcome.ALREADY_DOWNLOADING
            }
            queueId = database.withTransaction {
                val id = row?.id?.takeIf { downloadQueueDao.requeueForTap(it) == 1 }
                    ?: downloadQueueDao.insert(
                        DownloadQueueEntity(
                            trackId = trackId,
                            syncId = null,
                            searchQuery = "${track.artist} - ${track.title}",
                            youtubeUrl = track.youtubeId?.let { "https://music.youtube.com/watch?v=$it" },
                            userRequested = true,
                        ),
                    )
                trackDao.markForReplacement(trackId)
                id
            }
            Outcome.QUEUED
        }
        if (outcome == Outcome.QUEUED) enqueuer.enqueue(queueId)
        return outcome
    }

    /**
     * Hands track [trackId]'s download to WorkManager again when its row still waits to start:
     * for a run of the caller that stopped after [queue]'s writes and before its enqueue. A no-op
     * for a row that is downloading, done, waiting for a lossless copy, or gone. The enqueue keeps
     * a run already waiting (`KEEP`), so calling this for a song that is queued changes nothing.
     */
    suspend fun resumeQueued(trackId: Long) {
        val row = downloadQueueDao.getByTrackId(trackId) ?: return
        if (row.status != DownloadStatus.PENDING) return
        Log.d(TAG, "handing queue row ${row.id} (track $trackId) to WorkManager again")
        enqueuer.enqueue(row.id)
    }

    private companion object {
        const val TAG = "RedownloadKeepingFile"
    }
}
