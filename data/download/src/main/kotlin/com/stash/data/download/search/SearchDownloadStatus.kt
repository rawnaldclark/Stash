package com.stash.data.download.search

/**
 * Status flow type for [SearchDownloadCoordinator]. Distinct from
 * [com.stash.core.model.DownloadStatus] — that enum (PENDING /
 * IN_PROGRESS / COMPLETED / FAILED / SKIPPED) is used by the sync
 * worker pipeline (`TrackDownloadWorker`, `DownloadQueueDao`) and
 * isn't extensible without breaking sync. Keeping these separate lets
 * the search-tab status carry typed metadata (which source we ended up
 * using) without polluting the sync model.
 */
sealed interface SearchDownloadStatus {
    /** Coordinator is calling `LosslessSourceRegistry.resolve()`. */
    data object Resolving : SearchDownloadStatus

    /** Coordinator has chosen a source and is downloading bytes. */
    data class Downloading(val via: Source) : SearchDownloadStatus

    data object Completed : SearchDownloadStatus

    data class Failed(val message: String) : SearchDownloadStatus

    /**
     * v0.9.17+: lossless registry couldn't serve the track and the user
     * has yt-dlp fallback off. The track sits under
     * [com.stash.core.model.DownloadStatus.WAITING_FOR_LOSSLESS] in the
     * queue; the search-tab UI surfaces this distinct from [Failed] so
     * the user knows it's a deferral, not a problem.
     */
    data object WaitingForLossless : SearchDownloadStatus

    /** Which path delivered the bytes, for UI labelling. */
    enum class Source { LOSSLESS, JIOSAAVN, YOUTUBE }
}
