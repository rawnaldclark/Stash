package com.stash.feature.nowplaying.listen

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Starting a session: the only thing to decide is the name others will see (spec §1: no accounts). */
@Composable
fun ListenTogetherStartDialog(name: String, onNameChange: (String) -> Unit, onStart: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Listen together") },
        text = {
            Column {
                Text(
                    "Friends who open your invite hear what you play, in time with you. You stay in control and can hand it over.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("Show my name as") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onStart) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The Android share sheet with the invite link (`https://…/l/{code}`, an App Link). */
fun shareInvite(context: Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Listen with me on Stash: $url")
    context.startActivity(Intent.createChooser(send, "Invite friends").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
