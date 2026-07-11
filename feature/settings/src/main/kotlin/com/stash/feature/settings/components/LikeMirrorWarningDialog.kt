package com.stash.feature.settings.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.stash.core.ui.R

/**
 * v0.9.52: explicit opt-in ack before enabling like-mirroring for a
 * service. The pref is only written when the user taps "I understand"
 * — dismissing leaves mirroring off, so no writes ever happen without
 * this ack. Copy covers: what it does (incl. symmetric un-like), the
 * risk (private, unofficial write path), the mitigation (secondary /
 * backup account).
 */
@Composable
fun LikeMirrorWarningDialog(
    serviceName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_title_mirror_likes, serviceName)) },
        text = {
            Text(
                stringResource(R.string.dialog_body_mirror_likes, serviceName),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_i_understand)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
