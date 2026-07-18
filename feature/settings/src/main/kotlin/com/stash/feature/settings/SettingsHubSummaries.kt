package com.stash.feature.settings

import com.stash.core.auth.model.AuthState
import com.stash.core.model.ThemeMode

/**
 * The current-state subtitles shown under each row of the Settings hub.
 *
 * Each string summarises the live state of its category (e.g. which accounts
 * are connected, the active audio quality) so the hub can show context without
 * the user drilling in.
 */
data class HubSummaries(
    val playback: String,
    val audioQuality: String,
    val accounts: String,
    val libraryStorage: String,
    val appearance: String,
    val about: String,
)

/**
 * Pure derivation of the Settings-hub row subtitles from current state.
 * Kept pure (no BuildConfig / Context reads) so it is unit-testable; the
 * hub passes the app version + the two streaming StateFlow values in.
 */
fun settingsHubSummaries(
    state: SettingsUiState,
    versionName: String,
    streamingEnabled: Boolean,
    streamOnCellular: Boolean,
): HubSummaries {
    val mode = if (streamingEnabled) "Online" else "Offline"
    val cellular = if (streamOnCellular) "Cellular on" else "Cellular off"
    val playback = "$mode · $cellular"

    val audioQuality = if (state.losslessEnabled) {
        val shortLossless = state.losslessQualityTier.displayLabel.substringBefore(" (")
        "Lossless · $shortLossless"
    } else {
        state.audioQuality.label
    }

    val connectedServices = buildList {
        if (state.spotifyAuthState is AuthState.Connected) add("Spotify")
        if (state.youTubeAuthState is AuthState.Connected) add("YouTube")
        if (state.lastFmState is LastFmAuthState.Connected) add("Last.fm")
    }
    val accounts = if (connectedServices.isEmpty()) {
        "Not connected"
    } else {
        connectedServices.joinToString(" · ")
    }

    val tracks = "${state.totalTracks} ${if (state.totalTracks == 1) "track" else "tracks"}"
    val size = formatBytes(state.totalStorageBytes)
    val libraryStorage = "$tracks · $size"

    val appearance = when (state.themeMode) {
        ThemeMode.LIGHT -> "Light"
        ThemeMode.DARK -> "Dark"
        ThemeMode.SYSTEM -> "Follow system"
    }.let { base ->
        // AMOLED only ever applies to dark; a forced-light picker hides it.
        if (state.amoledDark && state.themeMode != ThemeMode.LIGHT) "$base · Pure black" else base
    }

    val about = "v$versionName"

    return HubSummaries(
        playback = playback,
        audioQuality = audioQuality,
        accounts = accounts,
        libraryStorage = libraryStorage,
        appearance = appearance,
        about = about,
    )
}

/**
 * Human-readable byte size (mirrors the private `formatBytes` in
 * `SettingsScreen.kt`). Returns "0 B" for non-positive sizes, otherwise one
 * decimal place over B/KB/MB/GB/TB chosen by log-1024.
 */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val safeIndex = digitGroups.coerceIn(0, units.lastIndex)
    return "%.1f %s".format(bytes / Math.pow(1024.0, safeIndex.toDouble()), units[safeIndex])
}
