package com.stash.core.model

/**
 * Coarse status for a download queue entry.
 *
 * Distinct from [DownloadState] which tracks fine-grained pipeline phases
 * (MATCHING, TAGGING, etc.). [DownloadStatus] represents the high-level
 * lifecycle of an item sitting in the download queue.
 */
enum class DownloadStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    SKIPPED,

    /**
     * v0.9.17+: the lossless registry could not serve this track (a source
     * busy, paced or out of today's allowance, etc.) AND the user has not
     * enabled yt-dlp fallback. The row sits in this state until
     * [LosslessRetryWorker] re-resolves it (signals: circuit breaker reset,
     * the allowance's reset, Tidal free again) and flips it back to PENDING
     * for the standard download worker chain.
     *
     * Distinct from FAILED — failed carries "give up" semantics in
     * DiffWorker / Library Health and surfaces as red errors. This is a
     * deliberate hold, not a problem.
     */
    WAITING_FOR_LOSSLESS,
}
