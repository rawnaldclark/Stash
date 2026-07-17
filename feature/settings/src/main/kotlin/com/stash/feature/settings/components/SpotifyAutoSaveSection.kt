package com.stash.feature.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * v0.9.13: Spotify-side counterpart to [YouTubeHistorySyncSection] — auto-save
 * liked tracks to Spotify Liked Songs after the user has played them on
 * [autoSaveThreshold] distinct days within the last 30. Drives Spotify's
 * recommendation algorithm to keep Daily Mix / Discover Weekly fresh, using
 * the public `PUT /v1/me/tracks` endpoint.
 *
 * The "Beta" pill is intentional — this is brand-new behaviour that we want
 * users to evaluate against their own algorithm responsiveness before
 * defaulting it on.
 *
 * Pure presentation; caller owns all state.
 */
@Composable
fun SpotifyAutoSaveSection(
    enabled: Boolean,
    threshold: Int,
    autoSavedCountLast7Days: Int,
    spotifyConnected: Boolean,
    onToggle: (Boolean) -> Unit,
    onThresholdChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (spotifyConnected) {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Auto-save liked tracks",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (spotifyConnected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    BetaPill()
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (!spotifyConnected) {
                        "Connect Spotify first"
                    } else {
                        "Helps mixes refresh."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            com.stash.core.ui.components.StashSwitch(
                checked = enabled,
                onCheckedChange = onToggle,
                enabled = spotifyConnected,
                modifier = Modifier.semantics { role = Role.Switch },
            )
        }

        if (enabled && spotifyConnected) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Threshold: $threshold day${if (threshold == 1) "" else "s"} in 30",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = threshold.toFloat(),
                onValueChange = { onThresholdChanged(it.toInt().coerceIn(1, 10)) },
                valueRange = 1f..10f,
                steps = 8,
            )
            Text(
                text = "Last 7 days: $autoSavedCountLast7Days tracks auto-saved",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
