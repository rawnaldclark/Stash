package com.stash.data.download

import com.stash.core.auth.TokenManager
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.model.Track
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.shared.TrackFinalizer
import com.stash.data.soundcloud.SoundCloudClient
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SoundCloudDownloads @Inject constructor(
    private val client: SoundCloudClient,
    private val tokens: TokenManager,
    private val executor: DownloadExecutor,
    private val files: FileOrganizer,
    private val probe: AudioDurationExtractor,
    private val finalizer: TrackFinalizer,
    private val tracks: TrackDao,
    private val localFiles: com.stash.core.data.files.LocalFileOps,
) {
    suspend fun download(track: Track, progress: (Float) -> Unit): TrackDownloadResult = withContext(Dispatchers.IO) {
        var temporary: File? = null
        try {
            val id = track.soundCloudId ?: return@withContext TrackDownloadResult.Failed("Missing SoundCloud identity")
            val token = tokens.getSoundCloudToken()
            val metadata = client.track(id, token)
            if (metadata.id != id) return@withContext TrackDownloadResult.Failed("SoundCloud identity changed")
            val stream = client.stream(id, token)
                ?: return@withContext TrackDownloadResult.Failed("SoundCloud track is unavailable")
            tracks.updateSoundCloudPreview(track.id, stream.preview || metadata.preview)
            if (stream.preview || metadata.preview) return@withContext TrackDownloadResult.Failed("Previews cannot be downloaded")
            val result = executor.downloadSoundCloud(metadata.permalink, token, files.getTempDir(),
                "sc_${track.id}_${UUID.randomUUID()}", progress)
            temporary = (result as? DownloadResult.Success)?.file
                ?: return@withContext TrackDownloadResult.Failed("SoundCloud download failed")
            if (!localFiles.acceptDownloadOrDelete(temporary!!.absolutePath))
                return@withContext TrackDownloadResult.Failed("Invalid SoundCloud audio was discarded")
            val audio = probe.extract(temporary!!.absolutePath)
                ?: return@withContext TrackDownloadResult.Failed("Could not verify downloaded audio")
            // Verify before committing: even a server/extractor preview without a preview flag is refused.
            if (!fullLength(audio.durationMs, metadata.durationMs) || temporary!!.length() <= 0)
                return@withContext TrackDownloadResult.Failed("Incomplete SoundCloud audio was discarded")
            when (val saved = finalizer.finalizeFile(temporary!!, track,
                AudioFormat(audio.format, audio.bitrateKbps, fileExtension = temporary!!.extension))) {
                is TrackFinalizer.FinalizeResult.Success -> TrackDownloadResult.Success(saved.committed.filePath)
                is TrackFinalizer.FinalizeResult.Failed -> TrackDownloadResult.Failed("Could not save SoundCloud audio")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            TrackDownloadResult.Failed("SoundCloud download failed")
        } finally {
            temporary?.delete()
        }
    }

    companion object {
        fun fullLength(actualMs: Long, expectedMs: Long): Boolean =
            expectedMs > 0 && actualMs > 0 && kotlin.math.abs(actualMs - expectedMs) <= maxOf(1500, expectedMs / 50)
    }
}
