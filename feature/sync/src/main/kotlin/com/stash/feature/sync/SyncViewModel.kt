package com.stash.feature.sync

import android.text.format.DateUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.sync.AuthExpiryState
import com.stash.core.data.sync.SyncPhase
import com.stash.core.data.sync.SyncPreferences
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.model.MusicSource
import com.stash.core.model.SyncMode
import com.stash.core.data.sync.DayOfWeekSet
import com.stash.core.data.sync.SyncScheduler
import com.stash.core.data.sync.SyncStateManager
import com.stash.core.data.sync.toDisplayStatus
import com.stash.core.model.SyncDisplayStatus
import com.stash.data.download.files.LibrarySizeHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Presentation-layer model for a single sync history row.
 *
 * Decoupled from the Room entity so the UI has no transitive Room dependency.
 */
data class SyncHistoryInfo(
    val id: Long,
    val startedAt: Long,
    val completedAt: Long? = null,
    val status: String,
    val tracksDownloaded: Int = 0,
    val tracksFailed: Int = 0,
    val newTracksFound: Int = 0,
    val playlistsChecked: Int = 0,
    val bytesDownloaded: Long = 0,
    val trigger: com.stash.core.model.SyncTrigger = com.stash.core.model.SyncTrigger.MANUAL,
    /** Online (true) / Offline (false) / unknown-legacy (null) at the time of this sync. */
    val streamingMode: Boolean? = null,
    val errorMessage: String? = null,
    val diagnostics: String? = null,
    /**
     * Richer display summary derived from the raw sync record. Prefer this
     * over [status] for rendering — it distinguishes partial success,
     * interruption (process kill), and genuine failure.
     */
    val displayStatus: SyncDisplayStatus = SyncDisplayStatus.Idle,
)

/**
 * Full UI state for the Sync screen.
 */
/**
 * Lightweight model for a Spotify playlist's sync preference toggle.
 */
data class SpotifySyncPlaylist(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val type: com.stash.core.model.PlaylistType,
    val syncEnabled: Boolean,
    val artUrl: String? = null,
    val hideFromHome: Boolean = false,
)

/**
 * Lightweight model for a YouTube Music playlist's sync preference toggle.
 * Mirrors [SpotifySyncPlaylist] so the UI can use a shared toggle row
 * composable; kept as a separate type so future YouTube-only fields don't
 * pollute the Spotify struct.
 */
data class YouTubeSyncPlaylist(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val type: com.stash.core.model.PlaylistType,
    val syncEnabled: Boolean,
    val artUrl: String? = null,
    val hideFromHome: Boolean = false,
)

data class SyncUiState(
    val syncPhase: SyncPhase = SyncPhase.Idle,
    val overallProgress: Float = 0f,
    val syncPreferences: SyncPreferences = SyncPreferences(),
    val spotifyConnected: Boolean = false,
    val youTubeConnected: Boolean = false,
    val recentSyncs: List<SyncHistoryInfo> = emptyList(),
    val isSyncing: Boolean = false,
    /** Spotify playlists available for sync preference toggles. */
    val spotifyPlaylists: List<SpotifySyncPlaylist> = emptyList(),
    /** YouTube Music playlists available for sync preference toggles. */
    val youTubePlaylists: List<YouTubeSyncPlaylist> = emptyList(),
    /**
     * Per-source sync modes. Each service's Sync Preferences card
     * renders its own Refresh/Accumulate chip row bound to one of
     * these. Defaults to ACCUMULATE for both.
     */
    val spotifySyncMode: SyncMode = SyncMode.ACCUMULATE,
    val youtubeSyncMode: SyncMode = SyncMode.ACCUMULATE,
    /**
     * Non-null while the Refresh-confirm dialog is shown for that source.
     * Set when the user taps Refresh on a source currently in ACCUMULATE
     * (Refresh deletes rotated-out downloads, so we ask first); cleared on
     * confirm/cancel. null = no dialog.
     */
    val pendingRefreshSource: MusicSource? = null,
    /**
     * When true, the YT Music Liked Songs sync filters out UGC, cover,
     * live, and podcast tracks. Other YT content is unaffected. Default false.
     */
    val youtubeLikedStudioOnly: Boolean = false,
    /**
     * Days-of-week bitmask for the auto-sync schedule. Bit 0 = Mon … bit 6 = Sun.
     * Default 127 = every day.
     */
    val syncDays: Int = 0b1111111,
    /** Number of tracks that could not be matched to a YouTube video. */
    val unmatchedCount: Int = 0,
    /**
     * Number of tracks the user flagged from Now Playing as "wrong
     * match." Drives the Sync-tab review card alongside [unmatchedCount]
     * so flagged tracks are reachable even when no sync failures exist.
     */
    val flaggedCount: Int = 0,

    // -- Hero card: last-sync metadata ----------------------------------------
    /** Relative time string for the most recent sync, e.g. "2 hours ago". Empty if never synced. */
    val lastSyncRelativeTime: String = "",
    /** Tracks downloaded in the most recent sync, or null if never synced. */
    val lastSyncTrackCount: Int? = null,
    /** Short health label: "✓ healthy", "! partial", "× failed", or "". */
    val lastSyncHealthLabel: String = "",
    /** Tint colour for [lastSyncHealthLabel]. */
    val lastSyncHealthColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent,

    // -- SyncStatusCard inputs (relocated from HomeUiState) -------------------
    /**
     * Aggregated stats + display status driving the
     * [com.stash.feature.sync.components.SyncStatusCard] at the top of
     * this screen. Assembled by [SyncViewModel.observeSyncStatusCard]
     * from the latest sync history + Room track counts + disk-walked
     * library size, mirroring the original HomeViewModel wiring verbatim.
     */
    val syncStatus: SyncStatusInfo = SyncStatusInfo(),
    /**
     * True after at least one sync has completed. Drives the
     * "Tap Sync Now" prompt vs. the stats grid in [SyncStatusCard].
     * Derived as `syncStatus.lastSyncTime != null` so it stays in lock-
     * step with the displayed "Last sync …" line.
     */
    val hasEverSynced: Boolean = false,
)

/**
 * ViewModel backing the Sync screen.
 *
 * Combines live sync phase, user preferences, auth state for each service,
 * and recent sync history into a single reactive [SyncUiState].
 */
@HiltViewModel
class SyncViewModel @Inject constructor(
    private val syncScheduler: SyncScheduler,
    private val syncStateManager: SyncStateManager,
    private val syncPreferencesManager: SyncPreferencesManager,
    private val tokenManager: TokenManager,
    private val syncHistoryDao: SyncHistoryDao,
    private val playlistDao: com.stash.core.data.db.dao.PlaylistDao,
    private val downloadQueueDao: com.stash.core.data.db.dao.DownloadQueueDao,
    private val musicRepository: com.stash.core.data.repository.MusicRepository,
    private val blocklistGuard: com.stash.core.data.blocklist.BlocklistGuard,
    /**
     * Disk-walked library size (storage-mode-aware: internal File walk
     * OR SAF DocumentFile traversal). Drives the SyncStatusCard's
     * Storage column. Mirrors HomeViewModel's injection — the Room
     * `file_size_bytes` column is bypassed because legacy libraries
     * have it stuck at 0 for thousands of rows.
     */
    private val librarySizeHolder: LibrarySizeHolder,
    /**
     * Online-vs-offline preference. Drives the Sync Now button label so
     * users can tell whether tapping it will download tracks to disk
     * (offline mode) or merely surface the library for streaming
     * playback (online mode). See [streamingEnabled].
     */
    private val streamingPreference: com.stash.core.data.prefs.StreamingPreference,
) : ViewModel() {

    /**
     * Reactive count of blocked songs, displayed as a badge on the
     * "Blocked Songs" row in the Sync screen's Library section.
     * v0.9.15: sources from BlocklistGuard (track_blocklist) instead
     * of the dropped tracks.is_blacklisted flag.
     */
    val blockedCount: StateFlow<Int> =
        blocklistGuard.observeCount()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = 0,
            )

    /**
     * Reactive count of FAILED rows in download_queue. Drives the
     * "Failed Downloads" card on the Sync tab — the card hides itself
     * when this is 0 so a healthy library shows no clutter.
     */
    val failedDownloadsCount: StateFlow<Int> =
        downloadQueueDao.getFailedDownloads()
            .map { it.size }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = 0,
            )

    /**
     * Per-source auth expiry state from SyncStateManager. The Sync tab's
     * AuthExpiredBanner subscribes to this flow and renders nothing when
     * `anyExpired == false`, so re-auth surfaces only when probes flag a
     * problem at sync start.
     */
    val authExpiry: StateFlow<AuthExpiryState> =
        syncStateManager.authExpiry
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = AuthExpiryState(false, false),
            )

    /**
     * Reactive online-streaming-mode flag. The Sync Now button reads
     * this to pick its label: "Surface Library for Streaming" in Online
     * mode, "Download Tracks to Device" in Offline mode. Initial value
     * matches `StreamingPreference.enabled`'s default (false / Offline)
     * so a not-yet-loaded flow displays the safer download-mode label.
     */
    val streamingEnabled: StateFlow<Boolean> =
        streamingPreference.enabled
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = false,
            )

    /**
     * Persist the user's choice between Online (streaming) and Offline
     * (download) mode. Hoisted from the Sync-tab segmented toggle so the
     * user can flip modes without leaving the Sync screen.
     */
    fun setStreamingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            streamingPreference.setEnabled(enabled)
        }
    }

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    init {
        observeSyncPhase()
        observePreferences()
        observeSyncMode()
        observeAuthStates()
        observeHistory()
        observeLastSync()
        observeSpotifyPlaylists()
        observeYouTubePlaylists()
        observeUnmatchedCount()
        observeFlaggedCount()
        observeSyncStatusCard()
    }

    // -- Public actions -------------------------------------------------------

    /** Trigger an immediate sync, replacing any pending schedule. */
    fun onSyncNow() {
        syncScheduler.triggerManualSync()
    }

    /**
     * Cancel any in-flight sync. Backed by `WorkManager.cancelUniqueWork`,
     * which cancels the work chain and any worker currently running — the
     * worker sees a cancellation exception and exits. In-progress track
     * downloads that finish before the cancellation is observed will still
     * complete, but no new tracks are enqueued.
     */
    fun onStopSync() {
        syncScheduler.cancelSync()
    }

    /** Toggle sync_enabled for a specific Spotify playlist. */
    fun onTogglePlaylistSync(playlistId: Long, enabled: Boolean) {
        viewModelScope.launch {
            playlistDao.updateSyncEnabled(playlistId, enabled)
            // v0.9.21: when DISABLING, sweep pending download_queue rows
            // whose tracks no longer belong to any sync-enabled playlist.
            // Without this, deselecting a playlist leaves its in-flight
            // downloads draining — confusing because the user expects
            // "deselect" to actually stop downloads. The query is
            // DELETE-with-NOT-IN so tracks shared with another enabled
            // playlist (e.g. same track in Liked Songs on both services)
            // stay queued.
            if (!enabled) {
                val cancelled = downloadQueueDao.cancelDownloadsWithNoEnabledPlaylist()
                if (cancelled > 0) {
                    android.util.Log.i(
                        "SyncToggle",
                        "cancelled $cancelled orphan PENDING download(s) after disable",
                    )
                }
            }
        }
    }

    /**
     * Toggle hide_from_home for a specific playlist. Unlike [onTogglePlaylistSync]
     * this does NOT sweep downloads — hiding a mix from Home has nothing to do
     * with what's queued or synced.
     */
    fun onToggleHideFromHome(playlistId: Long, hidden: Boolean) {
        viewModelScope.launch { playlistDao.setHideFromHome(playlistId, hidden) }
    }

    /**
     * Update the daily sync schedule time and reschedule.
     *
     * @param hour   Hour of day (0-23).
     * @param minute Minute of hour (0-59).
     */
    fun onSetSyncTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            syncPreferencesManager.setSyncTime(hour, minute)
            val prefs = _uiState.value.syncPreferences
            if (prefs.autoSyncEnabled) {
                syncScheduler.scheduleDailySync(
                    hour,
                    minute,
                    wifiOnly = prefs.wifiOnly,
                    days = DayOfWeekSet(prefs.syncDays),
                )
            }
        }
    }

    /** Toggle the daily auto-sync on or off. */
    fun onToggleAutoSync() {
        viewModelScope.launch {
            val current = _uiState.value.syncPreferences.autoSyncEnabled
            val newValue = !current
            syncPreferencesManager.setAutoSyncEnabled(newValue)
            if (newValue) {
                val prefs = _uiState.value.syncPreferences
                syncScheduler.scheduleDailySync(
                    prefs.syncHour,
                    prefs.syncMinute,
                    wifiOnly = prefs.wifiOnly,
                    days = DayOfWeekSet(prefs.syncDays),
                )
            } else {
                syncScheduler.cancelSync()
            }
        }
    }

    /** Toggle the Wi-Fi-only constraint on or off. */
    fun onToggleWifiOnly() {
        viewModelScope.launch {
            val current = _uiState.value.syncPreferences.wifiOnly
            syncPreferencesManager.setWifiOnly(!current)
        }
    }

    /** Switch Spotify's Refresh/Accumulate mode. */
    fun onSpotifySyncModeChanged(mode: SyncMode) {
        viewModelScope.launch {
            syncPreferencesManager.setSpotifySyncMode(mode)
        }
    }

    /** Switch YouTube's Refresh/Accumulate mode. */
    fun onYoutubeSyncModeChanged(mode: SyncMode) {
        viewModelScope.launch {
            syncPreferencesManager.setYoutubeSyncMode(mode)
        }
    }

    /** Refresh chip tapped for Spotify. If currently ACCUMULATE, confirm first
     *  (Refresh deletes rotated-out downloads); if already REFRESH, no-op. */
    fun onRequestSpotifyRefresh() {
        if (_uiState.value.spotifySyncMode == SyncMode.ACCUMULATE) {
            _uiState.update { it.copy(pendingRefreshSource = MusicSource.SPOTIFY) }
        }
    }

    /** Refresh chip tapped for YouTube — see [onRequestSpotifyRefresh]. */
    fun onRequestYoutubeRefresh() {
        if (_uiState.value.youtubeSyncMode == SyncMode.ACCUMULATE) {
            _uiState.update { it.copy(pendingRefreshSource = MusicSource.YOUTUBE) }
        }
    }

    /** Confirm the pending Refresh switch — applies REFRESH to that source. */
    fun confirmRefreshMode() {
        val source = _uiState.value.pendingRefreshSource ?: return
        viewModelScope.launch {
            when (source) {
                MusicSource.YOUTUBE -> syncPreferencesManager.setYoutubeSyncMode(SyncMode.REFRESH)
                MusicSource.SPOTIFY -> syncPreferencesManager.setSpotifySyncMode(SyncMode.REFRESH)
                MusicSource.LOCAL, MusicSource.BOTH -> Unit
            }
        }
        _uiState.update { it.copy(pendingRefreshSource = null) }
    }

    /** Dismiss the dialog — keep the current (Accumulate) mode. */
    fun cancelRefreshMode() {
        _uiState.update { it.copy(pendingRefreshSource = null) }
    }

    /** Persists the user's choice for the studio-only Liked Songs filter. */
    fun onYoutubeLikedStudioOnlyChanged(enabled: Boolean) {
        viewModelScope.launch {
            syncPreferencesManager.setYoutubeLikedStudioOnly(enabled)
        }
    }

    /** Persists the days-of-week selection. UI passes the new bitmask. */
    fun onSyncDaysChanged(bitmask: Int) {
        viewModelScope.launch {
            syncPreferencesManager.setSyncDays(bitmask)
        }
    }

    // -- Internal observers ---------------------------------------------------

    private fun observeSyncPhase() {
        viewModelScope.launch {
            syncStateManager.phase.collect { phase ->
                _uiState.update {
                    it.copy(
                        syncPhase = phase,
                        overallProgress = phase.progress,
                        isSyncing = phase !is SyncPhase.Idle &&
                            phase !is SyncPhase.Completed &&
                            phase !is SyncPhase.Error,
                    )
                }
            }
        }
    }

    private fun observePreferences() {
        viewModelScope.launch {
            syncPreferencesManager.preferences.collect { prefs ->
                _uiState.update { it.copy(syncPreferences = prefs) }
            }
        }
    }

    private fun observeSyncMode() {
        viewModelScope.launch {
            syncPreferencesManager.spotifySyncMode.collect { mode ->
                _uiState.update { it.copy(spotifySyncMode = mode) }
            }
        }
        viewModelScope.launch {
            syncPreferencesManager.youtubeSyncMode.collect { mode ->
                _uiState.update { it.copy(youtubeSyncMode = mode) }
            }
        }
        viewModelScope.launch {
            syncPreferencesManager.youtubeLikedStudioOnly.collect { enabled ->
                _uiState.update { it.copy(youtubeLikedStudioOnly = enabled) }
            }
        }
        viewModelScope.launch {
            syncPreferencesManager.syncDays.collect { bitmask ->
                _uiState.update { it.copy(syncDays = bitmask) }
            }
        }
    }

    private fun observeAuthStates() {
        viewModelScope.launch {
            combine(
                tokenManager.spotifyAuthState,
                tokenManager.youTubeAuthState,
            ) { spotify, youtube ->
                Pair(spotify is AuthState.Connected, youtube is AuthState.Connected)
            }.collect { (spotifyConnected, youTubeConnected) ->
                _uiState.update {
                    it.copy(
                        spotifyConnected = spotifyConnected,
                        youTubeConnected = youTubeConnected,
                    )
                }
            }
        }
    }

    private fun observeHistory() {
        viewModelScope.launch {
            syncHistoryDao.getRecentSyncs(limit = 10).collect { entities ->
                _uiState.update {
                    it.copy(recentSyncs = entities.map { e -> e.toInfo() })
                }
            }
        }
    }

    /**
     * Derives hero-card last-sync metadata from the most recent history entry.
     * Uses a limit-1 query so only the minimum data is loaded for this purpose.
     * Health is derived from [SyncDisplayStatus] — same source used by
     * [SyncHistoryRow] to render icons and badge colours.
     */
    private fun observeLastSync() {
        viewModelScope.launch {
            syncHistoryDao.getRecentSyncs(limit = 1).collect { entities ->
                val latest = entities.firstOrNull()?.toInfo()
                val relativeTime = latest?.let {
                    DateUtils.getRelativeTimeSpanString(
                        it.startedAt,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    ).toString()
                } ?: ""
                val healthLabel = when (latest?.displayStatus) {
                    null -> ""
                    SyncDisplayStatus.Success -> "✓"
                    is SyncDisplayStatus.PartialSuccess -> "!"
                    is SyncDisplayStatus.Interrupted -> "!"
                    SyncDisplayStatus.Cancelled -> "⊘"
                    is SyncDisplayStatus.Failed -> "×"
                    else -> ""
                }
                val healthColor = when (latest?.displayStatus) {
                    SyncDisplayStatus.Success ->
                        androidx.compose.ui.graphics.Color(0xFF10B981)
                    is SyncDisplayStatus.PartialSuccess,
                    is SyncDisplayStatus.Interrupted ->
                        androidx.compose.ui.graphics.Color(0xFFF59E0B)
                    SyncDisplayStatus.Cancelled ->
                        androidx.compose.ui.graphics.Color(0xFF9CA3AF)
                    is SyncDisplayStatus.Failed ->
                        androidx.compose.ui.graphics.Color(0xFFEF4444)
                    else -> androidx.compose.ui.graphics.Color.Transparent
                }
                _uiState.update { state ->
                    state.copy(
                        lastSyncRelativeTime = relativeTime,
                        lastSyncTrackCount = latest?.tracksDownloaded,
                        lastSyncHealthLabel = healthLabel,
                        lastSyncHealthColor = healthColor,
                    )
                }
            }
        }
    }

    private fun observeSpotifyPlaylists() {
        viewModelScope.launch {
            playlistDao.getSpotifyPlaylistsForPreferences().collect { entities ->
                _uiState.update {
                    it.copy(
                        spotifyPlaylists = entities.map { e ->
                            SpotifySyncPlaylist(
                                id = e.id,
                                name = e.name,
                                trackCount = e.trackCount,
                                type = e.type,
                                syncEnabled = e.syncEnabled,
                                artUrl = e.artUrl,
                                hideFromHome = e.hideFromHome,
                            )
                        }
                    )
                }
            }
        }
    }

    private fun observeYouTubePlaylists() {
        viewModelScope.launch {
            playlistDao.getYouTubePlaylistsForPreferences().collect { entities ->
                _uiState.update {
                    it.copy(
                        youTubePlaylists = entities.map { e ->
                            YouTubeSyncPlaylist(
                                id = e.id,
                                name = e.name,
                                trackCount = e.trackCount,
                                type = e.type,
                                syncEnabled = e.syncEnabled,
                                artUrl = e.artUrl,
                                hideFromHome = e.hideFromHome,
                            )
                        }
                    )
                }
            }
        }
    }

    /**
     * Observe the live count of tracks that failed YouTube matching.
     *
     * Drives the amber warning card on the Sync screen so the user always
     * knows when there are songs requiring manual review.
     */
    private fun observeUnmatchedCount() {
        viewModelScope.launch {
            musicRepository.getUnmatchedCount().collect { count ->
                _uiState.update { it.copy(unmatchedCount = count) }
            }
        }
    }

    /**
     * Observe the live count of user-flagged "wrong match" tracks so the
     * Sync-tab review card is reachable even when no sync-side matching
     * failures exist. Without this, tracks flagged from Now Playing had
     * no entry-point surface into Failed Matches and appeared lost.
     */
    private fun observeFlaggedCount() {
        viewModelScope.launch {
            musicRepository.getFlaggedCount().collect { count ->
                _uiState.update { it.copy(flaggedCount = count) }
            }
        }
    }

    /**
     * Mirrors HomeViewModel's `syncStatusFlow` + `musicDataFlow` +
     * `sourceCountsFlow` assembly that originally populated the
     * SyncStatusCard at the top of the Home screen. The card now
     * lives at the top of the Sync screen; this observer keeps it
     * fed with the same flow shape so the relocation introduces no
     * behavioural change.
     *
     * Inputs (all reactive):
     *  - `observeLatestSync()` for last-sync timestamp + display status
     *  - `getTrackCount()` for the "Tracks" stat
     *  - `getSpotifyDownloadedCount()` / `getYouTubeDownloadedCount()`
     *    for the per-source stats
     *  - `librarySizeHolder.size` for storage (disk truth — the Room
     *    `file_size_bytes` SUM is unreliable for legacy libraries)
     */
    private fun observeSyncStatusCard() {
        val syncStatusFlow = musicRepository.observeLatestSync().map { latestSync ->
            if (latestSync != null) {
                SyncStatusInfo(
                    lastSyncTime = latestSync.startedAt.toEpochMilli(),
                    nextSyncTime = latestSync.completedAt?.toEpochMilli()?.plus(6 * 3_600_000L),
                    state = latestSync.status,
                    displayStatus = latestSync.toDisplayStatus(),
                )
            } else {
                SyncStatusInfo(displayStatus = SyncDisplayStatus.Idle)
            }
        }

        viewModelScope.launch {
            combine(
                syncStatusFlow,
                musicRepository.getTrackCount(),
                musicRepository.getSpotifyDownloadedCount(),
                musicRepository.getYouTubeDownloadedCount(),
                librarySizeHolder.size,
            ) { base, trackCount, spotifyCount, youtubeCount, librarySize ->
                base.copy(
                    totalTracks = trackCount,
                    spotifyTracks = spotifyCount,
                    youTubeTracks = youtubeCount,
                    storageUsedBytes = librarySize.totalBytes,
                    flacTracks = librarySize.losslessFileCount,
                    flacStorageBytes = librarySize.losslessBytes,
                )
            }.collect { status ->
                _uiState.update {
                    it.copy(
                        syncStatus = status,
                        hasEverSynced = status.lastSyncTime != null,
                    )
                }
            }
        }
    }

    /** Map Room entity to lightweight presentation model. */
    private fun SyncHistoryEntity.toInfo() = SyncHistoryInfo(
        id = id,
        startedAt = startedAt.toEpochMilli(),
        completedAt = completedAt?.toEpochMilli(),
        status = status.name,
        tracksDownloaded = tracksDownloaded,
        tracksFailed = tracksFailed,
        newTracksFound = newTracksFound,
        playlistsChecked = playlistsChecked,
        bytesDownloaded = bytesDownloaded,
        trigger = trigger,
        streamingMode = streamingMode,
        errorMessage = errorMessage,
        diagnostics = diagnostics,
        displayStatus = toDisplayStatus(),
    )
}
