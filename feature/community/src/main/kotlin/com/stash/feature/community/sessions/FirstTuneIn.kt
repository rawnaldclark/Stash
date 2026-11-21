package com.stash.feature.community.sessions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.stash.feature.community.R

/**
 * Before the first tune-in ever (Sessions step 2): one line, "Everyone in a session sees your name: Rawn", with
 * Change, which opens the "Show my name as" field, then Tune in. A phone with no name yet gets the field at once.
 * It never shows again once Tune in is tapped. [name] is the display name as it stands; null while it isn't asked.
 */
@Composable
internal fun FirstTuneIn(name: String?, onTuneIn: (String) -> Unit, onDismiss: () -> Unit) {
    if (name == null) return
    // Local, synchronous state: a field fed from a flow drops keystrokes.
    var typed by rememberSaveable(name) { mutableStateOf(name) }
    var changing by rememberSaveable(name) { mutableStateOf(name.isBlank()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sessions_tune_in)) },
        text = {
            Column {
                Text(
                    if (changing || typed.isBlank()) stringResource(R.string.sessions_name_line_bare)
                    else stringResource(R.string.sessions_name_line, typed.trim()),
                )
                if (changing) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it.replace('\n', ' ').take(NAME_MAX) },
                        label = { Text(stringResource(R.string.sessions_name_field)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onTuneIn(typed) }),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                } else {
                    TextButton(onClick = { changing = true }, modifier = Modifier.padding(top = 4.dp)) {
                        Text(stringResource(R.string.sessions_name_change))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onTuneIn(typed) }) { Text(stringResource(R.string.sessions_tune_in)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.station_cancel)) } },
    )
}

/** As the display name is kept: 40 characters. */
private const val NAME_MAX = 40
