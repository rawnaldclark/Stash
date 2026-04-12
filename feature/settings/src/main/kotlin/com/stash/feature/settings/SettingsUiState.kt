package com.stash.feature.settings

import com.stash.core.auth.model.AuthState
import com.stash.core.model.QualityTier
import com.stash.core.model.ThemeMode

/**
 * Immutable UI state for the Settings screen.
 *
 * @property spotifyAuthState Current Spotify authentication lifecycle state.
 * @property youTubeAuthState Current YouTube Music authentication lifecycle state.
 * @property audioQuality Selected download / streaming quality tier.
 * @property totalStorageBytes Total bytes used by downloaded tracks on disk.
 * @property totalTracks Number of tracks currently stored in the library.
 * @property showYouTubeCookieDialog Whether the YouTube cookie input dialog should be visible.
 * @property showSpotifyCookieDialog Whether the Spotify sp_dc cookie input dialog should be visible.
 * @property spotifyCookieError Error message to display in the Spotify cookie dialog, or null if none.
 * @property isSpotifyCookieValidating Whether the sp_dc cookie is currently being validated.
 * @property youTubeCookieError Error message to display in the YouTube cookie dialog, or null if none.
 * @property isYouTubeCookieValidating Whether the YouTube cookie is currently being validated.
 * @property eqEnabled Whether the equalizer effect chain is active.
 * @property eqPreset Name of the currently selected equalizer preset.
 * @property eqBandFrequencies Display labels for each EQ band (e.g. "60Hz").
 * @property eqBandGains Normalized gain (0.0..1.0) for each band; 0.5 is flat.
 * @property eqBassBoost Normalized bass boost strength (0.0..1.0).
 * @property eqVirtualizer Normalized virtualizer / surround strength (0.0..1.0).
 */
data class SettingsUiState(
    val spotifyAuthState: AuthState = AuthState.NotConnected,
    val youTubeAuthState: AuthState = AuthState.NotConnected,
    val audioQuality: QualityTier = QualityTier.BEST,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val totalStorageBytes: Long = 0,
    val totalTracks: Int = 0,
    val showYouTubeCookieDialog: Boolean = false,
    val showSpotifyWebLogin: Boolean = false,
    val showSpotifyCookieDialog: Boolean = false,
    val spotifyCookieError: String? = null,
    val isSpotifyCookieValidating: Boolean = false,
    val youTubeCookieError: String? = null,
    val isYouTubeCookieValidating: Boolean = false,
    val youTubeError: String? = null,
    val eqEnabled: Boolean = false,
    val eqPreset: String = "Flat",
    val eqBandFrequencies: List<String> = listOf("60Hz", "250Hz", "1kHz", "4kHz", "16kHz"),
    val eqBandGains: List<Float> = listOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f),
    val eqBassBoost: Float = 0f,
    val eqVirtualizer: Float = 0f,
)
