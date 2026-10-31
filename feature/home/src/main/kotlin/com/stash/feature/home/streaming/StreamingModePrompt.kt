package com.stash.feature.home.streaming

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.stash.core.ui.theme.StashTheme

/**
 * Shown once, the first time the user switches to "Stream only" (added in
 * v0.9.30 as a streaming disclosure; reworded 2026-09-17 when streaming
 * became unconditional). Single button, informational only — the switch has
 * already flipped by the time this renders. It explains what the switch
 * decides: whether Sync writes files, and so (since 2026-10-29) whether anything streams.
 */
@Composable
fun StreamingDisclosureDialog(
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stream only") },
        text = {
            Text(
                "Sync will keep your playlists up to date without saving " +
                    "music to this phone, and everything streams. Switch back " +
                    "to Download any time: it saves your switched-on playlists " +
                    "and mixes and plays only what's saved.",
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
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
