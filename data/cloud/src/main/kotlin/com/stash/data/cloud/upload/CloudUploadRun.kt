package com.stash.data.cloud.upload

import android.util.Log
import com.stash.core.data.db.dao.CloudDao
import com.stash.core.data.db.entity.CloudUploadEntity
import com.stash.core.data.db.entity.CloudUploadState
import com.stash.data.cloud.provider.CloudApiException
import com.stash.data.cloud.provider.CloudAuthRequiredException
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * One pass over the upload queue (`cloud_uploads` rows in QUEUED), oldest first, one song at a
 * time. A trouble with one Drive account stops that account for this pass and only that account:
 * the others' songs carry on behind it.
 *
 *  - Drive full: the account pauses ([CloudUploadSettings.fullAccounts]); its songs stay queued and
 *    its card says "Your Google Drive is full" until the user tries again.
 *  - Signed out: its songs stay queued; its card shows the usual Reconnect, which starts the
 *    uploads again ([CloudUploads.resume]).
 *  - No connection / Drive asked to slow down: nothing is wrong with the song; its account stops
 *    for this pass and [Outcome.RetryLater] asks for another.
 *  - Anything wrong with one song: that song goes to the back of the queue; after
 *    [CloudUploadWorker.MAX_ATTEMPTS] tries it is FAILED and the others carry on.
 *
 * @param upload Sends one song ([DriveUploader.upload]); hears (bytes committed, total bytes).
 * @param onProgress Hears (song title, songs left, bytes committed, total bytes) for the notification.
 */
internal class CloudUploadRun(
    private val dao: CloudDao,
    private val settings: CloudUploadSettings,
    private val upload: suspend (CloudUploadEntity, suspend (Long, Long) -> Unit) -> UploadResult,
    private val onProgress: suspend (String, Int, Long, Long) -> Unit = { _, _, _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Outcome {
        /** Nothing is left that this pass can send. */
        Finished,

        /** Some account lost its connection or was asked to slow down: run again later. */
        RetryLater,
    }

    suspend fun run(): Outcome {
        // The accounts done for this pass. Full ones are read each time round: the user may have
        // tried again meanwhile, and one that fills up now joins them.
        val stopped = mutableSetOf<Long>()
        var outcome = Outcome.Finished
        var saved = 0
        while (true) {
            val skip = (stopped + settings.fullAccounts.value).toList()
            val row = dao.nextQueuedUpload(skip) ?: break
            val left = dao.countQueuedUploads(skip)
            val title = dao.getTrack(row.trackId)?.title ?: "a song"
            onProgress(title, left, 0, 0)
            try {
                when (val r = upload(row) { sent, total -> onProgress(title, left, sent, total) }) {
                    is UploadResult.Saved -> {
                        dao.finishUpload(row.trackId, CloudUploadState.DONE.name, r.fileId, null, clock())
                        saved++
                    }
                    is UploadResult.Skipped -> dao.finishUpload(row.trackId, CloudUploadState.SKIPPED.name, null, r.reason, clock())
                }
            } catch (e: CancellationException) {
                // Wi-Fi lost, or cancelled: the session and offset are saved; the next run resumes.
                throw e
            } catch (e: DriveFullException) {
                Log.i(TAG, "a Google Drive is full: its uploads wait")
                settings.setDriveFull(row.accountId, true)
                stopped += row.accountId
            } catch (e: CloudAuthRequiredException) {
                Log.i(TAG, "a Google Drive account needs a reconnect: its uploads wait")
                stopped += row.accountId
            } catch (e: LocalFileException) {
                Log.w(TAG, "one song's file couldn't be read: ${e.cause?.javaClass?.simpleName ?: "it got shorter"}")
                dao.failUploadAttempt(row.trackId, "Couldn't read this song's file.", CloudUploadWorker.MAX_ATTEMPTS, clock())
            } catch (e: CloudApiException) {
                Log.w(TAG, "upload of one song failed: HTTP ${e.status}")
                dao.failUploadAttempt(row.trackId, "Google Drive didn't take this song (error ${e.status}).", CloudUploadWorker.MAX_ATTEMPTS, clock())
            } catch (e: IOException) {
                // No connection, Drive busy (CloudUnavailableException), a session gone mid-way:
                // nothing is wrong with the song. Try again later, from the saved offset.
                Log.w(TAG, "uploads paused: ${e.javaClass.simpleName}")
                stopped += row.accountId
                outcome = Outcome.RetryLater
            } catch (e: Exception) {
                // A file that can't be read (permission gone on an SD card...): one song's problem.
                Log.w(TAG, "upload of one song failed: ${e.javaClass.simpleName}")
                dao.failUploadAttempt(row.trackId, "Couldn't read this song's file.", CloudUploadWorker.MAX_ATTEMPTS, clock())
            }
        }
        if (saved > 0) Log.i(TAG, "saved $saved songs to Google Drive")
        return outcome
    }

    private companion object {
        const val TAG = "CloudUpload"
    }
}
