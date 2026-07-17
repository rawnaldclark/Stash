package com.stash.data.download.files

import com.stash.core.data.repository.MusicRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Single source of truth for the music library's on-disk size + lossless
 * breakdown. Replaces per-screen StateFlow + collector glue with a shared
 * @Singleton observed by [com.stash.feature.home.HomeViewModel] and
 * [com.stash.feature.settings.SettingsViewModel].
 *
 * The legacy DAO `SUM(file_size_bytes)` is unreliable on libraries that
 * predate the v0.8.x downloader fix — many rows have the column stuck at
 * 0 and the recovery backfill fails on orphaned rows + content:// path
 * mismatches. [FileOrganizer.computeMusicLibrarySize] walks the actual
 * filesystem (storage-mode-aware: internal `filesDir/music` for default,
 * SAF DocumentFile traversal for users on external storage) and returns
 * disk truth.
 *
 * Lifecycle:
 *  - The walker only runs when at least one consumer observes [size]
 *    (`SharingStarted.WhileSubscribed(5_000)`); a 5-second grace period
 *    covers brief navigation transitions.
 *  - Walks happen on [Dispatchers.IO] via `flowOn`.
 *  - On walk failure, `scan` returns the previous value — the StateFlow
 *    never flashes to 0 mid-recompute.
 *
 * Cold-start: the StateFlow starts at `LibrarySizeBreakdown(0, 0, 0)`. On
 * SAF storage with ~3000 files the first walk can take 1-2 minutes; the
 * UI shows 0 GB during that window. Persisting the last value across app
 * restarts is out of scope (see spec).
 */
@Singleton
class LibrarySizeHolder @Inject constructor(
    private val fileOrganizer: FileOrganizer,
    private val musicRepository: MusicRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshVersion = MutableStateFlow(0L)

    val size: StateFlow<LibrarySizeBreakdown> = combine(
        musicRepository.getTrackCount().distinctUntilChanged(),
        refreshVersion,
    ) { _, _ -> Unit }
        .scan(LibrarySizeBreakdown(0L, 0L, 0)) { prev, _ ->
            runCatching { fileOrganizer.computeMusicLibrarySize() }
                .getOrDefault(prev)
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LibrarySizeBreakdown(0L, 0L, 0),
        )

    /** Requests a fresh filesystem walk even when the track count is unchanged. */
    fun refresh() {
        refreshVersion.update { it + 1 }
    }
}
