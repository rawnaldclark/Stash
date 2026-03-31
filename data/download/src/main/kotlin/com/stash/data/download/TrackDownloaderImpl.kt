package com.stash.data.download

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
) : TrackDownloader {

    override suspend fun downloadTrack(track: Track, preResolvedUrl: String?): TrackDownloadOutcome {
        return when (val result = downloadManager.downloadTrack(track, preResolvedUrl)) {
            is TrackDownloadResult.Success -> TrackDownloadOutcome.Success(result.filePath)
            is TrackDownloadResult.Unmatched -> TrackDownloadOutcome.Unmatched
            is TrackDownloadResult.Failed -> TrackDownloadOutcome.Failed(result.error)
        }
    }
}
