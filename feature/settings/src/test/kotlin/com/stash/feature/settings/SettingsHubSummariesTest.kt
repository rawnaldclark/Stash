package com.stash.feature.settings

import com.stash.core.auth.model.AuthState
import com.stash.core.auth.model.UserInfo
import com.stash.core.model.QualityTier
import com.stash.core.model.ThemeMode
import com.stash.data.download.lossless.LosslessQualityTier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [settingsHubSummaries] — the pure derivation of the Settings-hub
 * row subtitles from current app state. Kept pure (no BuildConfig / Context)
 * precisely so it can be unit-tested here.
 */
class SettingsHubSummariesTest {

    private fun connected(name: String = "user") =
        AuthState.Connected(UserInfo(id = name, displayName = name))

    // --- playback ---------------------------------------------------------

    @Test fun `playback online + cellular off`() {
        val s = settingsHubSummaries(
            state = SettingsUiState(),
            versionName = "0.9.51",
            streamingEnabled = true,
            streamOnCellular = false,
        )
        assertEquals("Online · Cellular off", s.playback)
    }

    @Test fun `playback offline + cellular on`() {
        val s = settingsHubSummaries(
            state = SettingsUiState(),
            versionName = "0.9.51",
            streamingEnabled = false,
            streamOnCellular = true,
        )
        assertEquals("Offline · Cellular on", s.playback)
    }

    // --- audioQuality -----------------------------------------------------

    @Test fun `audio lossless on with MAX tier → Lossless · Max`() {
        val s = settingsHubSummaries(
            state = SettingsUiState(
                losslessEnabled = true,
                losslessQualityTier = LosslessQualityTier.MAX,
            ),
            versionName = "0.9.51",
            streamingEnabled = true,
            streamOnCellular = false,
        )
        assertEquals("Lossless · Max", s.audioQuality)
    }

    @Test fun `audio lossless on with HI_RES tier → Lossless · Hi-Res`() {
        val s = settingsHubSummaries(
            state = SettingsUiState(
                losslessEnabled = true,
                losslessQualityTier = LosslessQualityTier.HI_RES,
            ),
            versionName = "0.9.51",
            streamingEnabled = true,
            streamOnCellular = false,
        )
        assertEquals("Lossless · Hi-Res", s.audioQuality)
    }

    @Test fun `audio lossless off → audioQuality label`() {
        val s = settingsHubSummaries(
            state = SettingsUiState(
                losslessEnabled = false,
                audioQuality = QualityTier.MAX,
            ),
            versionName = "0.9.51",
            streamingEnabled = true,
            streamOnCellular = false,
        )
        assertEquals("Max", s.audioQuality)
    }

    // --- accounts ---------------------------------------------------------

    @Test fun `accounts Spotify + YouTube connected → joined`() {
        val s = settingsHubSummaries(
            state = SettingsUiState(
                spotifyAuthState = connected("spotify"),
                youTubeAuthState = connected("yt"),
            ),
            versionName = "0.9.51",
            streamingEnabled = true,
            streamOnCellular = false,
        )
        assertEquals("Spotify · YouTube", s.accounts)
    }

    @Test fun `accounts all three connected → ordered join`() {
        val s = settingsHubSummaries(
            state = SettingsUiState(
                spotifyAuthState = connected("spotify"),
                youTubeAuthState = connected("yt"),
                lastFmState = LastFmAuthState.Connected(username = "scrobbler", pendingScrobbles = 0),
            ),
            versionName = "0.9.51",
            streamingEnabled = true,
            streamOnCellular = false,
        )
        assertEquals("Spotify · YouTube · Last.fm", s.accounts)
    }

    @Test fun `accounts none connected → Not connected`() {
        val s = settingsHubSummaries(
            state = SettingsUiState(),
            versionName = "0.9.51",
            streamingEnabled = true,
            streamOnCellular = false,
        )
        assertEquals("Not connected", s.accounts)
    }

    // --- libraryStorage ---------------------------------------------------

    @Test fun `libraryStorage default → 0 tracks 0 B`() {
        val s = settingsHubSummaries(
            state = SettingsUiState(),
            versionName = "0.9.51",
            streamingEnabled = true,
            streamOnCellular = false,
        )
        assertEquals("0 tracks · 0 B", s.libraryStorage)
    }

    @Test fun `libraryStorage single track is singular`() {
        val s = settingsHubSummaries(
            state = SettingsUiState(totalTracks = 1, totalStorageBytes = 1536L),
            versionName = "0.9.51",
            streamingEnabled = true,
            streamOnCellular = false,
        )
        assertEquals("1 track · 1.5 KB", s.libraryStorage)
    }

    // --- appearance -------------------------------------------------------

    @Test fun `appearance SYSTEM → Follow system`() {
        val s = settingsHubSummaries(
            state = SettingsUiState(themeMode = ThemeMode.SYSTEM),
            versionName = "0.9.51",
            streamingEnabled = true,
            streamOnCellular = false,
        )
        assertEquals("Follow system", s.appearance)
    }

    @Test fun `appearance DARK → Dark`() {
        val s = settingsHubSummaries(
            state = SettingsUiState(themeMode = ThemeMode.DARK),
            versionName = "0.9.51",
            streamingEnabled = true,
            streamOnCellular = false,
        )
        assertEquals("Dark", s.appearance)
    }

    @Test fun `appearance DARK plus AMOLED → Dark · Pure black`() {
        val s = settingsHubSummaries(
            state = SettingsUiState(themeMode = ThemeMode.DARK, amoledDark = true),
            versionName = "0.9.51",
            streamingEnabled = true,
            streamOnCellular = false,
        )
        assertEquals("Dark · Pure black", s.appearance)
    }

    @Test fun `appearance LIGHT hides dormant AMOLED flag`() {
        val s = settingsHubSummaries(
            state = SettingsUiState(themeMode = ThemeMode.LIGHT, amoledDark = true),
            versionName = "0.9.51",
            streamingEnabled = true,
            streamOnCellular = false,
        )
        assertEquals("Light", s.appearance)
    }

    // --- about ------------------------------------------------------------

    @Test fun `about prefixes v to versionName`() {
        val s = settingsHubSummaries(
            state = SettingsUiState(),
            versionName = "0.9.51",
            streamingEnabled = true,
            streamOnCellular = false,
        )
        assertEquals("v0.9.51", s.about)
    }
}
