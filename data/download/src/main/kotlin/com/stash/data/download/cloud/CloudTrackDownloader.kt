package com.stash.data.download.cloud

import android.util.Log
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.model.CloudRef
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import com.stash.data.cloud.di.CloudModule
import com.stash.data.cloud.net.RequestPriority
import com.stash.data.cloud.net.await
import com.stash.data.cloud.playback.CloudLinkResolver
import com.stash.data.download.TrackDownloadResult
import com.stash.data.download.files.FileOrganizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Downloads a [MusicSource.CLOUD] song: the user's own file, copied byte for byte from their
 * cloud (Dropbox temporary link / OneDrive downloadUrl, no Authorization header) into the
 * library where every other download lands ([FileOrganizer.commitDownload]). No search, no
 * matching, no lossless lookup, no re-encode: what is in the cloud is what lands on the phone.
 *
 * The file's tags are the user's own, so the row is stamped `metadata_embedded_at` here and the
 * tag-embedding backfill leaves it alone (as "Import from device" does). A link the content host
 * refuses (expired / revoked) is resolved again once.
 */
@Singleton
class CloudTrackDownloader @Inject constructor(
    private val links: CloudLinkResolver,
    @Named(CloudModule.CONTENT_CLIENT) client: OkHttpClient,
    private val fileOrganizer: FileOrganizer,
    private val trackDao: TrackDao,
) {
    /** Whole-song fetches stall for longer than API calls may. */
    private val fetchClient = client.newBuilder().readTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build()

    suspend fun download(track: Track, onProgress: (Float) -> Unit = {}): TrackDownloadResult {
        val ref = CloudRef.parse(track.cloudUri) ?: return TrackDownloadResult.Failed("Cloud song has no cloud address")
        val ext = extensionFor(track.fileFormat)
        val temp = File(fileOrganizer.getTempDir(), "cloud_${track.id}_${UUID.randomUUID().toString().take(8)}.$ext")
        return try {
            var link = links.resolve(ref, RequestPriority.INTERACTIVE)
            val code = fetch(link.url, temp, link.size, onProgress)
            if (code in REFRESH_CODES) {
                link = links.resolve(ref, RequestPriority.INTERACTIVE, fresh = true)
                val retry = fetch(link.url, temp, link.size, onProgress)
                if (retry != 200) return fail(temp, "Cloud answered $retry")
            } else if (code != 200) {
                return fail(temp, "Cloud answered $code")
            }
            if (link.size > 0 && temp.length() != link.size) return fail(temp, "Cloud download was cut short")

            val committed = fileOrganizer.commitDownload(
                tempFile = temp,
                artist = track.artist,
                album = track.album.ifBlank { null },
                title = track.title,
                format = ext,
                trackId = track.id,
            )
            runCatching { trackDao.setMetadataEmbeddedAt(track.id, System.currentTimeMillis()) }
            TrackDownloadResult.Success(committed.filePath)
        } catch (e: CancellationException) {
            runCatching { temp.delete() }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "cloud download failed for ${track.id}: ${e.javaClass.simpleName}")
            fail(temp, "Couldn't download from the cloud")
        }
    }

    /** Streams [url] into [dest]; returns the HTTP status (200 = written whole). */
    private suspend fun fetch(url: String, dest: File, size: Long, onProgress: (Float) -> Unit): Int {
        val request = Request.Builder().url(url).header("Accept-Encoding", "identity").build()
        // Unbuffered: a song can be hundreds of MB; it is copied to disk in chunks below (worker thread).
        fetchClient.newCall(request).await(bufferBody = false).use { response ->
            if (response.code != 200) return response.code
            withContext(Dispatchers.IO) {
                val total = size.takeIf { it > 0 } ?: response.body!!.contentLength()
                response.body!!.byteStream().use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            return 200
        }
    }

    private fun fail(temp: File, message: String): TrackDownloadResult {
        runCatching { temp.delete() }
        return TrackDownloadResult.Failed(message)
    }

    companion object {
        private const val TAG = "CloudDownload"
        private val REFRESH_CODES = setOf(401, 403, 404, 410)

        /** The library file's extension for a cloud song's `file_format` (ALAC lives in .m4a). */
        internal fun extensionFor(format: String): String = when (val f = format.lowercase()) {
            "alac", "aac" -> "m4a"
            "oga", "vorbis" -> "ogg"
            else -> f.takeIf { FileOrganizer.isSafeFormat(it) } ?: "bin"
        }
    }
}
