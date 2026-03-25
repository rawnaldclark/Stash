package com.stash.feature.settings

import com.stash.core.auth.model.AuthState
import com.stash.core.auth.model.DeviceCodeState
import com.stash.core.model.QualityTier

/**
 * Immutable UI state for the Settings screen.
 *
 * @property spotifyAuthState Current Spotify authentication lifecycle state.
 * @property youTubeAuthState Current YouTube Music authentication lifecycle state.
 * @property audioQuality Selected download / streaming quality tier.
 * @property totalStorageBytes Total bytes used by downloaded tracks on disk.
 * @property totalTracks Number of tracks currently stored in the library.
 * @property deviceCodeState Non-null when the YouTube device-code auth flow is active.
 * @property showYouTubeDialog Whether the YouTube device-code dialog should be visible.
 * @property showSpotifyCookieDialog Whether the Spotify sp_dc cookie input dialog should be visible.
 * @property spotifyCookieError Error message to display in the cookie dialog, or null if none.
 * @property isSpotifyCookieValidating Whether the sp_dc cookie is currently being validated.
 * @property showYouTubeCredentialsDialog Whether the YouTube credentials input dialog should be visible.
 */
data class SettingsUiState(
    val spotifyAuthState: AuthState = AuthState.NotConnected,
    val youTubeAuthState: AuthState = AuthState.NotConnected,
    val audioQuality: QualityTier = QualityTier.BEST,
    val totalStorageBytes: Long = 0,
    val totalTracks: Int = 0,
    val deviceCodeState: DeviceCodeState? = null,
    val showYouTubeDialog: Boolean = false,
    val showYouTubeCredentialsDialog: Boolean = false,
    val showSpotifyCookieDialog: Boolean = false,
    val spotifyCookieError: String? = null,
    val isSpotifyCookieValidating: Boolean = false,
    val youTubeError: String? = null,
)
