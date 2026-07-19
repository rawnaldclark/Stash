package com.stash.feature.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.model.DownloadNetworkMode
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.settings.components.SettingsGroupCard
import com.stash.feature.settings.components.SettingsNavRow
import com.stash.feature.settings.components.SettingsScaffold
import com.stash.feature.settings.components.SettingsSectionLabel
import com.stash.feature.settings.components.SettingsToggleRow
import com.stash.feature.settings.components.SettingsValueRow

/**
 * The Library & Storage spoke of the hub-and-spoke Settings redesign.
 *
 * Behavior-preserving relocation of the Downloads-network-mode picker, the
 * Stash Mixes (beta) toggle, the Library Health drilldown, and the full
 * Storage section (database backup, storage meter, download-location picker,
 * and the move-library state machine) from the monolithic [SettingsScreen].
 *
 * This is the most STATEFUL relocation: it re-homes the canonical host-level
 * [treePicker] (which handles all three [LibStoragePickerIntent]s including the
 * import-restore restart-kill), the export/import activity-result launchers,
 * and both backup dialogs verbatim. Every control calls the SAME
 * [SettingsViewModel] method the old screen used; no logic is changed.
 *
 * Transient duplication: [LibStoragePickerIntent], [LibStorageMoveLibrarySection],
 * and [libStorageFormatBytes] are copied verbatim from [SettingsScreen] (only
 * renamed with a `LibStorage`/`libStorage` prefix — top-level `private`
 * declarations still collide on name across files in the same package, so the
 * monolith's `PickerIntent` / `MoveLibrarySection` / `formatBytes` would clash).
 * The monolith keeps its copies until the Task D1 cutover deletes them there.
 */
@Composable
fun SettingsLibraryStorageScreen(
    onBack: () -> Unit,
    onNavigateToLibraryHealth: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val extendedColors = StashTheme.extendedColors

    LaunchedEffect(viewModel) {
        viewModel.refreshStorageUsage()
    }

    SettingsScaffold(title = "Library & Storage", onBack = onBack, modifier = modifier) {
        val context = LocalContext.current
        val contentResolver = context.contentResolver
        // Tracks what action the user intended when they tapped the folder
        // picker. "SetOnly" = redirect new downloads; "SetAndMove" = also start
        // the library migration to the picked folder as soon as it's granted;
        // "SetAndRestart" = an import-restore re-grant, restart after.
        var pendingLibStoragePickerIntent by remember { mutableStateOf(LibStoragePickerIntent.SetOnly) }
        val treePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                // Take a persistable permission BEFORE handing the URI to the
                // VM — without this, the permission is revoked when the app
                // is backgrounded and the persisted URI becomes useless.
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                viewModel.setExternalStorageUri(uri)
                when (pendingLibStoragePickerIntent) {
                    LibStoragePickerIntent.SetAndMove -> viewModel.startMoveLibrary(uri)
                    LibStoragePickerIntent.SetAndRestart -> {
                        // Success: permission re-granted for the restored folder.
                        // We can now safely restart the app.
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                    LibStoragePickerIntent.SetOnly -> Unit
                }
            }
            pendingLibStoragePickerIntent = LibStoragePickerIntent.SetOnly
        }
        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip"),
        ) { uri -> if (uri != null) viewModel.onExportDatabase(uri) }
        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri -> if (uri != null) viewModel.onImportDatabase(uri) }

        // -- Downloads --------------------------------------------------------
        SettingsSectionLabel("Downloads")

        GlassCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
            ) {
                Text(
                    text = "Run recommendations when",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))

                DownloadNetworkMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = uiState.downloadNetworkMode == mode,
                                onClick = { viewModel.onDownloadNetworkModeChanged(mode) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = uiState.downloadNetworkMode == mode,
                            onClick = null, // handled by Row's selectable
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.primary,
                                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = mode.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // -- Stash Mixes (beta) toggle ----------------------------------------
        SettingsGroupCard(
            rows = listOf(
                {
                    SettingsToggleRow(
                        title = "Stash Mixes (beta)",
                        subtitle = if (uiState.stashMixesEnabled) {
                            "Daily Discover, Deep Cuts, and First Listen mixes auto-refresh in the background."
                        } else {
                            "Auto-generated mix playlists are hidden. Background discovery downloads are off."
                        },
                        checked = uiState.stashMixesEnabled,
                        onCheckedChange = viewModel::onStashMixesEnabledChanged,
                    )
                },
                {
                    SettingsToggleRow(
                        title = "Qobuz discovery on Home",
                        subtitle = if (uiState.qobuzDiscoveryEnabled) {
                            "New Releases, Qobuz Playlists, and Top Albums show on Home."
                        } else {
                            "Home keeps only your mixes and imported music."
                        },
                        checked = uiState.qobuzDiscoveryEnabled,
                        onCheckedChange = viewModel::onQobuzDiscoveryEnabledChanged,
                    )
                },
            ),
        )

        // -- Library Health ---------------------------------------------------
        SettingsSectionLabel("Library")
        SettingsGroupCard(
            rows = listOf(
                {
                    SettingsNavRow(
                        title = "Library Health",
                        onClick = onNavigateToLibraryHealth,
                    )
                },
            ),
        )

        // -- Storage ----------------------------------------------------------
        SettingsSectionLabel("Storage")

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
        val internalPath = remember(context) {
            java.io.File(context.filesDir, "music").absolutePath
        }

        val databaseBackupState = uiState.databaseBackupState
        if (databaseBackupState !is DatabaseBackupState.Idle) {
            AlertDialog(
                onDismissRequest = {
                    if (databaseBackupState !is DatabaseBackupState.Exporting &&
                        databaseBackupState !is DatabaseBackupState.Importing) {
                        viewModel.onDismissDatabaseBackupStatus()
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large,
                title = {
                    Text(
                        text = when (databaseBackupState) {
                            DatabaseBackupState.Exporting -> "Exporting Database"
                            DatabaseBackupState.Importing -> "Importing Database"
                            is DatabaseBackupState.Success -> "Success"
                            is DatabaseBackupState.Error -> "Error"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (val state = databaseBackupState) {
                            DatabaseBackupState.Exporting, DatabaseBackupState.Importing -> {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    text = "Processing...",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            is DatabaseBackupState.Success -> {
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            is DatabaseBackupState.Error -> {
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    if (databaseBackupState is DatabaseBackupState.Success ||
                        databaseBackupState is DatabaseBackupState.Error) {
                        TextButton(onClick = viewModel::onDismissDatabaseBackupStatus) {
                            Text("OK")
                        }
                    }
                },
            )
        }

        if (uiState.showImportConfirmation) {
            AlertDialog(
                onDismissRequest = viewModel::onCancelImportDatabase,
                containerColor = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large,
                title = {
                    Text(
                        text = "Overwrite Library & Settings?",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                text = {
                    Text(
                        text = "This will completely replace your existing library metadata, playlists, settings, and account connections. This action cannot be undone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.onConfirmImportDatabase { restoredUri ->
                                pendingLibStoragePickerIntent = LibStoragePickerIntent.SetAndRestart
                                treePicker.launch(restoredUri)
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text("Overwrite")
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::onCancelImportDatabase) {
                        Text("Cancel")
                    }
                },
            )
        }

        GlassCard {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Database Backup -----------------------------------------
                Text(
                    text = "Backup",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Export settings and database or import from a previous backup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { exportLauncher.launch("stash-backup.zip") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Text("Export Backup")
                    }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Text("Import Backup")
                    }
                }
            }
        }

        GlassCard {
            Column(modifier = Modifier.fillMaxWidth()) {
                SettingsValueRow(label = "Total tracks", value = "${uiState.totalTracks}")
                Spacer(modifier = Modifier.height(8.dp))
                SettingsValueRow(label = "Storage used", value = libStorageFormatBytes(uiState.totalStorageBytes))
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(
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
                    OutlinedButton(
                        onClick = {
                            pendingLibStoragePickerIntent = LibStoragePickerIntent.SetOnly
                            treePicker.launch(null)
                        },
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
                        OutlinedButton(
                            onClick = { viewModel.setExternalStorageUri(null) },
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
                    text = "New downloads go to the selected location.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Move library — rendered only when there's work to do. We
                // refresh the count reactively after each move (state
                // transition to Idle) and when the user picks a new folder.
                // If everything is already in the external target the
                // section simply disappears so the button isn't a dead-end.
                var movableCount by remember { mutableStateOf<Int?>(null) }
                LaunchedEffect(uiState.moveLibraryState, externalTree) {
                    if (uiState.moveLibraryState !is com.stash.data.download.files.MoveLibraryState.Running) {
                        movableCount = runCatching { viewModel.countMovableTracks() }.getOrNull()
                    }
                }

                val showMoveSection = when (uiState.moveLibraryState) {
                    com.stash.data.download.files.MoveLibraryState.Idle ->
                        (movableCount ?: 0) > 0
                    else -> true  // show progress/done/error regardless of count
                }

                if (showMoveSection) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(
                        color = extendedColors.glassBorder,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LibStorageMoveLibrarySection(
                        state = uiState.moveLibraryState,
                        hasExternalFolder = externalTree != null,
                        movableCount = movableCount ?: 0,
                        onStart = {
                            if (externalTree != null) {
                                viewModel.startMoveLibrary(externalTree)
                            } else {
                                pendingLibStoragePickerIntent = LibStoragePickerIntent.SetAndMove
                                treePicker.launch(null)
                            }
                        },
                        onCancel = viewModel::cancelMoveLibrary,
                        onDismiss = viewModel::dismissMoveLibrary,
                    )
                }
            }
        }
    }
}

/**
 * Tracks what the user meant when they tapped the SAF folder-picker.
 * [SetOnly] = redirect new downloads; [SetAndMove] = also start the
 * library migration to the picked folder as soon as it's granted.
 *
 * Verbatim copy of the file-private enum in [SettingsScreen] (transient
 * duplication; removed there at the Task D1 cutover).
 */
private enum class LibStoragePickerIntent { SetOnly, SetAndMove, SetAndRestart }

/**
 * Renders the "Move existing library" action inside the Storage card.
 *
 * Shows four visual states driven by the underlying
 * [com.stash.data.download.files.MoveLibraryState]:
 * - **Idle** — prompt + "Move library to this folder" button.
 * - **Running(c, t)** — live progress ("Moving c of t...") + linear bar + Cancel.
 * - **Done(moved, failed)** — result summary + Dismiss.
 * - **Error(msg)** — error text + Dismiss.
 *
 * Verbatim copy of the file-private helper in [SettingsScreen] (transient
 * duplication; removed there at the Task D1 cutover).
 */
@Composable
private fun LibStorageMoveLibrarySection(
    state: com.stash.data.download.files.MoveLibraryState,
    hasExternalFolder: Boolean,
    movableCount: Int,
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
                    "Move $movableCount track${if (movableCount == 1) "" else "s"} still on your device into the folder above so they're accessible over USB too."
                } else {
                    "Move $movableCount track${if (movableCount == 1) "" else "s"} on your device to an external folder (SD / USB) so you can access them over USB too. You'll pick the destination next."
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
                    text = if (hasExternalFolder) {
                        "Move $movableCount track${if (movableCount == 1) "" else "s"} to this folder"
                    } else {
                        "Pick destination and move library"
                    },
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
 * Formats a byte count into a human-readable string (B, KB, MB, GB).
 *
 * Verbatim copy of the file-private helper in [SettingsScreen] (transient
 * duplication; removed there at the Task D1 cutover).
 */
private fun libStorageFormatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val safeIndex = digitGroups.coerceIn(0, units.lastIndex)
    return "%.1f %s".format(bytes / Math.pow(1024.0, safeIndex.toDouble()), units[safeIndex])
}
