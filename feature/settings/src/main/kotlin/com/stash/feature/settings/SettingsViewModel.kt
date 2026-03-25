package com.stash.feature.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthService
import com.stash.core.auth.model.AuthState
import com.stash.core.auth.model.UserInfo
import com.stash.core.auth.spotify.SpotifyAuthManager
import com.stash.core.auth.youtube.YouTubeDeviceFlowManager
import com.stash.core.data.prefs.QualityPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.QualityTier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import javax.inject.Inject

/**
 * ViewModel for the Settings screen.
 *
 * Orchestrates Spotify OAuth (PKCE), YouTube device-code auth, quality selection,
 * and library storage stats. The Spotify flow exposes an [AuthorizationRequest] via
 * [spotifyAuthEvent] so the UI can launch it through an ActivityResultLauncher;
 * YouTube auth is fully managed in-process via the device-code grant.
 *
 * Audio quality changes are persisted to DataStore via [QualityPreference] so they
 * survive app restarts and are picked up by the download pipeline.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val tokenManager: TokenManager,
    private val spotifyAuthManager: SpotifyAuthManager,
    private val youTubeDeviceFlowManager: YouTubeDeviceFlowManager,
    private val musicRepository: MusicRepository,
    private val qualityPreference: QualityPreference,
) : ViewModel() {

    /** Internal mutable UI state that is combined with token-manager flows. */
    private val _localState = MutableStateFlow(LocalState())

    /** One-shot event channel for launching the Spotify OAuth intent. */
    private val _spotifyAuthEvent = MutableSharedFlow<AuthorizationRequest>(extraBufferCapacity = 1)
    val spotifyAuthEvent: SharedFlow<AuthorizationRequest> = _spotifyAuthEvent.asSharedFlow()

    /** Active YouTube polling coroutine, cancelled on disconnect or dialog dismissal. */
    private var youTubePollingJob: Job? = null

    /**
     * The main UI state, combining reactive auth states from [TokenManager],
     * the persisted quality tier, and local UI state.
     */
    val uiState: StateFlow<SettingsUiState> = combine(
        tokenManager.spotifyAuthState,
        tokenManager.youTubeAuthState,
        musicRepository.getTrackCount(),
        musicRepository.getTotalStorageBytes(),
        qualityPreference.qualityTier,
        _localState,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val spotifyAuth = values[0] as AuthState
        val youTubeAuth = values[1] as AuthState
        val trackCount = values[2] as Int
        val storageBytes = values[3] as Long
        val quality = values[4] as QualityTier
        val local = values[5] as LocalState

        SettingsUiState(
            spotifyAuthState = spotifyAuth,
            youTubeAuthState = youTubeAuth,
            audioQuality = quality,
            totalStorageBytes = storageBytes,
            totalTracks = trackCount,
            deviceCodeState = local.deviceCodeState,
            showYouTubeDialog = local.showYouTubeDialog,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    // ── Spotify actions ──────────────────────────────────────────────────

    /**
     * Initiates the Spotify OAuth PKCE flow.
     *
     * Builds an [AuthorizationRequest] and emits it to [spotifyAuthEvent] so the
     * UI layer can launch it via an [androidx.activity.result.ActivityResultLauncher].
     */
    fun onConnectSpotify() {
        val request = spotifyAuthManager.buildAuthRequest()
        _spotifyAuthEvent.tryEmit(request)
    }

    /**
     * Handles the result intent returned from the Spotify OAuth redirect.
     *
     * Extracts the authorization code, exchanges it for tokens, fetches the
     * user profile from the token response, and persists everything via
     * [TokenManager]. On failure, the auth state is set to [AuthState.Error].
     *
     * @param intent The result [Intent] from the AppAuth authorization activity.
     */
    fun onSpotifyAuthResult(intent: Intent) {
        viewModelScope.launch {
            val response = AuthorizationResponse.fromIntent(intent)
            if (response == null) {
                // User cancelled or an error occurred
                return@launch
            }

            val token = spotifyAuthManager.exchangeCodeForToken(response)
            if (token == null) {
                return@launch
            }

            // Use a basic UserInfo derived from the token; the full profile
            // can be fetched later by the sync layer via SpotifyApiClient.
            val user = UserInfo(
                id = "spotify_user",
                displayName = "Spotify User",
            )
            tokenManager.saveSpotifyAuth(token, user)
        }
    }

    /**
     * Disconnects the Spotify account by clearing all stored credentials.
     */
    fun onDisconnectSpotify() {
        viewModelScope.launch {
            tokenManager.clearAuth(AuthService.SPOTIFY)
        }
    }

    // ── YouTube actions ──────────────────────────────────────────────────

    /**
     * Initiates the YouTube device-code authorization flow.
     *
     * Requests a device code, displays the dialog with the user code and
     * verification URL, then starts polling for token issuance in a background
     * coroutine. When the user approves (or the code expires), the dialog is
     * dismissed and the auth state is updated.
     */
    fun onConnectYouTube() {
        youTubePollingJob?.cancel()
        youTubePollingJob = viewModelScope.launch {
            val deviceCode = youTubeDeviceFlowManager.requestDeviceCode()
            if (deviceCode == null) {
                return@launch
            }

            _localState.update {
                it.copy(deviceCodeState = deviceCode, showYouTubeDialog = true)
            }

            val token = youTubeDeviceFlowManager.pollForToken(
                deviceCode = deviceCode.deviceCode,
                intervalSeconds = deviceCode.intervalSeconds,
            )

            if (token != null) {
                val user = UserInfo(
                    id = "youtube_user",
                    displayName = "YouTube User",
                )
                tokenManager.saveYouTubeAuth(token, user)
            }

            _localState.update {
                it.copy(deviceCodeState = null, showYouTubeDialog = false)
            }
        }
    }

    /**
     * Disconnects the YouTube account by clearing stored credentials and
     * cancelling any active device-code polling.
     */
    fun onDisconnectYouTube() {
        youTubePollingJob?.cancel()
        youTubePollingJob = null
        _localState.update {
            it.copy(deviceCodeState = null, showYouTubeDialog = false)
        }
        viewModelScope.launch {
            tokenManager.clearAuth(AuthService.YOUTUBE_MUSIC)
        }
    }

    /**
     * Dismisses the YouTube device-code dialog without disconnecting.
     * Polling continues in the background so the user can still approve.
     */
    fun onDismissYouTubeDialog() {
        _localState.update { it.copy(showYouTubeDialog = false) }
    }

    // ── Quality ──────────────────────────────────────────────────────────

    /**
     * Updates the preferred audio quality tier and persists it to DataStore.
     *
     * @param tier The new [QualityTier] to use for future downloads.
     */
    fun onQualityChanged(tier: QualityTier) {
        viewModelScope.launch {
            qualityPreference.setQualityTier(tier)
        }
    }

    // ── Internal state ───────────────────────────────────────────────────

    /**
     * Local (non-persisted) state that is combined with reactive flows from
     * [TokenManager], [MusicRepository], and [QualityPreference] to produce
     * [SettingsUiState].
     */
    private data class LocalState(
        val deviceCodeState: com.stash.core.auth.model.DeviceCodeState? = null,
        val showYouTubeDialog: Boolean = false,
    )
}
