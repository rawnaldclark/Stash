package com.stash.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthService
import com.stash.core.auth.model.AuthState
import com.stash.core.auth.youtube.YouTubeCookieHelper
import com.stash.core.data.prefs.QualityPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.QualityTier
import dagger.hilt.android.lifecycle.HiltViewModel
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
 * Orchestrates Spotify sp_dc cookie auth, YouTube Music cookie auth, quality selection,
 * and library storage stats. Both Spotify and YouTube use a cookie-paste flow where
 * the user copies cookies from their browser.
 *
 * Audio quality changes are persisted to DataStore via [QualityPreference] so they
 * survive app restarts and are picked up by the download pipeline.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val tokenManager: TokenManager,
    private val musicRepository: MusicRepository,
    private val qualityPreference: QualityPreference,
    private val youTubeCookieHelper: YouTubeCookieHelper,
) : ViewModel() {

    /** Internal mutable UI state that is combined with token-manager flows. */
    private val _localState = MutableStateFlow(LocalState())

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
            showYouTubeCookieDialog = local.showYouTubeCookieDialog,
            showSpotifyCookieDialog = local.showSpotifyCookieDialog,
            spotifyCookieError = local.spotifyCookieError,
            isSpotifyCookieValidating = local.isSpotifyCookieValidating,
            youTubeCookieError = local.youTubeCookieError,
            isYouTubeCookieValidating = local.isYouTubeCookieValidating,
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
    fun onConnectSpotifyWithCookie(cookie: String, username: String = "") {
        if (cookie.isBlank()) {
            _localState.update { it.copy(spotifyCookieError = "Cookie cannot be empty") }
            return
        }

        viewModelScope.launch {
            _localState.update {
                it.copy(isSpotifyCookieValidating = true, spotifyCookieError = null)
            }

            val success = tokenManager.connectSpotifyWithCookie(cookie, username)

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
     * Opens the YouTube Music cookie input dialog.
     */
    fun onConnectYouTube() {
        _localState.update {
            it.copy(
                showYouTubeCookieDialog = true,
                youTubeCookieError = null,
                isYouTubeCookieValidating = false,
            )
        }
    }

    /**
     * Validates the user-provided YouTube Music cookie and connects their account.
     *
     * The cookie must contain a SAPISID or __Secure-3PAPISID value which is
     * used for SAPISIDHASH authentication with the InnerTube API.
     *
     * @param cookie The full cookie string from the user's music.youtube.com browser session.
     */
    fun onConnectYouTubeWithCookie(cookie: String) {
        if (cookie.isBlank()) {
            _localState.update { it.copy(youTubeCookieError = "Cookie cannot be empty") }
            return
        }

        // Pre-validate before sending to TokenManager
        val sapiSid = youTubeCookieHelper.extractSapiSid(cookie)
        if (sapiSid == null) {
            _localState.update {
                it.copy(
                    youTubeCookieError = "Missing SAPISID cookie. Make sure you copied the FULL " +
                        "cookie header from music.youtube.com (Network tab > any request > Cookie header).",
                )
            }
            return
        }

        if (!youTubeCookieHelper.hasLoginInfo(cookie)) {
            _localState.update {
                it.copy(
                    youTubeCookieError = "Missing LOGIN_INFO cookie. yt-dlp requires LOGIN_INFO " +
                        "to download. Make sure you're copying the complete Cookie header, not " +
                        "individual cookies. The header should be one long string with many values " +
                        "separated by semicolons.",
                )
            }
            return
        }

        viewModelScope.launch {
            _localState.update {
                it.copy(isYouTubeCookieValidating = true, youTubeCookieError = null)
            }

            val success = tokenManager.connectYouTubeWithCookie(cookie)

            if (success) {
                _localState.update {
                    it.copy(
                        showYouTubeCookieDialog = false,
                        youTubeCookieError = null,
                        isYouTubeCookieValidating = false,
                    )
                }
            } else {
                _localState.update {
                    it.copy(
                        youTubeCookieError = "Failed to save cookie. Please try again.",
                        isYouTubeCookieValidating = false,
                    )
                }
            }
        }
    }

    /**
     * Dismisses the YouTube cookie input dialog without connecting.
     */
    fun onDismissYouTubeCookieDialog() {
        _localState.update {
            it.copy(
                showYouTubeCookieDialog = false,
                youTubeCookieError = null,
                isYouTubeCookieValidating = false,
            )
        }
    }

    /**
     * Disconnects the YouTube account by clearing stored credentials.
     */
    fun onDisconnectYouTube() {
        viewModelScope.launch {
            tokenManager.clearAuth(AuthService.YOUTUBE_MUSIC)
        }
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
        val showYouTubeCookieDialog: Boolean = false,
        val showSpotifyCookieDialog: Boolean = false,
        val spotifyCookieError: String? = null,
        val isSpotifyCookieValidating: Boolean = false,
        val youTubeCookieError: String? = null,
        val isYouTubeCookieValidating: Boolean = false,
        val youTubeError: String? = null,
    )
}
