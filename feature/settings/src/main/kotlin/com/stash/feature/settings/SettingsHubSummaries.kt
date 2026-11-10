package com.stash.feature.settings

import android.content.res.Resources
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
 * Reads strings through [res] so it supports localization and can be unit-tested.
 */
fun settingsHubSummaries(
    state: SettingsUiState,
    versionName: String,
    streamingEnabled: Boolean,
    streamOnCellular: Boolean,
    /** Connected accounts that live outside [SettingsUiState] (ListenBrainz, cloud storage). */
    otherAccounts: List<String> = emptyList(),
    res: Resources,
): HubSummaries {
    val mode = if (streamingEnabled) res.getString(R.string.settings_mode_online) else res.getString(R.string.settings_mode_offline)
    val cellular = if (streamOnCellular) res.getString(R.string.settings_summary_cellular_on) else res.getString(R.string.settings_summary_cellular_off)
    val playback = "$mode · $cellular"

    // What downloads save (Download quality); streaming's Lossless switch is inside the screen.
    val audioQuality = when (state.downloadMode) {
        com.stash.core.model.DownloadMode.LOSSLESS -> {
            val shortLossless = state.losslessQualityTier.displayLabel.substringBefore(" (")
            res.getString(R.string.settings_summary_lossless, shortLossless)
        }
        com.stash.core.model.DownloadMode.AAC -> "AAC 320"
        com.stash.core.model.DownloadMode.OPUS -> res.getString(R.string.settings_summary_opus, state.audioQuality.label)
    }

    val connectedServices = buildList {
        if (state.spotifyAuthState is AuthState.Connected) add("Spotify")
        if (state.youTubeAuthState is AuthState.Connected) add("YouTube")
        if (state.discordAuthState is AuthState.Connected) add("Discord")
        if (state.lastFmState is LastFmAuthState.Connected) add("Last.fm")
        addAll(otherAccounts)
    }
    // Names while they fit on the row's one line; a count past that.
    val accounts = when {
        connectedServices.isEmpty() -> res.getString(R.string.settings_account_not_connected)
        connectedServices.size <= 3 -> connectedServices.joinToString(" · ")
        else -> res.getString(R.string.settings_summary_connected_count, connectedServices.size)
    }

    val tracks = res.getQuantityString(R.plurals.settings_summary_tracks_count, state.totalTracks, state.totalTracks)
    val size = formatBytes(state.totalStorageBytes)
    val libraryStorage = "$tracks · $size"

    val appearance = when (state.themeMode) {
        ThemeMode.LIGHT -> res.getString(R.string.settings_theme_light)
        ThemeMode.DARK -> res.getString(R.string.settings_theme_dark)
        ThemeMode.SYSTEM -> res.getString(R.string.settings_theme_follow_system)
    }.let { base ->
        // AMOLED only ever applies to dark; a forced-light picker hides it.
        if (state.amoledDark && state.themeMode != ThemeMode.LIGHT) "$base · ${res.getString(R.string.settings_summary_pure_black)}" else base
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
