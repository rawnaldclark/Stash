package com.stash.feature.sync

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.sync.SyncPhase
import com.stash.core.data.sync.SyncPreferences
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.model.SyncMode
import com.stash.core.data.sync.SyncScheduler
import com.stash.core.data.sync.SyncStateManager
import com.stash.core.data.sync.toDisplayStatus
import com.stash.core.model.SyncDisplayStatus
import com.stash.data.download.backfill.YtLibraryBackfillWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    val status: String,
    val tracksDownloaded: Int,
    val tracksFailed: Int,
    val newTracksFound: Int = 0,
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
    /** Controls whether mixes refresh or accumulate during sync. */
    val syncMode: SyncMode = SyncMode.REFRESH,
    /** Number of tracks that could not be matched to a YouTube video. */
    val unmatchedCount: Int = 0,
    /**
     * Number of tracks the user flagged from Now Playing as "wrong
     * match." Drives the Sync-tab review card alongside [unmatchedCount]
     * so flagged tracks are reachable even when no sync failures exist.
     */
    val flaggedCount: Int = 0,
)

/**
 * ViewModel backing the Sync screen.
 *
 * Combines live sync phase, user preferences, auth state for each service,
 * and recent sync history into a single reactive [SyncUiState].
 */
@HiltViewModel
class SyncViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val syncScheduler: SyncScheduler,
    private val syncStateManager: SyncStateManager,
    private val syncPreferencesManager: SyncPreferencesManager,
    private val tokenManager: TokenManager,
    private val syncHistoryDao: SyncHistoryDao,
    private val playlistDao: com.stash.core.data.db.dao.PlaylistDao,
    private val musicRepository: com.stash.core.data.repository.MusicRepository,
) : ViewModel() {

    /**
     * Reactive count of blocked songs, displayed as a badge on the
     * "Blocked Songs" row in the Sync screen's Library section.
     * Moved here from SettingsViewModel in Phase 8 so Library actions
     * are grouped with Sync-adjacent maintenance tasks.
     */
    val blockedCount: StateFlow<Int> =
        musicRepository.getBlacklistedCount()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = 0,
            )

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    init {
        observeSyncPhase()
        observePreferences()
        observeSyncMode()
        observeAuthStates()
        observeHistory()
        observeSpotifyPlaylists()
        observeYouTubePlaylists()
        observeUnmatchedCount()
        observeFlaggedCount()
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
    /**
     * Enqueue a one-shot [YtLibraryBackfillWorker] run. Walks YT-source
     * tracks with music-video title markers, verifies each videoId via
     * InnerTube's player endpoint, and either reschedules OMVs for
     * re-download or refreshes stale-metadata ATVs in place.
     *
     * `ExistingWorkPolicy.KEEP` — if a backfill is already queued or
     * running, a second tap is a no-op rather than replacing it.
     */
    fun onRunYtLibraryBackfill() {
        val work = OneTimeWorkRequestBuilder<YtLibraryBackfillWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .addTag("yt_library_backfill")
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            YtLibraryBackfillWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            work,
        )
    }

    fun onStopSync() {
        syncScheduler.cancelSync()
    }

    /** Toggle sync_enabled for a specific Spotify playlist. */
    fun onTogglePlaylistSync(playlistId: Long, enabled: Boolean) {
        viewModelScope.launch {
            playlistDao.updateSyncEnabled(playlistId, enabled)
        }
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
                syncScheduler.scheduleDailySync(hour, minute, wifiOnly = prefs.wifiOnly)
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

    /** Switch between REFRESH and ACCUMULATE sync modes. */
    fun onSyncModeChanged(mode: SyncMode) {
        viewModelScope.launch {
            syncPreferencesManager.setSyncMode(mode)
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
            syncPreferencesManager.syncMode.collect { mode ->
                _uiState.update { it.copy(syncMode = mode) }
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

    /** Map Room entity to lightweight presentation model. */
    private fun SyncHistoryEntity.toInfo() = SyncHistoryInfo(
        id = id,
        startedAt = startedAt.toEpochMilli(),
        status = status.name,
        tracksDownloaded = tracksDownloaded,
        tracksFailed = tracksFailed,
        newTracksFound = newTracksFound,
        errorMessage = errorMessage,
        diagnostics = diagnostics,
        displayStatus = toDisplayStatus(),
    )
}

// Extension function kept outside the class for the same reason the
// SyncHistoryEntity.toInfo() mapper above lives outside — it's a pure
// shape transform with no ViewModel state dependency. The WorkManager
// enqueue logic below, however, lives INSIDE the class because it
// needs `appContext`.
