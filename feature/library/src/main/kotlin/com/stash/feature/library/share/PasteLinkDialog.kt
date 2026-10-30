package com.stash.feature.library.share

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import com.stash.core.data.link.MusicLinks

/**
 * "Add from a link": a field for a Spotify or YouTube link, filled in already when the clipboard holds one. [onOpen] gets the
 * link found in what was typed or pasted.
 */
@Composable
fun PasteLinkDialog(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf(MusicLinks.findUrl(clipboard.getText()?.text).orEmpty()) }
    val link = MusicLinks.findUrl(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add from a link") },
        text = {
            Column {
                Text(
                    "A Spotify, YouTube or SoundCloud link.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Paste a link") },
                    singleLine = true,
                    isError = text.isNotBlank() && link == null,
                    supportingText = if (text.isNotBlank() && link == null) {
                        { Text("That isn't a supported music link") }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { link?.let(onOpen) }, enabled = link != null) { Text("Open") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
