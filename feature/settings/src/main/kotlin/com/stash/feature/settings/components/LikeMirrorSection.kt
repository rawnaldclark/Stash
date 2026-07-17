package com.stash.feature.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * v0.9.52 "Sync your likes" (Beta): per-service opt-in for mirroring
 * the Stash heart to Spotify / YouTube Music — symmetric (un-heart
 * removes the remote Like), forward-only (new likes only; no backfill
 * of the existing library). Both toggles default OFF and only enable
 * past the [LikeMirrorWarningDialog] ack, which the caller owns.
 *
 * Each row gates on its own account connection, same as
 * [SpotifyAutoSaveSection] — visible but greyed with a "Connect …
 * first" hint when disconnected.
 *
 * Pure presentation; caller owns all state.
 */
@Composable
fun LikeMirrorSection(
    spotifyEnabled: Boolean,
    ytMusicEnabled: Boolean,
    spotifyConnected: Boolean,
    ytConnected: Boolean,
    onSpotifyToggle: (Boolean) -> Unit,
    onYtMusicToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Sync your likes",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.padding(horizontal = 4.dp))
            BetaPill()
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "Mirror hearts to your accounts. Applies to new likes only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))

        MirrorToggleRow(
            label = "Spotify",
            connected = spotifyConnected,
            enabled = spotifyEnabled,
            connectHint = "Connect Spotify first",
            onToggle = onSpotifyToggle,
        )
        MirrorToggleRow(
            label = "YouTube Music",
            connected = ytConnected,
            enabled = ytMusicEnabled,
            connectHint = "Connect YouTube Music first",
            onToggle = onYtMusicToggle,
        )
    }
}

@Composable
private fun MirrorToggleRow(
    label: String,
    connected: Boolean,
    enabled: Boolean,
    connectHint: String,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (connected) {
                    Modifier.clickable(
                        role = Role.Switch,
                        onClickLabel = if (enabled) "Disable" else "Enable",
                    ) { onToggle(!enabled) }
                } else {
                    Modifier
                }
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (connected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (!connected) {
                Text(
                    text = connectHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        com.stash.core.ui.components.StashSwitch(
            checked = enabled,
            onCheckedChange = onToggle,
            enabled = connected,
            modifier = Modifier.semantics { role = Role.Switch },
        )
    }
}
