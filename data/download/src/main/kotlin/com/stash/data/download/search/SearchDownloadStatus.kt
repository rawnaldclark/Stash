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

    /**
     * The library already holds this song's audio, so nothing was downloaded and no row was made:
     * the same video's row has a file, or a row the same-song rule calls this song (no id
     * disagrees, the same version) does. A different version or another upload of the song is not
     * this, and downloads on a row of its own.
     */
    data object AlreadyInLibrary : SearchDownloadStatus

    data class Failed(val message: String) : SearchDownloadStatus

    /**
     * v0.9.17+: lossless registry couldn't serve the track and the user
     * has yt-dlp fallback off. The track sits under
     * [com.stash.core.model.DownloadStatus.WAITING_FOR_LOSSLESS] in the
     * queue; the search-tab UI surfaces this distinct from [Failed] so
     * the user knows it's a deferral, not a problem.
     */
    data object WaitingForLossless : SearchDownloadStatus

    /**
     * AAC 320 download mode: Tidal has no AAC of this song yet and the lossy fallback is off.
     * Waits like [WaitingForLossless] (the same queue status); the UI says "Not available as AAC yet".
     */
    data object WaitingForAac : SearchDownloadStatus

    /**
     * The day's Tidal & Qobuz allowance is spent and the lossy fallback is off: the download
     * waits (the same queue status) and goes out after the reset.
     */
    data object WaitingForAllowance : SearchDownloadStatus

    /** Tidal was busy for its AAC: the download waits and is tried again shortly, whatever the fallback. */
    data object WaitingForTidal : SearchDownloadStatus

    /** Which path delivered the bytes, for UI labelling. */
    enum class Source { LOSSLESS, TIDAL_AAC, JIOSAAVN, YOUTUBE }
}
