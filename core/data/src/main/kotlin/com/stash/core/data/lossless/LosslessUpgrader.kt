package com.stash.core.data.lossless

import com.stash.core.model.Track
import com.stash.core.model.UpgradeResult

/**
 * v0.9.18: thin abstraction for the Now Playing "Find in FLAC" path.
 *
 * Lives in `:core:data` so `:feature:nowplaying` (which depends on
 * `:core:data` but not `:data:download`) can call the lossless
 * pipeline. The implementation lives in `:data:download` where it
 * has access to [com.stash.data.download.DownloadManager].
 *
 * Distinct from the sync-time download flow: this entry point
 * always passes `forced = true` so it ignores the user's global
 * lossless toggle and `youtubeFallbackEnabled` pref. The user
 * tapped Find in FLAC; they want a lossless attempt regardless of
 * their default settings.
 */
interface LosslessUpgrader {
    /**
     * Whether downloads are in Lossless mode (Settings › Download quality). False in AAC 320 and
     * Opus mode: the FLAC upgrade sweeps (after a sync, Library Health's check) don't run then. An
     * upgrade the user asks for by name (Find in FLAC, an "Upgrade to FLAC" selection) still does.
     */
    suspend fun isLosslessEnabled(): Boolean

    /**
     * @param sweep true for background batches (the FLAC upgrade worker): the request is
     *   labelled a download, so the relay serves it only from the day's spare capacity and
     *   may answer [UpgradeResult.Paced]. A single tap leaves it false and keeps stream priority.
     */
    suspend fun upgradeToLossless(track: Track, sweep: Boolean = false): UpgradeResult
}
