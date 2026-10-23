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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.stash.feature.settings.R

/**
 * v0.9.52 "Sync your likes" (Beta): per-service opt-in for mirroring
 * the Stash heart to Spotify / YouTube Music — symmetric (un-heart
 * removes the remote Like), forward-only (new likes only; no backfill
 * of the existing library). Both toggles default OFF and only enable
 * past the [LikeMirrorWarningDialog] ack, which the caller owns.
 *
 * Each row gates on its own account connection, same as
 * the other account sub-settings — visible but greyed with a "Connect …
 * first" hint when disconnected.
 *
 * Pure presentation; caller owns all state.
 */
@Composable
fun LikeMirrorSection(
    spotifyEnabled: Boolean,
    ytMusicEnabled: Boolean,
    lastFmEnabled: Boolean,
    spotifyConnected: Boolean,
    ytConnected: Boolean,
    lastFmConnected: Boolean,
    onSpotifyToggle: (Boolean) -> Unit,
    onYtMusicToggle: (Boolean) -> Unit,
    onLastFmToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.settings_like_mirror_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.padding(horizontal = 4.dp))
            BetaPill()
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.settings_like_mirror_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))

        MirrorToggleRow(
            label = "Spotify",
            connected = spotifyConnected,
            enabled = spotifyEnabled,
            connectHint = stringResource(R.string.settings_like_mirror_connect_first, "Spotify"),
            onToggle = onSpotifyToggle,
        )
        MirrorToggleRow(
            label = "YouTube Music",
            connected = ytConnected,
            enabled = ytMusicEnabled,
            connectHint = stringResource(R.string.settings_like_mirror_connect_first, "YouTube Music"),
            onToggle = onYtMusicToggle,
        )
        // Last.fm loved tracks. Stash already imports your Last.fm loves; this is
        // the direction that was missing, so a heart here reaches Last.fm too.
        MirrorToggleRow(
            label = "Last.fm",
            connected = lastFmConnected,
            enabled = lastFmEnabled,
            connectHint = stringResource(R.string.settings_like_mirror_connect_first, "Last.fm"),
            onToggle = onLastFmToggle,
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
                        onClickLabel = if (enabled) stringResource(R.string.settings_disable) else stringResource(R.string.settings_enable),
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
