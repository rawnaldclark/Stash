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
}
