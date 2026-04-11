package com.stash.feature.sync

import android.text.format.DateUtils
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.sync.SyncPhase
import com.stash.core.model.SyncDisplayStatus
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.theme.StashTheme
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Main Sync screen.
 *
 * Displays connected source status, schedule configuration, a manual sync
 * trigger with live progress, and recent sync history.
 */
@Composable
fun SyncScreen(
    modifier: Modifier = Modifier,
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // -- Header -----------------------------------------------------------
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Sync",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
            )
        }

        // -- Connected Sources ------------------------------------------------
        item {
            Text(
                text = "Connected Sources",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SourceCard(
                    name = "Spotify",
                    connected = uiState.spotifyConnected,
                    accentColor = StashTheme.extendedColors.spotifyGreen,
                    modifier = Modifier.weight(1f),
                )
                SourceCard(
                    name = "YouTube",
                    connected = uiState.youTubeConnected,
                    accentColor = StashTheme.extendedColors.youtubeRed,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // -- Manual Sync Button + Progress ------------------------------------
        item {
            SyncActionSection(
                isSyncing = uiState.isSyncing,
                phase = uiState.syncPhase,
                progress = uiState.overallProgress,
                onSyncNow = viewModel::onSyncNow,
            )
        }

        // -- Schedule ---------------------------------------------------------
        item {
            Text(
                text = "Schedule",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        item {
            ScheduleCard(
                syncHour = uiState.syncPreferences.syncHour,
                syncMinute = uiState.syncPreferences.syncMinute,
                autoSyncEnabled = uiState.syncPreferences.autoSyncEnabled,
                wifiOnly = uiState.syncPreferences.wifiOnly,
                onTimeSelected = viewModel::onSetSyncTime,
                onToggleAutoSync = viewModel::onToggleAutoSync,
                onToggleWifiOnly = viewModel::onToggleWifiOnly,
            )
        }

        // -- Recent History ---------------------------------------------------
        if (uiState.recentSyncs.isNotEmpty()) {
            item {
                Text(
                    text = "Recent Syncs",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            items(uiState.recentSyncs, key = { it.id }) { sync ->
                SyncHistoryRow(sync)
            }
        }

        // Bottom spacing so content isn't hidden behind nav bar
        item { Spacer(Modifier.height(80.dp)) }
    }
}

// -- Connected-source card ----------------------------------------------------

@Composable
private fun SourceCard(
    name: String,
    connected: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Status dot
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (connected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (connected) "Connected" else "Not connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// -- Sync action button + progress --------------------------------------------

@Composable
private fun SyncActionSection(
    isSyncing: Boolean,
    phase: SyncPhase,
    progress: Float,
    onSyncNow: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onSyncNow,
            enabled = !isSyncing,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
            ),
        ) {
            if (isSyncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
            } else {
                Icon(
                    imageVector = Icons.Filled.Sync,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = if (isSyncing) phaseLabel(phase) else "Sync Now",
                style = MaterialTheme.typography.labelLarge,
            )
        }

        if (isSyncing) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

/** Human-readable label for the current sync phase. */
private fun phaseLabel(phase: SyncPhase): String = when (phase) {
    is SyncPhase.Authenticating -> "Authenticating..."
    is SyncPhase.FetchingPlaylists -> "Fetching playlists..."
    is SyncPhase.Diffing -> "Comparing changes..."
    is SyncPhase.Downloading -> "Downloading ${phase.downloaded}/${phase.total}..."
    is SyncPhase.Finalizing -> "Finalizing..."
    is SyncPhase.Completed -> "Complete"
    is SyncPhase.Error -> "Error"
    else -> "Syncing..."
}

// -- Schedule card ------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleCard(
    syncHour: Int,
    syncMinute: Int,
    autoSyncEnabled: Boolean,
    wifiOnly: Boolean,
    onTimeSelected: (Int, Int) -> Unit,
    onToggleAutoSync: () -> Unit,
    onToggleWifiOnly: () -> Unit,
) {
    var showTimePicker by remember { mutableStateOf(false) }
    val formattedTime = remember(syncHour, syncMinute) {
        String.format("%02d:%02d", syncHour, syncMinute)
    }

    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Auto-sync toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CloudSync,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Auto sync",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Switch(
                    checked = autoSyncEnabled,
                    onCheckedChange = { onToggleAutoSync() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }

            // Schedule time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Sync time",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Button(
                    onClick = { showTimePicker = true },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // Wi-Fi only toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Wi-Fi only",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Switch(
                    checked = wifiOnly,
                    onCheckedChange = { onToggleWifiOnly() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }
    }

    if (showTimePicker) {
        TimePickerDialog(
            initialHour = syncHour,
            initialMinute = syncMinute,
            onConfirm = { hour, minute ->
                onTimeSelected(hour, minute)
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true,
    )

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = { onConfirm(state.hour, state.minute) }) {
                Text("OK")
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text("Cancel")
            }
        },
        title = { Text("Select sync time") },
        text = {
            TimePicker(
                state = state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = MaterialTheme.colorScheme.surfaceVariant,
                    selectorColor = MaterialTheme.colorScheme.primary,
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

// -- Sync history row ---------------------------------------------------------

@Composable
private fun SyncHistoryRow(sync: SyncHistoryInfo) {
    val extendedColors = StashTheme.extendedColors
    var expanded by remember { mutableStateOf(false) }

    // Derive icon, tint and label directly from the richer displayStatus so
    // Partial and Interrupted outcomes don't read as a generic "Failed".
    val icon: ImageVector = when (sync.displayStatus) {
        SyncDisplayStatus.Success -> Icons.Filled.CheckCircle
        is SyncDisplayStatus.PartialSuccess -> Icons.Filled.Warning
        is SyncDisplayStatus.Interrupted -> Icons.Filled.PauseCircle
        is SyncDisplayStatus.Failed -> Icons.Filled.Error
        SyncDisplayStatus.Running -> Icons.Filled.Sync
        SyncDisplayStatus.Idle -> Icons.Filled.Schedule
    }
    val tint: Color = when (sync.displayStatus) {
        SyncDisplayStatus.Success -> extendedColors.success
        is SyncDisplayStatus.PartialSuccess -> extendedColors.warning
        is SyncDisplayStatus.Interrupted -> extendedColors.warning
        is SyncDisplayStatus.Failed -> MaterialTheme.colorScheme.error
        SyncDisplayStatus.Running,
        SyncDisplayStatus.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val badgeLabel: String = when (sync.displayStatus) {
        SyncDisplayStatus.Success -> "Completed"
        is SyncDisplayStatus.PartialSuccess -> "Partial"
        is SyncDisplayStatus.Interrupted -> "Interrupted"
        is SyncDisplayStatus.Failed -> "Failed"
        SyncDisplayStatus.Running -> "Running"
        SyncDisplayStatus.Idle -> "Idle"
    }
    val badgeBg: Color = when (sync.displayStatus) {
        SyncDisplayStatus.Success -> extendedColors.success.copy(alpha = 0.12f)
        is SyncDisplayStatus.PartialSuccess -> extendedColors.warning.copy(alpha = 0.14f)
        is SyncDisplayStatus.Interrupted -> extendedColors.warning.copy(alpha = 0.14f)
        is SyncDisplayStatus.Failed -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    GlassCard(
        modifier = Modifier.clickable { expanded = !expanded },
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = DateUtils.getRelativeTimeSpanString(
                                sync.startedAt,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS,
                            ).toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = buildString {
                                append("Found ${sync.newTracksFound}")
                                append(" / ${sync.tracksDownloaded} downloaded")
                                if (sync.tracksFailed > 0) append(" / ${sync.tracksFailed} failed")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Status badge
                Text(
                    text = badgeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeBg)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            // Expanded details: show error and diagnostics so user can debug on-device
            if (expanded) {
                Spacer(Modifier.height(8.dp))

                if (!sync.errorMessage.isNullOrBlank()) {
                    Text(
                        text = "Error: ${sync.errorMessage}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                }

                if (!sync.diagnostics.isNullOrBlank()) {
                    Text(
                        text = "Diagnostics:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = sync.diagnostics,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 20,
                    )
                }

                if (sync.errorMessage.isNullOrBlank() && sync.diagnostics.isNullOrBlank()) {
                    Text(
                        text = "No additional details available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
