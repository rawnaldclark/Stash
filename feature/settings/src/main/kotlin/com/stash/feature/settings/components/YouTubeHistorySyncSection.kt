package com.stash.feature.settings.components

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stash.core.data.youtube.YouTubeScrobblerHealth
import com.stash.feature.settings.R

/**
 * "Add plays to your YouTube Music history", inside the YouTube Music card.
 * Worded around history so it can't be mistaken for the likes switch beside it.
 *
 * Shown only while YouTube Music is connected. Turning it ON for the first
 * time asks first (unofficial endpoint); turning it OFF is immediate. The
 * subtitle is the sync's health, and a Retry appears when the kill-switch has
 * tripped ([YouTubeScrobblerHealth.PROTOCOL_BROKEN]).
 *
 * This composable carries no state itself; callers own enabled state and pass
 * [onToggle] / [onRetry].
 */
@Composable
fun YouTubeHistorySyncSection(
    enabled: Boolean,
    health: YouTubeScrobblerHealth,
    pendingCount: Int,
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
        SettingsToggleRow(
            title = stringResource(R.string.settings_yt_sync_title),
            // Off says nothing: the switch already does.
            subtitle = if (health == YouTubeScrobblerHealth.DISABLED) null else statusLine(health, pendingCount),
            subtitleColor = statusColor(health),
            checked = enabled,
            onCheckedChange = { checked ->
                if (checked) showConfirmDialog = true else onToggle(false)
            },
        )
        if (health == YouTubeScrobblerHealth.PROTOCOL_BROKEN) {
            TextButton(
                onClick = onRetry,
                modifier = Modifier.padding(start = 4.dp),
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
                    text = stringResource(R.string.settings_yt_sync_dialog_risk_body),
                    style = MaterialTheme.typography.bodyMedium,
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
