package com.stash.core.data.sync

import com.stash.core.model.Track

/**
 * Abstraction over the download pipeline used by [workers.TrackDownloadWorker].
 *
 * The interface lives in `:core:data` so the worker can depend on it without
 * introducing a circular dependency on `:data:download`. The concrete
 * implementation ([com.stash.data.download.TrackDownloaderImpl]) is provided
 * by Hilt from the `:data:download` module.
 */
interface TrackDownloader {

    /**
     * Downloads a single track through the full pipeline (search, download,
     * metadata embed, file organization).
     *
     * @param track          The track to download.
     * @param preResolvedUrl Optional YouTube URL if already resolved (skips search).
     * @return The absolute path of the final downloaded file, or null on failure.
     */
    suspend fun downloadTrack(track: Track, preResolvedUrl: String? = null): String?
}
