package com.stash.feature.nowplaying.cast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.media.cast.CastConnection
import com.stash.core.media.cast.CastDevice
import com.stash.core.ui.components.SheetOptionRow

/**
 * The cast icon for Now Playing's top bar. Draws nothing when Cast isn't
 * available (no Play Services on the phone). Accented while connected.
 */
@Composable
internal fun CastButton(
    connection: CastConnection,
    tint: Color,
    accentColor: Color,
    onClick: () -> Unit,
) {
    if (connection == CastConnection.Unavailable) return
    val connected = connection is CastConnection.Connected
    IconButton(onClick = onClick) {
        if (connection is CastConnection.Connecting) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = accentColor)
        } else {
            Icon(
                imageVector = if (connected) Icons.Default.CastConnected else Icons.Default.Cast,
                contentDescription = when (connection) {
                    is CastConnection.Connected -> "Playing on ${connection.deviceName}"
                    else -> "Play on a speaker"
                },
                tint = if (connected) accentColor else tint,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * Speaker picker (not connected) or speaker controls (connected). Scans for
 * speakers only while it is open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CastSheet(
    viewModel: CastViewModel,
    onDismiss: () -> Unit,
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val speakers by viewModel.speakers.collectAsStateWithLifecycle()
    val volume by viewModel.volume.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        viewModel.startScan()
        onDispose { viewModel.stopScan() }
    }
    val extendedColors = com.stash.core.ui.theme.StashTheme.extendedColors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = extendedColors.elevatedSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
        ) {
            Text(
                text = "Play on a speaker",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier
                    .padding(bottom = 20.dp)
                    .align(Alignment.CenterHorizontally),
            )

            when (val state = connection) {
                is CastConnection.Connected -> ConnectedControls(
                    deviceName = state.deviceName,
                    volume = volume,
                    onVolume = viewModel::setVolume,
                    onStop = {
                        viewModel.disconnect()
                        onDismiss()
                    },
                )
                is CastConnection.Connecting -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(16.dp))
                        Text("Connecting to ${state.deviceName}…", style = MaterialTheme.typography.bodyLarge)
                    }
                    SheetOptionRow(icon = Icons.Default.Close, label = "Cancel", onClick = viewModel::disconnect)
                }
                else -> SpeakerList(
                    speakers = speakers,
                    onPick = viewModel::connect,
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = "While casting, the speaker plays the music itself, so the equalizer, crossfade, " +
                    "loudness levelling and playback speed don't apply.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SpeakerList(speakers: List<CastDevice>, onPick: (CastDevice) -> Unit) {
    if (speakers.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(16.dp))
            Text("Looking for speakers…", style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            text = "Your phone and the speaker need to be on the same Wi-Fi.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        speakers.forEach { speaker ->
            SheetOptionRow(icon = Icons.Default.Speaker, label = speaker.name, onClick = { onPick(speaker) })
        }
    }
}

@Composable
private fun ConnectedControls(
    deviceName: String,
    volume: Float?,
    onVolume: (Float) -> Unit,
    onStop: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
        Icon(
            Icons.Default.CastConnected,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            "Playing on $deviceName",
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (volume != null) {
        // Local while dragging; sent once on release so a drag isn't dozens of round trips.
        var dragging by remember { mutableStateOf<Float?>(null) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = null, modifier = Modifier.size(20.dp))
            Slider(
                value = dragging ?: volume,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let(onVolume)
                    dragging = null
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .semantics { contentDescription = "Speaker volume" },
            )
            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
    Spacer(Modifier.height(8.dp))
    SheetOptionRow(icon = Icons.Default.Close, label = "Stop casting", onClick = onStop)
}
