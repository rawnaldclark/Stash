package com.stash.feature.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.stash.core.data.youtube.YouTubeScrobblerHealth
import com.stash.feature.settings.R

/**
 * Settings row that exposes the YouTube Music history sync toggle.
 *
 * Renders a title + subtitle row with a trailing [Switch]. When
 * [ytConnected] is false the entire row is greyed out and non-interactive.
 * Turning the toggle ON for the first time shows a confirmation dialog
 * explaining the unofficial-API risk; turning it OFF is immediate.
 * When [health] is [YouTubeScrobblerHealth.PROTOCOL_BROKEN] a "Retry"
 * button appears below the row.
 *
 * This composable carries no state itself — callers own enabled state
 * and pass [onToggle] / [onRetry] callbacks.
 */
@Composable
fun YouTubeHistorySyncSection(
    enabled: Boolean,
    health: YouTubeScrobblerHealth,
    pendingCount: Int,
    ytConnected: Boolean,
    onToggle: (Boolean) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showConfirmDialog by remember { mutableStateOf(false) }

    if (showConfirmDialog) {
        FirstEnableConfirmDialog(
            onConfirm = {
                showConfirmDialog = false
                onToggle(true)
            },
            onDismiss = { showConfirmDialog = false },
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (ytConnected) {
                        Modifier
                            .clickable(
                                role = Role.Switch,
                                onClickLabel = if (enabled) stringResource(R.string.settings_disable) else stringResource(R.string.settings_enable),
                            ) {
                                if (enabled) {
                                    onToggle(false)
                                } else {
                                    showConfirmDialog = true
                                }
                            }
                    } else {
                        Modifier
                    }
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_yt_sync_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ytConnected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (!ytConnected) {
                        stringResource(R.string.settings_yt_sync_connect_first)
                    } else {
                        statusLine(health, pendingCount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!ytConnected) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        statusColor(health)
                    },
                )
            }

            com.stash.core.ui.components.StashSwitch(
                checked = enabled,
                onCheckedChange = { checked ->
                    if (checked) {
                        showConfirmDialog = true
                    } else {
                        onToggle(false)
                    }
                },
                enabled = ytConnected,
                modifier = Modifier.semantics { role = Role.Switch },
            )
        }

        if (health == YouTubeScrobblerHealth.PROTOCOL_BROKEN && ytConnected) {
            TextButton(
                onClick = onRetry,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_yt_sync_retry),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun statusColor(health: YouTubeScrobblerHealth): Color = when (health) {
    YouTubeScrobblerHealth.OK -> MaterialTheme.colorScheme.primary
    YouTubeScrobblerHealth.OFFLINE,
    YouTubeScrobblerHealth.DISABLED -> MaterialTheme.colorScheme.onSurfaceVariant
    YouTubeScrobblerHealth.AUTH_FAILED,
    YouTubeScrobblerHealth.PROTOCOL_BROKEN -> MaterialTheme.colorScheme.error
}

@Composable
private fun statusLine(health: YouTubeScrobblerHealth, pending: Int): String = when (health) {
    YouTubeScrobblerHealth.OK ->
        if (pending == 0) stringResource(R.string.settings_yt_status_up_to_date) else stringResource(R.string.settings_yt_status_pending, pending)
    YouTubeScrobblerHealth.OFFLINE -> stringResource(R.string.settings_yt_status_pending_offline, pending)
    YouTubeScrobblerHealth.AUTH_FAILED -> stringResource(R.string.settings_yt_status_auth_failed)
    YouTubeScrobblerHealth.PROTOCOL_BROKEN -> stringResource(R.string.settings_yt_status_protocol_broken)
    YouTubeScrobblerHealth.DISABLED -> stringResource(R.string.settings_yt_status_disabled)
}

@Composable
private fun FirstEnableConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        title = {
            Text(
                text = stringResource(R.string.settings_yt_sync_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_yt_sync_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_yt_sync_dialog_how_it_works_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_yt_sync_dialog_how_it_works_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_yt_sync_dialog_risk_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_yt_sync_dialog_risk_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.settings_enable))
            }
        },
    )
}
