package com.stash.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.stash.core.data.sync.workers.UpdateCheckWorker
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

    // Spotify WebView login (full-screen overlay)
    if (uiState.showSpotifyWebLogin) {
        com.stash.feature.settings.components.SpotifyLoginWebView(
            onCookieExtracted = viewModel::onSpotifyWebLoginCookieExtracted,
            onDismiss = viewModel::onDismissSpotifyWebLogin,
            onManualFallback = viewModel::onConnectSpotifyManual,
        )
        return // Full-screen WebView replaces the Settings content
    }

    // Spotify sp_dc cookie input dialog (manual fallback)
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
        onSetExternalStorage = viewModel::setExternalStorageUri,
        onStartMoveLibrary = viewModel::startMoveLibrary,
        onCancelMoveLibrary = viewModel::cancelMoveLibrary,
        onDismissMoveLibrary = viewModel::dismissMoveLibrary,
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
    onSetExternalStorage: (android.net.Uri?) -> Unit,
    onStartMoveLibrary: (android.net.Uri) -> Unit,
    onCancelMoveLibrary: () -> Unit,
    onDismissMoveLibrary: () -> Unit,
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

        // -- Support section --------------------------------------------------
        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

        SectionHeader(title = "Support Stash")

        GlassCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "If Stash replaced a subscription for you, consider supporting the project.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    androidx.compose.material3.OutlinedButton(
                        onClick = { uriHandler.openUri("https://ko-fi.com/rawnald") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FavoriteBorder,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Donate", style = MaterialTheme.typography.labelMedium)
                    }

                    androidx.compose.material3.OutlinedButton(
                        onClick = { uriHandler.openUri("https://github.com/rawnaldclark/Stash") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Star", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

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

        val storageContext = LocalContext.current
        val contentResolver = storageContext.contentResolver
        // Tracks what action the user intended when they tapped the folder
        // picker. "SetOnly" = just pick a destination for new downloads.
        // "SetAndMove" = pick destination AND auto-start the library move
        // once the URI is saved (single tap flow for users with existing
        // libraries who want to migrate).
        var pendingPickerIntent by remember { mutableStateOf(PickerIntent.SetOnly) }
        val treePicker = androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                // Take a persistable permission BEFORE handing the URI to the
                // VM — without this, the permission is revoked when the app
                // is backgrounded and the persisted URI becomes useless.
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                onSetExternalStorage(uri)
                if (pendingPickerIntent == PickerIntent.SetAndMove) {
                    onStartMoveLibrary(uri)
                }
            }
            pendingPickerIntent = PickerIntent.SetOnly
        }

        val externalTree = uiState.externalTreeUri
        // Derive a human-readable folder name from the tree URI without
        // pulling the documentfile dep into this module. Tree URIs look like
        // `content://com.android.externalstorage.documents/tree/primary%3AMusic%2FStash`
        // — after decoding, the last path segment after the colon is the
        // visible folder.
        val externalFolderName = remember(externalTree) {
            externalTree?.lastPathSegment
                ?.substringAfterLast(':', "")
                ?.substringAfterLast('/', "")
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                ?.takeIf { it.isNotBlank() }
                ?: externalTree?.let { "External folder" }
                ?: ""
        }
        val internalPath = remember(storageContext) {
            java.io.File(storageContext.filesDir, "music").absolutePath
        }

        GlassCard {
            Column(modifier = Modifier.fillMaxWidth()) {
                StorageRow(label = "Total tracks", value = "${uiState.totalTracks}")
                Spacer(modifier = Modifier.height(8.dp))
                StorageRow(label = "Storage used", value = formatBytes(uiState.totalStorageBytes))
                Spacer(modifier = Modifier.height(12.dp))
                androidx.compose.material3.HorizontalDivider(
                    color = extendedColors.glassBorder,
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Current location ----------------------------------------
                Text(
                    text = "Download location",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when {
                        externalTree == null -> "Internal (app-private)"
                        externalFolderName.isBlank() -> "External folder (SD card / USB)"
                        else -> externalFolderName
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (externalTree != null) {
                        "Tracks are stored in this folder and survive uninstall. Visible to other apps and over USB."
                    } else {
                        internalPath
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Actions -------------------------------------------------
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    androidx.compose.material3.OutlinedButton(
                        onClick = { treePicker.launch(null) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Text(
                            text = if (externalTree != null) "Change folder" else "Pick SD / folder",
                        )
                    }
                    if (externalTree != null) {
                        androidx.compose.material3.OutlinedButton(
                            onClick = { onSetExternalStorage(null) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Text("Use internal")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "New downloads go to the selected location. Use \"Move library\" below to transfer existing tracks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Move library — always visible. When no external folder has
                // been picked yet, the Start button launches the picker and
                // auto-starts the move in a single tap flow.
                Spacer(modifier = Modifier.height(12.dp))
                androidx.compose.material3.HorizontalDivider(
                    color = extendedColors.glassBorder,
                )
                Spacer(modifier = Modifier.height(12.dp))
                MoveLibrarySection(
                    state = uiState.moveLibraryState,
                    hasExternalFolder = externalTree != null,
                    onStart = {
                        if (externalTree != null) {
                            onStartMoveLibrary(externalTree)
                        } else {
                            pendingPickerIntent = PickerIntent.SetAndMove
                            treePicker.launch(null)
                        }
                    },
                    onCancel = onCancelMoveLibrary,
                    onDismiss = onDismissMoveLibrary,
                )
            }
        }

        // -- About section ----------------------------------------------------
        SectionHeader(title = "About")

        val aboutContext = LocalContext.current
        val installedVersion = remember(aboutContext) {
            runCatching {
                aboutContext.packageManager
                    .getPackageInfo(aboutContext.packageName, 0)
                    .versionName
            }.getOrNull() ?: "0.3.4-beta.2"
        }

        GlassCard {
            Column(modifier = Modifier.fillMaxWidth()) {
                StorageRow(label = "Version", value = installedVersion)
                Spacer(modifier = Modifier.height(8.dp))
                StorageRow(label = "License", value = "GPL-3.0")
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        UpdateCheckWorker.enqueueOneTimeCheck(aboutContext)
                        Toast.makeText(
                            aboutContext,
                            "Checking for updates\u2026",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text("Check for updates")
                }
            }
        }

        // Bottom padding for navigation bar clearance
        Spacer(modifier = Modifier.height(80.dp))
    }
}

/**
 * Tracks what the user meant when they tapped the SAF folder-picker.
 * [SetOnly] = redirect new downloads; [SetAndMove] = also start the
 * library migration to the picked folder as soon as it's granted.
 */
private enum class PickerIntent { SetOnly, SetAndMove }

/**
 * Renders the "Move existing library" action inside the Storage card.
 *
 * Shows four visual states driven by the underlying
 * [com.stash.data.download.files.MoveLibraryState]:
 * - **Idle** — prompt + "Move library to this folder" button.
 * - **Running(c, t)** — live progress ("Moving c of t...") + linear bar + Cancel.
 * - **Done(moved, failed)** — result summary + Dismiss.
 * - **Error(msg)** — error text + Dismiss.
 */
@Composable
private fun MoveLibrarySection(
    state: com.stash.data.download.files.MoveLibraryState,
    hasExternalFolder: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        com.stash.data.download.files.MoveLibraryState.Idle -> {
            Text(
                text = "Existing library",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (hasExternalFolder) {
                    "Move tracks already on your device into the folder above so they're accessible over USB too."
                } else {
                    "Move tracks already on your device to an external folder (SD / USB) so you can access them over USB too. You'll pick the destination next."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.OutlinedButton(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(
                    text = if (hasExternalFolder) "Move library to this folder"
                    else "Pick destination and move library",
                )
            }
        }
        is com.stash.data.download.files.MoveLibraryState.Running -> {
            Text(
                text = "Moving ${state.current} of ${state.total}...",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            androidx.compose.material3.LinearProgressIndicator(
                progress = {
                    if (state.total == 0) 0f
                    else state.current.toFloat() / state.total.toFloat()
                },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.TextButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Cancel")
            }
        }
        is com.stash.data.download.files.MoveLibraryState.Done -> {
            Text(
                text = buildString {
                    append("Moved ${state.moved} track")
                    if (state.moved != 1) append("s")
                    if (state.failed > 0) {
                        append(" • ${state.failed} failed")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (state.failed > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Failed tracks stay in internal storage. Try again later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Dismiss")
            }
        }
        is com.stash.data.download.files.MoveLibraryState.Error -> {
            Text(
                text = "Couldn't move library",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Dismiss")
            }
        }
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
