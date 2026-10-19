package com.stash.feature.nowplaying

import com.stash.core.model.DownloadStatus
import com.stash.core.model.DownloadWaitReason

/** Where a download started from Now Playing has got to, as far as its ring around ⋮ cares. */
internal enum class DownloadWatch {
    /** Waiting or downloading: the ring keeps turning. */
    RUNNING,

    /** The file landed. */
    DONE,
    FAILED,

    /** Cancelled (from the Downloads screen, or a playlist's Download switch going off). */
    CANCELLED,

    /** Held until a lossless source can serve it (strict lossless, no fallback). */
    WAITING_FOR_LOSSLESS,

    /** The queue row went away (the song was blocked, or its download removed). */
    GONE,
}

/** What the queue row's [status] says about the download; null (no row) is [DownloadWatch.GONE]. */
internal fun downloadWatchOf(status: DownloadStatus?): DownloadWatch = when (status) {
    null -> DownloadWatch.GONE
    DownloadStatus.PENDING, DownloadStatus.IN_PROGRESS -> DownloadWatch.RUNNING
    DownloadStatus.COMPLETED -> DownloadWatch.DONE
    DownloadStatus.FAILED -> DownloadWatch.FAILED
    DownloadStatus.SKIPPED -> DownloadWatch.CANCELLED
    DownloadStatus.WAITING_FOR_LOSSLESS -> DownloadWatch.WAITING_FOR_LOSSLESS
}

/**
 * The short message for a download that stopped without its file, or null: a
 * finished one speaks for itself (the ring goes, the Download action flips), and
 * one whose row is gone was removed on purpose.
 */
internal fun downloadWatchMessage(
    watch: DownloadWatch,
    title: String,
    reason: DownloadWaitReason? = null,
): String? = when (watch) {
    DownloadWatch.FAILED -> "Couldn't download '$title'."
    // Why it waits, from its own queue row.
    DownloadWatch.WAITING_FOR_LOSSLESS -> when (reason) {
        DownloadWaitReason.AAC -> "'$title' isn't available as AAC yet."
        DownloadWaitReason.RESET -> "'$title' is waiting for the daily reset."
        DownloadWaitReason.TIDAL_BUSY -> "Tidal is busy. Trying '$title' again shortly."
        DownloadWaitReason.FALLBACK -> "'$title' waits for Lossy fallback to be on."
        DownloadWaitReason.LOSSLESS, null -> "Waiting for a lossless copy of '$title'."
    }
    DownloadWatch.CANCELLED -> "Download of '$title' cancelled."
    DownloadWatch.RUNNING, DownloadWatch.DONE, DownloadWatch.GONE -> null
}
