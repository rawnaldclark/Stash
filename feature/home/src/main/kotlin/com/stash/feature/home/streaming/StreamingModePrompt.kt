package com.stash.feature.home.streaming

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.home.R

/**
 * Shown once, the first time the user switches to "Online" (called "Stream only"
 * until 2026-11-01; added in v0.9.30 as a streaming disclosure; reworded
 * 2026-09-17 when streaming became unconditional). Single button, informational
 * only — the switch has already flipped by the time this renders. It explains what
 * the switch decides: whether Sync writes files, and so (since 2026-10-29) whether
 * anything streams.
 */
@Composable
fun StreamingDisclosureDialog(
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_streaming_dialog_title)) },
        text = {
            Text(stringResource(R.string.home_streaming_dialog_body))
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_streaming_dialog_confirm))
            }
        },
    )
}

@Preview(name = "Disclosure", showBackground = true, backgroundColor = 0xFF101012)
@Composable
private fun PreviewStreamingDisclosureDialog() {
    StashTheme {
        StreamingDisclosureDialog(onDismiss = {})
    }
}
