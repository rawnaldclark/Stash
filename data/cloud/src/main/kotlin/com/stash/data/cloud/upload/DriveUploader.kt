package com.stash.data.cloud.upload

import android.util.Log
import com.stash.core.data.db.dao.CloudDao
import com.stash.core.data.db.entity.CloudItemEntity
import com.stash.core.data.db.entity.CloudUploadEntity
import com.stash.core.model.MusicSource
import com.stash.data.cloud.provider.CloudApiException
import com.stash.data.cloud.provider.gdrive.GoogleDriveJson.FileJson
import com.stash.data.cloud.provider.gdrive.GoogleDriveProvider
import com.stash.data.cloud.provider.gdrive.driveFolderRow
import com.stash.data.cloud.scan.CloudFiles

/** How one queued song ended. */
sealed interface UploadResult {
    /** In Drive and linked to the song. [reused] = an earlier copy was found, nothing was sent. */
    data class Saved(val fileId: String, val reused: Boolean) : UploadResult

    /** Nothing to upload any more (the download or the song is gone). [reason] is for the card. */
    data class Skipped(val reason: String) : UploadResult
}

/**
 * Saves one downloaded song to the user's Google Drive and links it to the song, idempotently:
 *
 *  1. The song must still be a download with its file on the phone; otherwise [UploadResult.Skipped].
 *  2. A session saved for the same bytes (size + md5) is resumed from what Drive committed.
 *  3. Otherwise Drive is searched for a copy Stash uploaded before (this phone before a
 *     reinstall, another phone): same [UploadKeys.trackKey] tag and the same md5 → that file is
 *     reused, nothing is sent.
 *  4. Otherwise a new resumable session is opened in Stash/<artist>/<album> ([DriveFolders]),
 *     saved on the song's row before the first byte, and the file goes up ([ResumableUpload]).
 *  5. Drive's md5 of the result is checked against the phone's; a mismatch deletes that
 *     just-made file (the only delete this feature ever sends) and fails the try.
 *  6. The Drive file is linked to the song ([CloudDao.linkUpload]) in the account's "Saved from
 *     Stash" folder row, so the Drive scan knows it and never adds it as a second song.
 *
 * Failures throw: [DriveFullException], CloudAuthRequiredException, connectivity
 * (IOException, CloudUnavailableException), [CloudApiException]; the worker decides.
 */
class DriveUploader(
    private val dao: CloudDao,
    private val api: DriveUploadApi,
    private val folders: DriveFolders,
    private val session: ResumableUpload,
    private val files: LocalAudioFiles,
    private val naming: UploadNaming,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** [onProgress] hears (bytes committed, total bytes) for the notification. */
    suspend fun upload(row: CloudUploadEntity, onProgress: suspend (Long, Long) -> Unit = { _, _ -> }): UploadResult {
        val track = dao.getTrack(row.trackId) ?: return UploadResult.Skipped(GONE)
        val path = track.filePath
        if (!track.isDownloaded || path == null || track.source == MusicSource.CLOUD) return UploadResult.Skipped(NOT_DOWNLOADED)
        val size = files.size(path)?.takeIf { it > 0 } ?: return UploadResult.Skipped(FILE_GONE)
        val md5 = files.md5(path)
        val extension = extensionOf(path, track.fileFormat)
        val place = naming.place(track.artist, track.album, track.title, extension)
        val key = UploadKeys.trackKey(track.isrc, track.artist, track.title, track.durationMs)
        val account = row.accountId

        val onCommitted: suspend (Long) -> Unit = { done ->
            dao.setUploadProgress(row.trackId, done, clock())
            onProgress(done, size)
        }

        var created: FileJson? = null
        var reused = false
        // 2. Carry on with a session opened for these very bytes.
        val saved = row.sessionUri?.takeIf { row.sessionSize == size && row.sessionMd5 == md5 }
        if (saved != null) {
            created = try {
                when (val s = session.status(saved, size)) {
                    is ResumableUpload.Status.Complete -> s.file
                    is ResumableUpload.Status.Partial -> {
                        onProgress(s.committed, size)
                        session.upload(saved, path, size, s.committed, onCommitted)
                    }
                }
            } catch (e: UploadSessionExpiredException) {
                dao.setUploadSession(row.trackId, null, null, null, clock())
                null
            }
        }
        // 3. Already in Drive?
        val found = if (created == null) api.findByTrackKey(account, key).firstOrNull { it.md5Checksum == md5 } else null
        val file: FileJson = when {
            created != null -> created
            found != null -> found.also { reused = true }
            else -> {
                // 4. A new upload.
                val parent = folders.resolve(account, place.folders)
                val uri = api.startSession(account, parent, place.fileName, UploadKeys.mimeType(extension), size, key)
                dao.setUploadSession(row.trackId, uri, size, md5, clock())
                onProgress(0, size)
                session.upload(uri, path, size, 0, onCommitted)
            }
        }
        // 5. What Drive holds must be what the phone has.
        if (!reused && file.md5Checksum != null && file.md5Checksum != md5) {
            Log.w(TAG, "uploaded copy differs from the phone's file; removing it")
            api.deleteOwnUpload(account, file.id)
            dao.setUploadSession(row.trackId, null, null, null, clock())
            throw CloudApiException(0, "the uploaded copy didn't match")
        }
        // 6. Link it.
        val folder = dao.driveFolderRow(account, GoogleDriveProvider.UPLOADS_ITEM_ID, clock())
        val name = file.name ?: place.fileName
        val linked = dao.linkUpload(
            row.trackId,
            CloudItemEntity(
                trackId = row.trackId, accountId = account, folderId = folder.id, itemId = file.id,
                name = name, relativePath = place.folders.joinToString("/"), parentItemId = file.parents?.firstOrNull(),
                cTag = file.version, eTag = file.headRevisionId, size = file.sizeBytes.takeIf { it > 0 } ?: size,
                mimeType = file.mimeType, seenGeneration = folder.scanGeneration, scannedAt = clock(),
            ),
        )
        if (!linked) Log.i(TAG, "saved, but the Drive file already belongs to another song here")
        return UploadResult.Saved(file.id, reused)
    }

    companion object {
        private const val TAG = "DriveUploader"
        const val GONE = "This song is no longer in your library."
        const val NOT_DOWNLOADED = "This song isn't downloaded any more."
        const val FILE_GONE = "The downloaded file is missing."

        /** The file's own extension when it has a plain one, else the recorded format. */
        internal fun extensionOf(path: String, format: String): String {
            val name = path.substringAfterLast('/').substringAfterLast("%2F")
            val ext = CloudFiles.extension(name)
            return if (ext.length in 2..5 && ext.all { it.isLetterOrDigit() }) ext else format.lowercase()
        }
    }
}
