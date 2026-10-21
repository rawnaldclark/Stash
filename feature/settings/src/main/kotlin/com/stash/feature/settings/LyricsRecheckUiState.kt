package com.stash.feature.settings

import com.stash.data.download.lyrics.LyricsFetchStatus

/** What the "Re-check library for word-synced lyrics" row in Settings → Playback → Lyrics shows. */
sealed interface LyricsRecheckUiState {
    /**
     * Nothing running; a tap asks to start (or carry on).
     * @param lastCheckedAtMs when a sweep last reached the end, null if none ever has.
     * @param stoppedEarly the last run gave up because the lyrics services were rate-limiting or down.
     * @param partway a sweep was cancelled or cut short before the end: the next one carries on.
     */
    data class Idle(
        val lastCheckedAtMs: Long? = null,
        val stoppedEarly: Boolean = false,
        val partway: Boolean = false,
    ) : LyricsRecheckUiState

    /** Queued, waiting for unmetered Wi-Fi and a battery that isn't low. */
    data object Waiting : LyricsRecheckUiState

    /** Running: [done] of [total] tracks covered by the sweep so far (across resumes). */
    data class Checking(val done: Int, val total: Int) : LyricsRecheckUiState
}

/**
 * Folds the re-check work's live status and its stored resume point / finish stamp into the row's
 * state. [cursor] > 0 means a sweep is under way (see `LyricsPreference.recheckCursor`).
 */
internal fun lyricsRecheckUiState(status: LyricsFetchStatus, finishedAtMs: Long?, cursor: Long): LyricsRecheckUiState =
    when (status) {
        LyricsFetchStatus.Queued -> LyricsRecheckUiState.Waiting
        is LyricsFetchStatus.Running -> LyricsRecheckUiState.Checking(status.done, status.total)
        is LyricsFetchStatus.Done -> LyricsRecheckUiState.Idle(
            lastCheckedAtMs = finishedAtMs,
            stoppedEarly = status.bailed,
            partway = cursor > 0L && !status.bailed,
        )
        LyricsFetchStatus.Idle -> LyricsRecheckUiState.Idle(lastCheckedAtMs = finishedAtMs, partway = cursor > 0L)
    }
