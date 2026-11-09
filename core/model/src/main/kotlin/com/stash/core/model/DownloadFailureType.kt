package com.stash.core.model

/**
 * Why a download failed. NONE for not-yet-failed rows; NO_MATCH owned by the
 * matching layer and surfaced in FailedMatchesScreen. The remaining buckets
 * are produced by [DownloadFailureClassifier] in the data/download module and
 * surfaced in FailedDownloadsScreen.
 *
 * Room stores these by name, so new values go at the end and old names never change.
 */
enum class DownloadFailureType {
    NONE,
    NO_MATCH,
    AUTH_EXPIRED,
    NETWORK,
    PROVIDER_UNAVAILABLE,
    FFMPEG_ERROR,
    STORAGE_ERROR,
    UNKNOWN,

    /** YouTube asked us to slow down (HTTP 429, its bot check). Not the song's fault: try again later. */
    RATE_LIMITED,

    /** The phone is out of space (ENOSPC). Every song after it would fail too, so the batch stops. */
    STORAGE_FULL,
}
