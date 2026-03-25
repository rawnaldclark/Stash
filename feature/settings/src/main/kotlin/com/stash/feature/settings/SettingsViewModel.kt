package com.stash.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthService
import com.stash.core.auth.model.AuthState
import com.stash.core.auth.model.UserInfo
import com.stash.core.auth.youtube.YouTubeCredentialsStore
import com.stash.core.auth.youtube.YouTubeDeviceFlowManager
import com.stash.core.data.prefs.QualityPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.QualityTier
import dagger.hilt.android.lifecycle.HiltViewModel
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Settings screen.
 *
 * Orchestrates Spotify sp_dc cookie auth, YouTube device-code auth, quality selection,
 * and library storage stats. The Spotify flow displays a dialog for the user to paste
 * their sp_dc cookie; YouTube auth is fully managed in-process via the device-code grant.
 *
 * Audio quality changes are persisted to DataStore via [QualityPreference] so they
 * survive app restarts and are picked up by the download pipeline.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val tokenManager: TokenManager,
    private val youTubeDeviceFlowManager: YouTubeDeviceFlowManager,
    private val youTubeCredentialsStore: YouTubeCredentialsStore,
    private val musicRepository: MusicRepository,
    private val qualityPreference: QualityPreference,
) : ViewModel() {

    /** Internal mutable UI state that is combined with token-manager flows. */
    private val _localState = MutableStateFlow(LocalState())

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
            showYouTubeCredentialsDialog = local.showYouTubeCredentialsDialog,
            showSpotifyCookieDialog = local.showSpotifyCookieDialog,
            spotifyCookieError = local.spotifyCookieError,
            isSpotifyCookieValidating = local.isSpotifyCookieValidating,
            youTubeError = local.youTubeError,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    // -- Spotify actions ------------------------------------------------------

    /**
     * Opens the sp_dc cookie input dialog so the user can paste their cookie.
     */
    fun onConnectSpotify() {
        _localState.update {
            it.copy(
                showSpotifyCookieDialog = true,
                spotifyCookieError = null,
                isSpotifyCookieValidating = false,
            )
        }
    }

    /**
     * Validates the user-provided sp_dc cookie and connects their Spotify account.
     *
     * Calls [TokenManager.connectSpotifyWithCookie] which exchanges the cookie for
     * an access token. On success the dialog is dismissed; on failure an error
     * message is displayed in the dialog.
     *
     * @param cookie The raw sp_dc cookie value pasted by the user.
     */
    fun onConnectSpotifyWithCookie(cookie: String) {
        if (cookie.isBlank()) {
            _localState.update { it.copy(spotifyCookieError = "Cookie cannot be empty") }
            return
        }

        viewModelScope.launch {
            _localState.update {
                it.copy(isSpotifyCookieValidating = true, spotifyCookieError = null)
            }

            val success = tokenManager.connectSpotifyWithCookie(cookie)

            if (success) {
                _localState.update {
                    it.copy(
                        showSpotifyCookieDialog = false,
                        spotifyCookieError = null,
                        isSpotifyCookieValidating = false,
                    )
                }
            } else {
                _localState.update {
                    it.copy(
                        spotifyCookieError = "Invalid or expired sp_dc cookie. Please try again.",
                        isSpotifyCookieValidating = false,
                    )
                }
            }
        }
    }

    /**
     * Dismisses the Spotify cookie input dialog without connecting.
     */
    fun onDismissSpotifyCookieDialog() {
        _localState.update {
            it.copy(
                showSpotifyCookieDialog = false,
                spotifyCookieError = null,
                isSpotifyCookieValidating = false,
            )
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

    // -- YouTube actions ------------------------------------------------------

    /**
     * Initiates the YouTube connection flow.
     *
     * If OAuth credentials (Client ID + Client Secret) are not yet stored,
     * the credentials input dialog is shown first. Otherwise the device-code
     * authorization flow is started directly.
     */
    fun onConnectYouTube() {
        viewModelScope.launch {
            if (youTubeCredentialsStore.hasCredentials()) {
                startYouTubeDeviceFlow()
            } else {
                _localState.update {
                    it.copy(showYouTubeCredentialsDialog = true, youTubeError = null)
                }
            }
        }
    }

    /**
     * Called when the user submits credentials from the [YouTubeCredentialsDialog].
     *
     * Saves the credentials to [YouTubeCredentialsStore], dismisses the credentials
     * dialog, and starts the device-code authorization flow.
     *
     * @param clientId     The Google Cloud OAuth Client ID.
     * @param clientSecret The Google Cloud OAuth Client Secret.
     */
    fun onSaveYouTubeCredentials(clientId: String, clientSecret: String) {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            _localState.update {
                it.copy(youTubeError = "Client ID and Client Secret cannot be empty.")
            }
            return
        }

        viewModelScope.launch {
            youTubeCredentialsStore.saveCredentials(clientId, clientSecret)
            _localState.update { it.copy(showYouTubeCredentialsDialog = false) }
            startYouTubeDeviceFlow()
        }
    }

    /**
     * Dismisses the YouTube credentials input dialog.
     */
    fun onDismissYouTubeCredentialsDialog() {
        _localState.update { it.copy(showYouTubeCredentialsDialog = false) }
    }

    /**
     * Starts the YouTube device-code authorization flow.
     *
     * Requests a device code, displays the dialog with the user code and
     * verification URL, then starts polling for token issuance in a background
     * coroutine. When the user approves (or the code expires), the dialog is
     * dismissed and the auth state is updated.
     */
    private fun startYouTubeDeviceFlow() {
        youTubePollingJob?.cancel()
        youTubePollingJob = viewModelScope.launch {
            try {
                val deviceCode = youTubeDeviceFlowManager.requestDeviceCode()
                if (deviceCode == null) {
                    _localState.update {
                        it.copy(
                            youTubeError = "Failed to start YouTube authorization. " +
                                "Please check your internet connection and API credentials.",
                        )
                    }
                    return@launch
                }

                _localState.update {
                    it.copy(
                        deviceCodeState = deviceCode,
                        showYouTubeDialog = true,
                        youTubeError = null,
                    )
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
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "YouTube connect failed", e)
                _localState.update {
                    it.copy(
                        deviceCodeState = null,
                        showYouTubeDialog = false,
                        youTubeError = "YouTube connection failed: ${e.localizedMessage ?: "Unknown error"}",
                    )
                }
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

    /**
     * Dismisses the YouTube error dialog.
     */
    fun onDismissYouTubeError() {
        _localState.update { it.copy(youTubeError = null) }
    }

    // -- Quality --------------------------------------------------------------

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

    // -- Internal state -------------------------------------------------------

    /**
     * Local (non-persisted) state that is combined with reactive flows from
     * [TokenManager], [MusicRepository], and [QualityPreference] to produce
     * [SettingsUiState].
     */
    private data class LocalState(
        val deviceCodeState: com.stash.core.auth.model.DeviceCodeState? = null,
        val showYouTubeDialog: Boolean = false,
        val showYouTubeCredentialsDialog: Boolean = false,
        val showSpotifyCookieDialog: Boolean = false,
        val spotifyCookieError: String? = null,
        val isSpotifyCookieValidating: Boolean = false,
        val youTubeError: String? = null,
    )
}
