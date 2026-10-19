package com.stash.feature.settings.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.stash.feature.settings.R

/**
 * Confirms disconnecting a source account (Spotify, YouTube Music) before it
 * happens. Disconnect is not just a sign-out: it also deletes every queued
 * and in-progress download of that source's songs
 * (`MusicRepository.cancelPendingDownloadsForSource`), which reconnecting does
 * not bring back until the next sync re-queues them. A stray tap on the red
 * button used to do all of that silently, so the dialog says what goes and
 * what stays.
 */
@Composable
fun DisconnectAccountDialog(
    serviceName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        title = { Text(stringResource(R.string.settings_disconnect_account_title, serviceName), style = MaterialTheme.typography.titleLarge) },
        text = {
            Text(
                stringResource(R.string.settings_disconnect_account_body, serviceName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.settings_account_disconnect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}
