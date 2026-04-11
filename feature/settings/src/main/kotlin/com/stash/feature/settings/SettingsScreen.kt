package com.stash.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.model.QualityTier
import com.stash.core.model.ThemeMode
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.theme.StashTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.stash.feature.settings.components.AccountConnectionCard
import com.stash.feature.settings.components.SpotifyCookieDialog
import com.stash.feature.settings.components.EqualizerSection
import com.stash.feature.settings.components.YouTubeCookieDialog

/**
 * Top-level Settings screen composable.
 *
 * Provides account connection management for Spotify and YouTube Music,
 * audio quality selection, storage statistics, and app information.
 * Spotify authentication uses the sp_dc cookie approach via [SpotifyCookieDialog].
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Spotify sp_dc cookie input dialog
    if (uiState.showSpotifyCookieDialog) {
        SpotifyCookieDialog(
            isValidating = uiState.isSpotifyCookieValidating,
            errorMessage = uiState.spotifyCookieError,
            onConnect = { cookie, username -> viewModel.onConnectSpotifyWithCookie(cookie, username) },
            onDismiss = viewModel::onDismissSpotifyCookieDialog,
        )
    }

    // YouTube Music cookie input dialog
    if (uiState.showYouTubeCookieDialog) {
        YouTubeCookieDialog(
            isValidating = uiState.isYouTubeCookieValidating,
            errorMessage = uiState.youTubeCookieError,
            onConnect = viewModel::onConnectYouTubeWithCookie,
            onDismiss = viewModel::onDismissYouTubeCookieDialog,
        )
    }

    // YouTube error dialog (missing credentials, network failure, etc.)
    if (uiState.youTubeError != null) {
        AlertDialog(
            onDismissRequest = viewModel::onDismissYouTubeError,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            title = {
                Text(
                    text = "YouTube Music",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    text = uiState.youTubeError!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::onDismissYouTubeError) {
                    Text("OK")
                }
            },
        )
    }

    SettingsContent(
        uiState = uiState,
        onConnectSpotify = viewModel::onConnectSpotify,
        onDisconnectSpotify = viewModel::onDisconnectSpotify,
        onConnectYouTube = viewModel::onConnectYouTube,
        onDisconnectYouTube = viewModel::onDisconnectYouTube,
        onQualityChanged = viewModel::onQualityChanged,
        onThemeChanged = viewModel::onThemeChanged,
        onEqEnabledChanged = viewModel::setEqEnabled,
        onEqPresetSelected = viewModel::setEqPreset,
        onEqBandGainChanged = viewModel::setEqBandGain,
        onBassBoostChanged = viewModel::setBassBoost,
        onVirtualizerChanged = viewModel::setVirtualizer,
        modifier = modifier,
    )
}

/**
 * Stateless inner content for [SettingsScreen], accepting all state and
 * callbacks as parameters for testability and preview support.
 */
@Composable
private fun SettingsContent(
    uiState: SettingsUiState,
    onConnectSpotify: () -> Unit,
    onDisconnectSpotify: () -> Unit,
    onConnectYouTube: () -> Unit,
    onDisconnectYouTube: () -> Unit,
    onQualityChanged: (QualityTier) -> Unit,
    onThemeChanged: (ThemeMode) -> Unit,
    onEqEnabledChanged: (Boolean) -> Unit,
    onEqPresetSelected: (String) -> Unit,
    onEqBandGainChanged: (Int, Float) -> Unit,
    onBassBoostChanged: (Float) -> Unit,
    onVirtualizerChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // -- Header -----------------------------------------------------------
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )

        // -- Accounts section -------------------------------------------------
        SectionHeader(title = "Accounts")

        AccountConnectionCard(
            serviceName = "Spotify",
            icon = Icons.Rounded.MusicNote,
            accentColor = extendedColors.spotifyGreen,
            authState = uiState.spotifyAuthState,
            onConnect = onConnectSpotify,
            onDisconnect = onDisconnectSpotify,
        )

        AccountConnectionCard(
            serviceName = "YouTube Music",
            icon = Icons.Rounded.PlayCircle,
            accentColor = extendedColors.youtubeRed,
            authState = uiState.youTubeAuthState,
            onConnect = onConnectYouTube,
            onDisconnect = onDisconnectYouTube,
        )

        // -- Audio Quality section --------------------------------------------
        SectionHeader(title = "Audio Quality")

        GlassCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
            ) {
                Text(
                    text = "Download quality",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))

                QualityTier.entries.forEach { tier ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = uiState.audioQuality == tier,
                                onClick = { onQualityChanged(tier) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = uiState.audioQuality == tier,
                            onClick = null, // handled by Row's selectable
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.primary,
                                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(
                                text = tier.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "${tier.bitrateKbps} kbps  ~${tier.sizeMbPerMinute} MB/min",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // -- Appearance section -----------------------------------------------
        SectionHeader(title = "Appearance")

        GlassCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
            ) {
                Text(
                    text = "Theme",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))

                val themeOptions = listOf(
                    ThemeMode.LIGHT to "Light",
                    ThemeMode.DARK to "Dark",
                    ThemeMode.SYSTEM to "Follow system",
                )
                themeOptions.forEach { (mode, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = uiState.themeMode == mode,
                                onClick = { onThemeChanged(mode) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = uiState.themeMode == mode,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.primary,
                                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }

        // -- Audio Effects section --------------------------------------------
        SectionHeader(title = "Audio Effects")

        EqualizerSection(
            enabled = uiState.eqEnabled,
            currentPreset = uiState.eqPreset,
            bandFrequencies = uiState.eqBandFrequencies,
            bandGains = uiState.eqBandGains,
            bassBoost = uiState.eqBassBoost,
            virtualizer = uiState.eqVirtualizer,
            onEnabledChanged = onEqEnabledChanged,
            onPresetSelected = onEqPresetSelected,
            onBandGainChanged = onEqBandGainChanged,
            onBassBoostChanged = onBassBoostChanged,
            onVirtualizerChanged = onVirtualizerChanged,
        )

        // -- Storage section --------------------------------------------------
        SectionHeader(title = "Storage")

        GlassCard {
            Column(modifier = Modifier.fillMaxWidth()) {
                StorageRow(label = "Total tracks", value = "${uiState.totalTracks}")
                Spacer(modifier = Modifier.height(8.dp))
                StorageRow(label = "Storage used", value = formatBytes(uiState.totalStorageBytes))
            }
        }

        // -- About section ----------------------------------------------------
        SectionHeader(title = "About")

        GlassCard {
            Column(modifier = Modifier.fillMaxWidth()) {
                StorageRow(label = "Version", value = "1.0.0")
                Spacer(modifier = Modifier.height(8.dp))
                StorageRow(label = "Licenses", value = "Open-source licenses")
            }
        }

        // Bottom padding for navigation bar clearance
        Spacer(modifier = Modifier.height(80.dp))
    }
}

/**
 * A styled section header label.
 */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * A horizontal row displaying a label on the left and a value on the right.
 */
@Composable
private fun StorageRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Formats a byte count into a human-readable string (B, KB, MB, GB).
 */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val safeIndex = digitGroups.coerceIn(0, units.lastIndex)
    return "%.1f %s".format(bytes / Math.pow(1024.0, safeIndex.toDouble()), units[safeIndex])
}
