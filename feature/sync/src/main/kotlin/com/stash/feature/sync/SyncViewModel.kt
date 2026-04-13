package com.stash.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.sync.SyncPhase
import com.stash.core.data.sync.SyncPreferences
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.data.sync.SyncScheduler
import com.stash.core.data.sync.SyncStateManager
import com.stash.core.data.sync.toDisplayStatus
import com.stash.core.model.SyncDisplayStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    init {
        observeSyncPhase()
        observePreferences()
        observeAuthStates()
        observeHistory()
        observeSpotifyPlaylists()
    }

    // -- Public actions -------------------------------------------------------

    /** Trigger an immediate sync, replacing any pending schedule. */
    fun onSyncNow() {
        syncScheduler.triggerManualSync()
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
